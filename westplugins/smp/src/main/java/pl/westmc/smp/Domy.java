package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Domy graczy: /home (menu z lozkami), /home <nazwa>, /sethome <nazwa>, /delhome <nazwa>. Limit wg uprawnien westsmp.home.<n>. */
final class Domy implements CommandExecutor, TabCompleter, Listener {
    private static final int TLO = 0xE260, MAX = 7;
    private static final int[] SLOTY = {10, 11, 12, 13, 14, 15, 16};
    private final WestSMP pl;
    private final File plik;
    private final YamlConfiguration d;

    Domy(WestSMP pl) { this.pl = pl; plik = new File(pl.getDataFolder(), "domy.yml"); d = YamlConfiguration.loadConfiguration(plik); }

    private void zapisz() { try { d.save(plik); } catch (Exception e) { pl.getLogger().warning("domy.yml: " + e.getMessage()); } }

    static final class Menu implements InventoryHolder { Inventory inv; final List<String> nazwy = new ArrayList<>(); @Override public Inventory getInventory() { return inv; } }

    int limit(Player p) {
        for (int i = MAX; i > 0; i--) if (p.hasPermission("westsmp.home." + i)) return i;
        return pl.getConfig().getInt("domy.limit-gracz", 2);
    }

    private String rangaDla(int nr) {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("domy.rangi");
        String best = null; int bl = 99;
        if (s != null) for (String k : s.getKeys(false)) { int l = s.getInt(k); if (l >= nr && l < bl) { bl = l; best = k; } }
        return best == null ? "wyzszej rangi" : "rangi " + best;
    }

