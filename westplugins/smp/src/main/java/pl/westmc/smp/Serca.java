package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Lifesteal: zabicie gracza = +1 serce, smierc z reki gracza = -1 serce. Przedmiot "Serce" (/serca wyplac). */
final class Serca implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final NamespacedKey KLUCZ, ITEM;

    Serca(WestSMP pl) { this.pl = pl; KLUCZ = new NamespacedKey(pl, "serca"); ITEM = new NamespacedKey(pl, "serce_item"); }

    private boolean wl() { return pl.getConfig().getBoolean("serca.wlaczone", true); }
    int min() { return pl.getConfig().getInt("serca.min", 3); }
    int max() { return pl.getConfig().getInt("serca.max", 20); }

    int serca(Player p) { return p.getPersistentDataContainer().getOrDefault(KLUCZ, PersistentDataType.INTEGER, pl.getConfig().getInt("serca.start", 10)); }

    void ustaw(Player p, int n) {
        n = Math.max(min(), Math.min(max(), n));
        p.getPersistentDataContainer().set(KLUCZ, PersistentDataType.INTEGER, n);
        zastosuj(p);
    }

    private void zastosuj(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        double hp = wl() ? serca(p) * 2.0 : 20.0;
        a.setBaseValue(hp);
        if (p.getHealth() > hp) p.setHealth(hp);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { zastosuj(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!wl()) return;
        Player o = e.getEntity(), k = o.getKiller();
        if (k == null || k.equals(o)) return;
        int so = serca(o);
        if (so > min()) {
            ustaw(o, so - 1);
            o.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Straciles serce! Masz teraz &c" + serca(o) + " ❤"));
        } else o.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Masz minimum serc (&c" + min() + " ❤&7) - nic nie straciles."));
        if (so <= min() && !pl.getConfig().getBoolean("serca.zabojca-dostaje-gdy-ofiara-na-minimum", false)) return;
        int sk = serca(k);
        if (sk < max()) {
            ustaw(k, sk + 1);
            k.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Zabrales serce graczowi &f" + o.getName() + "&7! Masz &c" + serca(k) + " ❤"));
            k.playSound(k.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
            k.getWorld().spawnParticle(Particle.HEART, k.getLocation().add(0, 2, 0), 6, 0.4, 0.3, 0.4, 0);
        } else {
            k.getInventory().addItem(item(1)).values().forEach(x -> k.getWorld().dropItemNaturally(k.getLocation(), x));
            k.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Masz juz maksimum serc - dostajesz &cSerce &7jako przedmiot."));
        }
    }

    ItemStack item(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("westmc:event/serce"));
        m.setDisplayName(WestSMP.c("&#FF3A4A&lSerce"));
        m.setLore(List.of(WestSMP.c("&7PPM - dodaje ci &c+1 ❤"), WestSMP.c("&7(max " + max() + " serc)"), "", WestSMP.c("&8Wyplacasz: /serca wyplac")));
        m.getPersistentDataContainer().set(ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean jestSerce(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ITEM, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!jestSerce(it)) return;
        e.setCancelled(true);
        if (!wl()) return;
        if (serca(p) >= max()) { p.sendMessage(WestSMP.c("&cMasz juz maksimum serc (&f" + max() + "&c).")); return; }
        it.setAmount(it.getAmount() - 1);
        ustaw(p, serca(p) + 1);
        p.setHealth(Math.min(p.getHealth() + 2, serca(p) * 2.0));
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.6f);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 8, 0.4, 0.3, 0.4, 0);
        p.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7+1 serce! Masz &c" + serca(p) + " ❤"));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (s.hasPermission("westsmp.admin") && (sub.equals("ustaw") || sub.equals("daj")) && a.length >= 3) {
            Player t = Bukkit.getPlayer(a[1]);
            if (t == null) { s.sendMessage(WestSMP.c("&cGracz offline.")); return true; }
            int n; try { n = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage(WestSMP.c("&cZla liczba.")); return true; }
            if (sub.equals("ustaw")) { ustaw(t, n); s.sendMessage(WestSMP.c("&a" + t.getName() + " ma teraz &c" + serca(t) + " ❤")); }
            else { t.getInventory().addItem(item(Math.max(1, n))).values().forEach(x -> t.getWorld().dropItemNaturally(t.getLocation(), x)); s.sendMessage(WestSMP.c("&aDano " + n + "x Serce graczowi " + t.getName())); }
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("/serca ustaw|daj <gracz> <ilosc>"); return true; }
        if (sub.equals("wyplac")) {
            if (serca(p) <= min()) { p.sendMessage(WestSMP.c("&cMasz minimum serc (&f" + min() + "&c) - nie mozesz wyplacic.")); return true; }
            if (p.getInventory().firstEmpty() < 0) { p.sendMessage(WestSMP.c("&cZrob miejsce w ekwipunku.")); return true; }
            ustaw(p, serca(p) - 1);
            p.getInventory().addItem(item(1));
            p.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Wyplaciles serce. Masz &c" + serca(p) + " ❤"));
            return true;
        }
        p.sendMessage(WestSMP.c("&#FF3A4A&lSERCA &8» &7Masz &c" + serca(p) + "&7/&c" + max() + " ❤ &8(min " + min() + ")"));
        p.sendMessage(WestSMP.c("&7Zabij gracza: &a+1 ❤&7, zginiesz z reki gracza: &c-1 ❤"));
        p.sendMessage(WestSMP.c("&f/serca wyplac &7- zamien serce w przedmiot"));
        if (s.hasPermission("westsmp.admin")) p.sendMessage(WestSMP.c("&7Admin: &f/serca ustaw <gracz> <n>&7, &f/serca daj <gracz> <n>"));
        return true;
    }
}
