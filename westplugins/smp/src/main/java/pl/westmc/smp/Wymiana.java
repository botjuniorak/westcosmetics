package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bezpieczna wymiana miedzy graczami (przedmioty + pieniadze), obaj potwierdzaja. */
final class Wymiana implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final Map<UUID, UUID> prosby = new HashMap<>();      // kogo zaproszono -> kto zaprasza
    private final Map<UUID, Handel> trwa = new HashMap<>();
    static final int[] MOJE = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    static final int[] JEGO = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};

    Wymiana(WestSMP pl) { this.pl = pl; }

    static final class Strona implements InventoryHolder {
        Handel h; Player gracz; Inventory inv; boolean gotowy; double kasa;
        @Override public Inventory getInventory() { return inv; }
    }

    final class Handel {
        Strona a, b; BukkitTask odliczanie; boolean koniec;
        Strona druga(Strona s) { return s == a ? b : a; }
    }

    private static boolean moje(int slot) { for (int s : MOJE) if (s == slot) return true; return false; }

    private ItemStack ikona(Material m, String n, List<String> lore) {
        ItemStack it = new ItemStack(m); ItemMeta im = it.getItemMeta(); im.setDisplayName(WestSMP.c(n));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); im.setLore(l); }
        im.addItemFlags(ItemFlag.values()); it.setItemMeta(im); return it;
    }

    private void start(Player a, Player b) {
        Handel h = new Handel();
        h.a = strona(h, a); h.b = strona(h, b);
        trwa.put(a.getUniqueId(), h); trwa.put(b.getUniqueId(), h);
        odswiez(h);
        a.openInventory(h.a.inv); b.openInventory(h.b.inv);
    }

    private Strona strona(Handel h, Player p) {
        Strona s = new Strona(); s.h = h; s.gracz = p;
        s.inv = Bukkit.createInventory(s, 54, Tlo.tytul(0xE200, 4));
        return s;
    }

    /** Lustro: prawa strona pokazuje rzeczy partnera, przyciski stanu. */
    private void odswiez(Handel h) {
        for (Strona s : new Strona[]{h.a, h.b}) {
            Strona o = h.druga(s);
            for (int i = 0; i < MOJE.length; i++) { ItemStack it = o.inv.getItem(MOJE[i]); s.inv.setItem(JEGO[i], it == null ? null : it.clone()); }
            s.inv.setItem(47, ikona(s.gotowy ? Material.LIME_DYE : Material.GRAY_DYE, s.gotowy ? "&a&l✔ GOTOWE" : "&7&lKLIKNIJ - GOTOWE", List.of("&7Potwierdz, gdy wszystko sie zgadza.")));
            s.inv.setItem(49, ikona(Material.GOLD_NUGGET, "&#FFC93D&lTWOJA KASA: &f" + pl.kasa.fmt(s.kasa), List.of("&7Kliknij, aby dodac pieniadze", "&7Partner daje: &#FFC93D" + pl.kasa.fmt(o.kasa))));
            s.inv.setItem(51, ikona(o.gotowy ? Material.LIME_DYE : Material.RED_DYE, "&f" + o.gracz.getName() + ": " + (o.gotowy ? "&a&lGOTOWY" : "&c&lNIEGOTOWY"), List.of("&7Kasa partnera: &#FFC93D" + pl.kasa.fmt(o.kasa))));
        }
    }

    private void zmiana(Handel h) {
        if (h.koniec) return;
        h.a.gotowy = false; h.b.gotowy = false;
        if (h.odliczanie != null) { h.odliczanie.cancel(); h.odliczanie = null; }
        odswiez(h);
    }

    private void anuluj(Handel h, String powod) {
        if (h.koniec) return;
        h.koniec = true;
        if (h.odliczanie != null) h.odliczanie.cancel();
        for (Strona s : new Strona[]{h.a, h.b}) {
            trwa.remove(s.gracz.getUniqueId());
            for (int slot : MOJE) { ItemStack it = s.inv.getItem(slot); if (it != null) { s.inv.setItem(slot, null); oddaj(s.gracz, it); } }
            if (s.gracz.isOnline()) {
                if (s.gracz.getOpenInventory().getTopInventory() == s.inv) s.gracz.closeInventory();
                s.gracz.sendMessage(WestSMP.c("&cWymiana anulowana" + (powod == null ? "." : " - " + powod)));
            }
        }
    }

    private void oddaj(Player p, ItemStack it) { p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r)); }

    private void finalizuj(Handel h) {
        if (h.koniec) return;
        // pieniadze: sprawdz i przelej
        if (!pl.kasa.ma(h.a.gracz, h.a.kasa) || !pl.kasa.ma(h.b.gracz, h.b.kasa)) { anuluj(h, "ktos nie ma juz tyle pieniedzy"); return; }
        h.koniec = true;
        if (h.a.kasa > 0 && pl.kasa.zabierz(h.a.gracz, h.a.kasa)) pl.kasa.dodaj(h.b.gracz, h.a.kasa);
        if (h.b.kasa > 0 && pl.kasa.zabierz(h.b.gracz, h.b.kasa)) pl.kasa.dodaj(h.a.gracz, h.b.kasa);
        for (Strona s : new Strona[]{h.a, h.b}) {
            Strona o = h.druga(s);
            for (int slot : MOJE) { ItemStack it = s.inv.getItem(slot); if (it != null) { s.inv.setItem(slot, null); oddaj(o.gracz, it); } }
        }
        for (Strona s : new Strona[]{h.a, h.b}) {
            trwa.remove(s.gracz.getUniqueId());
            s.gracz.closeInventory();
            s.gracz.sendMessage(WestSMP.c("&a&l✔ Wymiana zakonczona z &f" + h.druga(s).gracz.getName()));
            s.gracz.playSound(s.gracz.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Strona s) || !(e.getWhoClicked() instanceof Player p)) return;
        Handel h = s.h;
        if (h.koniec) { e.setCancelled(true); return; }
        InventoryAction act = e.getAction();
        if (act == InventoryAction.COLLECT_TO_CURSOR) { e.setCancelled(true); return; }
        boolean gora = e.getClickedInventory() == e.getInventory();
        if (!gora) {
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it == null) return;
                for (int slot : MOJE) if (s.inv.getItem(slot) == null) { s.inv.setItem(slot, it); e.setCurrentItem(null); break; }
                Bukkit.getScheduler().runTask(pl, () -> zmiana(h));
            }
            return;
        }
        int slot = e.getRawSlot();
        if (moje(slot)) { Bukkit.getScheduler().runTask(pl, () -> zmiana(h)); return; }
        e.setCancelled(true);
        if (slot == 47) {
            s.gotowy = !s.gotowy;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, s.gotowy ? 1.5f : 0.8f);
            odswiez(h);
            if (h.a.gotowy && h.b.gotowy && h.odliczanie == null) {
                int[] t = {3};
                h.odliczanie = Bukkit.getScheduler().runTaskTimer(pl, () -> {
                    if (t[0] <= 0) { h.odliczanie.cancel(); h.odliczanie = null; finalizuj(h); return; }
                    for (Strona x : new Strona[]{h.a, h.b}) { x.gracz.sendActionBar(WestSMP.c("&a&lWymiana za " + t[0] + "...")); x.gracz.playSound(x.gracz.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1f + (3 - t[0]) * 0.2f); }
                    t[0]--;
                }, 0L, 20L);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Strona s)) return;
        for (int slot : e.getRawSlots()) if (slot < 54 && !moje(slot)) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(pl, () -> zmiana(s.h));
    }

    private final Map<UUID, Long> pytaKase = new HashMap<>();

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Strona s) || s.h.koniec) return;
        Player p = (Player) e.getPlayer();
        if (e.getReason() == InventoryCloseEvent.Reason.OPEN_NEW || e.getReason() == InventoryCloseEvent.Reason.PLUGIN) return;
        anuluj(s.h, p.getName() + " zamknal okno");
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { Handel h = trwa.get(e.getPlayer().getUniqueId()); if (h != null) anuluj(h, "gracz wyszedl"); }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onKasaClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Strona s) || e.getRawSlot() != 49 || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        pytaKase.put(p.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTask(pl, () -> {
            p.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            p.sendMessage(WestSMP.c("&#FFC93D&l» &fIle pieniedzy dodajesz do wymiany? &7(napisz kwote, 0 = nic)"));
            pl.bank.pytanie(p, txt -> {
                if (s.h.koniec) return;
                double v = txt.trim().equals("0") ? 0 : Kasa.kwota(txt);
                if (v < 0) p.sendMessage(WestSMP.c("&cZla kwota."));
                else if (!pl.kasa.ma(p, v)) p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy."));
                else s.kasa = v;
                zmiana(s.h);
                p.openInventory(s.inv);
            });
        });
    }

    @Override
    public boolean onCommand(CommandSender cs, Command cmd, String label, String[] a) {
        if (!(cs instanceof Player p)) return true;
        if (a.length == 0) { p.sendMessage(WestSMP.c("&7/wymiana <gracz> &8| &7/wymiana akceptuj")); return true; }
        if (a[0].equalsIgnoreCase("akceptuj")) {
            UUID od = prosby.remove(p.getUniqueId());
            Player o = od == null ? null : Bukkit.getPlayer(od);
            if (o == null) { p.sendMessage(WestSMP.c("&cNikt nie zaprosil cie do wymiany.")); return true; }
            if (trwa.containsKey(o.getUniqueId()) || trwa.containsKey(p.getUniqueId())) { p.sendMessage(WestSMP.c("&cKtos juz handluje.")); return true; }
            double max = pl.getConfig().getDouble("wymiana.odleglosc", 10);
            if (pl.getConfig().getBoolean("wymiana.tylko-blisko", false) && (o.getWorld() != p.getWorld() || o.getLocation().distance(p.getLocation()) > max)) { p.sendMessage(WestSMP.c("&cMusicie stac blisko siebie.")); return true; }
            start(o, p);
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t == p) { p.sendMessage(WestSMP.c("&cGracz musi byc online.")); return true; }
        prosby.put(t.getUniqueId(), p.getUniqueId());
        p.sendMessage(WestSMP.c("&aWyslano propozycje wymiany do &f" + t.getName()));
        t.sendMessage(WestSMP.c("&#4EE08F&lWYMIANA &8» &f" + p.getName() + " &7chce sie z toba wymienic. Wpisz &a/wymiana akceptuj"));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
        return true;
    }
}
