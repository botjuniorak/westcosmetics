package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Rynek graczy za $: wystawianie z reki, strony, sortowanie, szukanie, moje oferty, skrzynka odbiorcza, podatek. */
final class Rynek implements Listener, CommandExecutor {
    private static final int RYNEK = 0xE120, KUP = 0xE220;
    private static final String[] SORT = {"najnowsze", "najtansze", "najdrozsze"};
    private final WestSMP pl;
    private final File plik;
    private final YamlConfiguration d;
    private BukkitTask task;

    Rynek(WestSMP pl) {
        this.pl = pl;
        plik = new File(pl.getDataFolder(), "rynek.yml");
        d = YamlConfiguration.loadConfiguration(plik);
    }

    private void zapisz() { try { d.save(plik); } catch (Exception e) { pl.getLogger().warning("rynek.yml: " + e.getMessage()); } }

    void start() { task = Bukkit.getScheduler().runTaskTimer(pl, this::wygasaj, 200L, 1200L); }
    void stop() { if (task != null) task.cancel(); zapisz(); }

    static final class Menu implements InventoryHolder {
        Inventory inv; String widok; int strona, sort; String szukaj; String oferta; final List<String> sloty = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    // ================================================================= dane
    record Oferta(String id, UUID kto, String nick, double cena, long czas, ItemStack item) { }

    private List<Oferta> oferty() {
        List<Oferta> l = new ArrayList<>();
        ConfigurationSection s = d.getConfigurationSection("oferty");
        if (s != null) for (String id : s.getKeys(false)) {
            try {
                l.add(new Oferta(id, UUID.fromString(s.getString(id + ".kto")), s.getString(id + ".nick", "?"), s.getDouble(id + ".cena"), s.getLong(id + ".czas"), s.getItemStack(id + ".item")));
            } catch (Exception ignored) { }
        }
        return l;
    }

    private Oferta oferta(String id) { for (Oferta o : oferty()) if (o.id.equals(id)) return o; return null; }

    private long wazna() { return pl.getConfig().getLong("rynek.wazna-godzin", 48) * 3600_000L; }

    private void doSkrzynki(UUID kto, ItemStack it) {
        List<ItemStack> l = skrzynka(kto); l.add(it);
        d.set("skrzynka." + kto, l);
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> skrzynka(UUID kto) {
        List<?> l = d.getList("skrzynka." + kto);
        List<ItemStack> r = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof ItemStack i) r.add(i);
        return r;
    }

    private void wygasaj() {
        long now = System.currentTimeMillis(); boolean zm = false;
        for (Oferta o : oferty()) if (now - o.czas > wazna()) {
            doSkrzynki(o.kto, o.item); d.set("oferty." + o.id, null); zm = true;
            Player p = Bukkit.getPlayer(o.kto);
            if (p != null) p.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &7Twoja oferta wygasla - przedmiot czeka w &fskrzynce odbiorczej &7(/rynek)."));
        }
        if (zm) zapisz();
    }

    private int limit(Player p) {
        int max = pl.getConfig().getInt("rynek.limit-ofert", 5);
        for (int i = 50; i > max; i--) if (p.hasPermission("westsmp.rynek.limit." + i)) return i;
        return max;
    }

    private static String czas(long ms) {
        long m = Math.max(0, ms / 60000), h = m / 60, dd = h / 24;
        return dd > 0 ? dd + "d " + (h % 24) + "h" : h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }

