package pl.westmc.osiagniecia;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Osiagniecia liczone ze statystyk gry: poziomy I-V, nagrody automatycznie, menu z paskiem postepu. */
public final class WestOsiagniecia extends JavaPlugin implements Listener {
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static final class Ach {
        String id, name, desc, stat;
        Material icon;
        double div = 1;
        long[] thresholds;
        List<List<String>> rewards = new ArrayList<>();
        List<String> rewardDesc = new ArrayList<>();
    }

    private final Map<String, Ach> achs = new LinkedHashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "gracze.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadAchs();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) check(p); if (dirty) { dirty = false; saveData(); } }, 200L, 1200L);
    }

    @Override
    public void onDisable() { saveData(); }

    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void saveData() { try { data.save(dataFile); } catch (Exception e) { getLogger().warning("Nie zapisano danych: " + e.getMessage()); } }

    private void loadAchs() {
        reloadConfig();
        achs.clear();
        ConfigurationSection s = getConfig().getConfigurationSection("osiagniecia");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection a = s.getConfigurationSection(id);
            Ach x = new Ach();
            x.id = id;
            x.name = a.getString("nazwa", id);
            x.desc = a.getString("opis", "");
            x.stat = a.getString("statystyka", "PLAYER_KILLS");
            x.div = Math.max(1, a.getDouble("dzielnik", 1));
            Material m = Material.matchMaterial(a.getString("ikona", "PAPER"));
            x.icon = m == null ? Material.PAPER : m;
            List<Long> th = a.getLongList("progi");
            x.thresholds = th.stream().mapToLong(Long::longValue).toArray();
            for (Object o : a.getList("nagrody", List.of())) {
                List<String> cmds = new ArrayList<>();
                if (o instanceof List<?> l) for (Object c : l) cmds.add(String.valueOf(c));
                else if (o != null) cmds.add(String.valueOf(o));
                x.rewards.add(cmds);
            }
            x.rewardDesc = a.getStringList("opis-nagrod");
            if (x.thresholds.length > 0) achs.put(id, x);
            else getLogger().warning("Osiągnięcie " + id + " nie ma progów - pomijam.");
        }
        getLogger().info("Wczytano " + achs.size() + " osiągnięć.");
    }

    // ------------------------------------------------------------ wartosci

    private long value(Player p, Ach a) {
        String st = a.stat.toUpperCase(Locale.ROOT);
        try {
            if (st.equals("SERIA")) return data.getLong(p.getUniqueId() + ".najlepsza-seria", 0);
            if (st.contains(":")) {
                String[] parts = st.split(":", 2);
                Statistic s = Statistic.valueOf(parts[0]);
                long sum = 0;
                for (String t : parts[1].split(",")) {
                    t = t.trim();
                    if (s.getType() == Statistic.Type.ENTITY) sum += p.getStatistic(s, EntityType.valueOf(t));
                    else { Material m = Material.matchMaterial(t); if (m != null) sum += p.getStatistic(s, m); }
                }
                return (long) (sum / a.div);
            }
            return (long) (p.getStatistic(Statistic.valueOf(st)) / a.div);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private int level(Player p, Ach a) { return data.getInt(p.getUniqueId() + ".poziomy." + a.id, 0); }

    // ------------------------------------------------------------ sprawdzanie i nagrody

    private void check(Player p) {
        boolean changed = false;
        String base = p.getUniqueId().toString();
        data.set(base + ".nick", p.getName());
        data.set(base + ".ostatnio", System.currentTimeMillis());
        for (Ach a : achs.values()) {
            long v = value(p, a);
            data.set(base + ".wartosci." + a.id, v);
            int lvl = level(p, a);
            while (lvl < a.thresholds.length && v >= a.thresholds[lvl]) {
                lvl++;
                data.set(p.getUniqueId() + ".poziomy." + a.id, lvl);
                data.set(p.getUniqueId() + ".nick", p.getName());
                changed = true;
                reward(p, a, lvl);
            }
        }
        dirty = true;
        if (changed) saveData();
    }

    private void reward(Player p, Ach a, int lvl) {
        if (lvl - 1 < a.rewards.size()) for (String cmd : a.rewards.get(lvl - 1)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{GRACZ}", p.getName()));
        }
        String r = lvl - 1 < a.rewardDesc.size() ? a.rewardDesc.get(lvl - 1) : "";
        String tier = ROMAN[Math.min(lvl - 1, ROMAN.length - 1)];
        p.sendTitle(c("&6&l✦ OSIĄGNIĘCIE ✦"), c("&e" + a.name + " " + tier + (r.isBlank() ? "" : " &8· &a+" + r)), 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        p.sendMessage(c("&6&l✦ &eZdobyłeś osiągnięcie &f" + a.name + " " + tier + (r.isBlank() ? "" : "&e! Nagroda: &a" + r)));
        if (lvl >= getConfig().getInt("ogloszenie-od-poziomu", 3)) {
            Bukkit.broadcastMessage(c("&6&l✦ &f" + p.getName() + " &7zdobył osiągnięcie &e" + a.name + " " + tier + "&7!"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        streak.remove(dead.getUniqueId());
        Player k = dead.getKiller();
        if (k == null || k.equals(dead)) return;
        int s = streak.merge(k.getUniqueId(), 1, Integer::sum);
        String path = k.getUniqueId() + ".najlepsza-seria";
        if (s > data.getInt(path, 0)) data.set(path, s);
        Bukkit.getScheduler().runTask(this, () -> check(k));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isOnline()) check(e.getPlayer()); }, 60L); }

    // ------------------------------------------------------------ menu

    private static final int[] CARD_SLOTS = {19, 21, 23, 25, 37, 39, 41, 43};
    private static final int PREV = 45, NEXT = 53, HEAD = 4;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        int page;
        @Override public Inventory getInventory() { return inv; }
    }

    private static String bar(double f) {
        int full = (int) Math.round(f * 20);
        return "&a" + "|".repeat(full) + "&8" + "|".repeat(20 - full);
    }

    private static String num(long v) { return String.format(Locale.ROOT, "%,d", v).replace(',', ' '); }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(c(name));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String x : lore) l.add(c(x)); im.setLore(l); }
        im.addItemFlags(ItemFlag.values());
        it.setItemMeta(im);
        return it;
    }

    private ItemStack card(Player p, Ach a) {
        int lvl = level(p, a);
        long v = value(p, a);
        boolean max = lvl >= a.thresholds.length;
        ItemStack it = new ItemStack(a.icon);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(c((max ? "&6&l" : lvl > 0 ? "&e&l" : "&f&l") + a.name + (lvl > 0 ? " " + ROMAN[Math.min(lvl - 1, ROMAN.length - 1)] : "")));
        List<String> lore = new ArrayList<>();
        lore.add(c("&7" + a.desc));
        lore.add("");
        if (max) {
            lore.add(c("&6✔ Wszystkie poziomy zdobyte!"));
            lore.add(c("&7Wynik: &f" + num(v)));
        } else {
            long prev = lvl == 0 ? 0 : a.thresholds[lvl - 1];
            long next = a.thresholds[lvl];
            double f = Math.max(0, Math.min(1, (double) (v - prev) / Math.max(1, next - prev)));
            lore.add(c("&7Poziom " + ROMAN[Math.min(lvl, ROMAN.length - 1)] + ": &f" + num(v) + " &8/ &f" + num(next)));
            lore.add(c(bar(f) + " &e" + (int) (f * 100) + "%"));
            if (lvl < a.rewardDesc.size()) lore.add(c("&7Nagroda: &a" + a.rewardDesc.get(lvl)));
        }
        lore.add("");
        StringBuilder tiers = new StringBuilder("&7Poziomy: ");
        for (int i = 0; i < a.thresholds.length; i++) tiers.append(i < lvl ? (max ? "&6" : "&a") : "&8").append(ROMAN[Math.min(i, ROMAN.length - 1)]).append(' ');
        lore.add(c(tiers.toString().trim()));
        m.setLore(lore);
        m.addItemFlags(ItemFlag.values());
        if (max) m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private void open(Player p, int page) {
        boolean tex = getConfig().getBoolean("tekstury", true);
        List<Ach> list = new ArrayList<>(achs.values());
        int pages = Math.max(1, (list.size() + CARD_SLOTS.length - 1) / CARD_SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        int done = 0, total = 0;
        for (Ach a : list) { total += a.thresholds.length; done += Math.min(level(p, a), a.thresholds.length); }
        int from = page * CARD_SLOTS.length;

        String title;
        if (tex) {
            Tytul t = new Tytul().obraz(0, '\uE3A3', 176);
            int step = total == 0 ? 0 : (int) Math.floor(done * 20.0 / total);
            if (step > 0) {
                int w = (int) Math.round(124 * step / 20.0);
                t.obraz(26, (char) (0xE3C0 + step), w);
            }
            for (int i = 0; i < CARD_SLOTS.length && from + i < list.size(); i++) {
                Ach a = list.get(from + i);
                int lvl = Math.min(5, Math.max(0, level(p, a) * 5 / Math.max(1, a.thresholds.length)));
                if (a.thresholds.length == 5) lvl = Math.min(5, level(p, a));
                int col = CARD_SLOTS[i] % 9, row = CARD_SLOTS[i] / 9;
                boolean top = row == 2;
                if (level(p, a) >= a.thresholds.length) t.obraz(18 * col, top ? '\uE3A5' : '\uE3A6', 33);
                t.obraz(18 * col + 4, (char) ((top ? 0xE3B0 : 0xE3B8) + lvl), 24);
            }
            title = t.tekst(c("&fOsiągnięcia" + (pages > 1 ? " &8· &7" + (page + 1) + "/" + pages : "")));
        } else title = c("&8Osiągnięcia");

        Holder h = new Holder();
        h.page = page;
        Inventory inv = Bukkit.createInventory(h, 54, title);
        h.inv = inv;
        if (!tex) {
            ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", null);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        }
        for (int i = 0; i < CARD_SLOTS.length && from + i < list.size(); i++) inv.setItem(CARD_SLOTS[i], card(p, list.get(from + i)));

        // bez glowy gracza: na serwerze offline-mode skin czesto sie nie laduje (czarno-fioletowa kostka)
        ItemStack head = new ItemStack(Material.NETHER_STAR);
        ItemMeta sm = head.getItemMeta();
        sm.setDisplayName(c("&6&l" + p.getName()));
        int pct = total == 0 ? 0 : done * 100 / total;
        sm.setLore(List.of(c("&7Zdobyte poziomy: &f" + done + " &8/ &f" + total), c(bar(total == 0 ? 0 : (double) done / total) + " &e" + pct + "%"),
            c("&7Najlepsza seria: &f" + data.getInt(p.getUniqueId() + ".najlepsza-seria", 0)), "", c("&8Nagrody wpadają same po zdobyciu poziomu.")));
        head.setItemMeta(sm);
        inv.setItem(HEAD, head);
        if (page > 0) inv.setItem(PREV, named(Material.ARROW, "&f« Poprzednia strona", null));
        if (page < pages - 1) inv.setItem(NEXT, named(Material.ARROW, "&fNastępna strona »", null));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() != Material.ARROW) return;
        if (e.getRawSlot() == PREV) open(p, h.page - 1);
        else if (e.getRawSlot() == NEXT) open(p, h.page + 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("osiagnieciaadmin")) {
            loadAchs();
            s.sendMessage(c("&aPrzeładowano osiągnięcia (" + achs.size() + ")."));
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        check(p);
        open(p, 0);
        return true;
    }
}
