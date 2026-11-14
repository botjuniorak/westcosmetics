package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ustawka: wyzwanie 1v1 o stawke na arenie. Ekwipunek zostaje (bez dropu), zwyciezca bierze pule. */
final class Ustawka implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final Map<UUID, Ofer> szkic = new HashMap<>();     // kto tworzy
    private final Map<UUID, Ofer> wyzwania = new HashMap<>();  // wyzwany -> oferta
    private final Map<UUID, Walka> walki = new HashMap<>();

    Ustawka(WestSMP pl) { this.pl = pl; }

    static final class Ofer { UUID od, cel; double stawka; String arena; }
    static final class Walka { Player a, b; double pula; Location wa, wb; boolean koniec; }
    static final class Menu implements InventoryHolder { Inventory inv; @Override public Inventory getInventory() { return inv; } }

    private List<String> areny() { ConfigurationSection s = pl.getConfig().getConfigurationSection("ustawka.areny"); return s == null ? List.of() : new ArrayList<>(s.getKeys(false)); }

    private ItemStack ikona(Material m, String n, List<String> lore) {
        ItemStack it = new ItemStack(m); ItemMeta im = it.getItemMeta(); im.setDisplayName(WestSMP.c(n));
        List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l); im.addItemFlags(ItemFlag.values()); it.setItemMeta(im); return it;
    }

    private void menu(Player p) {
        Ofer o = szkic.get(p.getUniqueId());
        Menu h = new Menu(); h.inv = Bukkit.createInventory(h, 27, Tlo.tytul(0xE140, 3));
        Player cel = Bukkit.getPlayer(o.cel);
        h.inv.setItem(10, ikona(Material.GOLD_INGOT, "&#FFC93D&lSTAWKA: &f" + pl.kasa.fmt(o.stawka), List.of("&7Kliknij i wpisz kwote", "&7Kazdy wplaca tyle samo, zwyciezca bierze wszystko")));
        h.inv.setItem(12, ikona(Material.IRON_SWORD, "&#3AA0FF&lARENA: &f" + (o.arena == null ? "brak" : o.arena), List.of(areny().isEmpty() ? "&cAdmin nie ustawil aren (/ustawka arena <nazwa> 1|2)" : "&7Kliknij, aby zmienic")));
        h.inv.setItem(14, ikona(Material.IRON_CHESTPLATE, "&#C08BFF&lZESTAW: &fwlasny ekwipunek", List.of("&7Walczycie tym, co macie.", "&aNic nie wypada po smierci.")));
        h.inv.setItem(16, ikona(Material.LIME_DYE, "&#4EE08F&lWYSLIJ WYZWANIE", List.of("&7Do: &f" + (cel == null ? "?" : cel.getName()))));
        p.openInventory(h.inv);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Ofer o = szkic.get(p.getUniqueId());
        if (o == null) return;
        switch (e.getRawSlot()) {
            case 10 -> { p.closeInventory(); p.sendMessage(WestSMP.c("&#FFC93D» &fJaka stawka?")); pl.bank.pytanie(p, s -> { double v = Kasa.kwota(s); if (v > 0) o.stawka = v; menu(p); }); }
            case 12 -> { List<String> a = areny(); if (!a.isEmpty()) { int i = o.arena == null ? 0 : (a.indexOf(o.arena) + 1) % a.size(); o.arena = a.get(i); } menu(p); }
            case 16 -> {
                Player cel = Bukkit.getPlayer(o.cel);
                if (cel == null) { p.sendMessage(WestSMP.c("&cGracz wyszedl.")); p.closeInventory(); return; }
                if (o.arena == null) { p.sendMessage(WestSMP.c("&cWybierz arene.")); return; }
                if (!pl.kasa.ma(p, o.stawka)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return; }
                wyzwania.put(o.cel, o); szkic.remove(p.getUniqueId()); p.closeInventory();
                p.sendMessage(WestSMP.c("&aWyslano wyzwanie do &f" + cel.getName()));
                cel.sendMessage(WestSMP.c("&#FF5A68&lUSTAWKA &8» &f" + p.getName() + " &7wyzywa cie! Stawka: &#FFC93D" + pl.kasa.fmt(o.stawka) + " &7Arena: &f" + o.arena));
                cel.sendMessage(WestSMP.c("&7Wpisz &a/ustawka akceptuj &7albo &c/ustawka odrzuc"));
                cel.playSound(cel.getLocation(), Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
            }
            default -> { }
        }
    }

    private Location punkt(String arena, String n) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("ustawka.areny." + arena + "." + n);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", "world"));
        return w == null ? null : new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), 0);
    }

    private void start(Ofer o) {
        Player a = Bukkit.getPlayer(o.od), b = Bukkit.getPlayer(o.cel);
        if (a == null || b == null) return;
        Location la = punkt(o.arena, "1"), lb = punkt(o.arena, "2");
        if (la == null || lb == null) { a.sendMessage(WestSMP.c("&cArena nie jest ustawiona.")); return; }
        if (!pl.kasa.zabierz(a, o.stawka)) { a.sendMessage(WestSMP.c("&cNie masz juz tyle pieniedzy.")); b.sendMessage(WestSMP.c("&cPrzeciwnik nie ma pieniedzy.")); return; }
        if (!pl.kasa.zabierz(b, o.stawka)) { pl.kasa.dodaj(a, o.stawka); b.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return; }
        Walka w = new Walka(); w.a = a; w.b = b; w.pula = o.stawka * 2; w.wa = a.getLocation(); w.wb = b.getLocation();
        walki.put(a.getUniqueId(), w); walki.put(b.getUniqueId(), w);
        a.teleport(la); b.teleport(lb);
        for (Player p : new Player[]{a, b}) { p.setHealth(Math.min(20, p.getHealth() + 20)); p.setFoodLevel(20); }
        int[] t = {5};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            for (Player p : new Player[]{a, b}) if (p.isOnline()) {
                p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.text(WestSMP.c(t[0] > 0 ? "&#FF5A68&l" + t[0] : "&#4EE08F&lWALKA!")),
                    net.kyori.adventure.text.Component.text(WestSMP.c("&7Pula: &#FFC93D" + pl.kasa.fmt(w.pula))), net.kyori.adventure.title.Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200))));
                p.playSound(p.getLocation(), t[0] > 0 ? Sound.BLOCK_NOTE_BLOCK_HAT : Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
            }
            if (t[0]-- <= 0) task.cancel();
        }, 0L, 20L);
        Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FF5A68&lUSTAWKA &8» &f" + a.getName() + " &7vs &f" + b.getName() + " &8• &7pula &#FFC93D" + pl.kasa.fmt(w.pula))));
    }

    private void koniec(Walka w, Player zwyc) {
        if (w.koniec) return;
        w.koniec = true;
        walki.remove(w.a.getUniqueId()); walki.remove(w.b.getUniqueId());
        Player przeg = zwyc == w.a ? w.b : w.a;
        pl.kasa.dodaj(zwyc, w.pula);
        Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FF5A68&lUSTAWKA &8» &f" + zwyc.getName() + " &7pokonal &f" + przeg.getName() + " &7i zgarnia &#FFC93D" + pl.kasa.fmt(w.pula) + "&7!")));
        Location powrot = zwyc == w.a ? w.wa : w.wb;
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (zwyc.isOnline()) { zwyc.teleport(powrot); zwyc.playSound(zwyc.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f); } }, 60L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Walka w = walki.get(e.getEntity().getUniqueId());
        if (w == null) return;
        e.setKeepInventory(true); e.getDrops().clear(); e.setKeepLevel(true); e.setDroppedExp(0);
        koniec(w, e.getEntity() == w.a ? w.b : w.a);
        powroty.put(e.getEntity().getUniqueId(), e.getEntity() == w.a ? w.wa : w.wb);
    }

    private final Map<UUID, Location> powroty = new HashMap<>();

    @EventHandler public void onRespawn(PlayerRespawnEvent e) { Location l = powroty.remove(e.getPlayer().getUniqueId()); if (l != null) e.setRespawnLocation(l); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { Walka w = walki.get(e.getPlayer().getUniqueId()); if (w != null) koniec(w, e.getPlayer() == w.a ? w.b : w.a); }

    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { if (walki.containsKey(e.getPlayer().getUniqueId()) && !e.getPlayer().hasPermission("westsmp.admin")) { e.setCancelled(true); e.getPlayer().sendMessage(WestSMP.c("&cW trakcie ustawki komendy sa zablokowane.")); } }

    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] a) {
        if (!(cs instanceof Player p)) return true;
        if (a.length == 0) { p.sendMessage(WestSMP.c("&7/ustawka <gracz> &8| &7akceptuj &8| &7odrzuc")); return true; }
        switch (a[0].toLowerCase()) {
            case "akceptuj" -> { Ofer o = wyzwania.remove(p.getUniqueId()); if (o == null) p.sendMessage(WestSMP.c("&cNie masz wyzwania.")); else start(o); }
            case "odrzuc" -> { Ofer o = wyzwania.remove(p.getUniqueId()); if (o != null) { Player od = Bukkit.getPlayer(o.od); if (od != null) od.sendMessage(WestSMP.c("&c" + p.getName() + " odrzucil wyzwanie.")); } }
            case "arena" -> {
                if (!p.hasPermission("westsmp.admin") || a.length < 3) { p.sendMessage(WestSMP.c("&7/ustawka arena <nazwa> <1|2> &8- miejsce startu gracza 1 i 2")); return true; }
                Location l = p.getLocation(); String b = "ustawka.areny." + a[1].toLowerCase() + "." + a[2] + ".";
                pl.getConfig().set(b + "swiat", l.getWorld().getName()); pl.getConfig().set(b + "x", l.getX()); pl.getConfig().set(b + "y", l.getY()); pl.getConfig().set(b + "z", l.getZ()); pl.getConfig().set(b + "yaw", (double) l.getYaw());
                pl.saveConfig(); p.sendMessage(WestSMP.c("&aUstawiono punkt " + a[2] + " areny " + a[1]));
            }
            default -> {
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t == p) { p.sendMessage(WestSMP.c("&cGracz musi byc online.")); return true; }
                Ofer o = new Ofer(); o.od = p.getUniqueId(); o.cel = t.getUniqueId(); o.stawka = pl.getConfig().getDouble("ustawka.domyslna-stawka", 1000);
                List<String> ar = areny(); o.arena = ar.isEmpty() ? null : ar.get(0);
                szkic.put(p.getUniqueId(), o); menu(p);
            }
        }
        return true;
    }
}
