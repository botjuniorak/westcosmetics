package pl.westmc.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/** Bankier: saldo/przelewy, czas gry (punkty czasu), czeki, coinflip. */
final class Bank implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final NamespacedKey CZEK;
    private final File plikCzas;
    private final YamlConfiguration czas;
    private final Map<UUID, Consumer<String>> pytanie = new HashMap<>();
    private final LinkedHashMap<UUID, Double> zaklady = new LinkedHashMap<>();   // otwarte coinflipy: kto -> kwota
    private final Random rnd = new Random();
    private final Map<UUID, Long> ruch = new HashMap<>();

    @EventHandler public void onMove(org.bukkit.event.player.PlayerMoveEvent e) { if (e.hasChangedOrientation() || e.hasChangedBlock()) ruch.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }
    private static final int[] SLOTY = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    Bank(WestSMP pl) {
        this.pl = pl;
        CZEK = new NamespacedKey(pl, "czek");
        plikCzas = new File(pl.getDataFolder(), "czas.yml");
        czas = YamlConfiguration.loadConfiguration(plikCzas);
    }

    void start() {
        // czas gry: co minute +1 minuta i punkty czasu
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            int pkt = pl.getConfig().getInt("czas.punkty-za-minute", 1);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (pl.getConfig().getBoolean("czas.afk-nie-liczy", true) && System.currentTimeMillis() - ruch.getOrDefault(p.getUniqueId(), System.currentTimeMillis()) > 300_000) continue;
                String b = p.getUniqueId() + ".";
                czas.set(b + "minuty", czas.getLong(b + "minuty") + 1);
                czas.set(b + "punkty", czas.getLong(b + "punkty") + pkt);
                czas.set(b + "nick", p.getName());
            }
            try { czas.save(plikCzas); } catch (Exception ignored) { }
        }, 1200L, 1200L);
    }

    void stop() {
        for (Map.Entry<UUID, Double> z : zaklady.entrySet()) pl.kasa.dodaj(Bukkit.getOfflinePlayer(z.getKey()), z.getValue());   // zwrot przy restarcie
        zaklady.clear();
        try { czas.save(plikCzas); } catch (Exception ignored) { }
    }

    long minuty(OfflinePlayer p) { return czas.getLong(p.getUniqueId() + ".minuty"); }
    long punkty(OfflinePlayer p) { return czas.getLong(p.getUniqueId() + ".punkty"); }
    boolean zabierzPunkty(OfflinePlayer p, long n) {
        if (punkty(p) < n) return false;
        czas.set(p.getUniqueId() + ".punkty", punkty(p) - n); return true;
    }
    void dodajPunkty(OfflinePlayer p, long n) { czas.set(p.getUniqueId() + ".punkty", punkty(p) + n); }

    static String czasTekst(long min) { long d = min / 1440, h = (min % 1440) / 60, m = min % 60; return (d > 0 ? d + "d " : "") + (h > 0 || d > 0 ? h + "h " : "") + m + "min"; }

    // ===================================================================== menu wspolne
    static final class Menu implements InventoryHolder {
        Inventory inv; String typ; final Map<Integer, String> akcje = new HashMap<>(); boolean animacja;
        @Override public Inventory getInventory() { return inv; }
    }

    private ItemStack ikona(Material m, String nazwa, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(WestSMP.c(nazwa));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l); }
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    private ItemStack glowa(OfflinePlayer o, String nazwa, List<String> lore) {
        ItemStack it = ikona(Material.PLAYER_HEAD, nazwa, lore);
        SkullMeta sm = (SkullMeta) it.getItemMeta(); sm.setOwningPlayer(o); it.setItemMeta(sm);
        return it;
    }

    void otworzBank(Player p) {
        Menu h = new Menu(); h.typ = "bank";
        h.inv = Bukkit.createInventory(h, 27, Tlo.tytul(0xE080, 3));
        String sym = pl.getConfig().getString("kasa.symbol", "$");
        h.inv.setItem(10, ikona(Material.GOLD_INGOT, "&#FFC93D&lSALDO: &f" + pl.kasa.fmt(pl.kasa.saldo(p)), List.of("&8&m                    ", "&7Przelew: &f/pay <gracz> <kwota>", "&7Np. &f/pay Kacper 2k", "", "&#FFC93D» Kliknij, aby zrobic przelew")));
        h.akcje.put(10, "przelew");
        h.inv.setItem(12, ikona(Material.CLOCK, "&#4EE08F&lCZAS GRY: &f" + czasTekst(minuty(p)), List.of("&8&m                    ", "&7Punkty czasu: &#4EE08F&l" + punkty(p), "&7Dostajesz je za kazda minute gry", "&7(AFK sie nie liczy).", "", "&#4EE08F» Wydasz je w sklepie za czas")));
        h.akcje.put(12, "sklepczas");
        h.inv.setItem(14, ikona(Material.PAPER, "&#8FD0FF&lWYPISZ CZEK", List.of("&8&m                    ", "&7Zamienia pieniadze na czek,", "&7ktory mozna dac innemu graczowi.", "&7PPM czekiem = wplata na konto.", "", "&#8FD0FF» Kliknij i wpisz kwote")));
        h.akcje.put(14, "czek");
        h.inv.setItem(16, ikona(Material.GOLD_NUGGET, "&#D9B8FF&lCOINFLIP", List.of("&8&m                    ", "&7Rzut moneta 50/50 z innym graczem.", "&7Otwartych zakladow: &f" + zaklady.size(), "", "&#D9B8FF» Kliknij, aby otworzyc")));
        h.akcje.put(16, "coinflip");
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.2f);
    }

    void pytanie(Player p, Consumer<String> odp) { pytanie.put(p.getUniqueId(), odp); }

    private void zapytaj(Player p, String tekst, Consumer<String> odp) {
        p.closeInventory();
        pytanie.put(p.getUniqueId(), odp);
        p.sendMessage(WestSMP.c("&#FFC93D&l» &f" + tekst + " &7(napisz na czacie, &fanuluj &7- rezygnacja)"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> odp = pytanie.remove(e.getPlayer().getUniqueId());
        if (odp == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (msg.equalsIgnoreCase("anuluj")) { e.getPlayer().sendMessage(WestSMP.c("&7Anulowano.")); return; }
        Bukkit.getScheduler().runTask(pl, () -> odp.accept(msg));
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        pytanie.remove(e.getPlayer().getUniqueId());
        Double z = zaklady.remove(e.getPlayer().getUniqueId());
        if (z != null) pl.kasa.dodaj(e.getPlayer(), z);
    }

    // ===================================================================== czeki
    ItemStack czek(double v, String od) {
        ItemStack it = ikona(Material.PAPER, "&#8FD0FF&lCZEK &8» &#FFC93D&l" + pl.kasa.fmt(v),
            List.of("&8&m                    ", "&7Wystawil: &f" + od, "&7Kwota: &#FFC93D" + pl.kasa.fmt(v), "", "&#8FD0FF» PPM, aby wplacic na konto"));
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("westmc:czek"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(CZEK, PersistentDataType.DOUBLE, v);
        it.setItemMeta(m);
        return it;
    }

    void wystawCzek(Player p, String arg) {
        double v = Kasa.kwota(arg);
        if (v <= 0) { p.sendMessage(WestSMP.c("&cZla kwota.")); return; }
        if (v < pl.getConfig().getDouble("czeki.min", 100)) { p.sendMessage(WestSMP.c("&cMinimalny czek: &f" + pl.kasa.fmt(pl.getConfig().getDouble("czeki.min", 100)))); return; }
        if (!pl.kasa.zabierz(p, v)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return; }
        p.getInventory().addItem(czek(v, p.getName())).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        p.sendMessage(WestSMP.c("&aWypisano czek na &#FFC93D" + pl.kasa.fmt(v)));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    @EventHandler
    public void onCzek(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        Double v = it.getItemMeta().getPersistentDataContainer().get(CZEK, PersistentDataType.DOUBLE);
        if (v == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        it.setAmount(it.getAmount() - 1);
        pl.kasa.dodaj(p, v);
        p.sendMessage(WestSMP.c("&aWplacono czek: &#FFC93D+" + pl.kasa.fmt(v) + " &7(saldo: " + pl.kasa.fmt(pl.kasa.saldo(p)) + ")"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.2, 0), 12, 0.3, 0.3, 0.3, 0);
    }

    // ===================================================================== coinflip
    void otworzCf(Player p) {
        Menu h = new Menu(); h.typ = "cf";
        h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(0xE1A0, 4));
        int i = 0;
        for (Map.Entry<UUID, Double> z : zaklady.entrySet()) {
            if (i >= SLOTY.length) break;
            OfflinePlayer o = Bukkit.getOfflinePlayer(z.getKey());
            boolean moj = z.getKey().equals(p.getUniqueId());
            h.inv.setItem(SLOTY[i], glowa(o, "&f" + o.getName() + " &8» &#FFC93D&l" + pl.kasa.fmt(z.getValue()),
                moj ? List.of("&7To twoj zaklad.", "", "&c» Kliknij, aby anulowac (zwrot)") : List.of("&7Stawka: &#FFC93D" + pl.kasa.fmt(z.getValue()), "&7Szansa: &f50%", "", "&a» Kliknij, aby przyjac")));
            h.akcje.put(SLOTY[i], "cf:" + z.getKey());
            i++;
        }
        h.inv.setItem(48, ikona(Material.EMERALD, "&#4EE08F&lNOWY ZAKLAD", List.of("&7Kliknij i wpisz kwote", "&7albo: &f/cf <kwota>")));
        h.akcje.put(48, "nowy");
        h.inv.setItem(49, ikona(Material.BARRIER, "&c&lZamknij", null));
        h.akcje.put(49, "zamknij");
        String b = p.getUniqueId() + ".cf.";
        double bil = czas.getDouble(b + "bilans");
        h.inv.setItem(50, ikona(Material.CLOCK, "&#3AA0FF&lTWOJE STATYSTYKI", List.of("&7Wygrane: &a" + czas.getInt(b + "wygrane"), "&7Przegrane: &c" + czas.getInt(b + "przegrane"), "&7Bilans: " + (bil >= 0 ? "&a+" : "&c-") + pl.kasa.fmt(Math.abs(bil)))));
        p.openInventory(h.inv);
    }

    void nowyZaklad(Player p, String arg) {
        double v = Kasa.kwota(arg);
        double min = pl.getConfig().getDouble("coinflip.min", 100), max = pl.getConfig().getDouble("coinflip.max", 10_000_000);
        if (v < min || v > max) { p.sendMessage(WestSMP.c("&cStawka od &f" + pl.kasa.fmt(min) + " &cdo &f" + pl.kasa.fmt(max))); return; }
        if (zaklady.containsKey(p.getUniqueId())) { p.sendMessage(WestSMP.c("&cMasz juz otwarty zaklad.")); return; }
        if (!pl.kasa.zabierz(p, v)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return; }
        zaklady.put(p.getUniqueId(), v);
        Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FFC93D&lCOINFLIP &8» &f" + p.getName() + " &7stawia &#FFC93D&l" + pl.kasa.fmt(v) + " &8(/cf)")));
        otworzCf(p);
    }

    private void przyjmij(Player p, UUID ownerId) {
        Double v = zaklady.get(ownerId);
        if (v == null) { p.sendMessage(WestSMP.c("&cTen zaklad juz nie istnieje.")); otworzCf(p); return; }
        if (!pl.kasa.zabierz(p, v)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return; }
        zaklady.remove(ownerId);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        boolean wygralWlasciciel = rnd.nextBoolean();
        OfflinePlayer zwyc = wygralWlasciciel ? owner : p, przeg = wygralWlasciciel ? p : owner;
        double pula = v * 2, podatek = pula * pl.getConfig().getDouble("coinflip.podatek", 0.05), wygrana = pula - podatek;
        // animacja dla obu (jesli online)
        List<Player> widzowie = new ArrayList<>();
        widzowie.add(p);
        if (owner.getPlayer() != null && owner.getPlayer().isOnline()) widzowie.add(owner.getPlayer());
        List<Menu> menu = new ArrayList<>();
        for (Player w : widzowie) {
            Menu h = new Menu(); h.typ = "anim"; h.animacja = true;
            h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(0xE1A0, 4));
            w.openInventory(h.inv); menu.add(h);
        }
        int[] krok = {0};
        int[] kiedy = {0};
        int koniec = 34;
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            krok[0]++;
            int odstep = krok[0] < 14 ? 1 : krok[0] < 22 ? 2 : krok[0] < 28 ? 3 : 4;
            if (++kiedy[0] < odstep && krok[0] < koniec) return;
            kiedy[0] = 0;
            boolean naWl = krok[0] % 2 == 0;
            OfflinePlayer pokaz = krok[0] >= koniec ? zwyc : (naWl ? owner : p);
            for (int i = 0; i < menu.size(); i++) {
                Menu h = menu.get(i);
                h.inv.setItem(22, glowa(pokaz, "&f" + pokaz.getName(), List.of("&7Pula: &#FFC93D" + pl.kasa.fmt(pula))));
                Player w = widzowie.get(i);
                if (w.isOnline()) w.playSound(w.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f + (krok[0] % 2) * 0.3f);
            }
            if (krok[0] >= koniec) {
                task.cancel();
                pl.kasa.dodaj(zwyc, wygrana);
                String bz = zwyc.getUniqueId() + ".cf.", bp = przeg.getUniqueId() + ".cf.";
                czas.set(bz + "wygrane", czas.getInt(bz + "wygrane") + 1); czas.set(bz + "bilans", czas.getDouble(bz + "bilans") + wygrana - v);
                czas.set(bp + "przegrane", czas.getInt(bp + "przegrane") + 1); czas.set(bp + "bilans", czas.getDouble(bp + "bilans") - v);
                for (Player w : widzowie) {
                    if (!w.isOnline()) continue;
                    boolean wygral = w.getUniqueId().equals(zwyc.getUniqueId());
                    w.showTitle(net.kyori.adventure.title.Title.title(
                        net.kyori.adventure.text.Component.text(WestSMP.c(wygral ? "&#4EE08F&lWYGRANA!" : "&#FF5A68&lPRZEGRANA")),
                        net.kyori.adventure.text.Component.text(WestSMP.c(wygral ? "&7+&#FFC93D" + pl.kasa.fmt(wygrana) : "&7Wygral &f" + zwyc.getName()))));
                    w.playSound(w.getLocation(), wygral ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                }
                if (pula >= pl.getConfig().getDouble("coinflip.ogloszenie-od", 50000))
                    Bukkit.broadcast(net.kyori.adventure.text.Component.text(WestSMP.c("&#FFC93D&lCOINFLIP &8» &f" + zwyc.getName() + " &7wygrywa &#FFC93D&l" + pl.kasa.fmt(wygrana) + " &7z &f" + przeg.getName() + "&7!")));
                Bukkit.getScheduler().runTaskLater(pl, () -> { for (Player w : widzowie) if (w.isOnline() && w.getOpenInventory().getTopInventory().getHolder() instanceof Menu m && m.animacja) w.closeInventory(); }, 50L);
            }
        }, 1L, 2L);
    }

    // ===================================================================== klikanie
    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler public void onClose(InventoryCloseEvent e) { }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory() || h.animacja) return;
        String a = h.akcje.get(e.getRawSlot());
        if (a == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        switch (a) {
            case "przelew" -> zapytaj(p, "Komu i ile? Wpisz: nick kwota", s -> { String[] x = s.split("\\s+"); if (x.length >= 2) p.performCommand("pay " + x[0] + " " + x[1]); else p.sendMessage(WestSMP.c("&cWpisz: nick kwota")); });
            case "sklepczas" -> p.performCommand(pl.getConfig().getString("czas.komenda-sklepu", "sklep czas"));
            case "czek" -> zapytaj(p, "Na ile wypisac czek?", s -> wystawCzek(p, s));
            case "coinflip" -> otworzCf(p);
            case "nowy" -> zapytaj(p, "Ile stawiasz w coinflipie?", s -> nowyZaklad(p, s));
            case "zamknij" -> p.closeInventory();
            default -> {
                if (!a.startsWith("cf:")) return;
                UUID id = UUID.fromString(a.substring(3));
                if (id.equals(p.getUniqueId())) {
                    Double v = zaklady.remove(id);
                    if (v != null) { pl.kasa.dodaj(p, v); p.sendMessage(WestSMP.c("&7Anulowano zaklad - zwrot &#FFC93D" + pl.kasa.fmt(v))); }
                    otworzCf(p);
                } else przyjmij(p, id);
            }
        }
    }

    // ===================================================================== komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("czas") && a.length >= 1 && s.hasPermission("westsmp.admin") && List.of("daj", "zabierz", "ustaw").contains(a[0].toLowerCase())) {
            if (a.length < 3) { s.sendMessage(WestSMP.c("&7/czas <daj|zabierz|ustaw> <gracz> <ilosc>")); return true; }
            org.bukkit.OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
            long n;
            try { n = Long.parseLong(a[2]); } catch (NumberFormatException e) { s.sendMessage(WestSMP.c("&cZla liczba.")); return true; }
            switch (a[0].toLowerCase()) {
                case "daj" -> dodajPunkty(t, Math.max(0, n));
                case "zabierz" -> zabierzPunkty(t, Math.min(Math.max(0, n), punkty(t)));
                default -> czas.set(t.getUniqueId() + ".punkty", Math.max(0, n));
            }
            s.sendMessage(WestSMP.c("&aPunkty czasu &f" + a[1] + "&a: &#4EE08F&l" + punkty(t)));
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "portfel" -> otworzBank(p);
            case "czek" -> { if (a.length < 1) zapytaj(p, "Na ile wypisac czek?", x -> wystawCzek(p, x)); else wystawCzek(p, a[0]); }
            case "coinflip" -> { if (a.length < 1) otworzCf(p); else nowyZaklad(p, a[0]); }
            case "czas" -> p.sendMessage(WestSMP.c("&7Czas gry: &f" + czasTekst(minuty(p)) + " &8• &7Punkty czasu: &#4EE08F&l" + punkty(p)));
            default -> { }
        }
        return true;
    }
}
