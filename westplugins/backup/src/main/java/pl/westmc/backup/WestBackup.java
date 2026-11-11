package pl.westmc.backup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Po kazdej smierci zapisuje ekwipunek gracza. Administracja: /backup <nick> -> lista smierci -> podglad -> "Zwroc".
 * Kazda kopie mozna zwrocic tylko raz (bez dupowania). Gracz offline dostaje przedmioty po wejsciu.
 */
public final class WestBackup extends JavaPlugin implements Listener {
    private File dir;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");
    private final Map<UUID, Long> confirmDelete = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dir = new File(getDataFolder(), "kopie");
        dir.mkdirs();
        getServer().getPluginManager().registerEvents(this, this);
    }

    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    // ------------------------------------------------------------ zapis

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private YamlConfiguration load(UUID id) { return YamlConfiguration.loadConfiguration(file(id)); }

    private void save(UUID id, YamlConfiguration y) {
        try { y.save(file(id)); } catch (Exception e) { getLogger().warning("Nie zapisano kopii: " + e.getMessage()); }
    }

    private static String cause(Player p) {
        Player k = p.getKiller();
        if (k != null) return "zabity przez " + k.getName();
        EntityDamageEvent last = p.getLastDamageCause();
        if (last == null) return "nieznana";
        if (last instanceof EntityDamageByEntityEvent ed) return "zabity przez " + ed.getDamager().getType().name().toLowerCase().replace('_', ' ');
        return switch (last.getCause()) {
            case FALL -> "upadek";
            case LAVA -> "lawa";
            case FIRE, FIRE_TICK -> "ogień";
            case DROWNING -> "utonięcie";
            case VOID -> "void";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "wybuch";
            case SUFFOCATION -> "uduszenie";
            case STARVATION -> "głód";
            case PROJECTILE -> "pocisk";
            case MAGIC, POISON -> "magia / trucizna";
            default -> last.getCause().name().toLowerCase().replace('_', ' ');
        };
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerInventory inv = p.getInventory();
        List<ItemStack> storage = new ArrayList<>(Arrays.asList(inv.getStorageContents()));
        List<ItemStack> armor = new ArrayList<>(Arrays.asList(inv.getArmorContents()));
        ItemStack off = inv.getItemInOffHand();
        boolean empty = storage.stream().allMatch(i -> i == null || i.getType().isAir())
            && armor.stream().allMatch(i -> i == null || i.getType().isAir()) && (off == null || off.getType().isAir());
        if (empty && getConfig().getBoolean("pomijaj-puste", true)) return;

        YamlConfiguration y = load(p.getUniqueId());
        int nr = y.getInt("licznik", 0) + 1;
        y.set("licznik", nr);
        y.set("nick", p.getName());
        String path = "kopie." + nr;
        Location l = p.getLocation();
        y.set(path + ".czas", System.currentTimeMillis());
        y.set(path + ".przyczyna", cause(p));
        y.set(path + ".miejsce", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        y.set(path + ".poziom", p.getLevel());
        y.set(path + ".ekwipunek", storage);
        y.set(path + ".zbroja", armor);
        y.set(path + ".druga-reka", off);
        // sprzatanie: limit i wiek
        ConfigurationSection s = y.getConfigurationSection("kopie");
        if (s != null) {
            long maxAge = getConfig().getLong("dni", 14) * 86400000L;
            List<Integer> nums = new ArrayList<>();
            for (String k : s.getKeys(false)) try { nums.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
            nums.sort(null);
            int max = getConfig().getInt("max-kopii", 25);
            for (int n : nums) {
                boolean old = System.currentTimeMillis() - s.getLong(n + ".czas") > maxAge;
                if (old || nums.size() - nums.indexOf(n) > max) s.set(String.valueOf(n), null);
            }
        }
        save(p.getUniqueId(), y);
    }

    // ------------------------------------------------------------ komenda

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player admin)) { s.sendMessage("Tylko w grze."); return true; }
        if (!admin.hasPermission("westbackup.uzyj")) { admin.sendMessage(c("&cBrak uprawnień.")); return true; }
        if (a.length < 1) { admin.sendMessage(c("&7Użycie: &f/backup <nick>")); return true; }
        @SuppressWarnings("deprecation") OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
        if (!file(t.getUniqueId()).exists()) { admin.sendMessage(c("&cBrak kopii dla gracza &f" + a[0] + "&c.")); return true; }
        openList(admin, t.getUniqueId(), 0);
        return true;
    }

    // ------------------------------------------------------------ GUI

    private static final class ListHolder implements InventoryHolder {
        final UUID target; final int page; final List<Integer> nums = new ArrayList<>(); Inventory inv;
        ListHolder(UUID t, int p) { target = t; page = p; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class ViewHolder implements InventoryHolder {
        final UUID target; final int nr; Inventory inv;
        ViewHolder(UUID t, int n) { target = t; nr = n; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(c(name));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String x : lore) l.add(c(x)); meta.setLore(l); }
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static int count(List<?> l) {
        int n = 0;
        for (Object o : l) if (o instanceof ItemStack i && !i.getType().isAir()) n++;
        return n;
    }

    private void openList(Player admin, UUID target, int page) {
        YamlConfiguration y = load(target);
        String nick = y.getString("nick", "?");
        ListHolder h = new ListHolder(target, page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8Kopie EQ · &0" + nick));
        h.inv = inv;
        ConfigurationSection s = y.getConfigurationSection("kopie");
        List<Integer> nums = new ArrayList<>();
        if (s != null) for (String k : s.getKeys(false)) try { nums.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        nums.sort((x, z) -> z - x);
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < nums.size(); i++) {
            int n = nums.get(from + i);
            String p = n + ".";
            boolean back = s.contains(p + "zwrocone-przez");
            List<String> lore = new ArrayList<>();
            lore.add("&8&m                    ");
            lore.add("&7Data: &f" + fmt.format(new Date(s.getLong(p + "czas"))));
            lore.add("&7Przyczyna: &f" + s.getString(p + "przyczyna"));
            lore.add("&7Miejsce: &f" + s.getString(p + "miejsce"));
            lore.add("&7Przedmioty: &f" + (count(s.getList(p + "ekwipunek", List.of())) + count(s.getList(p + "zbroja", List.of()))) + " &8· &7poziom: &f" + s.getInt(p + "poziom"));
            lore.add("&8&m                    ");
            lore.add(back ? "&a✔ Zwrócone przez " + s.getString(p + "zwrocone-przez") : "&eKliknij, aby podejrzeć i zwrócić");
            inv.setItem(i, item(back ? Material.MINECART : Material.CHEST_MINECART, (back ? "&7" : "&b&l") + "Śmierć #" + n, lore));
            h.nums.add(n);
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        sm.setDisplayName(c("&e&l" + nick));
        sm.setLore(List.of(c("&7Kopii: &f" + nums.size()), c("&7Kopie robią się same po każdej śmierci.")));
        head.setItemMeta(sm);
        inv.setItem(49, head);
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&f« Poprzednia strona", null));
        if (from + 45 < nums.size()) inv.setItem(53, item(Material.ARROW, "&fNastępna strona »", null));
        admin.openInventory(inv);
    }

    private void openView(Player admin, UUID target, int nr) {
        YamlConfiguration y = load(target);
        String p = "kopie." + nr + ".";
        if (!y.contains(p + "czas")) { admin.sendMessage(c("&cTa kopia już nie istnieje.")); return; }
        ViewHolder h = new ViewHolder(target, nr);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8Kopia #" + nr + " · &0" + y.getString("nick", "?")));
        h.inv = inv;
        List<?> storage = y.getList(p + "ekwipunek", List.of());
        for (int i = 0; i < 36 && i < storage.size(); i++) if (storage.get(i) instanceof ItemStack it) inv.setItem(i < 9 ? 27 + i : i - 9, it);
        List<?> armor = y.getList(p + "zbroja", List.of());
        for (int i = 0; i < 4 && i < armor.size(); i++) if (armor.get(i) instanceof ItemStack it) inv.setItem(39 - i, it);  // helm..buty: 36-39 odwrotnie
        ItemStack off = y.getItemStack(p + "druga-reka");
        if (off != null) inv.setItem(41, off);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, "&8", null);
        inv.setItem(40, pane);
        for (int i = 42; i < 45; i++) inv.setItem(i, pane);
        boolean back = y.contains(p + "zwrocone-przez");
        inv.setItem(45, item(Material.ARROW, "&f« Wróć do listy", null));
        inv.setItem(47, item(Material.PAPER, "&f&lInformacje", List.of(
            "&7Data: &f" + fmt.format(new Date(y.getLong(p + "czas"))),
            "&7Przyczyna: &f" + y.getString(p + "przyczyna"),
            "&7Miejsce: &f" + y.getString(p + "miejsce"),
            "&7Poziom: &f" + y.getInt(p + "poziom"),
            "&8Rzędy 1–3: plecak · rząd 4: pasek",
            "&8Rząd 5: hełm, klata, spodnie, buty, druga ręka")));
        if (back) inv.setItem(49, item(Material.LIME_DYE, "&a&lJuż zwrócone", List.of("&7przez &f" + y.getString(p + "zwrocone-przez"), "&7Każdą kopię można zwrócić tylko raz.")));
        else inv.setItem(49, item(Material.EMERALD_BLOCK, "&a&lZWRÓĆ EKWIPUNEK", List.of("&7Przedmioty trafią do gracza.", "&7Nie zmieścią się? Wypadną mu pod nogi.", "&7Offline? Dostanie je po wejściu.", "", "&eKliknij, aby zwrócić")));
        if (admin.hasPermission("westbackup.usun")) inv.setItem(53, item(Material.BARRIER, "&c&lUsuń kopię", List.of("&7Kliknij dwa razy, aby usunąć.")));
        admin.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof ListHolder) && !(holder instanceof ViewHolder)) return;
        e.setCancelled(true);   // nic nie da sie wyjac - podglad tylko do odczytu
        if (!(e.getWhoClicked() instanceof Player admin) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (holder instanceof ListHolder h) {
            if (slot < 45 && slot < h.nums.size()) openView(admin, h.target, h.nums.get(slot));
            else if (slot == 45 && h.page > 0) openList(admin, h.target, h.page - 1);
            else if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) openList(admin, h.target, h.page + 1);
        } else if (holder instanceof ViewHolder v) {
            if (slot == 45) openList(admin, v.target, 0);
            else if (slot == 49 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.EMERALD_BLOCK) restore(admin, v.target, v.nr);
            else if (slot == 53 && admin.hasPermission("westbackup.usun")) {
                Long t = confirmDelete.get(admin.getUniqueId());
                if (t != null && System.currentTimeMillis() - t < 4000) {
                    confirmDelete.remove(admin.getUniqueId());
                    YamlConfiguration y = load(v.target);
                    y.set("kopie." + v.nr, null);
                    save(v.target, y);
                    admin.sendMessage(c("&7Usunięto kopię &f#" + v.nr + "&7."));
                    openList(admin, v.target, 0);
                } else {
                    confirmDelete.put(admin.getUniqueId(), System.currentTimeMillis());
                    admin.sendMessage(c("&eKliknij jeszcze raz, aby usunąć kopię #" + v.nr + "."));
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof ListHolder || h instanceof ViewHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ zwracanie

    private void restore(Player admin, UUID target, int nr) {
        YamlConfiguration y = load(target);
        String p = "kopie." + nr + ".";
        if (y.contains(p + "zwrocone-przez")) { admin.sendMessage(c("&cTa kopia została już zwrócona.")); return; }
        List<ItemStack> items = new ArrayList<>();
        for (String k : List.of("ekwipunek", "zbroja")) for (Object o : y.getList(p + k, List.of())) if (o instanceof ItemStack it && !it.getType().isAir()) items.add(it);
        ItemStack off = y.getItemStack(p + "druga-reka");
        if (off != null && !off.getType().isAir()) items.add(off);
        y.set(p + "zwrocone-przez", admin.getName());
        y.set(p + "zwrocone-czas", System.currentTimeMillis());
        Player t = Bukkit.getPlayer(target);
        if (t != null) give(t, items, nr);
        else {
            List<Object> pending = new ArrayList<>(y.getList("do-oddania", new ArrayList<>()));
            pending.addAll(items);
            y.set("do-oddania", pending);
            y.set("do-oddania-nr", nr);
        }
        save(target, y);
        getLogger().info(admin.getName() + " zwrócił kopię #" + nr + " graczowi " + y.getString("nick") + " (" + items.size() + " przedmiotów)");
        admin.sendMessage(c("&aZwrócono kopię &f#" + nr + " &agraczowi &f" + y.getString("nick") + (t == null ? " &7(dostanie po wejściu)" : "") + "&a."));
        admin.playSound(admin.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        openView(admin, target, nr);
    }

    private void give(Player t, List<ItemStack> items, int nr) {
        for (ItemStack left : t.getInventory().addItem(items.toArray(new ItemStack[0])).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
        t.sendMessage(c(getConfig().getString("wiadomosc-dla-gracza", "").replace("{NR}", String.valueOf(nr))));
        t.playSound(t.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!file(p.getUniqueId()).exists()) return;
        YamlConfiguration y = load(p.getUniqueId());
        List<?> pending = y.getList("do-oddania");
        if (pending == null || pending.isEmpty()) return;
        List<ItemStack> items = new ArrayList<>();
        for (Object o : pending) if (o instanceof ItemStack it) items.add(it);
        int nr = y.getInt("do-oddania-nr", 0);
        y.set("do-oddania", null);
        y.set("do-oddania-nr", null);
        save(p.getUniqueId(), y);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) give(p, items, nr); }, 40L);
    }
}
