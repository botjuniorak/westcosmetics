package pl.westmc.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Otchlan: itemy z czyszczenia trafiaja tutaj; przez X sekund kazdy moze je zabrac (/otchlan). */
final class Otchlan implements Listener, CommandExecutor {
    private static final int TLO = 0xE2C0;
    private final WestSMP pl;
    private final List<ItemStack> itemy = new ArrayList<>();
    private long zamkniecie;
    private BukkitTask task;

    Otchlan(WestSMP pl) { this.pl = pl; }

    static final class Menu implements InventoryHolder { Inventory inv; int strona; @Override public Inventory getInventory() { return inv; } }

    boolean otwarta() { return System.currentTimeMillis() < zamkniecie; }

    /** Wywolywane przy czyszczeniu: dodaje itemy i otwiera otchlan na X sekund. */
    void wrzuc(List<ItemStack> l) {
        if (!pl.getConfig().getBoolean("otchlan.wlaczona", true) || l.isEmpty()) return;
        int max = pl.getConfig().getInt("otchlan.max-stackow", 540);
        for (ItemStack i : l) if (itemy.size() < max) itemy.add(i);
        zamkniecie = System.currentTimeMillis() + pl.getConfig().getInt("otchlan.sekund", 120) * 1000L;
        Bukkit.broadcast(Component.text(WestSMP.c("&#B06AFF&lOTCHLAN &8» &7Itemy z ziemi trafily do Otchlani! &d&n[KLIKNIJ, ABY OTWORZYC]")).clickEvent(ClickEvent.runCommand("/otchlan")));
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tik, 20L, 20L);
    }

    private void tik() {
        if (!otwarta()) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
            itemy.clear();
            task.cancel(); task = null;
            Bukkit.broadcast(Component.text(WestSMP.c("&#B06AFF&lOTCHLAN &8» &7Otchlan zostala zamknieta.")));
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h) zegar(h);
    }

    private void zegar(Menu h) {
        long s = Math.max(0, (zamkniecie - System.currentTimeMillis()) / 1000);
        h.inv.setItem(49, pl.sklepy.ikona(Material.CLOCK, null, "&#B06AFF&lOTCHLAN", List.of("&7Tu trafiaja itemy z ziemi", "&7po czyszczeniu. Otwarta jeszcze:", "&f" + s / 60 + ":" + String.format("%02d", s % 60), "", "&7Kto pierwszy, ten lepszy!"), false));
    }

    void otworz(Player p, int strona) {
        if (!otwarta()) { p.sendMessage(WestSMP.c("&#B06AFF&lOTCHLAN &8» &7Otchlan jest zamknieta. Otwiera sie po czyszczeniu itemow z ziemi.")); return; }
        Menu h = new Menu();
        int stron = Math.max(1, (itemy.size() + 44) / 45);
        h.strona = Math.max(0, Math.min(strona, stron - 1));
        h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(TLO, 4));
        rysuj(h);
        p.openInventory(h.inv);
    }

    private void rysuj(Menu h) {
        int stron = Math.max(1, (itemy.size() + 44) / 45);
        if (h.strona >= stron) h.strona = stron - 1;
        for (int i = 0; i < 45; i++) { int k = h.strona * 45 + i; h.inv.setItem(i, k < itemy.size() ? itemy.get(k) : null); }
        h.inv.setItem(45, pl.sklepy.ikona(Material.ARROW, null, "&#3ADFFF&lPOPRZEDNIA", List.of("&7Strona &f" + (h.strona + 1) + "&7/&f" + stron), false));
        h.inv.setItem(53, pl.sklepy.ikona(Material.ARROW, null, "&#3ADFFF&lNASTEPNA", List.of("&7Strona &f" + (h.strona + 1) + "&7/&f" + stron), false));
        zegar(h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (s == 45) { h.strona--; if (h.strona < 0) h.strona = 0; rysuj(h); return; }
        if (s == 53) { h.strona++; rysuj(h); return; }
        if (s >= 45) return;
        int k = h.strona * 45 + s;
        if (k >= itemy.size()) return;
        ItemStack it = itemy.get(k);
        var reszta = p.getInventory().addItem(it.clone());
        if (reszta.isEmpty()) itemy.remove(k);
        else { ItemStack r = reszta.values().iterator().next(); if (r.getAmount() == it.getAmount()) { p.sendMessage(WestSMP.c("&cZrob miejsce w ekwipunku.")); return; } it.setAmount(r.getAmount()); }
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        for (Player o : Bukkit.getOnlinePlayers()) if (o.getOpenInventory().getTopInventory().getHolder() instanceof Menu oh) rysuj(oh);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (s instanceof Player p) otworz(p, 0); else s.sendMessage("Tylko w grze.");
        return true;
    }
}
