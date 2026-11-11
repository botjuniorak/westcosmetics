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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "gracze.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadAchs();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) check(p); }, 200L, 1200L);
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
        for (Ach a : achs.values()) {
            long v = value(p, a);
            int lvl = level(p, a);
            while (lvl < a.thresholds.length && v >= a.thresholds[lvl]) {
                lvl++;
                data.set(p.getUniqueId() + ".poziomy." + a.id, lvl);
                data.set(p.getUniqueId() + ".nick", p.getName());
                changed = true;
                reward(p, a, lvl);
            }
        }
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

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static String bar(double f) {
        int full = (int) Math.round(f * 20);
        return "&a" + "|".repeat(full) + "&8" + "|".repeat(20 - full);
    }

    private static String num(long v) { return String.format(Locale.ROOT, "%,d", v).replace(',', ' '); }

    private void open(Player p) {
        Holder h = new Holder();
        int rows = Math.max(3, Math.min(6, (achs.size() + 8) / 9 + 2));
        Inventory inv = Bukkit.createInventory(h, rows * 9, c("&8Osiągnięcia"));
        h.inv = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta(); pm.setDisplayName(" "); pane.setItemMeta(pm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        int done = 0, total = 0;
        int slot = 10;
        for (Ach a : achs.values()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= inv.getSize() - 9) break;
            int lvl = level(p, a);
            long v = value(p, a);
            total += a.thresholds.length;
            done += lvl;
            boolean max = lvl >= a.thresholds.length;
            ItemStack it = new ItemStack(a.icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(c((max ? "&6&l" : "&e&l") + a.name + (lvl > 0 ? " " + ROMAN[Math.min(lvl - 1, ROMAN.length - 1)] : "")));
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
            for (int i = 0; i < a.thresholds.length; i++) tiers.append(i < lvl ? "&a" : "&8").append(ROMAN[Math.min(i, ROMAN.length - 1)]).append(' ');
            lore.add(c(tiers.toString().trim()));
            m.setLore(lore);
            m.addItemFlags(ItemFlag.values());
            if (max) m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }
        ItemStack sum = new ItemStack(Material.NETHER_STAR);
        ItemMeta sm = sum.getItemMeta();
        sm.setDisplayName(c("&b&lTwoje postępy"));
        sm.setLore(List.of(c("&7Zdobyte poziomy: &f" + done + " &8/ &f" + total), c(bar(total == 0 ? 0 : (double) done / total) + " &e" + (total == 0 ? 0 : done * 100 / total) + "%"),
            c("&7Najlepsza seria: &f" + data.getInt(p.getUniqueId() + ".najlepsza-seria", 0))));
        sum.setItemMeta(sm);
        inv.setItem(inv.getSize() - 5, sum);
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }

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
        open(p);
        return true;
    }
}
