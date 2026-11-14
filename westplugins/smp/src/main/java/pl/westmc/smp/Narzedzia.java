package pl.westmc.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Drobne systemy SMP: toolsy administracji (vanish, adminchat, helpop, gm, god, fly, heal, feed),
 * czat, automatyczne wiadomosci, bossbar, czyszczenie itemow, antylogout ze szklem, losowy teleport,
 * kody, keyall o godzinach, strefa AFK, powiekszony enderchest, czyszczenie blokow w Endzie.
 */
final class Narzedzia implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final Set<UUID> vanish = new HashSet<>(), god = new HashSet<>(), adminchat = new HashSet<>();
    private final Map<UUID, Long> walka = new HashMap<>();
    private final Map<UUID, Set<Location>> szklo = new HashMap<>();
    private final Map<UUID, Long> cdRtp = new HashMap<>(), cdHelpop = new HashMap<>();
    private final Map<UUID, Integer> afk = new HashMap<>();
    private final Random rnd = new Random();
    private BossBar bar;
    private int msgIdx, barIdx;
    private long clearAt;
    private String ostatniKeyall = "";
    private final File plikKody, plikEnd;
    private final YamlConfiguration kody, endBloki;

    Narzedzia(WestSMP pl) {
        this.pl = pl;
        plikKody = new File(pl.getDataFolder(), "kody-uzyte.yml"); kody = YamlConfiguration.loadConfiguration(plikKody);
        plikEnd = new File(pl.getDataFolder(), "end-bloki.yml"); endBloki = YamlConfiguration.loadConfiguration(plikEnd);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() { return pl.getConfig(); }

    void start() {
        clearAt = System.currentTimeMillis() + cfg().getLong("czyszczenie.co-ile-minut", 10) * 60_000L;
        bar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        Bukkit.getScheduler().runTaskTimer(pl, this::sekunda, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::wiadomosc, 1200L, cfg().getLong("wiadomosci.co-ile-sekund", 180) * 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::antylogoutTick, 10L, 10L);
        for (Player p : Bukkit.getOnlinePlayers()) if (cfg().getBoolean("bossbar.wlaczony", true)) p.showBossBar(bar);
    }

    void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) { p.hideBossBar(bar); czyscSzklo(p); }
        try { endBloki.save(plikEnd); kody.save(plikKody); } catch (Exception ignored) { }
    }

    // ===================================================================== co sekunde
    private void sekunda() {
        long now = System.currentTimeMillis();
        // --- bossbar: rotacja tekstow
        List<String> teksty = cfg().getStringList("bossbar.teksty");
        if (!teksty.isEmpty() && (now / 1000) % cfg().getInt("bossbar.co-ile-sekund", 10) == 0) {
            barIdx = (barIdx + 1) % teksty.size();
            bar.name(Component.text(WestSMP.c(teksty.get(barIdx).replace("{ONLINE}", String.valueOf(Bukkit.getOnlinePlayers().size())))));
            BossBar.Color[] k = {BossBar.Color.BLUE, BossBar.Color.YELLOW, BossBar.Color.PURPLE, BossBar.Color.GREEN};
            bar.color(k[barIdx % k.length]);
        }
        // --- czyszczenie itemow
        long left = (clearAt - now) / 1000;
        if (cfg().getBoolean("czyszczenie.wlaczone", true)) {
            if (left == 60 || left == 30 || left == 10 || (left <= 5 && left >= 1))
                Bukkit.broadcast(Component.text(WestSMP.c("&#FF7A5A&lCZYSZCZENIE &8» &7Itemy z ziemi znikna za &f" + left + "s")));
            if (left <= 0) {
                int n = 0;
                for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
                    if (e instanceof Item it && !chroniony(it.getItemStack())) { e.remove(); n++; }
                    else if (e instanceof Arrow a && a.isInBlock()) { e.remove(); n++; }
                }
                Bukkit.broadcast(Component.text(WestSMP.c("&#FF7A5A&lCZYSZCZENIE &8» &7Usunieto &f" + n + " &7itemow z ziemi.")));
                clearAt = now + cfg().getLong("czyszczenie.co-ile-minut", 10) * 60_000L;
            }
        }
        // --- keyall o godzinach
        String hhmm = String.format("%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute());
        if (cfg().getStringList("keyall.godziny").contains(hhmm) && !hhmm.equals(ostatniKeyall)) {
            ostatniKeyall = hhmm;
            for (Player p : Bukkit.getOnlinePlayers()) for (String c : cfg().getStringList("keyall.komendy")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{GRACZ}", p.getName()));
            Bukkit.broadcast(Component.text(WestSMP.c(cfg().getString("keyall.wiadomosc", "&#FFC93D&lKEYALL &8» &7Kazdy dostal klucz!"))));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        }
        // --- strefa AFK: nagroda co X minut
        BoundingBox strefa = box("strefa-afk");
        if (strefa != null) {
            int co = cfg().getInt("strefa-afk.nagroda-co-minut", 10) * 60;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getWorld().getName().equals(cfg().getString("strefa-afk.swiat", "world")) || !strefa.contains(p.getLocation().toVector())) { afk.remove(p.getUniqueId()); continue; }
                int t = afk.merge(p.getUniqueId(), 1, Integer::sum);
                p.sendActionBar(WestSMP.c("&#4EE08F&lSTREFA AFK &8» &7nagroda za &f" + ((co - t % co) / 60) + "m " + ((co - t % co) % 60) + "s"));
                if (t % co == 0) {
                    for (String c : cfg().getStringList("strefa-afk.komendy")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{GRACZ}", p.getName()));
                    p.sendMessage(WestSMP.c("&#4EE08F&lSTREFA AFK &8» &7Odebrales nagrode!"));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
                }
            }
        }
        // --- bloki postawione w Endzie znikaja po czasie
        if ((now / 1000) % 30 == 0 && cfg().getBoolean("end-bloki.wlaczone", true)) {
            long ttl = cfg().getLong("end-bloki.znikaja-po-minutach", 60) * 60_000L;
            for (String k : new ArrayList<>(endBloki.getKeys(false))) {
                if (now - endBloki.getLong(k) < ttl) continue;
                String[] x = k.split("_");
                World w = Bukkit.getWorld(x[0].replace("~", "_"));
                if (w != null) { Block b = w.getBlockAt(Integer.parseInt(x[1]), Integer.parseInt(x[2]), Integer.parseInt(x[3])); if (!b.getType().isAir()) b.setType(Material.AIR); }
                endBloki.set(k, null);
            }
        }
    }

    private boolean chroniony(ItemStack it) {
        if (pl.eventowki != null && pl.eventowki.id(it) != null) return true;
        return cfg().getStringList("czyszczenie.nie-usuwaj").contains(it.getType().name());
    }

    private BoundingBox box(String s) {
        ConfigurationSection c = cfg().getConfigurationSection(s);
        if (c == null || !c.isSet("od") || !c.isSet("do")) return null;
        List<Double> a = c.getDoubleList("od"), b = c.getDoubleList("do");
        if (a.size() < 3 || b.size() < 3) return null;
        return BoundingBox.of(new org.bukkit.util.Vector(a.get(0), a.get(1), a.get(2)), new org.bukkit.util.Vector(b.get(0), b.get(1), b.get(2)));
    }

    private void wiadomosc() {
        List<String> l = cfg().getStringList("wiadomosci.lista");
        if (l.isEmpty()) return;
        msgIdx = (msgIdx + 1) % l.size();
        for (String linia : l.get(msgIdx).split("\\|")) Bukkit.broadcast(Component.text(WestSMP.c(linia)));
    }

    // ===================================================================== antylogout ze szklem
    private boolean wWalce(Player p) { Long t = walka.get(p.getUniqueId()); return t != null && t > System.currentTimeMillis(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player ofiara)) return;
        Player atak = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (atak == null || atak == ofiara) return;
        long until = System.currentTimeMillis() + cfg().getLong("antylogout.sekundy", 30) * 1000L;
        for (Player p : new Player[]{ofiara, atak}) {
            if (p.hasPermission("westsmp.antylogout.omin")) continue;
            if (!wWalce(p)) p.sendMessage(WestSMP.c("&#FF5A68&lWALKA &8» &7Jestes w walce! &fNie wylogowuj sie."));
            walka.put(p.getUniqueId(), until);
            if (p.isFlying()) { p.setFlying(false); p.setAllowFlight(false); }
        }
    }

    private void antylogoutTick() {
        long now = System.currentTimeMillis();
        BoundingBox spawn = box("antylogout.strefa-spawnu");
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long t = walka.get(p.getUniqueId());
            if (t == null) continue;
            if (t <= now) { walka.remove(p.getUniqueId()); czyscSzklo(p); p.sendActionBar(WestSMP.c("&a✔ Koniec walki - mozesz sie wylogowac.")); continue; }
            long s = (t - now + 999) / 1000;
            p.sendActionBar(WestSMP.c("&#FF5A68&lWALKA &8» &f" + s + "s &8| &7nie wychodz z serwera"));
            if (spawn != null && p.getWorld().getName().equals(cfg().getString("antylogout.strefa-spawnu.swiat", "world"))) pokazSzklo(p, spawn);
        }
    }

    /** Czerwone szklo (tylko dla gracza w walce) na granicy strefy spawnu, w poblizu gracza. */
    private void pokazSzklo(Player p, BoundingBox b) {
        Set<Location> nowe = new HashSet<>();
        Location l = p.getLocation();
        int r = 5;
        for (int dx = -r; dx <= r; dx++) for (int dy = -2; dy <= 3; dy++) for (int dz = -r; dz <= r; dz++) {
            int x = l.getBlockX() + dx, y = l.getBlockY() + dy, z = l.getBlockZ() + dz;
            boolean brzegX = (x == (int) Math.floor(b.getMinX()) - 1 || x == (int) Math.floor(b.getMaxX()) + 1) && z >= b.getMinZ() - 1 && z <= b.getMaxZ() + 1;
            boolean brzegZ = (z == (int) Math.floor(b.getMinZ()) - 1 || z == (int) Math.floor(b.getMaxZ()) + 1) && x >= b.getMinX() - 1 && x <= b.getMaxX() + 1;
            if (!brzegX && !brzegZ) continue;
            Location bl = new Location(l.getWorld(), x, y, z);
            if (!bl.getBlock().getType().isAir()) continue;
            nowe.add(bl);
        }
        Set<Location> stare = szklo.getOrDefault(p.getUniqueId(), Set.of());
        for (Location o : stare) if (!nowe.contains(o)) p.sendBlockChange(o, o.getBlock().getBlockData());
        for (Location n : nowe) if (!stare.contains(n)) p.sendBlockChange(n, Material.RED_STAINED_GLASS.createBlockData());
        szklo.put(p.getUniqueId(), nowe);
    }

    private void czyscSzklo(Player p) {
        Set<Location> s = szklo.remove(p.getUniqueId());
        if (s != null) for (Location o : s) p.sendBlockChange(o, o.getBlock().getBlockData());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoveWalka(PlayerMoveEvent e) {
        if (!wWalce(e.getPlayer()) || e.getTo() == null || !e.hasChangedBlock()) return;
        BoundingBox spawn = box("antylogout.strefa-spawnu");
        if (spawn != null && spawn.contains(e.getTo().toVector()) && !spawn.contains(e.getFrom().toVector())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(WestSMP.c("&c✖ Nie mozesz wejsc na spawn w trakcie walki!"));
        }
    }

    @EventHandler
    public void onCmdWalka(PlayerCommandPreprocessEvent e) {
        if (!wWalce(e.getPlayer())) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (cfg().getStringList("antylogout.zablokowane-komendy").contains(c)) { e.setCancelled(true); e.getPlayer().sendMessage(WestSMP.c("&cNie mozesz tego uzyc w walce.")); }
    }

    @EventHandler
    public void onQuitWalka(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        czyscSzklo(p); afk.remove(p.getUniqueId());
        if (wWalce(p)) {
            walka.remove(p.getUniqueId());
            p.setHealth(0);
            Bukkit.broadcast(Component.text(WestSMP.c("&#FF5A68&lANTYLOGOUT &8» &f" + p.getName() + " &7wylogowal sie w trakcie walki i zginal!")));
        }
    }

    @EventHandler public void onDeathWalka(PlayerDeathEvent e) { walka.remove(e.getEntity().getUniqueId()); czyscSzklo(e.getEntity()); }

    // ===================================================================== czat, vanish, join
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        if (e.isCancelled()) return;
        Player p = e.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (adminchat.contains(p.getUniqueId()) || (msg.startsWith("#") && p.hasPermission("westsmp.adminchat"))) {
            e.setCancelled(true);
            String m = msg.startsWith("#") ? msg.substring(1).trim() : msg;
            for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("westsmp.adminchat")) o.sendMessage(WestSMP.c("&#FF5A68&lAC &8» &f" + p.getName() + "&7: &#FFB3B8") + m);
            return;
        }
        if (!cfg().getBoolean("czat.wlaczony", true)) return;
        String ranga = "";
        ConfigurationSection r = cfg().getConfigurationSection("czat.rangi");
        if (r != null) for (String k : r.getKeys(false)) if (p.hasPermission("westsmp.ranga." + k)) { ranga = r.getString(k); break; }
        String f = WestSMP.c(cfg().getString("czat.format", "{RANGA}&f{GRACZ} &8» &7").replace("{RANGA}", ranga).replace("{GRACZ}", p.getName()));
        String tresc = p.hasPermission("westsmp.czat.kolory") ? WestSMP.c(msg) : msg;
        e.renderer((source, name, message, viewer) -> Component.text(f + tresc));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (cfg().getBoolean("bossbar.wlaczony", true)) p.showBossBar(bar);
        for (UUID v : vanish) { Player vp = Bukkit.getPlayer(v); if (vp != null && !p.hasPermission("westsmp.vanish")) p.hidePlayer(pl, vp); }
        if (vanish.contains(p.getUniqueId())) e.joinMessage(null);
        else if (cfg().isSet("czat.wejscie")) e.joinMessage(Component.text(WestSMP.c(cfg().getString("czat.wejscie").replace("{GRACZ}", p.getName()))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (vanish.contains(e.getPlayer().getUniqueId())) e.quitMessage(null);
        else if (cfg().isSet("czat.wyjscie")) e.quitMessage(Component.text(WestSMP.c(cfg().getString("czat.wyjscie").replace("{GRACZ}", e.getPlayer().getName()))));
    }

    @EventHandler
    public void onGod(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && god.contains(p.getUniqueId())) e.setCancelled(true); }

    // ===================================================================== End: zapis postawionych blokow
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (b.getWorld().getEnvironment() != World.Environment.THE_END || !cfg().getBoolean("end-bloki.wlaczone", true)) return;
        if (e.getPlayer().hasPermission("westsmp.admin") && e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        endBloki.set(b.getWorld().getName().replace("_", "~") + "_" + b.getX() + "_" + b.getY() + "_" + b.getZ(), System.currentTimeMillis());
    }

    // ===================================================================== enderchest powiekszony
    @EventHandler(priority = EventPriority.HIGH)
    public void onEnderchest(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.ENDER_CHEST || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getPlayer().isSneaking()) return;
        int rzedy = rzedyEc(e.getPlayer());
        if (rzedy <= 3) return;
        e.setCancelled(true);
        otworzEc(e.getPlayer(), rzedy);
    }

    private int rzedyEc(Player p) { for (int r = 6; r >= 4; r--) if (p.hasPermission("westsmp.enderchest." + r)) return r; return 3; }

    private final Map<UUID, Inventory> ecOtwarte = new HashMap<>();

    private void otworzEc(Player p, int rzedy) {
        File f = new File(pl.getDataFolder(), "enderchest/" + p.getUniqueId() + ".yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Inventory inv = Bukkit.createInventory(new EcHolder(p.getUniqueId()), rzedy * 9, Component.text(WestSMP.c("&#B06AFF&lEnderchest &8(" + rzedy + " rzedow)")));
        ItemStack[] vanilla = p.getEnderChest().getContents();
        for (int i = 0; i < vanilla.length; i++) inv.setItem(i, vanilla[i]);
        for (int i = 27; i < rzedy * 9; i++) inv.setItem(i, y.getItemStack("s" + i));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1f);
    }

    static final class EcHolder implements org.bukkit.inventory.InventoryHolder { final UUID id; EcHolder(UUID id) { this.id = id; } public Inventory getInventory() { return null; } }

    @EventHandler
    public void onEcClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EcHolder h) || !(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        for (int i = 0; i < 27; i++) p.getEnderChest().setItem(i, inv.getItem(i));
        File f = new File(pl.getDataFolder(), "enderchest/" + h.id + ".yml");
        f.getParentFile().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        for (int i = 27; i < inv.getSize(); i++) y.set("s" + i, inv.getItem(i));
        try { y.save(f); } catch (Exception ignored) { }
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.8f, 1f);
    }

    // ===================================================================== RTP
    private void rtp(Player p) {
        long cd = cfg().getLong("rtp.cooldown-sekund", 60) * 1000L;
        if (!p.hasPermission("westsmp.admin") && System.currentTimeMillis() - cdRtp.getOrDefault(p.getUniqueId(), 0L) < cd) {
            p.sendMessage(WestSMP.c("&cLosowy teleport za &f" + ((cd - (System.currentTimeMillis() - cdRtp.get(p.getUniqueId()))) / 1000) + "s")); return;
        }
        World w = Bukkit.getWorld(cfg().getString("rtp.swiat", "world"));
        if (w == null) w = p.getWorld();
        int min = cfg().getInt("rtp.min", 500), max = cfg().getInt("rtp.max", 5000);
        p.sendActionBar(WestSMP.c("&7Szukam bezpiecznego miejsca..."));
        szukaj(p, w, min, max, 0);
    }

    private void szukaj(Player p, World w, int min, int max, int proba) {
        if (proba > 12) { p.sendMessage(WestSMP.c("&cNie znaleziono miejsca, sprobuj ponownie.")); return; }
        double ang = rnd.nextDouble() * Math.PI * 2, r = min + rnd.nextDouble() * (max - min);
        int x = (int) (Math.cos(ang) * r), z = (int) (Math.sin(ang) * r);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
            Block top = w.getHighestBlockAt(x, z);
            Material m = top.getType();
            if (top.isLiquid() || m == Material.LAVA || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m.name().contains("LEAVES") && proba < 8) { szukaj(p, w, min, max, proba + 1); return; }
            Location to = top.getLocation().add(0.5, 1, 0.5);
            cdRtp.put(p.getUniqueId(), System.currentTimeMillis());
            p.teleportAsync(to).thenAccept(ok -> {
                if (!ok) return;
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text(WestSMP.c("&#FFC93D&lLOSOWY TELEPORT")), Component.text(WestSMP.c("&7X: &f" + x + " &7Z: &f" + z))));
                p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
            });
        });
    }

    // ===================================================================== komendy
    private void vanishToggle(Player p) {
        if (vanish.remove(p.getUniqueId())) {
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(pl, p);
            p.sendMessage(WestSMP.c("&7Vanish: &cWYLACZONY"));
        } else {
            vanish.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.hasPermission("westsmp.vanish")) o.hidePlayer(pl, p);
            p.sendMessage(WestSMP.c("&7Vanish: &aWLACZONY &8(inni gracze cie nie widza)"));
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        Player p = s instanceof Player pp ? pp : null;
        Player cel = a.length > 0 ? Bukkit.getPlayerExact(a[a.length - 1]) : null;
        switch (n) {
            case "vanish" -> { if (p != null) vanishToggle(p); }
            case "adminchat" -> {
                if (p == null) return true;
                if (a.length > 0) { String m = String.join(" ", a); for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("westsmp.adminchat")) o.sendMessage(WestSMP.c("&#FF5A68&lAC &8» &f" + p.getName() + "&7: &#FFB3B8") + m); }
                else p.sendMessage(WestSMP.c("&7Adminchat: " + (adminchat.add(p.getUniqueId()) ? "&aWLACZONY" : (adminchat.remove(p.getUniqueId()) ? "&cWYLACZONY" : ""))));
            }
            case "helpop" -> {
                if (p == null || a.length == 0) { s.sendMessage(WestSMP.c("&7/helpop <wiadomosc>")); return true; }
                if (System.currentTimeMillis() - cdHelpop.getOrDefault(p.getUniqueId(), 0L) < 30_000) { p.sendMessage(WestSMP.c("&cHelpop mozesz wyslac co 30s.")); return true; }
                cdHelpop.put(p.getUniqueId(), System.currentTimeMillis());
                String m = String.join(" ", a);
                for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("westsmp.helpop.widzi")) { o.sendMessage(WestSMP.c("&#FFC93D&lHELPOP &8» &f" + p.getName() + "&7: &f") + m); o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f); }
                p.sendMessage(WestSMP.c("&aWyslano do administracji."));
            }
            case "gm" -> {
                if (a.length == 0) { s.sendMessage(WestSMP.c("&7/gm <0|1|2|3> [gracz]")); return true; }
                Player t = a.length > 1 ? cel : p;
                if (t == null) return true;
                GameMode gm = switch (a[0]) { case "1", "c" -> GameMode.CREATIVE; case "2", "a" -> GameMode.ADVENTURE; case "3", "sp" -> GameMode.SPECTATOR; default -> GameMode.SURVIVAL; };
                t.setGameMode(gm); s.sendMessage(WestSMP.c("&7Tryb gry &f" + t.getName() + "&7: &a" + gm.name()));
            }
            case "god" -> { Player t = cel != null ? cel : p; if (t != null) s.sendMessage(WestSMP.c("&7God &f" + t.getName() + "&7: " + (god.add(t.getUniqueId()) ? "&aWLACZONY" : (god.remove(t.getUniqueId()) ? "&cWYLACZONY" : "")))); }
            case "fly" -> { Player t = cel != null ? cel : p; if (t != null) { boolean v = !t.getAllowFlight(); if (v && wWalce(t)) { s.sendMessage(WestSMP.c("&cGracz jest w walce.")); return true; } t.setAllowFlight(v); if (!v) t.setFlying(false); s.sendMessage(WestSMP.c("&7Latanie &f" + t.getName() + "&7: " + (v ? "&aWLACZONE" : "&cWYLACZONE"))); } }
            case "heal" -> { Player t = cel != null ? cel : p; if (t != null) { var m = t.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH); t.setHealth(m == null ? 20 : m.getValue()); t.setFoodLevel(20); t.setFireTicks(0); s.sendMessage(WestSMP.c("&aUleczono &f" + t.getName())); } }
            case "feed" -> { Player t = cel != null ? cel : p; if (t != null) { t.setFoodLevel(20); t.setSaturation(20); s.sendMessage(WestSMP.c("&aNakarmiono &f" + t.getName())); } }
            case "rtp" -> { if (p != null) rtp(p); }
            case "kod" -> {
                if (p == null || a.length == 0) { s.sendMessage(WestSMP.c("&7/kod <KOD>")); return true; }
                String k = a[0].toLowerCase();
                ConfigurationSection c = cfg().getConfigurationSection("kody." + k);
                if (c == null) { p.sendMessage(WestSMP.c("&cNie ma takiego kodu.")); return true; }
                if (c.isSet("wazny-do") && System.currentTimeMillis() > java.time.LocalDate.parse(c.getString("wazny-do")).plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) { p.sendMessage(WestSMP.c("&cTen kod juz wygasl.")); return true; }
                if (kody.getBoolean(k + "." + p.getUniqueId())) { p.sendMessage(WestSMP.c("&cJuz uzyles tego kodu.")); return true; }
                int limit = c.getInt("limit", 0);
                if (limit > 0 && kody.getInt(k + ".__uzyc") >= limit) { p.sendMessage(WestSMP.c("&cKod zostal juz wykorzystany.")); return true; }
                kody.set(k + "." + p.getUniqueId(), true); kody.set(k + ".__uzyc", kody.getInt(k + ".__uzyc") + 1);
                try { kody.save(plikKody); } catch (Exception ignored) { }
                for (String c2 : c.getStringList("komendy")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c2.replace("{GRACZ}", p.getName()));
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text(WestSMP.c("&#FFC93D&lKOD AKTYWOWANY")), Component.text(WestSMP.c(c.getString("opis", "&7Nagroda odebrana!")))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
            case "keyall" -> {
                for (Player o : Bukkit.getOnlinePlayers()) for (String c : cfg().getStringList("keyall.komendy")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{GRACZ}", o.getName()));
                Bukkit.broadcast(Component.text(WestSMP.c(cfg().getString("keyall.wiadomosc", "&#FFC93D&lKEYALL &8» &7Kazdy dostal klucz!"))));
            }
            case "czyszczenie" -> { clearAt = System.currentTimeMillis() + 11_000; s.sendMessage(WestSMP.c("&7Czyszczenie za 10s.")); }
            case "strefa" -> {
                if (p == null || a.length < 2) { s.sendMessage(WestSMP.c("&7/strefa <spawn|afk> <od|do> &8- ustawia rog strefy tam, gdzie stoisz")); return true; }
                String sek = a[0].equalsIgnoreCase("afk") ? "strefa-afk" : "antylogout.strefa-spawnu";
                Location l = p.getLocation();
                cfg().set(sek + "." + (a[1].equalsIgnoreCase("od") ? "od" : "do"), List.of((double) l.getBlockX(), (double) l.getBlockY() - (a[1].equalsIgnoreCase("od") ? 5 : -10), (double) l.getBlockZ()));
                cfg().set(sek + ".swiat", l.getWorld().getName());
                pl.saveConfig();
                s.sendMessage(WestSMP.c("&aUstawiono &f" + a[1] + " &astrefy &f" + a[0] + "&a. Ustaw oba rogi (od i do)."));
            }
            default -> { }
        }
        return true;
    }
}
