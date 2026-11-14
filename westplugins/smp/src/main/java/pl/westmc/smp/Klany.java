package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Klany: tag na czacie, czlonkowie, baza, skarbiec, brak friendly fire, ranking (punkty za zabojstwa). */
final class Klany implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final File plik;
    private final YamlConfiguration d;
    private final Map<UUID, String> zaproszenia = new HashMap<>();
    private static final int[] CZL = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    Klany(WestSMP pl) { this.pl = pl; plik = new File(pl.getDataFolder(), "klany.yml"); d = YamlConfiguration.loadConfiguration(plik); }

    private void zapisz() { try { d.save(plik); } catch (Exception ignored) { } }

    String klan(OfflinePlayer p) { return d.getString("gracze." + p.getUniqueId()); }
    String tag(OfflinePlayer p) { String k = klan(p); return k == null ? "" : WestSMP.c("&8[" + d.getString("klany." + k + ".kolor", "&#FF5A68") + d.getString("klany." + k + ".tag", k) + "&8] "); }
    private List<String> czlonkowie(String k) { return d.getStringList("klany." + k + ".czlonkowie"); }
    private boolean szef(Player p, String k) { return p.getUniqueId().toString().equals(d.getString("klany." + k + ".szef")); }

    List<String[]> ranking(int n) {
        List<String[]> l = new ArrayList<>();
        ConfigurationSection s = d.getConfigurationSection("klany");
        if (s != null) for (String k : s.getKeys(false)) l.add(new String[]{d.getString("klany." + k + ".tag", k), String.valueOf(d.getInt("klany." + k + ".punkty"))});
        l.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return l.subList(0, Math.min(n, l.size()));
    }

    static final class Menu implements InventoryHolder { Inventory inv; String klan; @Override public Inventory getInventory() { return inv; } }

    private ItemStack ikona(Material m, String n, List<String> lore) {
        ItemStack it = new ItemStack(m); ItemMeta im = it.getItemMeta(); im.setDisplayName(WestSMP.c(n));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l); }
        im.addItemFlags(ItemFlag.values()); it.setItemMeta(im); return it;
    }

    void menu(Player p) {
        String k = klan(p);
        if (k == null) { p.sendMessage(WestSMP.c("&7Nie masz klanu. Zaloz: &f/klan zaloz <TAG> <nazwa> &8(koszt " + pl.kasa.fmt(pl.getConfig().getDouble("klany.cena", 10000)) + ")&7, albo dolacz po zaproszeniu.")); return; }
        Menu h = new Menu(); h.klan = k;
        h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(0xE0A0, 4));
        String b = "klany." + k + ".";
        h.inv.setItem(4, ikona(Material.WHITE_BANNER, d.getString(b + "kolor", "&#FF5A68") + "&l" + d.getString(b + "tag") + " &8» &f" + d.getString(b + "nazwa"),
            List.of("&7Szef: &f" + Bukkit.getOfflinePlayer(UUID.fromString(d.getString(b + "szef"))).getName(), "&7Czlonkowie: &f" + czlonkowie(k).size() + "/" + pl.getConfig().getInt("klany.max-czlonkow", 10),
                "&7Punkty: &#FFC93D" + d.getInt(b + "punkty"), "&7Skarbiec: &#FFC93D" + pl.kasa.fmt(d.getDouble(b + "skarbiec")))));
        List<String> cz = czlonkowie(k);
        for (int i = 0; i < cz.size() && i < CZL.length; i++) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(UUID.fromString(cz.get(i)));
            ItemStack g = ikona(Material.PLAYER_HEAD, (cz.get(i).equals(d.getString(b + "szef")) ? "&#FFC93D♛ " : "&f") + o.getName(), List.of(o.isOnline() ? "&a● online" : "&8● offline", szef(p, k) && !o.getUniqueId().equals(p.getUniqueId()) ? "&c» PPM - wyrzuc" : ""));
            SkullMeta sm = (SkullMeta) g.getItemMeta(); sm.setOwningPlayer(o); g.setItemMeta(sm);
            h.inv.setItem(CZL[i], g);
        }
        h.inv.setItem(46, ikona(Material.LIME_DYE, "&#4EE08F&lZAPROS", List.of("&7Kliknij i wpisz nick gracza")));
        h.inv.setItem(48, ikona(Material.COMPASS, "&#3AA0FF&lBAZA KLANU", List.of(d.isSet(b + "baza.x") ? "&7» Kliknij, aby sie przeniesc" : "&cBrak bazy", "&7Szef ustawia: &f/klan ustawbaze")));
        h.inv.setItem(50, ikona(Material.GOLD_INGOT, "&#C08BFF&lSKARBIEC: &#FFC93D" + pl.kasa.fmt(d.getDouble(b + "skarbiec")), List.of("&7Kliknij i wpisz kwote do wplaty", "&7Wyplata (szef): &f/klan wyplac <kwota>")));
        h.inv.setItem(52, ikona(Material.BARRIER, "&#FF5A68&lOPUSC KLAN", List.of(szef(p, k) ? "&7Jestes szefem - opuszczenie &crozwiazuje klan&7!" : "&7Kliknij, aby odejsc")));
        p.openInventory(h.inv);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        switch (s) {
            case 46 -> { p.closeInventory(); p.sendMessage(WestSMP.c("&#FFC93D» &fWpisz nick gracza do zaproszenia:")); pl.bank.pytanie(p, n -> p.performCommand("klan zapros " + n.trim())); }
            case 48 -> { p.closeInventory(); p.performCommand("klan baza"); }
            case 50 -> { p.closeInventory(); p.sendMessage(WestSMP.c("&#FFC93D» &fIle wplacasz do skarbca?")); pl.bank.pytanie(p, n -> p.performCommand("klan wplac " + n.trim())); }
            case 52 -> { p.closeInventory(); p.performCommand("klan opusc"); }
            default -> {
                if (!e.isRightClick() || !szef(p, h.klan)) return;
                ItemStack it = e.getCurrentItem();
                if (it != null && it.getItemMeta() instanceof SkullMeta sm && sm.getOwningPlayer() != null && !sm.getOwningPlayer().getUniqueId().equals(p.getUniqueId())) {
                    p.performCommand("klan wyrzuc " + sm.getOwningPlayer().getName()); menu(p);
                }
            }
        }
    }

    // ===================================================================== bez friendly fire + punkty
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player o)) return;
        Player a = e.getDamager() instanceof Player x ? x : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player y ? y : null;
        if (a == null || a == o) return;
        String k = klan(a);
        if (k != null && k.equals(klan(o)) && !pl.getConfig().getBoolean("klany.friendly-fire", false)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k == e.getEntity()) return;
        String kl = klan(k);
        if (kl != null && !kl.equals(klan(e.getEntity()))) { d.set("klany." + kl + ".punkty", d.getInt("klany." + kl + ".punkty") + 1); zapisz(); }
    }

    // ===================================================================== komendy
    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] a) {
        if (!(cs instanceof Player p)) return true;
        if (a.length == 0) { menu(p); return true; }
        String k = klan(p);
        String b = k == null ? null : "klany." + k + ".";
        switch (a[0].toLowerCase()) {
            case "zaloz" -> {
                if (k != null) { p.sendMessage(WestSMP.c("&cMasz juz klan.")); return true; }
                if (a.length < 3) { p.sendMessage(WestSMP.c("&7/klan zaloz <TAG 2-5 znakow> <nazwa>")); return true; }
                String tag = a[1].toUpperCase();
                if (!tag.matches("[A-Z0-9]{2,5}")) { p.sendMessage(WestSMP.c("&cTag: 2-5 liter/cyfr.")); return true; }
                String id = tag.toLowerCase();
                if (d.isSet("klany." + id)) { p.sendMessage(WestSMP.c("&cTaki klan juz istnieje.")); return true; }
                double cena = pl.getConfig().getDouble("klany.cena", 10000);
                if (!pl.kasa.zabierz(p, cena)) { p.sendMessage(WestSMP.c("&cZalozenie klanu kosztuje &f" + pl.kasa.fmt(cena))); return true; }
                String nb = "klany." + id + ".";
                d.set(nb + "tag", tag); d.set(nb + "nazwa", String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                d.set(nb + "szef", p.getUniqueId().toString()); d.set(nb + "czlonkowie", List.of(p.getUniqueId().toString()));
                d.set("gracze." + p.getUniqueId(), id); zapisz();
                Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FF5A68&lKLANY &8» &f" + p.getName() + " &7zalozyl klan &#FF5A68" + tag + "&7!")));
            }
            case "zapros" -> {
                if (k == null || a.length < 2) { p.sendMessage(WestSMP.c("&7/klan zapros <gracz>")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { p.sendMessage(WestSMP.c("&cGracz offline.")); return true; }
                if (klan(t) != null) { p.sendMessage(WestSMP.c("&cOn ma juz klan.")); return true; }
                if (czlonkowie(k).size() >= pl.getConfig().getInt("klany.max-czlonkow", 10)) { p.sendMessage(WestSMP.c("&cKlan jest pelny.")); return true; }
                zaproszenia.put(t.getUniqueId(), k);
                p.sendMessage(WestSMP.c("&aZaproszono &f" + t.getName()));
                t.sendMessage(WestSMP.c("&#FF5A68&lKLANY &8» &7Zaproszenie do klanu &f" + d.getString(b + "tag") + "&7. Wpisz &a/klan dolacz"));
            }
            case "dolacz" -> {
                String z = zaproszenia.remove(p.getUniqueId());
                if (z == null || !d.isSet("klany." + z)) { p.sendMessage(WestSMP.c("&cNie masz zaproszenia.")); return true; }
                List<String> cz = czlonkowie(z); cz.add(p.getUniqueId().toString());
                d.set("klany." + z + ".czlonkowie", cz); d.set("gracze." + p.getUniqueId(), z); zapisz();
                for (String u : cz) { Player o = Bukkit.getPlayer(UUID.fromString(u)); if (o != null) o.sendMessage(WestSMP.c("&#FF5A68&lKLAN &8» &f" + p.getName() + " &7dolaczyl!")); }
            }
            case "opusc" -> {
                if (k == null) return true;
                if (szef(p, k)) {
                    double sk = d.getDouble(b + "skarbiec"); if (sk > 0) pl.kasa.dodaj(p, sk);
                    for (String u : czlonkowie(k)) d.set("gracze." + u, null);
                    d.set("klany." + k, null); zapisz();
                    p.sendMessage(WestSMP.c("&cRozwiazano klan." + (sk > 0 ? " &7Skarbiec wrocil do ciebie." : "")));
                } else {
                    List<String> cz = czlonkowie(k); cz.remove(p.getUniqueId().toString());
                    d.set(b + "czlonkowie", cz); d.set("gracze." + p.getUniqueId(), null); zapisz();
                    p.sendMessage(WestSMP.c("&7Opusciles klan."));
                }
            }
            case "wyrzuc" -> {
                if (k == null || !szef(p, k) || a.length < 2) return true;
                OfflinePlayer o = Bukkit.getOfflinePlayer(a[1]);
                List<String> cz = czlonkowie(k);
                if (cz.remove(o.getUniqueId().toString())) { d.set(b + "czlonkowie", cz); d.set("gracze." + o.getUniqueId(), null); zapisz(); p.sendMessage(WestSMP.c("&7Wyrzucono &f" + a[1])); }
            }
            case "ustawbaze" -> {
                if (k == null || !szef(p, k)) { p.sendMessage(WestSMP.c("&cTylko szef.")); return true; }
                Location l = p.getLocation();
                d.set(b + "baza.swiat", l.getWorld().getName()); d.set(b + "baza.x", l.getX()); d.set(b + "baza.y", l.getY()); d.set(b + "baza.z", l.getZ()); d.set(b + "baza.yaw", (double) l.getYaw()); zapisz();
                p.sendMessage(WestSMP.c("&aUstawiono baze klanu."));
            }
            case "baza" -> {
                if (k == null || !d.isSet(b + "baza.x")) { p.sendMessage(WestSMP.c("&cKlan nie ma bazy.")); return true; }
                Location l = new Location(Bukkit.getWorld(d.getString(b + "baza.swiat")), d.getDouble(b + "baza.x"), d.getDouble(b + "baza.y"), d.getDouble(b + "baza.z"), (float) d.getDouble(b + "baza.yaw"), 0);
                pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".swiat", l.getWorld().getName());
                pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".x", l.getX()); pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".y", l.getY()); pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".z", l.getZ());
                pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".yaw", (double) l.getYaw()); pl.getConfig().set("warpy.__klan_" + p.getUniqueId() + ".nazwa", "&#FF5A68&lBAZA KLANU");
                pl.teleporty.teleport(p, "__klan_" + p.getUniqueId());
            }
            case "wplac" -> {
                if (k == null || a.length < 2) return true;
                double v = Kasa.kwota(a[1]);
                if (v <= 0 || !pl.kasa.zabierz(p, v)) { p.sendMessage(WestSMP.c("&cZla kwota albo za malo pieniedzy.")); return true; }
                d.set(b + "skarbiec", d.getDouble(b + "skarbiec") + v); zapisz();
                p.sendMessage(WestSMP.c("&aWplacono &#FFC93D" + pl.kasa.fmt(v) + " &ado skarbca."));
            }
            case "wyplac" -> {
                if (k == null || !szef(p, k) || a.length < 2) return true;
                double v = Kasa.kwota(a[1]);
                if (v <= 0 || d.getDouble(b + "skarbiec") < v) { p.sendMessage(WestSMP.c("&cW skarbcu nie ma tyle.")); return true; }
                d.set(b + "skarbiec", d.getDouble(b + "skarbiec") - v); zapisz(); pl.kasa.dodaj(p, v);
                p.sendMessage(WestSMP.c("&aWyplacono &#FFC93D" + pl.kasa.fmt(v)));
            }
            case "kolor" -> {
                if (k == null || !szef(p, k) || a.length < 2 || !a[1].matches("#[0-9a-fA-F]{6}")) { p.sendMessage(WestSMP.c("&7/klan kolor #RRGGBB &8(szef)")); return true; }
                d.set(b + "kolor", "&" + a[1]); zapisz(); p.sendMessage(WestSMP.c("&aZmieniono kolor tagu."));
            }
            default -> p.sendMessage(WestSMP.c("&7/klan &8| &7zaloz, zapros, dolacz, opusc, wyrzuc, baza, ustawbaze, wplac, wyplac, kolor"));
        }
        return true;
    }
}
