package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** /sell - okno wielkosci podwojnej skrzyni: wrzucasz itemy, klikasz SPRZEDAJ. /cena - cena itemu w rece. */
final class Sprzedaz implements Listener, CommandExecutor {
    private static final int TLO = 0xE240, POLA = 45, INFO = 45, SPRZEDAJ = 49, ZAMKNIJ = 53;
    private final WestSMP pl;

    Sprzedaz(WestSMP pl) { this.pl = pl; }

    static final class Menu implements InventoryHolder { Inventory inv; boolean sprzedano; @Override public Inventory getInventory() { return inv; } }

    private ItemStack pusty() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("westmc:pusty"));
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    void otworz(Player p) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(TLO, 4));
        for (int i = POLA; i < 54; i++) h.inv.setItem(i, pusty());
        h.inv.setItem(INFO, pl.sklepy.ikona(Material.BOOK, null, "&#3ADFFF&lJAK TO DZIALA?", List.of("&7Wrzuc przedmioty do okna", "&7i kliknij &aSPRZEDAJ&7.", "&7Cene widac w opisie itemu", "&7albo pod &f/cena&7.", "&7Zamkniecie = itemy wracaja"), false));
        h.inv.setItem(ZAMKNIJ, pl.sklepy.ikona(Material.BARRIER, null, "&#FF5A68&lZAMKNIJ", List.of("&7Itemy wroca do ekwipunku"), false));
        odswiez(h);
        p.openInventory(h.inv);
    }

    private void odswiez(Menu h) {
        double suma = 0; int ile = 0, zle = 0;
        for (int i = 0; i < POLA; i++) {
            ItemStack it = h.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double c = pl.ceny.cena(it);
            if (c > 0) { suma += c; ile += it.getAmount(); } else zle += it.getAmount();
        }
        List<String> lore = new java.util.ArrayList<>(List.of("&8&m                         ", "&7Przedmiotow: &f" + String.format("%,d", ile).replace(',', ' '), "&7Wartosc: &#FFC93D&l" + Ceny.fmt(suma)));
        if (zle > 0) lore.add("&c" + zle + " &7nie do sprzedania (wroca)");
        lore.add(""); lore.add("&#3AE07A» Kliknij, aby sprzedac");
        h.inv.setItem(SPRZEDAJ, pl.sklepy.ikona(Material.EMERALD, null, "&#3AE07A&lSPRZEDAJ WSZYSTKO", lore, suma > 0));
    }

    private void sprzedaj(Player p, Menu h) {
        double suma = 0; int ile = 0;
        for (int i = 0; i < POLA; i++) {
            ItemStack it = h.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double c = pl.ceny.cena(it);
            if (c <= 0) continue;
            suma += c; ile += it.getAmount();
            h.inv.setItem(i, null);
        }
        if (suma <= 0) { p.sendMessage(WestSMP.c("&cNie ma tu nic do sprzedania.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); return; }
        suma = Math.floor(suma * 100) / 100.0;
        pl.kasa.dodaj(p, suma);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        p.sendMessage(WestSMP.c("&#FFC23D&lSPRZEDAZ &8» &7Sprzedano &f" + ile + " &7przedmiotow za &#FFC93D&l" + Ceny.fmt(suma)));
        odswiez(h);
    }

    private void oddaj(Player p, Menu h) {
        for (int i = 0; i < POLA; i++) {
            ItemStack it = h.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            h.inv.setItem(i, null);
            p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        int s = e.getRawSlot();
        if (s >= POLA && s < 54) {
            e.setCancelled(true);
            if (s == SPRZEDAJ) sprzedaj(p, h);
            else if (s == ZAMKNIJ) p.closeInventory();
            return;
        }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }   // zbieranie stacka zabraloby przyciski
        Bukkit.getScheduler().runTask(pl, () -> odswiez(h));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        for (int s : e.getRawSlots()) if (s >= POLA && s < 54) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(pl, () -> odswiez(h));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu h && e.getPlayer() instanceof Player p) oddaj(p, h);
    }

    void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h) { oddaj(p, h); p.closeInventory(); }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("sell") && a.length > 0 && a[0].equalsIgnoreCase("przeladuj") && s.hasPermission("westsmp.admin")) {
            pl.ceny.wczytaj(); s.sendMessage(WestSMP.c("&aPrzeladowano ceny.yml")); return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        ItemStack r = p.getInventory().getItemInMainHand();
        if (cmd.getName().equalsIgnoreCase("cena")) {
            if (r.getType().isAir()) { p.sendMessage(WestSMP.c("&7Wez przedmiot do reki.")); return true; }
            double c = pl.ceny.cena(r);
            if (c <= 0) p.sendMessage(WestSMP.c("&#FFC23D&lCENA &8» &cTego przedmiotu nie da sie sprzedac."));
            else p.sendMessage(WestSMP.c("&#FFC23D&lCENA &8» &7" + (r.getAmount() > 1 ? r.getAmount() + "x " : "") + "&f" + r.getType().name().toLowerCase().replace('_', ' ') + " &7= &#FFC93D&l" + Ceny.fmt(c)
                + (r.getAmount() > 1 ? " &8(1 szt: " + Ceny.fmt(c / r.getAmount()) + ")" : "")));
            return true;
        }
        if (a.length > 0 && (a[0].equalsIgnoreCase("reka") || a[0].equalsIgnoreCase("hand"))) {
            double c = pl.ceny.cena(r);
            if (c <= 0) { p.sendMessage(WestSMP.c("&cTego nie da sie sprzedac.")); return true; }
            int n = r.getAmount();
            p.getInventory().setItemInMainHand(null);
            pl.kasa.dodaj(p, c);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
            p.sendMessage(WestSMP.c("&#FFC23D&lSPRZEDAZ &8» &7Sprzedano &f" + n + " &7przedmiotow za &#FFC93D&l" + Ceny.fmt(c)));
            return true;
        }
        otworz(p);
        return true;
    }
}
