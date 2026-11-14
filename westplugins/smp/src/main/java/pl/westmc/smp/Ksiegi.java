package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Ksiegi z wlasnymi zaczarowaniami + Magiczne Kowadlo. */
final class Ksiegi implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final NamespacedKey KSIEGA, ZDROWIE;
    private final Random rnd = new Random();
    static final String[][] K = {
        // id, nazwa, opis, na co
        {"przetapianie", "&#FF7A2APrzetapianie", "Ruda wypada od razu jako sztabka", "narzedzie"},
        {"telekineza", "&#C08BFFTelekineza", "Wykopane rzeczy wpadaja do ekwipunku", "narzedzie"},
        {"kopacz", "&#FFD84ASzybki Kopacz", "Haste II, gdy trzymasz narzedzie", "narzedzie"},
        {"noc", "&#5AAEFFNocne Oko", "Widzenie w ciemnosci (helm)", "helm"},
        {"lekkosc", "&#D8F6FFLekkosc", "-60% obrazen od upadku (buty)", "buty"},
        {"odbicie", "&#FF5A68Odbicie", "20% szansy odbicia ciosu (zbroja)", "zbroja"},
        {"madrosc", "&#7AFFB4Madrosc", "+50% EXP (bron i narzedzia)", "bron"},
        {"zycie", "&#FF8AB4Zycie", "+2 serca za kazda czesc zbroi", "zbroja"},
    };

    Ksiegi(WestSMP pl) { this.pl = pl; KSIEGA = new NamespacedKey(pl, "ksiega"); ZDROWIE = new NamespacedKey(pl, "zycie"); }

    private static String[] def(String id) { for (String[] k : K) if (k[0].equals(id)) return k; return null; }
    private NamespacedKey zKey(String id) { return new NamespacedKey(pl, "z_" + id); }

    ItemStack ksiega(String id) {
        String[] d = def(id);
        if (d == null) return null;
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("westmc:ksiega/" + id));
        m.setDisplayName(WestSMP.c("&f&lKsiega: " + d[1]));
        m.setLore(List.of(WestSMP.c("&7" + d[2]), WestSMP.c("&7Pasuje na: &f" + d[3]), "", WestSMP.c("&#C08BFF» Nałóż w Magicznym Kowadle &8(/kowadlo)")));
        m.getPersistentDataContainer().set(KSIEGA, PersistentDataType.STRING, id);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    boolean ma(ItemStack it, String id) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(zKey(id)); }

    private boolean pasuje(ItemStack it, String gdzie) {
        if (it == null) return false;
        String n = it.getType().name();
        return switch (gdzie) {
            case "narzedzie" -> n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
            case "helm" -> n.endsWith("_HELMET");
            case "buty" -> n.endsWith("_BOOTS");
            case "zbroja" -> n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
            case "bron" -> n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT");
            default -> false;
        };
    }

    private ItemStack nałóż(ItemStack it, String id) {
        ItemStack w = it.clone();
        ItemMeta m = w.getItemMeta();
        m.getPersistentDataContainer().set(zKey(id), PersistentDataType.BYTE, (byte) 1);
        List<String> lore = m.getLore() == null ? new ArrayList<>() : new ArrayList<>(m.getLore());
        lore.add(0, WestSMP.c("&7✦ " + def(id)[1]));
        m.setLore(lore);
        m.setEnchantmentGlintOverride(true);
        w.setItemMeta(m);
        return w;
    }

    // ===================================================================== kowadlo
    static final class Kowadlo implements InventoryHolder { Inventory inv; @Override public Inventory getInventory() { return inv; } }

    void kowadlo(Player p) {
        Kowadlo h = new Kowadlo();
        h.inv = Bukkit.createInventory(h, 27, Tlo.tytul(0xE180, 3));
        ItemStack strz = new ItemStack(Material.ARROW); ItemMeta m = strz.getItemMeta(); m.setDisplayName(WestSMP.c("&7Poloz przedmiot i ksiege")); strz.setItemMeta(m);
        h.inv.setItem(14, strz);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f, 1.4f);
    }

    private String ksiegaId(ItemStack it) { return it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(KSIEGA, PersistentDataType.STRING); }

    private void podglad(Kowadlo h) {
        ItemStack it = h.inv.getItem(10), ks = h.inv.getItem(12);
        String id = ksiegaId(ks);
        if (it == null || id == null) { h.inv.setItem(16, null); return; }
        String[] d = def(id);
        if (!pasuje(it, d[3]) || ma(it, id)) {
            ItemStack x = new ItemStack(Material.BARRIER); ItemMeta m = x.getItemMeta();
            m.setDisplayName(WestSMP.c(ma(it, id) ? "&cTen przedmiot juz to ma" : "&cTa ksiega nie pasuje (" + d[3] + ")")); x.setItemMeta(m);
            h.inv.setItem(16, x); return;
        }
        ItemStack w = nałóż(it, id);
        ItemMeta m = w.getItemMeta();
        List<String> lore = new ArrayList<>(m.getLore());
        lore.add(""); lore.add(WestSMP.c("&7Koszt: &#FFC93D" + pl.kasa.fmt(pl.getConfig().getDouble("ksiegi.cena", 2500)) + " &7+ &a" + pl.getConfig().getInt("ksiegi.poziomy", 10) + " lvl"));
        lore.add(WestSMP.c("&#4EE08F» Kliknij, aby nalozyc"));
        m.setLore(lore); w.setItemMeta(m);
        h.inv.setItem(16, w);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Kowadlo h) || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
        boolean gora = e.getClickedInventory() == e.getInventory();
        if (!gora) {
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack c = e.getCurrentItem();
                if (c == null) return;
                int slot = ksiegaId(c) != null ? 12 : 10;
                if (h.inv.getItem(slot) == null) { h.inv.setItem(slot, c); e.setCurrentItem(null); }
            }
            Bukkit.getScheduler().runTask(pl, () -> podglad(h));
            return;
        }
        int s = e.getRawSlot();
        if (s == 10 || s == 12) { Bukkit.getScheduler().runTask(pl, () -> podglad(h)); return; }
        e.setCancelled(true);
        if (s != 16) return;
        ItemStack it = h.inv.getItem(10); String id = ksiegaId(h.inv.getItem(12));
        if (it == null || id == null || !pasuje(it, def(id)[3]) || ma(it, id)) return;
        double cena = pl.getConfig().getDouble("ksiegi.cena", 2500); int lvl = pl.getConfig().getInt("ksiegi.poziomy", 10);
        if (p.getLevel() < lvl) { p.sendMessage(WestSMP.c("&cPotrzebujesz &f" + lvl + " &cpoziomow.")); return; }
        if (!pl.kasa.zabierz(p, cena)) { p.sendMessage(WestSMP.c("&cPotrzebujesz &f" + pl.kasa.fmt(cena))); return; }
        p.setLevel(p.getLevel() - lvl);
        ItemStack w = nałóż(it, id);
        ItemStack ks = h.inv.getItem(12); ks.setAmount(ks.getAmount() - 1);
        h.inv.setItem(12, ks.getAmount() <= 0 ? null : ks);
        h.inv.setItem(10, w);
        podglad(h);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.3f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        p.sendMessage(WestSMP.c("&a&l✔ &7Nalozono: " + def(id)[1]));
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Kowadlo h) { for (int s : e.getRawSlots()) if (s < 27 && s != 10 && s != 12) { e.setCancelled(true); return; } Bukkit.getScheduler().runTask(pl, () -> podglad(h)); } }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Kowadlo h) || !(e.getPlayer() instanceof Player p)) return;
        for (int s : new int[]{10, 12}) { ItemStack it = h.inv.getItem(s); if (it != null) { h.inv.setItem(s, null); p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r)); } }
    }

    // ===================================================================== dzialanie zaczarowan
    void start() {
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack reka = p.getInventory().getItemInMainHand();
                if (ma(reka, "kopacz")) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 50, 1, true, false, true));
                if (ma(p.getInventory().getHelmet(), "noc")) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, true));
                int serca = 0;
                for (ItemStack a : p.getInventory().getArmorContents()) if (ma(a, "zycie")) serca += 4;
                AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
                if (ai == null) continue;
                AttributeModifier old = null;
                for (AttributeModifier mo : ai.getModifiers()) if (mo.getKey().equals(ZDROWIE)) old = mo;
                if (old != null && old.getAmount() == serca) continue;
                if (old != null) ai.removeModifier(old);
                if (serca > 0) ai.addModifier(new AttributeModifier(ZDROWIE, serca, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                if (p.getHealth() > ai.getValue()) p.setHealth(ai.getValue());
            }
        }, 40L, 40L);
    }

    private static final Map<Material, Material> PIEC = Map.of(Material.RAW_IRON, Material.IRON_INGOT, Material.RAW_GOLD, Material.GOLD_INGOT, Material.RAW_COPPER, Material.COPPER_INGOT,
        Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP, Material.COBBLESTONE, Material.STONE, Material.SAND, Material.GLASS, Material.KELP, Material.DRIED_KELP);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
        boolean piec = ma(tool, "przetapianie"), tele = ma(tool, "telekineza");
        if (!piec && !tele) return;
        for (Item i : new ArrayList<>(e.getItems())) {
            ItemStack s = i.getItemStack();
            if (piec && PIEC.containsKey(s.getType())) { s = new ItemStack(PIEC.get(s.getType()), s.getAmount()); i.setItemStack(s); }
            if (tele) { final ItemStack fs = s; e.getItems().remove(i); e.getPlayer().getInventory().addItem(fs).values().forEach(r -> e.getPlayer().getWorld().dropItemNaturally(e.getBlock().getLocation(), r)); }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakXp(BlockBreakEvent e) { if (ma(e.getPlayer().getInventory().getItemInMainHand(), "madrosc")) e.setExpToDrop((int) Math.round(e.getExpToDrop() * 1.5)); }

    @EventHandler
    public void onKill(EntityDeathEvent e) { Player k = e.getEntity().getKiller(); if (k != null && ma(k.getInventory().getItemInMainHand(), "madrosc")) e.setDroppedExp((int) Math.round(e.getDroppedExp() * 1.5)); }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) { if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && ma(p.getInventory().getBoots(), "lekkosc")) e.setDamage(e.getDamage() * 0.4); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getDamager() instanceof LivingEntity atak)) return;
        int n = 0; for (ItemStack a : p.getInventory().getArmorContents()) if (ma(a, "odbicie")) n++;
        if (n > 0 && rnd.nextDouble() < 0.05 * n) { atak.damage(e.getFinalDamage() * 0.3, p); p.getWorld().playSound(p.getLocation(), Sound.ENCHANT_THORNS_HIT, 0.8f, 1f); }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("kowadlo")) { if (s instanceof Player p) kowadlo(p); return true; }
        if (!s.hasPermission("westsmp.admin") || a.length < 3 || !a[0].equalsIgnoreCase("daj")) { StringBuilder b = new StringBuilder(); for (String[] k : K) b.append(k[0]).append(", "); s.sendMessage(WestSMP.c("&7/ksiega daj <gracz> <" + b.substring(0, b.length() - 2) + "> [ilosc]")); return true; }
        Player t = Bukkit.getPlayerExact(a[1]); ItemStack k = ksiega(a[2].toLowerCase());
        if (t == null || k == null) { s.sendMessage(WestSMP.c("&cZly gracz lub ksiega.")); return true; }
        k.setAmount(a.length > 3 ? Integer.parseInt(a[3]) : 1);
        t.getInventory().addItem(k);
        s.sendMessage(WestSMP.c("&aDano ksiege."));
        return true;
    }
}
