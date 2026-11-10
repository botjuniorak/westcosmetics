package pl.westmc.panel.paper;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** WestGuard: x-ray, autoklik, heurystyki killaury + przekazywanie wykryc Grima. */
final class Guard implements Listener {
    private static final Set<Material> STONE = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE,
        Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.COBBLED_DEEPSLATE);
    private static final Set<Material> NETHER_STONE = EnumSet.of(Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE, Material.SOUL_SAND, Material.SOUL_SOIL);
    private static final Set<Material> DIAMOND = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);

    final WestPanelPaper pl;
    YamlConfiguration cfg;
    boolean punishMode;

    // VL: gracz -> sprawdzenie -> [vl, ostatnia zmiana ms, ostatni alert ms]
    private final Map<UUID, Map<String, double[]>> vl = new ConcurrentHashMap<>();
    // x-ray
    private final Map<UUID, Deque<long[]>> mined = new HashMap<>();   // [czas, typ 0=kamien 1=zyla diamentu 2=netherrack 3=zyla netherytu]
    private final Map<UUID, Location> lastOre = new HashMap<>();
    private final Set<Long> placedOres = new HashSet<>();
    // killaura
    private final Map<UUID, long[]> lastHit = new HashMap<>();        // [tick, id celu]

    Guard(WestPanelPaper pl) {
        this.pl = pl;
        reload();
    }

    void reload() {
        File f = new File(pl.getDataFolder(), "guard.yml");
        if (!f.exists()) pl.saveResource("guard.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        punishMode = "kary".equalsIgnoreCase(cfg.getString("tryb", "alerty"));
    }

    // ------------------------------------------------------------ wspolne: VL i alerty

    /** Dodaje VL i wysyla alert do panelu i ekipy, gdy przekroczy prog. Bezpieczne z dowolnego watku. */
    void flag(UUID id, String name, String check, double add, String info) {
        Map<String, double[]> m = vl.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        double[] v = m.computeIfAbsent(check, k -> new double[]{0, now, 0});
        synchronized (v) {
            // zanikanie: -1 VL za kazde 60 s bez wykrycia
            v[0] = Math.max(0, v[0] - (now - v[1]) / 60000.0);
            v[0] += add;
            v[1] = now;
            int threshold = cfg.getInt("progi-alertu." + check, 1);
            if (v[0] >= threshold && now - v[2] > 15000) {
                v[2] = now;
                pl.acAlert(name, check, (int) Math.round(v[0]), info, "WestGuard");
                if (punishMode) maybePunish(name, check, v[0]);
            }
        }
    }

    private void maybePunish(String name, String check, double value) {
        int need = cfg.getInt("kary." + check + ".vl", 0);
        String cmd = cfg.getString("kary." + check + ".komenda", "");
        if (need <= 0 || cmd.isBlank() || value < need) return;
        String c = cmd.replace("%player%", name);
        Bukkit.getScheduler().runTask(pl, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            pl.event("kara", "WestGuard ukarał " + name + ": /" + c);
        });
        vl.values().forEach(m -> m.remove(check));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        mined.remove(id);
        lastOre.remove(id);
        lastHit.remove(id);
    }

    private static boolean counts(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL && !p.hasPermission("westguard.pomijaj");
    }

    // ------------------------------------------------------------ X-Ray

    private static long key(Location l) {
        return ((long) l.getBlockX() & 0x3FFFFFF) << 38 | ((long) l.getBlockZ() & 0x3FFFFFF) << 12 | (l.getBlockY() & 0xFFF);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlock().getType();
        if (DIAMOND.contains(m) || m == Material.ANCIENT_DEBRIS) {
            placedOres.add(key(e.getBlock().getLocation()));
            if (placedOres.size() > 20000) placedOres.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!cfg.getBoolean("xray.wlaczony", true)) return;
        Player p = e.getPlayer();
        if (!counts(p)) return;
        Material m = e.getBlock().getType();
        Location loc = e.getBlock().getLocation();
        int type;
        if (STONE.contains(m)) type = 0;
        else if (NETHER_STONE.contains(m)) type = 2;
        else if (DIAMOND.contains(m) || m == Material.ANCIENT_DEBRIS) {
            if (placedOres.remove(key(loc))) return;            // ruda postawiona przez gracza
            Location prev = lastOre.put(p.getUniqueId(), loc);
            boolean sameVein = prev != null && prev.getWorld() == loc.getWorld() && prev.distanceSquared(loc) <= 9;
            if (sameVein) return;                                // ta sama zyla
            type = DIAMOND.contains(m) ? 1 : 3;
        } else return;

        long now = System.currentTimeMillis();
        Deque<long[]> d = mined.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        d.addLast(new long[]{now, type});
        long window = cfg.getLong("xray.okno-minut", 60) * 60000L;
        while (!d.isEmpty() && now - d.peekFirst()[0] > window) d.pollFirst();
        if (d.size() > 20000) d.pollFirst();
        if (type != 1 && type != 3) return;

        int stone = 0, dia = 0, nether = 0, debris = 0;
        for (long[] x : d) switch ((int) x[1]) { case 0 -> stone++; case 1 -> dia++; case 2 -> nether++; default -> debris++; }
        if (type == 1 && dia >= cfg.getInt("xray.min-zyl-diamentow", 4) && stone < dia * cfg.getInt("xray.kamien-na-zyle", 120)) {
            flag(p.getUniqueId(), p.getName(), "xray", 1, dia + " żył diamentów na " + stone + " kamienia w " + (window / 60000) + " min · Y " + loc.getBlockY());
        }
        if (type == 3 && debris >= cfg.getInt("xray.min-zyl-netherytu", 4) && nether < debris * cfg.getInt("xray.netherrack-na-zyle", 150)) {
            flag(p.getUniqueId(), p.getName(), "xray", 1, debris + " żył netherytu na " + nether + " netherracka w " + (window / 60000) + " min");
        }
    }

    // ------------------------------------------------------------ KillAura (heurystyki)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!cfg.getBoolean("killaura.wlaczony", true)) return;
        if (!(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!counts(p) || !(e.getEntity() instanceof LivingEntity target)) return;

        // 1) cel poza polem widzenia
        Location eye = p.getEyeLocation();
        Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
        double dist = toTarget.length();
        if (dist > 1.8) {
            double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
            if (angle > cfg.getDouble("killaura.max-kat", 75)) {
                flag(p.getUniqueId(), p.getName(), "killaura", 1, String.format(java.util.Locale.ROOT, "uderzenie pod kątem %.0f° na %.1f kratki", angle, dist));
            }
        }
        // 2) dwa rozne cele w tym samym ticku
        if (cfg.getBoolean("killaura.multi-cele", true)) {
            long tick = Bukkit.getCurrentTick();
            long[] last = lastHit.get(p.getUniqueId());
            if (last != null && last[0] == tick && last[1] != target.getEntityId()) {
                flag(p.getUniqueId(), p.getName(), "killaura", 2, "uderzył 2 różne cele w jednym ticku");
            }
            lastHit.put(p.getUniqueId(), new long[]{tick, target.getEntityId()});
        }
    }

    static Entity none() { return null; }
}