    private static String nazwa(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return org.bukkit.ChatColor.stripColor(m.getDisplayName());
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private ItemStack zLore(ItemStack src, List<String> dodaj) {
        ItemStack it = src.clone();
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        for (String s : dodaj) lore.add(WestSMP.c(s));
        m.setLore(lore); it.setItemMeta(m);
        return it;
    }

    // ================================================================= widoki
    private Menu nowe(String widok, int rozmiar, int base, int rzedy) {
        Menu h = new Menu(); h.widok = widok;
        h.inv = Bukkit.createInventory(h, rozmiar, Tlo.tytul(base, rzedy));
        return h;
    }

    void otworz(Player p, int strona, int sort, String szukaj, String widok) {
        Menu h = nowe(widok, 54, RYNEK, 4);
        h.strona = strona; h.sort = sort; h.szukaj = szukaj;
        long now = System.currentTimeMillis();
        List<String> lista = new ArrayList<>();
        List<ItemStack> ikony = new ArrayList<>();
        if (widok.equals("skrzynka")) {
            List<ItemStack> s = skrzynka(p.getUniqueId());
            for (int i = 0; i < s.size(); i++) { lista.add(String.valueOf(i)); ikony.add(zLore(s.get(i), List.of("&8&m                    ", "&#3AE07A» Kliknij, aby odebrac"))); }
        } else {
            List<Oferta> l = oferty();
            if (widok.equals("moje")) l.removeIf(o -> !o.kto.equals(p.getUniqueId()));
            if (szukaj != null) l.removeIf(o -> !nazwa(o.item).toLowerCase(Locale.ROOT).contains(szukaj.toLowerCase(Locale.ROOT)));
            switch (sort) {
                case 1 -> l.sort(Comparator.comparingDouble(Oferta::cena));
                case 2 -> l.sort(Comparator.comparingDouble(Oferta::cena).reversed());
                default -> l.sort(Comparator.comparingLong(Oferta::czas).reversed());
            }
            for (Oferta o : l) {
                lista.add(o.id);
                ikony.add(zLore(o.item, widok.equals("moje")
                    ? List.of("&8&m                    ", "&7Cena: &#FFC93D&l" + pl.kasa.fmt(o.cena), "&7Wygasa za: &f" + czas(o.czas + wazna() - now), "", "&#FF5A68» Kliknij, aby zdjac oferte")
                    : List.of("&8&m                    ", "&7Sprzedaje: &f" + o.nick, "&7Cena: &#FFC93D&l" + pl.kasa.fmt(o.cena), "&7Wygasa za: &f" + czas(o.czas + wazna() - now), "", "&#3AE07A» Kliknij, aby kupic")));
            }
        }
        int stron = Math.max(1, (lista.size() + 35) / 36);
        h.strona = Math.max(0, Math.min(strona, stron - 1));
        for (int i = 0; i < 36; i++) {
            int k = h.strona * 36 + i;
            if (k >= lista.size()) break;
            h.inv.setItem(i, ikony.get(k)); h.sloty.add(lista.get(k));
        }
        Sklepy sk = pl.sklepy;
        h.inv.setItem(45, sk.ikona(Material.ARROW, null, "&#3ADFFF&lPOPRZEDNIA STRONA", List.of("&7Strona &f" + (h.strona + 1) + "&7/&f" + stron), false));
        h.inv.setItem(53, sk.ikona(Material.ARROW, null, "&#3ADFFF&lNASTEPNA STRONA", List.of("&7Strona &f" + (h.strona + 1) + "&7/&f" + stron), false));
        h.inv.setItem(47, sk.ikona(Material.BOOK, null, widok.equals("moje") ? "&#C08BFF&lWSZYSTKIE OFERTY" : "&#C08BFF&lMOJE OFERTY",
            List.of("&7Wystawione: &f" + oferty().stream().filter(o -> o.kto.equals(p.getUniqueId())).count() + "&7/&f" + limit(p), "&7Wystaw: &f/rynek wystaw <cena>", "&8(przedmiot trzymany w rece)"), widok.equals("moje")));
        int wSkrzynce = skrzynka(p.getUniqueId()).size();
        h.inv.setItem(48, sk.ikona(Material.CHEST_MINECART, null, "&#FFC23D&lSKRZYNKA ODBIORCZA", List.of("&7Przedmioty do odebrania: &f" + wSkrzynce, "&8(wygasle i zdjete oferty)"), wSkrzynce > 0));
        h.inv.setItem(49, sk.ikona(Material.GOLD_INGOT, null, "&#3AE07A&lTWOJE SALDO", List.of("&#FFC93D&l" + pl.kasa.fmt(pl.kasa.saldo(p)), "&7Podatek od sprzedazy: &f" + pl.getConfig().getDouble("rynek.podatek-procent", 5) + "%"), false));
        List<String> sl = new ArrayList<>();
        for (int i = 0; i < SORT.length; i++) sl.add((i == sort ? "&#FF8A00» &f" : "&7  ") + SORT[i]);
        sl.add(""); sl.add("&#FF8A00» Kliknij, aby zmienic");
        h.inv.setItem(50, sk.ikona(Material.HOPPER, null, "&#FF8A00&lSORTOWANIE", sl, false));
        h.inv.setItem(51, sk.ikona(Material.SPYGLASS, null, "&#3ADFFF&lSZUKAJ", szukaj == null ? List.of("&7Wpisz nazwe przedmiotu na czacie", "", "&#3ADFFF» Kliknij") : List.of("&7Szukasz: &f" + szukaj, "", "&#3ADFFF» LPM - nowe szukanie", "&#3ADFFF» PPM - wyczysc"), szukaj != null));
        p.openInventory(h.inv);
    }

    private void kupno(Player p, Oferta o) {
        Menu h = nowe("kup", 27, KUP, 3);
        h.oferta = o.id;
        Sklepy sk = pl.sklepy;
        h.inv.setItem(11, sk.ikona(Material.LIME_DYE, null, "&#3AE07A&lKUP", List.of("&7Cena: &#FFC93D&l" + pl.kasa.fmt(o.cena), "&7Twoje saldo: &f" + pl.kasa.fmt(pl.kasa.saldo(p))), false));
        h.inv.setItem(13, zLore(o.item, List.of("&8&m                    ", "&7Sprzedaje: &f" + o.nick, "&7Cena: &#FFC93D&l" + pl.kasa.fmt(o.cena))));
        h.inv.setItem(15, sk.ikona(Material.RED_DYE, null, "&#FF5A68&lANULUJ", List.of("&7Wroc do rynku"), false));
        p.openInventory(h.inv);
    }

    // ================================================================= klikanie
    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (h.widok.equals("kup")) {
            if (s == 15) { otworz(p, 0, 0, null, "rynek"); return; }
            if (s != 11) return;
            Oferta o = oferta(h.oferta);
            if (o == null) { p.closeInventory(); p.sendMessage(WestSMP.c("&cTa oferta jest juz nieaktualna.")); return; }
            if (o.kto.equals(p.getUniqueId())) { p.sendMessage(WestSMP.c("&cNie mozesz kupic wlasnej oferty.")); return; }
            if (p.getInventory().firstEmpty() < 0) { p.sendMessage(WestSMP.c("&cZrob miejsce w ekwipunku.")); return; }
            if (!pl.kasa.zabierz(p, o.cena)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); return; }
            d.set("oferty." + o.id, null); zapisz();
            double netto = Math.floor(o.cena * (100 - pl.getConfig().getDouble("rynek.podatek-procent", 5))) / 100.0;
            pl.kasa.dodaj(Bukkit.getOfflinePlayer(o.kto), netto);
            p.getInventory().addItem(o.item.clone());
            p.closeInventory();
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            p.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &7Kupiles &f" + nazwa(o.item) + " &7za &#FFC93D" + pl.kasa.fmt(o.cena)));
            Player sp = Bukkit.getPlayer(o.kto);
            if (sp != null) sp.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &f" + p.getName() + " &7kupil &f" + nazwa(o.item) + "&7. Dostajesz &#FFC93D" + pl.kasa.fmt(netto) + " &8(po podatku)"));
            return;
        }
        if (s < 36) {
            if (s >= h.sloty.size()) return;
            String id = h.sloty.get(s);
            if (h.widok.equals("skrzynka")) {
                List<ItemStack> l = skrzynka(p.getUniqueId());
                int i = Integer.parseInt(id);
                if (i >= l.size()) return;
                if (p.getInventory().firstEmpty() < 0) { p.sendMessage(WestSMP.c("&cZrob miejsce w ekwipunku.")); return; }
                p.getInventory().addItem(l.remove(i));
                d.set("skrzynka." + p.getUniqueId(), l.isEmpty() ? null : l); zapisz();
                otworz(p, h.strona, h.sort, null, "skrzynka");
                return;
            }
            Oferta o = oferta(id);
            if (o == null) { otworz(p, h.strona, h.sort, h.szukaj, h.widok); return; }
            if (h.widok.equals("moje") || (o.kto.equals(p.getUniqueId()))) {
                d.set("oferty." + o.id, null);
                if (p.getInventory().firstEmpty() >= 0) p.getInventory().addItem(o.item); else doSkrzynki(p.getUniqueId(), o.item);
                zapisz();
                p.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &7Zdjeto oferte."));
                otworz(p, h.strona, h.sort, h.szukaj, h.widok);
                return;
            }
            kupno(p, o);
            return;
        }
        switch (s) {
            case 45 -> otworz(p, h.strona - 1, h.sort, h.szukaj, h.widok);
            case 53 -> otworz(p, h.strona + 1, h.sort, h.szukaj, h.widok);
            case 47 -> otworz(p, 0, h.sort, null, h.widok.equals("moje") ? "rynek" : "moje");
            case 48 -> otworz(p, 0, h.sort, null, "skrzynka");
            case 50 -> otworz(p, 0, (h.sort + 1) % SORT.length, h.szukaj, h.widok.equals("skrzynka") ? "rynek" : h.widok);
            case 51 -> {
                if (e.isRightClick()) { otworz(p, 0, h.sort, null, "rynek"); return; }
                p.closeInventory();
                p.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &7Wpisz na czacie nazwe przedmiotu &8(np. diament, elytra)&7:"));
                int sort = h.sort;
                pl.bank.pytanie(p, txt -> Bukkit.getScheduler().runTask(pl, () -> otworz(p, 0, sort, txt.trim(), "rynek")));
            }
            default -> { }
        }
    }

