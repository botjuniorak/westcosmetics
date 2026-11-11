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
 * Konsola/panel: /backup oddaj <uuid|nick> <nr> [kto].
 */
public final class WestBackup extends JavaPlugin implements Listener {
    private static final int[] LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int RESTORE = 49, BACK = 45, INFO = 47, DELETE = 53, PREV = 48, CLOSE = 49, NEXT = 50, HEAD = 4;
    private File dir;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");
    private final Map<UUID, Long> confirmDelete = new HashMap<>();
    private boolean tex;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tex = getConfig().getBoolean("tekstury", true);
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
        if (p.getKiller() != null) y.set(path + ".zabojca", p.getKiller().getName());
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

    @SuppressWarnings("deprecation")
    private static UUID resolve(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return Bukkit.getOfflinePlayer(s).getUniqueId(); }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("westbackup.uzyj")) { s.sendMessage(c("&cBrak uprawnień.")); return true; }
        if (a.length >= 3 && a[0].equalsIgnoreCase("oddaj")) {
            int nr;
            try { nr = Integer.parseInt(a[2]); } catch (NumberFormatException e) { s.sendMessage(c("&cZły numer kopii.")); return true; }
            String by = a.length >= 4 ? a[3] + " (panel)" : s.getName();
            s.sendMessage(c(restore(by, resolve(a[1]), nr)));
            return true;
        }
        if (!(s instanceof Player admin)) { s.sendMessage("Użycie z konsoli: backup oddaj <uuid|nick> <nr> [kto]"); return true; }
        if (a.length < 1) { admin.sendMessage(c("&7Użycie: &f/backup <nick>")); return true; }
        UUID t = resolve(a[0]);
        if (!file(t).exists()) { admin.sendMessage(c("&cBrak kopii dla gracza &f" + a[0] + "&c.")); return true; }
        openList(admin, t, 0);
        return true;
    }

    // ------------------------------------------------------------ GUI

    private static final class ListHolder implements InventoryHolder {
        final UUID target; final int page; final Map<Integer, Integer> nums = new HashMap<>(); Inventory inv;
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

    /** Niewidzialny przedmiot z paczki (structure_void) - poszerza przyciski narysowane na teksturze. */
    private ItemStack hitbox(ItemStack like) {
        ItemStack it = like.clone();
        it.setType(Material.STRUCTURE_VOID);
        return it;
    }

    private void fill(Inventory inv) {
        if (tex) return;
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, "&8", null);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    private static int count(List<?> l) {
        int n = 0;
        for (Object o : l) if (o instanceof ItemStack i && !i.getType().isAir()) n++;
        return n;
    }

    private String title(char bg, String text, String plain) {
        if (!tex) return c(plain);
        return new Tytul().obraz(0, bg, 176).tekst(c(text));
    }

    private void openList(Player admin, UUID target, int page) {
        YamlConfiguration y = load(target);
        String nick = y.getString("nick", "?");
        ListHolder h = new ListHolder(target, page);
        Inventory inv = Bukkit.createInventory(h, 54, title('', "&fKopie ekwipunku &8· &e" + nick, "&8Kopie EQ · &0" + nick));
        h.inv = inv;
        ConfigurationSection s = y.getConfigurationSection("kopie");
        List<Integer> nums = new ArrayList<>();
        if (s != null) for (String k : s.getKeys(false)) try { nums.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        nums.sort((x, z) -> z - x);
        int per = LIST_SLOTS.length;
        int from = page * per;
        int open = 0;
        for (int n : nums) if (!s.contains(n + ".zwrocone-przez")) open++;
        for (int i = 0; i < per && from + i < nums.size(); i++) {
            int n = nums.get(from + i);
            String p = n + ".";
            boolean back = s.contains(p + "zwrocone-przez");
            String killer = s.getString(p + "zabojca");
            List<String> lore = new ArrayList<>();
            lore.add("&8" + fmt.format(new Date(s.getLong(p + "czas"))));
            lore.add("");
            lore.add("&7☠ &f" + s.getString(p + "przyczyna"));
            lore.add("&7⌖ &f" + s.getString(p + "miejsce"));
            lore.add("&7▣ &f" + (count(s.getList(p + "ekwipunek", List.of())) + count(s.getList(p + "zbroja", List.of()))) + " przedmiotów &8· &7poziom &f" + s.getInt(p + "poziom"));
            lore.add("");
            lore.add(back ? "&a✔ Zwrócone &8· &7" + s.getString(p + "zwrocone-przez") : "&e▶ Kliknij, aby podejrzeć");
            Material icon = back ? Material.MINECART : killer != null ? Material.IRON_SWORD : Material.CHEST;
            ItemStack it = item(icon, (back ? "&7" : killer != null ? "&c&l" : "&b&l") + "Śmierć #" + n, lore);
            if (!back && i == 0 && page == 0) { ItemMeta m = it.getItemMeta(); m.setEnchantmentGlintOverride(true); it.setItemMeta(m); }
            inv.setItem(LIST_SLOTS[i], it);
            h.nums.put(LIST_SLOTS[i], n);
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        sm.setDisplayName(c("&e&l" + nick));
        sm.setLore(List.of(c("&7Kopii: &f" + nums.size()), c("&7Do zwrotu: " + (open > 0 ? "&e" : "&a") + open),
            c("&8Kopie robią się same po każdej śmierci."), c("&8Najnowsza jest pierwsza i błyszczy.")));
        head.setItemMeta(sm);
        inv.setItem(HEAD, head);
        int pages = Math.max(1, (nums.size() + per - 1) / per);
        if (page > 0) inv.setItem(PREV, item(Material.ARROW, "&f« Poprzednia strona", List.of("&7Strona " + page + " z " + pages)));
        if (from + per < nums.size()) inv.setItem(NEXT, item(Material.ARROW, "&fNastępna strona »", List.of("&7Strona " + (page + 2) + " z " + pages)));
        inv.setItem(CLOSE, item(Material.OAK_DOOR, "&cZamknij", null));
        fill(inv);
        admin.openInventory(inv);
    }

    private void openView(Player admin, UUID target, int nr) {
        YamlConfiguration y = load(target);
        String p = "kopie." + nr + ".";
        if (!y.contains(p + "czas")) { admin.sendMessage(c("&cTa kopia już nie istnieje.")); return; }
        boolean back = y.contains(p + "zwrocone-przez");
        ViewHolder h = new ViewHolder(target, nr);
        String nick = y.getString("nick", "?");
        String t;
        if (tex) {
            Tytul tt = new Tytul().obraz(0, '', 176);
            if (back) tt.obraz(7 + 18 * 3 - 1, '', 56);
            t = tt.tekst(c("&fKopia &e#" + nr + " &8· &f" + nick));
        } else t = c("&8Kopia #" + nr + " · &0" + nick);
        Inventory inv = Bukkit.createInventory(h, 54, t);
        h.inv = inv;
        List<?> storage = y.getList(p + "ekwipunek", List.of());
        for (int i = 0; i < 36 && i < storage.size(); i++) if (storage.get(i) instanceof ItemStack it) inv.setItem(i < 9 ? 27 + i : i - 9, it);
        List<?> armor = y.getList(p + "zbroja", List.of());
        for (int i = 0; i < 4 && i < armor.size(); i++) if (armor.get(i) instanceof ItemStack it) inv.setItem(39 - i, it);  // buty..helm -> 39..36
        ItemStack off = y.getItemStack(p + "druga-reka");
        if (off != null) inv.setItem(41, off);
        if (!tex) {
            ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, "&8", null);
            inv.setItem(40, pane);
            for (int i = 42; i < 45; i++) inv.setItem(i, pane);
        }
        inv.setItem(BACK, item(Material.ARROW, "&f« Wróć do listy", null));
        String killer = y.getString(p + "zabojca");
        List<String> info = new ArrayList<>(List.of(
            "&8" + fmt.format(new Date(y.getLong(p + "czas"))), "",
            "&7☠ &f" + y.getString(p + "przyczyna"),
            "&7⌖ &f" + y.getString(p + "miejsce"),
            "&7✦ Poziom: &f" + y.getInt(p + "poziom")));
        if (back) info.addAll(List.of("", "&a✔ Zwrócone przez &f" + y.getString(p + "zwrocone-przez"), "&8" + fmt.format(new Date(y.getLong(p + "zwrocone-czas")))));
        info.addAll(List.of("", "&8Rzędy 1–3: plecak · rząd 4: pasek", "&8Rząd 5: hełm, klata, spodnie, buty, druga ręka"));
        inv.setItem(INFO, item(killer != null ? Material.IRON_SWORD : Material.PAPER, "&f&lInformacje", info));
        ItemStack btn;
        if (back) btn = item(Material.LIME_DYE, "&7&lJuż zwrócone", List.of("&7przez &f" + y.getString(p + "zwrocone-przez"), "&8Każdą kopię można zwrócić tylko raz."));
        else btn = item(Material.EMERALD_BLOCK, "&a&lZWRÓĆ EKWIPUNEK", List.of("&7Przedmioty trafią do &f" + nick + "&7.", "&7Nie zmieszczą się? Wypadną mu pod nogi.", "&7Offline? Dostanie je po wejściu.", "", "&a▶ Kliknij, aby zwrócić"));
        inv.setItem(RESTORE, btn);
        if (tex) { inv.setItem(RESTORE - 1, hitbox(btn)); inv.setItem(RESTORE + 1, hitbox(btn)); }
        if (admin.hasPermission("westbackup.usun")) inv.setItem(DELETE, item(Material.BARRIER, "&c&lUsuń kopię", List.of("&7Kliknij dwa razy, aby usunąć.")));
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
            if (h.nums.containsKey(slot)) openView(admin, h.target, h.nums.get(slot));
            else if (slot == PREV && h.page > 0) openList(admin, h.target, h.page - 1);
            else if (slot == NEXT && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) openList(admin, h.target, h.page + 1);
            else if (slot == CLOSE) admin.closeInventory();
        } else if (holder instanceof ViewHolder v) {
            if (slot == BACK) openList(admin, v.target, 0);
            else if (slot >= RESTORE - 1 && slot <= RESTORE + 1) {
                ItemStack b = e.getView().getTopInventory().getItem(RESTORE);
                if (b != null && b.getType() == Material.EMERALD_BLOCK) {
                    admin.sendMessage(c(restore(admin.getName(), v.target, v.nr)));
                    admin.playSound(admin.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                    openView(admin, v.target, v.nr);
                }
            } else if (slot == DELETE && admin.hasPermission("westbackup.usun")) {
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

    /** Zwraca kopie; wynik to wiadomosc dla tego, kto zwracal. */
    private String restore(String by, UUID target, int nr) {
        if (!file(target).exists()) return "&cBrak kopii dla tego gracza.";
        YamlConfiguration y = load(target);
        String p = "kopie." + nr + ".";
        if (!y.contains(p + "czas")) return "&cKopia #" + nr + " nie istnieje.";
        if (y.contains(p + "zwrocone-przez")) return "&cKopia #" + nr + " została już zwrócona (" + y.getString(p + "zwrocone-przez") + ").";
        List<ItemStack> items = new ArrayList<>();
        for (String k : List.of("ekwipunek", "zbroja")) for (Object o : y.getList(p + k, List.of())) if (o instanceof ItemStack it && !it.getType().isAir()) items.add(it);
        ItemStack off = y.getItemStack(p + "druga-reka");
        if (off != null && !off.getType().isAir()) items.add(off);
        y.set(p + "zwrocone-przez", by);
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
        getLogger().info(by + " zwrócił kopię #" + nr + " graczowi " + y.getString("nick") + " (" + items.size() + " przedmiotów)");
        return "&aZwrócono kopię &f#" + nr + " &agraczowi &f" + y.getString("nick") + (t == null ? " &7(dostanie po wejściu)" : "") + "&a.";
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
        if (!p.getName().equals(y.getString("nick"))) { y.set("nick", p.getName()); save(p.getUniqueId(), y); }
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
