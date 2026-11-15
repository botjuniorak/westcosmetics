package pl.westmc.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /pomoc (/help): menu kategorii HD; po kliknieciu lista komend na czacie (klikalne). Wszystkie teksty w configu. */
final class Pomoc implements CommandExecutor, Listener {
    private static final int TLO = 0xE2A0;
    private final WestSMP pl;

    Pomoc(WestSMP pl) { this.pl = pl; }

    static final class Menu implements InventoryHolder { Inventory inv; final Map<Integer, String> akcje = new HashMap<>(); @Override public Inventory getInventory() { return inv; } }

    void otworz(Player p) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 54, Tlo.tytul(TLO, 4));
        ConfigurationSection k = pl.getConfig().getConfigurationSection("pomoc.kategorie");
        if (k != null) for (String id : k.getKeys(false)) {
            ConfigurationSection c = k.getConfigurationSection(id);
            Material m = Material.matchMaterial(c.getString("ikona", "BOOK"));
            List<String> lore = new java.util.ArrayList<>(c.getStringList("opis"));
            lore.add(""); lore.add("&#3ADFFF» Kliknij, aby zobaczyc komendy");
            h.inv.setItem(c.getInt("slot"), pl.sklepy.ikona(m == null ? Material.BOOK : m, null, c.getString("nazwa", id), lore, false));
            h.akcje.put(c.getInt("slot"), id);
        }
        h.inv.setItem(4, pl.sklepy.ikona(Material.WRITABLE_BOOK, null, "&#FFC23D&lZASADY SERWERA", List.of("&7Przeczytaj, zanim zaczniesz grac!", "", "&#FFC23D» Kliknij, aby przeczytac"), true));
        h.akcje.put(4, "zasady");
        h.inv.setItem(53, pl.sklepy.ikona(Material.BARRIER, "westmc:przycisk_zamknij", "&c&lZamknij", null, false));
        h.akcje.put(53, "zamknij");
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
    }

    private void wypisz(Player p, String id) {
        boolean zasady = id.equals("zasady");
        String nazwa = zasady ? "&#FFC23D&lZASADY" : pl.getConfig().getString("pomoc.kategorie." + id + ".nazwa", id);
        List<String> linie = pl.getConfig().getStringList(zasady ? "pomoc.zasady" : "pomoc.kategorie." + id + ".linie");
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text(WestSMP.c("&8&m                &r " + nazwa + " &8&m                ")));
        for (String l : linie) {
            int i = l.indexOf('|');
            if (l.startsWith("/") && i > 0) {
                String kom = l.substring(0, i).trim(), opis = l.substring(i + 1).trim();
                String wpisz = kom.contains(" <") ? kom.substring(0, kom.indexOf(" <")) + " " : kom;
                p.sendMessage(Component.text(WestSMP.c(" &#3ADFFF" + kom + " &8- &7" + opis))
                    .clickEvent(ClickEvent.suggestCommand(wpisz))
                    .hoverEvent(HoverEvent.showText(Component.text(WestSMP.c("&7Kliknij, aby wpisac &f" + kom)))));
            } else p.sendMessage(Component.text(WestSMP.c(" " + l)));
        }
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text(WestSMP.c("  &8» &#3ADFFF&n[Wroc do pomocy]")).clickEvent(ClickEvent.runCommand("/pomoc"))
            .hoverEvent(HoverEvent.showText(Component.text(WestSMP.c("&7Otworz menu pomocy")))));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        String a = h.akcje.get(e.getRawSlot());
        if (a == null) return;
        p.closeInventory();
        if (!a.equals("zamknij")) wypisz(p, a);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        if (cmd.getName().equalsIgnoreCase("zasady")) { wypisz(p, "zasady"); return true; }
        if (a.length > 0 && (a[0].equalsIgnoreCase("zasady") || pl.getConfig().isConfigurationSection("pomoc.kategorie." + a[0].toLowerCase()))) wypisz(p, a[0].toLowerCase());
        else otworz(p);
        return true;
    }
}
