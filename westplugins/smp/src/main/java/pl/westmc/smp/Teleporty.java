package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Spawny / warpy (spawn, end, nether, afk + dowolne), odliczanie, menu Teleporty u Przewodnika. */
final class Teleporty implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final Map<UUID, BukkitTask> czeka = new HashMap<>();
    private final Map<UUID, Location> start = new HashMap<>();

    Teleporty(WestSMP pl) { this.pl = pl; }

    Location warp(String n) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("warpy." + n.toLowerCase());
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", "world"));
        if (w == null) return null;
        return new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
    }

    void teleport(Player p, String nazwa) {
        Location to = warp(nazwa);
        if (to == null) { p.sendMessage(WestSMP.c("&cTen spawn nie jest jeszcze ustawiony. &7(/setwarp " + nazwa + ")")); return; }
        teleport(p, to, pl.getConfig().getString("warpy." + nazwa.toLowerCase() + ".nazwa", nazwa));
    }

    /** Teleport z odliczaniem do dowolnego miejsca (np. dom). */
    void teleport(Player p, Location to, String ladna) {
        int sek = p.hasPermission("westsmp.teleport.natychmiast") ? 0 : pl.getConfig().getInt("teleport.odliczanie", 3);
        anuluj(p, false);
        if (sek <= 0) { wykonaj(p, to, ladna); return; }
        start.put(p.getUniqueId(), p.getLocation());
        final int[] left = {sek};
        czeka.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(pl, () -> {
            if (!p.isOnline()) { anuluj(p, false); return; }
            if (left[0] <= 0) { anuluj(p, false); wykonaj(p, to, ladna); return; }
            p.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text(WestSMP.c("&#3AA0FF&l" + left[0])),
                net.kyori.adventure.text.Component.text(WestSMP.c("&7Teleport: &f" + ladna + " &8• &7nie ruszaj sie")),
                net.kyori.adventure.title.Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f + (3 - left[0]) * 0.15f);
            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.3);
            left[0]--;
        }, 0L, 20L));
    }

    private void wykonaj(Player p, Location to, String ladna) {
        p.teleportAsync(to).thenAccept(ok -> {
            if (!ok) return;
            p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
            p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.text(WestSMP.c(ladna)),
                net.kyori.adventure.text.Component.text(WestSMP.c("&7Witaj!")),
                net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(400))));
        });
    }

    private void anuluj(Player p, boolean info) {
        BukkitTask t = czeka.remove(p.getUniqueId());
        start.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            if (info) { p.clearTitle(); p.sendActionBar(WestSMP.c("&c✖ Teleport anulowany - ruszyles sie.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f); }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location s = start.get(e.getPlayer().getUniqueId());
        if (s == null || e.getTo() == null) return;
        if (s.getWorld() != e.getTo().getWorld() || s.distanceSquared(e.getTo()) > 0.25) anuluj(e.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!e.getPlayer().hasPlayedBefore() && pl.getConfig().getBoolean("teleport.nowi-na-spawn", true)) {
            Location sp = warp("spawn");
            if (sp != null) Bukkit.getScheduler().runTaskLater(pl, () -> e.getPlayer().teleport(sp), 5L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.isBedSpawn() || e.isAnchorSpawn() || !pl.getConfig().getBoolean("teleport.odrodzenie-na-spawnie", true)) return;
        Location sp = warp("spawn");
        if (sp != null) e.setRespawnLocation(sp);
    }

    // ===================================================================== komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "setwarp" -> {
                if (!p.hasPermission("westsmp.admin")) { p.sendMessage(WestSMP.c("&cBrak uprawnien.")); return true; }
                if (a.length < 1) { p.sendMessage(WestSMP.c("&7/setwarp <spawn|end|nether|afk|nazwa> [ladna nazwa]")); return true; }
                String n = a[0].toLowerCase(); Location l = p.getLocation();
                String b = "warpy." + n + ".";
                pl.getConfig().set(b + "swiat", l.getWorld().getName());
                pl.getConfig().set(b + "x", l.getX()); pl.getConfig().set(b + "y", l.getY()); pl.getConfig().set(b + "z", l.getZ());
                pl.getConfig().set(b + "yaw", (double) l.getYaw()); pl.getConfig().set(b + "pitch", (double) l.getPitch());
                if (a.length > 1) pl.getConfig().set(b + "nazwa", String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                else if (!pl.getConfig().isSet(b + "nazwa")) pl.getConfig().set(b + "nazwa", "&f" + n);
                pl.saveConfig();
                p.sendMessage(WestSMP.c("&aUstawiono &f" + n + " &atutaj."));
            }
            case "delwarp" -> {
                if (!p.hasPermission("westsmp.admin") || a.length < 1) return true;
                pl.getConfig().set("warpy." + a[0].toLowerCase(), null); pl.saveConfig();
                p.sendMessage(WestSMP.c("&aUsunieto &f" + a[0]));
            }
            case "warp" -> {
                if (a.length < 1) { otworz(p); return true; }
                teleport(p, a[0]);
            }
            case "teleporty" -> otworz(p);
            default -> teleport(p, cmd.getName().toLowerCase());   // spawn, end, nether, afk
        }
        return true;
    }

    // ===================================================================== menu
    static final class Menu implements InventoryHolder {
        Inventory inv; final Map<Integer, String> akcje = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private ItemStack ikona(Material m, String model, String nazwa, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (model != null) im.setItemModel(NamespacedKey.fromString(model));
        im.setDisplayName(WestSMP.c(nazwa));
        List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    void otworz(Player p) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 27, Tlo.tytul(Tlo.TELEPORTY, 3));
        ConfigurationSection m = pl.getConfig().getConfigurationSection("teleporty-menu");
        if (m != null) for (String k : m.getKeys(false)) {
            ConfigurationSection c = m.getConfigurationSection(k);
            Material mat = Material.matchMaterial(c.getString("ikona", "COMPASS"));
            List<String> lore = new ArrayList<>(c.getStringList("opis"));
            lore.add(""); lore.add("&#FFC93D» Kliknij, aby sie przeniesc");
            h.inv.setItem(c.getInt("slot"), ikona(mat == null ? Material.COMPASS : mat, null, c.getString("nazwa", k), lore));
            h.akcje.put(c.getInt("slot"), c.getString("akcja", "warp:" + k));
        }
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String a = h.akcje.get(e.getRawSlot());
        if (a == null) return;
        p.closeInventory();
        if (a.startsWith("warp:")) teleport(p, a.substring(5));
        else if (a.startsWith("komenda:")) p.performCommand(a.substring(8));
    }
}
