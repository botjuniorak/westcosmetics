package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Sklep ($ i czas), skrzynie z kluczami i ruletka, zestawy, dzienne nagrody, kowal. */
final class Sklepy implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final NamespacedKey KLUCZ;
    private final File plik;
    private final YamlConfiguration dane;
    private final Random rnd = new Random();
    private final Set<UUID> losuje = new HashSet<>();
    private static final int[] SIATKA = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final String[] TYPY = {"zwykla", "epicka", "legendarna"};

    Sklepy(WestSMP pl) {
        this.pl = pl;
        KLUCZ = new NamespacedKey(pl, "klucz");
        plik = new File(pl.getDataFolder(), "gracze.yml");
        dane = YamlConfiguration.loadConfiguration(plik);
    }

    private void zapisz() { try { dane.save(plik); } catch (Exception ignored) { } }

    static final class Menu implements InventoryHolder {
        Inventory inv; String typ, tab = ""; final Map<Integer, String> akcje = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private Menu menu(String typ, int rozmiar, int base, int rzedy) {
        Menu h = new Menu(); h.typ = typ;
        h.inv = Bukkit.createInventory(h, rozmiar, Tlo.tytul(base, rzedy));
        return h;
    }

    ItemStack ikona(Material m, String model, String nazwa, List<String> lore, boolean glint) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (model != null) im.setItemModel(NamespacedKey.fromString(model));
        if (nazwa != null) im.setDisplayName(WestSMP.c(nazwa));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l); }
        if (glint) im.setEnchantmentGlintOverride(true);
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    /** "DIAMOND 5", "eventowka:kilof", "klucz:epicka 2" -> przedmiot. */
    ItemStack przedmiot(String spec) {
        String[] x = spec.trim().split("\\s+");
        int n = x.length > 1 ? Integer.parseInt(x[1]) : 1;
        if (x[0].startsWith("eventowka:")) return pl.eventowki.item(x[0].substring(10), n);
        if (x[0].startsWith("ksiega:")) { ItemStack k = pl.ksiegi.ksiega(x[0].substring(7)); if (k != null) { k.setAmount(n); return k; } }
        if (x[0].startsWith("klucz:")) { ItemStack k = klucz(x[0].substring(6)); k.setAmount(n); return k; }
        Material m = Material.matchMaterial(x[0]);
        return new ItemStack(m == null ? Material.STONE : m, n);
    }

    private void wykonaj(Player p, List<String> komendy, List<String> przedmioty) {
        for (String c : komendy) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{GRACZ}", p.getName()));
        for (String s : przedmioty) p.getInventory().addItem(przedmiot(s)).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

    // ===================================================================== SKLEP
    void sklep(Player p, String waluta) {
        Menu h = menu("sklep", 54, 0xE060, 4); h.tab = waluta;
        ConfigurationSection s = pl.getConfig().getConfigurationSection("sklep." + waluta);
        int i = 0;
        if (s != null) for (String k : s.getKeys(false)) {
            if (i >= SIATKA.length) break;
            ConfigurationSection t = s.getConfigurationSection(k);
            double cena = t.getDouble("cena");
            String cenaTxt = waluta.equals("czas") ? "&#4EE08F" + (long) cena + " pkt czasu" : "&#FFC93D" + pl.kasa.fmt(cena);
            ItemStack it = przedmiot(t.getString("przedmiot", "STONE"));
            ItemMeta im = it.getItemMeta();
            if (t.isSet("nazwa")) im.setDisplayName(WestSMP.c(t.getString("nazwa")));
            List<String> lore = new ArrayList<>();
            if (im.hasLore() && im.getLore() != null) lore.addAll(im.getLore());
            lore.add(""); lore.add(WestSMP.c("&7Cena: " + cenaTxt)); lore.add(WestSMP.c("&#FFC93D» LPM - kup &8| &#FFC93DPPM - kup x" + t.getInt("hurt", 1) * 8));
            im.setLore(lore); im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES); it.setItemMeta(im);
            h.inv.setItem(SIATKA[i], it);
            h.akcje.put(SIATKA[i], "kup:" + k);
            i++;
        }
        h.inv.setItem(47, ikona(Material.GOLD_INGOT, null, (waluta.equals("dolary") ? "&a» " : "") + "&#FFC93D&lSKLEP ZA $", List.of("&7Saldo: &f" + pl.kasa.fmt(pl.kasa.saldo(p))), waluta.equals("dolary")));
        h.akcje.put(47, "tab:dolary");
        h.inv.setItem(49, ikona(Material.BARRIER, "westmc:przycisk_zamknij", "&c&lZamknij", null, false)); h.akcje.put(49, "zamknij");
        h.inv.setItem(51, ikona(Material.CLOCK, null, (waluta.equals("czas") ? "&a» " : "") + "&#4EE08F&lSKLEP ZA CZAS", List.of("&7Punkty czasu: &f" + pl.bank.punkty(p)), waluta.equals("czas")));
        h.akcje.put(51, "tab:czas");
        p.openInventory(h.inv);
    }

    private void kup(Player p, String waluta, String id, boolean duzo) {
        ConfigurationSection t = pl.getConfig().getConfigurationSection("sklep." + waluta + "." + id);
        if (t == null) return;
        int razy = duzo ? 8 : 1;
        double cena = t.getDouble("cena") * razy;
        boolean ok = waluta.equals("czas") ? pl.bank.zabierzPunkty(p, (long) cena) : pl.kasa.zabierz(p, cena);
        if (!ok) { p.sendMessage(WestSMP.c(waluta.equals("czas") ? "&cMasz za malo punktow czasu." : "&cNie masz tyle pieniedzy.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f); return; }
        for (int i = 0; i < razy; i++) wykonaj(p, t.getStringList("komendy"), t.isSet("przedmiot") ? List.of(t.getString("przedmiot")) : List.of());
        p.sendMessage(WestSMP.c("&aKupiono &f" + WestSMP.c(t.getString("nazwa", id)) + (razy > 1 ? " &7x" + razy : "")));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        sklep(p, waluta);
    }

    // ===================================================================== SKRZYNIE + KLUCZE
    ItemStack klucz(String typ) {
        ItemStack k = ikona(Material.TRIPWIRE_HOOK, "westmc:klucz/" + typ, pl.getConfig().getString("skrzynie." + typ + ".nazwa-klucza", "&fKlucz " + typ),
            List.of("&7Otwiera: " + pl.getConfig().getString("skrzynie." + typ + ".nazwa", typ), "", "&7Uzyj przy skrzyni na spawnie", "&7albo w &f/skrzynie"), true);
        ItemMeta m = k.getItemMeta(); m.getPersistentDataContainer().set(KLUCZ, PersistentDataType.STRING, typ); k.setItemMeta(m);
        return k;
    }

    private int ileKluczy(Player p, String typ) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.hasItemMeta() && typ.equals(it.getItemMeta().getPersistentDataContainer().get(KLUCZ, PersistentDataType.STRING))) n += it.getAmount();
        return n;
    }

    private boolean zabierzKlucz(Player p, String typ) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it != null && it.hasItemMeta() && typ.equals(it.getItemMeta().getPersistentDataContainer().get(KLUCZ, PersistentDataType.STRING))) {
                it.setAmount(it.getAmount() - 1); p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it); return true;
            }
        }
        return false;
    }

    void skrzynie(Player p) {
        Menu h = menu("skrzynie", 27, 0xE0C0, 3);
        int[] sl = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            String t = TYPY[i];
            h.inv.setItem(sl[i], ikona(Material.CHEST, "westmc:skrzynia/" + t, pl.getConfig().getString("skrzynie." + t + ".nazwa", t),
                List.of("&7Twoje klucze: &f" + ileKluczy(p, t), "", "&#FFC93D» LPM - otworz", "&7» PPM - zobacz nagrody"), i == 2));
            h.akcje.put(sl[i], "skrzynia:" + t);
        }
        p.openInventory(h.inv);
    }

    void podglad(Player p, String typ) {
        Menu h = menu("podglad", 54, 0xE1E0, 4); h.tab = typ;
        List<Map<?, ?>> n = pl.getConfig().getMapList("skrzynie." + typ + ".nagrody");
        int suma = 0; for (Map<?, ?> m : n) suma += ((Number) ((Map<String, Object>) m).getOrDefault("szansa", 1)).intValue();
        for (int i = 0; i < n.size() && i < SIATKA.length; i++) {
            Map<String, Object> m = (Map<String, Object>) n.get(i);
            ItemStack it = nagrodaIkona(m);
            ItemMeta im = it.getItemMeta();
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            lore.add(""); lore.add(WestSMP.c("&7Szansa: &f" + String.format("%.1f", 100.0 * ((Number) m.getOrDefault("szansa", 1)).intValue() / Math.max(1, suma)) + "%"));
            im.setLore(lore); it.setItemMeta(im);
            h.inv.setItem(SIATKA[i], it);
        }
        h.inv.setItem(49, ikona(Material.BARRIER, "westmc:przycisk_zamknij", "&c&lWstecz", null, false)); h.akcje.put(49, "wstecz");
        p.openInventory(h.inv);
    }

    private ItemStack nagrodaIkona(Map<String, Object> m) {
        ItemStack it = przedmiot(String.valueOf(m.getOrDefault("przedmiot", m.getOrDefault("ikona", "PAPER"))));
        if (m.containsKey("nazwa")) { ItemMeta im = it.getItemMeta(); im.setDisplayName(WestSMP.c(String.valueOf(m.get("nazwa")))); it.setItemMeta(im); }
        return it;
    }

    private Map<String, Object> losuj(String typ) {
        List<Map<?, ?>> n = pl.getConfig().getMapList("skrzynie." + typ + ".nagrody");
        int suma = 0; for (Map<?, ?> m : n) suma += ((Number) ((Map<String, Object>) m).getOrDefault("szansa", 1)).intValue();
        int r = rnd.nextInt(Math.max(1, suma));
        for (Map<?, ?> m0 : n) { Map<String, Object> m = (Map<String, Object>) m0; r -= ((Number) m.getOrDefault("szansa", 1)).intValue(); if (r < 0) return m; }
        return n.isEmpty() ? Map.of() : (Map<String, Object>) n.get(0);
    }

    void otworzSkrzynie(Player p, String typ) {
        if (losuje.contains(p.getUniqueId())) return;
        if (!zabierzKlucz(p, typ)) { p.sendMessage(WestSMP.c("&cNie masz klucza do tej skrzyni.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f); podglad(p, typ); return; }
        losuje.add(p.getUniqueId());
        Map<String, Object> wygrana = losuj(typ);
        Menu h = menu("ruletka", 27, 0xE1C0, 3);
        h.inv.setItem(4, ikona(Material.HOPPER, null, "&#FFC93D▼", null, false));
        h.inv.setItem(22, ikona(Material.HOPPER, null, "&#FFC93D▲", null, false));
        p.openInventory(h.inv);
        List<ItemStack> tasma = new ArrayList<>();
        int kroki = 34;
        for (int i = 0; i < kroki + 9; i++) tasma.add(nagrodaIkona(i == kroki + 4 ? wygrana : losuj(typ)));
        int[] k = {0}, czek = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            int odstep = k[0] < 18 ? 1 : k[0] < 26 ? 2 : k[0] < 31 ? 3 : 5;
            if (++czek[0] < odstep && k[0] < kroki) return;
            czek[0] = 0;
            for (int i = 0; i < 9; i++) h.inv.setItem(9 + i, tasma.get(k[0] + i));
            if (p.isOnline()) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
            if (k[0] >= kroki) {
                task.cancel();
                losuje.remove(p.getUniqueId());
                List<String> kom = (List<String>) wygrana.getOrDefault("komendy", List.of());
                List<String> prz = wygrana.containsKey("przedmiot") ? List.of(String.valueOf(wygrana.get("przedmiot"))) : List.of();
                if (p.isOnline()) {
                    wykonaj(p, kom, prz);
                    String nz = WestSMP.c(String.valueOf(wygrana.getOrDefault("nazwa", nagrodaIkona(wygrana).getType().name())));
                    p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.text(WestSMP.c("&#FFC93D&lWYGRANA!")), net.kyori.adventure.text.Component.text(nz)));
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
                    if (Boolean.TRUE.equals(wygrana.get("ogloszenie")))
                        Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FF7A5A&lSKRZYNIE &8» &f" + p.getName() + " &7wylosowal " + nz + " &7z " + pl.getConfig().getString("skrzynie." + typ + ".nazwa", typ) + "&7!")));
                }
                return;
            }
            k[0]++;
        }, 2L, 2L);
    }

    @EventHandler
    public void onSkrzyniaBlok(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        String key = b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
        String typ = pl.getConfig().getString("skrzynie-bloki." + key.replace('.', '_'));
        if (typ == null) return;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) podglad(e.getPlayer(), typ);
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) { otworzSkrzynie(e.getPlayer(), typ); if (pl.skrzynieModele != null) pl.skrzynieModele.puls(key.replace('.', '_')); }
        b.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 1.2, 0.5), 6, 0.3, 0.2, 0.3, 0.02);
    }

    // ===================================================================== ZESTAWY
    void zestawy(Player p) {
        Menu h = menu("zestawy", 27, 0xE100, 3);
        ConfigurationSection s = pl.getConfig().getConfigurationSection("zestawy");
        if (s != null) for (String k : s.getKeys(false)) {
            ConfigurationSection t = s.getConfigurationSection(k);
            int slot = t.getInt("slot", 10);
            String perm = t.getString("uprawnienie", "");
            boolean ma = perm.isEmpty() || p.hasPermission(perm);
            long za = dane.getLong(p.getUniqueId() + ".zestaw." + k) + t.getLong("co-ile-godzin", 24) * 3_600_000L - System.currentTimeMillis();
            List<String> lore = new ArrayList<>(t.getStringList("opis"));
            lore.add("");
            if (!ma) lore.add("&c✖ Wymaga rangi " + t.getString("ranga", ""));
            else if (za > 0) lore.add("&e⌚ Dostepny za: &f" + (za / 3_600_000) + "h " + (za / 60_000 % 60) + "min");
            else lore.add("&a» Kliknij, aby odebrac!");
            Material m = Material.matchMaterial(t.getString("ikona", "CHEST"));
            h.inv.setItem(slot, ikona(m == null ? Material.CHEST : m, null, t.getString("nazwa", k), lore, ma && za <= 0));
            h.akcje.put(slot, "zestaw:" + k);
        }
        p.openInventory(h.inv);
    }

    private void odbierzZestaw(Player p, String k) {
        ConfigurationSection t = pl.getConfig().getConfigurationSection("zestawy." + k);
        if (t == null) return;
        String perm = t.getString("uprawnienie", "");
        if (!perm.isEmpty() && !p.hasPermission(perm)) { p.sendMessage(WestSMP.c("&cTen zestaw wymaga rangi " + t.getString("ranga", ""))); return; }
        String b = p.getUniqueId() + ".zestaw." + k;
        if (dane.getLong(b) + t.getLong("co-ile-godzin", 24) * 3_600_000L > System.currentTimeMillis()) { p.sendMessage(WestSMP.c("&cJeszcze nie mozesz odebrac tego zestawu.")); return; }
        dane.set(b, System.currentTimeMillis()); zapisz();
        File f = new File(pl.getDataFolder(), "zestawy.yml");
        YamlConfiguration z = YamlConfiguration.loadConfiguration(f);
        List<?> zapisane = z.getList(k);
        if (zapisane != null) for (Object o : zapisane) if (o instanceof ItemStack it) p.getInventory().addItem(it.clone()).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        wykonaj(p, t.getStringList("komendy"), t.getStringList("przedmioty"));
        p.sendMessage(WestSMP.c("&aOdebrano zestaw " + t.getString("nazwa", k)));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
        zestawy(p);
    }

    // ===================================================================== DZIENNE NAGRODY
    void nagrody(Player p) {
        Menu h = menu("nagrody", 27, 0xE0E0, 3);
        String b = p.getUniqueId() + ".dzienne.";
        LocalDate dzis = LocalDate.now();
        String ost = dane.getString(b + "ostatnio", "");
        int seria = dane.getInt(b + "seria", 0);
        if (!ost.isEmpty() && LocalDate.parse(ost).isBefore(dzis.minusDays(1))) seria = 0;   // przerwana seria
        boolean odebrane = ost.equals(dzis.toString());
        int dzisiejszy = odebrane ? seria : (seria % 7) + 1;
        for (int d = 1; d <= 7; d++) {
            ConfigurationSection t = pl.getConfig().getConfigurationSection("dzienne-nagrody.dzien-" + d);
            Material m = t == null ? Material.GOLD_NUGGET : Material.matchMaterial(t.getString("ikona", "GOLD_NUGGET"));
            List<String> lore = new ArrayList<>(t == null ? List.of() : t.getStringList("opis"));
            lore.add("");
            boolean wziete = d < dzisiejszy || (d == dzisiejszy && odebrane);
            if (wziete) lore.add("&a✔ Odebrane");
            else if (d == dzisiejszy) lore.add("&#FFC93D» Kliknij, aby odebrac!");
            else lore.add("&7Za " + (d - dzisiejszy) + " dni");
            h.inv.setItem(9 + d, ikona(m == null ? Material.GOLD_NUGGET : m, null, "&#FFC93D&lDZIEN " + d, lore, d == dzisiejszy && !odebrane));
            if (d == dzisiejszy && !odebrane) h.akcje.put(9 + d, "dzienna:" + d);
        }
        p.openInventory(h.inv);
    }

    private void odbierzDzienna(Player p, int d) {
        String b = p.getUniqueId() + ".dzienne.";
        LocalDate dzis = LocalDate.now();
        if (dzis.toString().equals(dane.getString(b + "ostatnio"))) return;
        String ost = dane.getString(b + "ostatnio", "");
        int seria = dane.getInt(b + "seria", 0);
        if (!ost.isEmpty() && LocalDate.parse(ost).isBefore(dzis.minusDays(1))) seria = 0;
        seria = (seria % 7) + 1;
        dane.set(b + "seria", seria); dane.set(b + "ostatnio", dzis.toString()); zapisz();
        ConfigurationSection t = pl.getConfig().getConfigurationSection("dzienne-nagrody.dzien-" + seria);
        if (t != null) wykonaj(p, t.getStringList("komendy"), t.getStringList("przedmioty"));
        p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.text(WestSMP.c("&#FFC93D&lDZIEN " + seria)), net.kyori.adventure.text.Component.text(WestSMP.c("&7Nagroda odebrana! Wroc jutro."))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        nagrody(p);
    }

    // ===================================================================== KOWAL
    void kowal(Player p) {
        Menu h = menu("kowal", 27, 0xE040, 3);
        h.inv.setItem(12, ikona(Material.ANVIL, null, "&#4EE08F&lNAPRAW", List.of("&7Naprawia przedmiot z lewego pola.", "&7Koszt: &f" + pl.kasa.fmt(pl.getConfig().getDouble("kowal.cena-za-punkt", 2)) + " &7za punkt wytrzymalosci"), false));
        h.akcje.put(12, "napraw");
        h.inv.setItem(14, ikona(Material.EXPERIENCE_BOTTLE, null, "&#FFC93D&lNAPRAW WSZYSTKO", List.of("&7Naprawia caly ekwipunek i zbroje.", "&7Koszt liczony za kazdy przedmiot."), false));
        h.akcje.put(14, "wszystko");
        h.inv.setItem(16, ikona(Material.ENCHANTED_BOOK, null, "&#D9B8FF&lULEPSZ", List.of("&7Podnosi losowe zaklecie o 1 poziom", "&7(do maksimum). Koszt: &f" + pl.kasa.fmt(pl.getConfig().getDouble("kowal.cena-ulepszenia", 5000)), "&7Szansa powodzenia: &f" + (int) (pl.getConfig().getDouble("kowal.szansa-ulepszenia", 0.6) * 100) + "%"), false));
        h.akcje.put(16, "ulepsz");
        p.openInventory(h.inv);
    }

    private double kosztNaprawy(ItemStack it) {
        if (it == null || !(it.getItemMeta() instanceof Damageable d) || !d.hasDamage()) return 0;
        return d.getDamage() * pl.getConfig().getDouble("kowal.cena-za-punkt", 2);
    }

    private void napraw(ItemStack it) { if (it != null && it.getItemMeta() instanceof Damageable d) { d.setDamage(0); it.setItemMeta(d); } }

    private void kowalAkcja(Player p, Menu h, String a) {
        ItemStack it = h.inv.getItem(10);
        switch (a) {
            case "napraw" -> {
                double k = kosztNaprawy(it);
                if (k <= 0) { p.sendMessage(WestSMP.c("&cPoloz uszkodzony przedmiot w lewym polu.")); return; }
                if (!pl.kasa.zabierz(p, k)) { p.sendMessage(WestSMP.c("&cNaprawa kosztuje &f" + pl.kasa.fmt(k))); return; }
                napraw(it); h.inv.setItem(10, it);
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
                p.sendMessage(WestSMP.c("&aNaprawiono za &#FFC93D" + pl.kasa.fmt(k)));
            }
            case "wszystko" -> {
                double k = kosztNaprawy(it);
                for (ItemStack i : p.getInventory().getContents()) k += kosztNaprawy(i);
                if (k <= 0) { p.sendMessage(WestSMP.c("&7Nic nie wymaga naprawy.")); return; }
                if (!pl.kasa.zabierz(p, k)) { p.sendMessage(WestSMP.c("&cNaprawa wszystkiego kosztuje &f" + pl.kasa.fmt(k))); return; }
                napraw(it); h.inv.setItem(10, it);
                for (ItemStack i : p.getInventory().getContents()) napraw(i);
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1f);
                p.sendMessage(WestSMP.c("&aNaprawiono wszystko za &#FFC93D" + pl.kasa.fmt(k)));
            }
            case "ulepsz" -> {
                if (it == null || it.getEnchantments().isEmpty()) { p.sendMessage(WestSMP.c("&cPoloz zaczarowany przedmiot w lewym polu.")); return; }
                List<Enchantment> mozna = new ArrayList<>();
                for (Map.Entry<Enchantment, Integer> en : it.getEnchantments().entrySet()) if (en.getValue() < en.getKey().getMaxLevel()) mozna.add(en.getKey());
                if (mozna.isEmpty()) { p.sendMessage(WestSMP.c("&7Wszystkie zaklecia sa juz na maksimum.")); return; }
                double cena = pl.getConfig().getDouble("kowal.cena-ulepszenia", 5000);
                if (!pl.kasa.zabierz(p, cena)) { p.sendMessage(WestSMP.c("&cUlepszenie kosztuje &f" + pl.kasa.fmt(cena))); return; }
                if (rnd.nextDouble() < pl.getConfig().getDouble("kowal.szansa-ulepszenia", 0.6)) {
                    Enchantment en = mozna.get(rnd.nextInt(mozna.size()));
                    it.addUnsafeEnchantment(en, it.getEnchantmentLevel(en) + 1); h.inv.setItem(10, it);
                    p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                    p.sendMessage(WestSMP.c("&a&lUDALO SIE! &7" + en.getKey().getKey() + " &f" + it.getEnchantmentLevel(en)));
                } else { p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 0.6f, 1.4f); p.sendMessage(WestSMP.c("&c&lNIE UDALO SIE. &7Sprobuj ponownie.")); }
            }
            default -> { }
        }
    }

    // ===================================================================== klikanie
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        if (h.typ.equals("kowal")) { for (int s : e.getRawSlots()) if (s < 27 && s != 10) { e.setCancelled(true); return; } return; }
        e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        boolean gora = e.getClickedInventory() == e.getInventory();
        if (h.typ.equals("kowal")) {
            if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
            if (!gora) {
                if (e.isShiftClick()) { e.setCancelled(true); if (h.inv.getItem(10) == null && e.getCurrentItem() != null) { h.inv.setItem(10, e.getCurrentItem()); e.setCurrentItem(null); } }
                return;
            }
            if (e.getRawSlot() == 10) return;   // pole na przedmiot - normalnie
            e.setCancelled(true);
        } else e.setCancelled(true);
        if (!gora) return;
        String a = h.akcje.get(e.getRawSlot());
        if (a == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        if (a.equals("zamknij")) { p.closeInventory(); return; }
        if (a.equals("wstecz")) { skrzynie(p); return; }
        if (a.startsWith("tab:")) { sklep(p, a.substring(4)); return; }
        if (a.startsWith("kup:")) { kup(p, h.tab, a.substring(4), e.isRightClick()); return; }
        if (a.startsWith("skrzynia:")) { if (e.isRightClick()) podglad(p, a.substring(9)); else otworzSkrzynie(p, a.substring(9)); return; }
        if (a.startsWith("zestaw:")) { odbierzZestaw(p, a.substring(7)); return; }
        if (a.startsWith("dzienna:")) { odbierzDzienna(p, Integer.parseInt(a.substring(8))); return; }
        if (h.typ.equals("kowal")) kowalAkcja(p, h, a);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !h.typ.equals("kowal")) return;
        ItemStack it = h.inv.getItem(10);
        if (it != null && e.getPlayer() instanceof Player p) { h.inv.setItem(10, null); p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r)); }
    }

    // ===================================================================== komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        if (n.equals("klucz")) {
            if (!s.hasPermission("westsmp.admin") || a.length < 3) { s.sendMessage(WestSMP.c("&7/klucz daj <gracz|*> <zwykla|epicka|legendarna> [ilosc]")); return true; }
            int ile = a.length > 3 ? Integer.parseInt(a[3]) : 1;
            List<Player> cele = a[1].equals("*") ? new ArrayList<>(Bukkit.getOnlinePlayers()) : new ArrayList<>();
            if (!a[1].equals("*")) { Player t = Bukkit.getPlayerExact(a[1]); if (t != null) cele.add(t); }
            for (Player t : cele) { ItemStack k = klucz(a[2].toLowerCase()); k.setAmount(ile); t.getInventory().addItem(k).values().forEach(r -> t.getWorld().dropItemNaturally(t.getLocation(), r)); }
            s.sendMessage(WestSMP.c("&aDano klucze: &f" + cele.size() + " &agraczom"));
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        switch (n) {
            case "sklep" -> sklep(p, a.length > 0 && a[0].equalsIgnoreCase("czas") ? "czas" : "dolary");
            case "skrzynie" -> {
                if (a.length >= 2 && a[0].equalsIgnoreCase("obroc") && p.hasPermission("westsmp.admin")) {
                    float o; try { o = Float.parseFloat(a[1]); } catch (NumberFormatException ex) { p.sendMessage(WestSMP.c("&7/skrzynie obroc <stopnie>  &8(np. 15, -45)")); return true; }
                    p.sendMessage(WestSMP.c(pl.skrzynieModele != null && pl.skrzynieModele.obroc(p, o) ? "&aObrocono skrzynie o &f" + o + "&a stopni." : "&cStan blizej skrzyni (do 3 kratek)."));
                } else if (a.length >= 2 && a[0].equalsIgnoreCase("ustaw") && p.hasPermission("westsmp.admin")) {
                    Block b = p.getTargetBlockExact(6);
                    if (b == null) { p.sendMessage(WestSMP.c("&cPatrz na blok skrzyni.")); return true; }
                    String key = (b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ()).replace('.', '_');
                    boolean usun = a[1].equalsIgnoreCase("usun");
                    if (!usun && !pl.getConfig().isConfigurationSection("skrzynie." + a[1].toLowerCase())) { p.sendMessage(WestSMP.c("&cNie ma skrzyni &f" + a[1] + "&c. Typy: zwykla, epicka, legendarna")); return true; }
                    pl.getConfig().set("skrzynie-bloki." + key, usun ? null : a[1].toLowerCase()); pl.saveConfig();
                    float yaw = Math.round(p.getLocation().getYaw() + 180);
                    if (pl.skrzynieModele != null) pl.skrzynieModele.ustawiono(key, usun ? null : a[1].toLowerCase(), yaw, b);
                    p.sendMessage(WestSMP.c(usun ? "&aUsunieto skrzynie z tego bloku." : "&aUstawiono skrzynie &f" + a[1] + " &ana tym bloku. (PPM = otworz, LPM = nagrody)"));
                } else skrzynie(p);
            }
            case "zestawy" -> {
                if (a.length >= 2 && a[0].equalsIgnoreCase("zapisz") && p.hasPermission("westsmp.admin")) {
                    File f = new File(pl.getDataFolder(), "zestawy.yml");
                    YamlConfiguration z = YamlConfiguration.loadConfiguration(f);
                    List<ItemStack> l = new ArrayList<>();
                    for (ItemStack it : p.getInventory().getContents()) if (it != null && !it.getType().isAir()) l.add(it.clone());
                    z.set(a[1].toLowerCase(), l);
                    try { z.save(f); } catch (Exception ignored) { }
                    p.sendMessage(WestSMP.c("&aZapisano &f" + l.size() + " &aprzedmiotow do zestawu &f" + a[1] + " &7(dodaj go w config.yml -> zestawy)"));
                } else zestawy(p);
            }
            case "nagrody" -> nagrody(p);
            case "kowal" -> kowal(p);
            default -> { }
        }
        return true;
    }
}