    // ================================================================= komendy
    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] a) {
        if (!(cs instanceof Player p)) { cs.sendMessage("Tylko w grze."); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("wystaw")) {
            if (a.length < 2) { p.sendMessage(WestSMP.c("&7/rynek wystaw <cena>  &8(np. 5000, 25k, 1.5m) - przedmiot z reki")); return true; }
            double cena = Kasa.kwota(a[1]);
            double min = pl.getConfig().getDouble("rynek.min-cena", 1), max = pl.getConfig().getDouble("rynek.max-cena", 1e9);
            if (cena < min || cena > max) { p.sendMessage(WestSMP.c("&cCena musi byc od &f" + pl.kasa.fmt(min) + " &cdo &f" + pl.kasa.fmt(max) + "&c.")); return true; }
            ItemStack it = p.getInventory().getItemInMainHand();
            if (it.getType().isAir()) { p.sendMessage(WestSMP.c("&cWez przedmiot do reki.")); return true; }
            for (String zak : pl.getConfig().getStringList("rynek.zakazane")) if (it.getType().name().equalsIgnoreCase(zak)) { p.sendMessage(WestSMP.c("&cTego nie mozna wystawic.")); return true; }
            long ile = oferty().stream().filter(o -> o.kto.equals(p.getUniqueId())).count();
            if (ile >= limit(p)) { p.sendMessage(WestSMP.c("&cMasz juz maksymalna liczbe ofert (&f" + limit(p) + "&c).")); return true; }
            String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
            d.set("oferty." + id + ".kto", p.getUniqueId().toString());
            d.set("oferty." + id + ".nick", p.getName());
            d.set("oferty." + id + ".cena", cena);
            d.set("oferty." + id + ".czas", System.currentTimeMillis());
            d.set("oferty." + id + ".item", it.clone());
            p.getInventory().setItemInMainHand(null);
            zapisz();
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
            p.sendMessage(WestSMP.c("&#3AE07A&lRYNEK &8» &7Wystawiono &f" + nazwa(it) + (it.getAmount() > 1 ? " x" + it.getAmount() : "") + " &7za &#FFC93D" + pl.kasa.fmt(cena)));
            if (pl.getConfig().getBoolean("rynek.ogloszenia", true))
                Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#3AE07A&lRYNEK &8» &f" + p.getName() + " &7wystawil &f" + nazwa(it) + " &7za &#FFC93D" + pl.kasa.fmt(cena) + " &8(/rynek)")));
            return true;
        }
        otworz(p, 0, 0, null, "rynek");
        return true;
    }
}