    private List<String> nazwy(Player p) {
        ConfigurationSection s = d.getConfigurationSection(p.getUniqueId().toString());
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    private Location dom(Player p, String n) {
        ConfigurationSection c = d.getConfigurationSection(p.getUniqueId() + "." + n);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", ""));
        return w == null ? null : new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
    }

    private String znajdz(Player p, String n) { for (String k : nazwy(p)) if (k.equalsIgnoreCase(n)) return k; return null; }

    private boolean ustaw(Player p, String n) {
        n = n.replaceAll("[^A-Za-z0-9_\\-ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]", "");
        if (n.isEmpty() || n.length() > 16) { p.sendMessage(WestSMP.c("&cNazwa domu: 1-16 znakow (litery, cyfry, _)")); return false; }
        String jest = znajdz(p, n);
        if (jest == null && nazwy(p).size() >= limit(p)) { p.sendMessage(WestSMP.c("&cMasz juz maksymalna liczbe domow (&f" + limit(p) + "&c).")); return false; }
        if (pl.getConfig().getStringList("domy.zablokowane-swiaty").contains(p.getWorld().getName())) { p.sendMessage(WestSMP.c("&cW tym swiecie nie mozna ustawiac domow.")); return false; }
        String k = p.getUniqueId() + "." + (jest != null ? jest : n);
        Location l = p.getLocation();
        d.set(k + ".swiat", l.getWorld().getName());
        d.set(k + ".x", Math.round(l.getX() * 100) / 100.0); d.set(k + ".y", Math.round(l.getY() * 100) / 100.0); d.set(k + ".z", Math.round(l.getZ() * 100) / 100.0);
        d.set(k + ".yaw", (double) Math.round(l.getYaw())); d.set(k + ".pitch", (double) Math.round(l.getPitch()));
        zapisz();
        p.playSound(l, Sound.BLOCK_WOOL_PLACE, 1f, 1f);
        p.sendMessage(WestSMP.c("&#4EE08F&lDOMY &8» &7Ustawiono dom &f" + (jest != null ? jest : n) + "&7."));
        return true;
    }

    private void usun(Player p, String n) {
        String k = znajdz(p, n);
        if (k == null) { p.sendMessage(WestSMP.c("&cNie masz domu o nazwie &f" + n + "&c.")); return; }
        d.set(p.getUniqueId() + "." + k, null); zapisz();
        p.sendMessage(WestSMP.c("&#4EE08F&lDOMY &8» &7Usunieto dom &f" + k + "&7."));
    }

    private void tp(Player p, String n) {
        String k = znajdz(p, n);
        Location l = k == null ? null : dom(p, k);
        if (l == null) { p.sendMessage(WestSMP.c("&cNie masz domu o nazwie &f" + n + "&c.")); return; }
        if (pl.narzedzia != null && pl.narzedzia.wWalce(p)) { p.sendMessage(WestSMP.c("&cNie mozesz sie teleportowac w trakcie walki!")); return; }
        pl.teleporty.teleport(p, l, "dom " + k);
    }

    void otworz(Player p) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 36, Tlo.tytul(TLO, 4));
        List<String> n = nazwy(p);
        int lim = limit(p);
        Sklepy sk = pl.sklepy;
        for (int i = 0; i < MAX; i++) {
            if (i < n.size()) {
                Location l = dom(p, n.get(i));
                h.inv.setItem(SLOTY[i], sk.ikona(Material.RED_BED, null, "&#4EE08F&l" + n.get(i), List.of(
                    "&7Swiat: &f" + (l == null ? "?" : l.getWorld().getName()),
                    l == null ? "&cSwiat nie istnieje" : "&7X: &f" + l.getBlockX() + " &7Y: &f" + l.getBlockY() + " &7Z: &f" + l.getBlockZ(),
                    "", "&#4EE08F» LPM - teleport", "&#FF5A68» PPM - usun"), false));
                h.nazwy.add(n.get(i));
            } else if (i < lim) {
                h.inv.setItem(SLOTY[i], sk.ikona(Material.WHITE_BED, null, "&#4EE08F&l+ Wolny slot", List.of("&7Kliknij, aby ustawic", "&7dom tutaj (nazwiesz go", "&7na czacie)."), false));
                h.nazwy.add(null);
            } else {
                h.inv.setItem(SLOTY[i], sk.ikona(Material.GRAY_BED, null, "&8&lZablokowany", List.of("&7Dom nr " + (i + 1) + " dostepny od", "&7" + rangaDla(i + 1) + "."), false));
                h.nazwy.add(null);
            }
        }
        h.inv.setItem(31, sk.ikona(Material.BOOK, null, "&#3ADFFF&lTWOJE DOMY", List.of("&7Masz &f" + n.size() + "&7/&f" + lim + " &7domow.", "",
            "&f/home <nazwa> &7- teleport", "&f/sethome <nazwa> &7- ustaw", "&f/delhome <nazwa> &7- usun"), false));
        h.inv.setItem(35, sk.ikona(Material.BARRIER, "westmc:przycisk_zamknij", "&c&lZamknij", null, false));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        int s = e.getRawSlot();
        if (s == 35) { p.closeInventory(); return; }
        for (int i = 0; i < SLOTY.length; i++) if (SLOTY[i] == s) {
            String n = h.nazwy.get(i);
            if (n != null) {
                p.closeInventory();
                if (e.isRightClick()) usun(p, n); else tp(p, n);
            } else if (i < limit(p)) {
                p.closeInventory();
                p.sendMessage(WestSMP.c("&#4EE08F&lDOMY &8» &7Wpisz na czacie nazwe nowego domu &8(np. baza)&7:"));
                pl.bank.pytanie(p, txt -> { if (ustaw(p, txt.trim())) otworz(p); });
            } else p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
    }

    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] a) {
        if (!(cs instanceof Player p)) { cs.sendMessage("Tylko w grze."); return true; }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> ustaw(p, a.length > 0 ? a[0] : "dom");
            case "delhome" -> { if (a.length < 1) p.sendMessage(WestSMP.c("&7/delhome <nazwa>")); else usun(p, a[0]); }
            default -> { if (a.length > 0) tp(p, a[0]); else otworz(p); }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p) || a.length != 1) return List.of();
        List<String> r = nazwy(p);
        r.removeIf(x -> !x.toLowerCase().startsWith(a[0].toLowerCase()));
        return r;
    }
}
