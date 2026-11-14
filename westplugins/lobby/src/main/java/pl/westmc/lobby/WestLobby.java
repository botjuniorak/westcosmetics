package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WestLobby - serwer lobby za proxy (Velocity/Bungee):
 * kompas z wyborem trybu (z liczba graczy), przedmioty startowe, ochrona, podwojny skok,
 * powrot z pustki, portale do serwerow z hologramami, /spawn.
 */
public final class WestLobby extends JavaPlugin implements Listener, PluginMessageListener {
    private static final String CH = "BungeeCord";
    private NamespacedKey keyItem;
    NamespacedKey keyObj;
    final Banery banery = new Banery(this);
    final Postacie maskotki = new Postacie(this);
    final Napisy napisy = new Napisy(this);
    final Hud hud = new Hud(this);
    final Efekty efekty = new Efekty(this);
    private final Map<String, Integer> counts = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Long> portalCd = new HashMap<>();
    private final List<TextDisplay> holos = new ArrayList<>();

    // ============================================================ start
    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrate();
        keyItem = new NamespacedKey(this, "przedmiot");
        keyObj = new NamespacedKey(this, "obiekt");
        banery.saveImages();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CH);
        getServer().getMessenger().registerIncomingPluginChannel(this, CH, this);
        Bukkit.getScheduler().runTaskTimer(this, this::pollCounts, 40L, 60L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshHolos, 60L, 40L);
        Bukkit.getScheduler().runTaskLater(this, this::setupWorld, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::spawnHolos, 40L);
        Bukkit.getScheduler().runTaskLater(this, () -> logo.build(), 50L);
        Bukkit.getScheduler().runTaskTimer(this, this::portalFx, 60L, 3L);
        Bukkit.getScheduler().runTaskLater(this, banery::load, 30L);
        Bukkit.getScheduler().runTaskTimer(this, banery::tick, 80L, 40L);
        Bukkit.getScheduler().runTaskLater(this, maskotki::respawnAll, 45L);
        Bukkit.getScheduler().runTaskTimer(this, maskotki::watchdog, 200L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, maskotki::animate, 60L, 2L);
        Bukkit.getScheduler().runTaskLater(this, napisy::respawnAll, 45L);
        Bukkit.getScheduler().runTaskTimer(this, napisy::watchdog, 210L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, napisy::refresh, 75L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, hud::updateAll, 65L, 40L);
        Bukkit.getScheduler().runTaskLater(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) hud.join(p); }, 20L);
        Bukkit.getScheduler().runTaskTimer(this, maskotki::refreshNames, 70L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::animateSelectors, 10L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateTab, 60L, 40L);
        if (getCommand("lobbyfx") != null) getCommand("lobbyfx").setExecutor(efekty);
        Bukkit.getScheduler().runTaskLater(this, efekty::start, 55L);
    }

    /** Dopisuje nowe opcje do starego config.yml (1.0 -> 1.1): Duele zamiast Lifesteal, banery, maskotki, TAB. */
    /** Aktualizacja starego config.yml: sekcje wygladu/trybow ustawiane od nowa (Duels, postacie, nowy kompas). Postawione rzeczy zostaja. */
    private void migrate() {
        if (getConfig().getInt("wersja-configu", 1) == 4) {
            // 4 -> 5: nowe lobby HD (postacie ze skinow, napisy HD, menu HD, scoreboard/TAB HD, bez kul i krysztalow)
            getConfig().options().copyDefaults(true);
            var c = getConfig();
            c.set("tryby.gildie.slot", 19); c.set("tryby.duels.slot", 22); c.set("tryby.wkrotce.slot", 25);
            c.set("wybor-trybu.rzedy", 5); c.set("wybor-trybu.hd", true);
            c.set("przedmioty.kompas.model", "westmc:kompas");
            for (String[] m : new String[][]{{"gildie", "rycerz", "DIAMOND_SWORD", "miecz"}, {"duels", "wojownik", "NETHERITE_SWORD", "atak"}, {"wkrotce", "mag", "", "magia"}}) {
                c.set("maskotki." + m[0] + ".skin", m[1]); c.set("maskotki." + m[0] + ".przedmiot", m[2]);
                c.set("maskotki." + m[0] + ".animacja", m[3]); c.set("maskotki." + m[0] + ".wielkosc", 1.5);
            }
            c.set("tab.wlaczony", false);
            c.set("fx.efekty", new ArrayList<>());
            c.set("fx.blyskawice", false);
            c.set("intro.wlaczone", false);
            c.set("wersja-configu", 5);
            saveConfig();
            reloadConfig();
            getLogger().info("Zaktualizowano config.yml do wersji 5 (lobby HD).");
            migrate();
            return;
        }
        if (getConfig().getInt("wersja-configu", 1) == 5) {
            // 5 -> 6: Westus i Zlotek jako roboty HD
            var c = getConfig();
            c.set("maskotki.discord.skin", "westus"); c.set("maskotki.discord.animacja", "macha"); c.set("maskotki.discord.przedmiot", "");
            c.set("maskotki.sklep.skin", "zlotek"); c.set("maskotki.sklep.animacja", "moneta"); c.set("maskotki.sklep.przedmiot", "");
            c.set("maskotki.discord.wielkosc", 1.5); c.set("maskotki.sklep.wielkosc", 1.5);
            c.set("wersja-configu", 6);
            saveConfig();
            reloadConfig();
            getLogger().info("Zaktualizowano config.yml do wersji 6 (Westus i Zlotek HD).");
            return;
        }
        if (getConfig().getInt("wersja-configu", 1) >= 6) return;
        if (getConfig().getInt("wersja-configu", 1) == 3) {
            // 3 -> 4: efekty (kule, krysztaly, snieg), ladne ikonki przedmiotow, kompas bez fioletowego blysku
            getConfig().options().copyDefaults(true);
            getConfig().set("przedmioty.kompas.blysk", false);
            getConfig().set("przedmioty.gracze.model", "westmc:oko");
            getConfig().set("przedmioty.gracze.model-ukryci", "westmc:oko_zamkniete");
            getConfig().set("wersja-configu", 4);
            saveConfig();
            reloadConfig();
            getLogger().info("Zaktualizowano config.yml do wersji 4 (efekty lobby, nowe ikonki).");
            migrate();
            return;
        }
        for (String sec : new String[]{"tryby", "przedmioty", "tab", "banery", "maskotki", "wybor-trybu"}) getConfig().set(sec, null);
        getConfig().options().copyDefaults(true);
        getConfig().set("wersja-configu", 6);
        saveConfig();
        reloadConfig();   // nowe klucze musza byc w pliku (getString(sciezka, domyslne) nie widzi wartosci domyslnych)
        getLogger().info("Zaktualizowano config.yml do wersji 3 (Duels, postacie trybow, nowy kompas).");
    }

    int online(String server) { return counts.getOrDefault(server, -1); }

    // ============================================================ TAB (naglowek / stopka listy graczy)
    private void updateTab() {
        if (!getConfig().getBoolean("tab.wlaczony", true) || getConfig().getBoolean("hud.tab", true)) return;
        String h = tabLines("tab.naglowek"), f = tabLines("tab.stopka");
        for (Player p : Bukkit.getOnlinePlayers()) p.setPlayerListHeaderFooter(h.replace("{GRACZ}", p.getName()), f.replace("{GRACZ}", p.getName()));
    }

    private String tabLines(String path) {
        StringBuilder b = new StringBuilder();
        for (String l : getConfig().getStringList(path)) {
            if (b.length() > 0) b.append('\n');
            b.append(fillOnline(l));
        }
        return c(b.toString());
    }

    private String fillOnline(String l) {
        int all = online("ALL");
        l = l.replace("{ONLINE}", all < 0 ? String.valueOf(Bukkit.getOnlinePlayers().size()) : String.valueOf(all));
        int i;
        while ((i = l.indexOf("{ONLINE_")) >= 0) {
            int j = l.indexOf('}', i);
            if (j < 0) break;
            int on = online(l.substring(i + 8, j));
            l = l.substring(0, i) + (on < 0 ? "?" : on) + l.substring(j + 1);
        }
        return l;
    }

    final Logo3D logo = new Logo3D(this);
    final Intro intro = new Intro(this);

    @Override
    public void onDisable() {
        efekty.stop();
        maskotki.despawnAll();
        napisy.despawnAll();
        removeHolos();
        logo.remove();
    }

    static String c(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 7 < s.length() && s.charAt(i + 1) == '#') {
                String hex = s.substring(i + 2, i + 8);
                if (hex.matches("[0-9a-fA-F]{6}")) {
                    b.append('§').append('x');
                    for (char h : hex.toLowerCase().toCharArray()) b.append('§').append(h);
                    i += 7;
                    continue;
                }
            }
            b.append(ch);
        }
        return ChatColor.translateAlternateColorCodes('&', b.toString());
    }

    private String t(String key, String def) { return c(getConfig().getString("teksty." + key, def)); }

    private void setupWorld() {
        Location sp = spawn();
        if (sp == null || sp.getWorld() == null) return;
        World w = sp.getWorld();
        if (getConfig().getBoolean("swiat.stala-pora-dnia", true)) {
            w.setTime(getConfig().getLong("swiat.godzina", 6000L));
            w.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        }
        w.setStorm(false);
        w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false);
    }

    // ============================================================ spawn
    Location spawn() {
        ConfigurationSection s = getConfig().getConfigurationSection("spawn");
        World w = Bukkit.getWorld(s == null ? "world" : s.getString("swiat", "world"));
        if (w == null && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
        if (w == null) return null;
        if (s == null) return w.getSpawnLocation();
        Location l = new Location(w, s.getDouble("x", 0.5), s.getDouble("y", 100), s.getDouble("z", 0.5));
        l.setYaw((float) s.getDouble("yaw", 0));
        l.setPitch((float) s.getDouble("pitch", 0));
        return l;
    }

    void prepare(Player p) {
        Location sp = spawn();
        if (sp != null) p.teleport(sp);
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.getInventory().clear();
        giveItems(p);
        if (getConfig().getBoolean("podwojny-skok", true)) p.setAllowFlight(true);
    }

    // ============================================================ przedmioty startowe
    private ItemStack tool(String id, Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(c(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        meta.setLore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    private String toolId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyItem, PersistentDataType.STRING);
    }

    private void giveItems(Player p) {
        ConfigurationSection s = getConfig().getConfigurationSection("przedmioty");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection i = s.getConfigurationSection(id);
            if (i == null) continue;
            Material m = Material.matchMaterial(i.getString("material", "STONE"));
            if (id.equals("gracze") && hidden.contains(p.getUniqueId())) m = Material.matchMaterial(i.getString("material-ukryci", "GRAY_DYE"));
            if (m == null) continue;
            String name = id.equals("gracze") && hidden.contains(p.getUniqueId()) ? i.getString("nazwa-ukryci", "&7Gracze: &cukryci") : i.getString("nazwa", id);
            ItemStack it = tool(id, m, name, i.getStringList("opis"));
            if (i.getBoolean("blysk", false)) hideAttr(it, true);
            String model = id.equals("gracze") && hidden.contains(p.getUniqueId()) ? i.getString("model-ukryci", "") : i.getString("model", "");
            if (model != null && !model.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(model);
                if (key != null) { ItemMeta mm = it.getItemMeta(); mm.setItemModel(key); it.setItemMeta(mm); }
            }
            p.getInventory().setItem(i.getInt("slot", 0), it);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) {
            if (e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = toolId(e.getItem());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        switch (id) {
            case "kompas" -> openSelector(p);
            case "gracze" -> toggleHide(p);
            default -> {
                for (String cmd : getConfig().getStringList("przedmioty." + id + ".komendy")) {
                    if (cmd.startsWith("[gracz] ")) p.performCommand(cmd.substring(8).replace("{GRACZ}", p.getName()));
                    else if (cmd.startsWith("[wiadomosc] ")) p.sendMessage(c(cmd.substring(12)));
                    else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{GRACZ}", p.getName()));
                }
            }
        }
    }

    private void toggleHide(Player p) {
        boolean hide = hidden.add(p.getUniqueId());
        if (!hide) hidden.remove(p.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            if (hide) p.hidePlayer(this, o); else p.showPlayer(this, o);
        }
        giveItems(p);
        p.sendMessage(t(hide ? "ukryto" : "pokazano", hide ? "&7Gracze sa teraz &cukryci&7." : "&7Gracze sa teraz &awidoczni&7."));
        p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.7f, hide ? 0.8f : 1.3f);
    }

    // ============================================================ wybor trybu (GUI)
    static final class Selector implements InventoryHolder {
        Inventory inv;
        Player owner;
        int frame;
        String stany = "";
        boolean hd;
        final Map<Integer, String> servers = new HashMap<>();
        final Map<Integer, String> actions = new HashMap<>();
        public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, Selector> openSel = new HashMap<>();

    private boolean hd() { return getConfig().getBoolean("wybor-trybu.hd", true); }

    /** Stan kazdego trybu do paska pod karta: z = zielony (online), y = zolty (wkrotce), c = czerwony (brak polaczenia). */
    private String stany() {
        StringBuilder b = new StringBuilder();
        ConfigurationSection s = getConfig().getConfigurationSection("tryby");
        if (s != null) for (String id : s.getKeys(false)) {
            boolean soon = s.getBoolean(id + ".wkrotce", false);
            int on = online(s.getString(id + ".serwer", id));
            b.append(id).append(':').append(soon ? 'y' : on < 0 ? 'c' : 'z').append(';');
        }
        return b.toString();
    }

    private net.kyori.adventure.text.Component tytulHd(String stany) {
        Glify.Tytul t = Glify.siatka(new Glify.Tytul(), -8, Glify.WYBOR, Glify.WYBOR_KOL, 4, 3);
        ConfigurationSection s = getConfig().getConfigurationSection("tryby");
        if (s != null) for (String id : s.getKeys(false)) {
            int slot = s.getInt(id + ".slot", 22), col = slot % 9;
            int cx = 16 + 18 * col;
            char st = stany.contains(id + ":y") ? Glify.STATUS_ZOLTY : stany.contains(id + ":c") ? Glify.STATUS_CZERWONY : Glify.STATUS_ZIELONY;
            t.obraz(cx - 17 - 8, st, 35);
        }
        t.idz(0);
        return Glify.lobby(t.tekst());
    }

    void openSelector(Player p) {
        if (hd()) {
            Selector h = new Selector();
            h.owner = p;
            h.stany = stany();
            h.hd = true;
            h.inv = Bukkit.createInventory(h, 45, tytulHd(h.stany));
            fillSelector(h);
            p.openInventory(h.inv);
            openSel.put(p.getUniqueId(), h);
            p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", 0.8f, 1.2f);
            return;
        }
        int rows = Math.max(3, Math.min(6, getConfig().getInt("wybor-trybu.rzedy", 5)));
        Selector h = new Selector();
        h.owner = p;
        h.inv = Bukkit.createInventory(h, rows * 9, c(getConfig().getString("wybor-trybu.tytul", "&8Wybierz tryb")));
        fillSelector(h);
        p.openInventory(h.inv);
        openSel.put(p.getUniqueId(), h);
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", 0.8f, 1.2f);
    }

    private static final Material[] WAVE = {Material.BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
        Material.WHITE_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE};

    private ItemStack pane(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(c(name));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String x : lore) l.add(c(x)); im.setLore(l); }
        it.setItemMeta(im);
        return it;
    }

    private ItemStack hideAttr(ItemStack it, boolean glint) {
        ItemMeta m = it.getItemMeta();
        if (!m.hasAttributeModifiers())
            m.addAttributeModifier(org.bukkit.attribute.Attribute.LUCK, new org.bukkit.attribute.AttributeModifier(
                keyItem, 0, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        m.setEnchantmentGlintOverride(glint);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack modelItem(String model, String name, List<String> lore) {
        ItemStack it = pane(Material.PAPER, name, lore);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(model));
        it.setItemMeta(m);
        return it;
    }

    private void fillHd(Selector h) {
        h.servers.clear(); h.actions.clear();
        h.inv.clear();
        int all = online("ALL");
        h.inv.setItem(4, modelItem("westmc:logo", "&#399EFC&l✦ WESTMC.EU ✦", List.of("&8&m                          ",
            "&7Graczy na serwerze: &#FFC23D&l" + (all < 0 ? Bukkit.getOnlinePlayers().size() : all),
            "&7Wybierz tryb ponizej i wbijaj!", "&8&m                          ")));
        ConfigurationSection s = getConfig().getConfigurationSection("tryby");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection m = s.getConfigurationSection(id);
            if (m == null) continue;
            Material mat = Material.matchMaterial(m.getString("ikona", "GRASS_BLOCK"));
            if (mat == null) mat = Material.GRASS_BLOCK;
            String server = m.getString("serwer", id);
            boolean soon = m.getBoolean("wkrotce", false);
            int on = online(server);
            List<String> lore = new ArrayList<>();
            lore.add("&8&m                          ");
            lore.addAll(m.getStringList("opis"));
            lore.add("");
            if (soon) lore.add("&8» &e&lJUZ NIEDLUGO!");
            else {
                lore.add("&8» &7Graczy online: " + (on < 0 ? "&8?" : "&a&l" + on));
                lore.add("&8&m                          ");
                lore.add("&#FFC23D&l» KLIKNIJ, ABY GRAC «");
            }
            int slot = m.getInt("slot", 22);
            if (slot < 0 || slot >= 45) continue;
            h.inv.setItem(slot, hideAttr(tool("tryb", mat, m.getString("nazwa", id), lore), false));
            if (!soon) h.servers.put(slot, server);
            if (slot + 9 < 36) {
                String st = soon ? "&e&l● WKROTCE" : on < 0 ? "&c&l● BRAK POLACZENIA" : "&a&l● ONLINE &8- &f" + on + " &7graczy";
                h.inv.setItem(slot + 9, modelItem("westmc:pusty", st, soon || on >= 0 ? null : List.of("&7Serwer jest wylaczony", "&7albo nie ma go w proxy.")));
                if (!soon && on >= 0) h.servers.put(slot + 9, server);
            }
        }
        h.inv.setItem(39, modelItem("westmc:przycisk_discord", "&#5865F2&lDISCORD", List.of("&7Konkursy, ogloszenia, ekipa.", "", "&#5865F2» Kliknij po link")));
        h.inv.setItem(40, modelItem("westmc:przycisk_zamknij", "&c&lZamknij", null));
        h.inv.setItem(41, modelItem("westmc:przycisk_sklep", "&#FFC23D&lSKLEP", List.of("&7Rangi i kosmetyki.", "", "&#FFC23D» Kliknij po link")));
        h.actions.put(39, "discord");
        h.actions.put(40, "zamknij");
        h.actions.put(41, "sklep");
    }

    void fillSelector(Selector h) {
        if (h.hd) { fillHd(h); return; }
        int size = h.inv.getSize(), rows = size / 9;
        h.servers.clear(); h.actions.clear();
        ItemStack bg = pane(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        // ramka z "plynaca" fala kolorow
        List<Integer> border = new ArrayList<>();
        for (int c = 0; c < 9; c++) border.add(c);
        for (int r = 1; r < rows; r++) border.add(r * 9 + 8);
        for (int c = 7; c >= 0; c--) border.add((rows - 1) * 9 + c);
        for (int r = rows - 2; r >= 1; r--) border.add(r * 9);
        for (int i = 0; i < size; i++) h.inv.setItem(i, bg);
        for (int k = 0; k < border.size(); k++)
            h.inv.setItem(border.get(k), pane(WAVE[Math.floorMod(k - h.frame, WAVE.length)], " ", null));
        // gwiazda na gorze: info o sieci
        int all = online("ALL");
        ItemStack star = pane(Material.NETHER_STAR, "&#399EFC&l✦ WESTMC.EU ✦", List.of("&8&m                          ",
            "&7Graczy na serwerze: &#FFC23D&l" + (all < 0 ? Bukkit.getOnlinePlayers().size() : all),
            "&7Wybierz tryb ponizej i wbijaj!", "&8&m                          "));
        h.inv.setItem(4, hideAttr(star, true));
        // tryby + paski statusu pod nimi
        ConfigurationSection s = getConfig().getConfigurationSection("tryby");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection m = s.getConfigurationSection(id);
            if (m == null) continue;
            Material mat = Material.matchMaterial(m.getString("ikona", "GRASS_BLOCK"));
            if (mat == null) mat = Material.GRASS_BLOCK;
            String server = m.getString("serwer", id);
            boolean soon = m.getBoolean("wkrotce", false);
            int on = online(server);
            List<String> lore = new ArrayList<>();
            lore.add("&8&m                          ");
            lore.addAll(m.getStringList("opis"));
            lore.add("");
            if (soon) lore.add("&8» &e&lJUZ NIEDLUGO!");
            else {
                lore.add("&8» &7Graczy online: " + (on < 0 ? "&8?" : "&a&l" + on));
                lore.add("&8&m                          ");
                lore.add("&#FFC23D&l» KLIKNIJ, ABY GRAC «");
            }
            ItemStack it = hideAttr(tool("tryb", mat, m.getString("nazwa", id), lore), !soon);
            int slot = m.getInt("slot", 22);
            if (slot < 0 || slot >= size) continue;
            h.inv.setItem(slot, it);
            if (!soon) h.servers.put(slot, server);
            if (slot + 9 < size - 9) {
                ItemStack st = soon ? pane(Material.YELLOW_STAINED_GLASS_PANE, "&e&l● WKROTCE", null)
                    : on < 0 ? pane(Material.RED_STAINED_GLASS_PANE, "&c&l● BRAK POLACZENIA", List.of("&7Serwer jest wylaczony", "&7albo nie ma go w proxy."))
                    : pane(Material.LIME_STAINED_GLASS_PANE, "&a&l● ONLINE &8- &f" + on + " &7graczy", null);
                h.inv.setItem(slot + 9, st);
                if (!soon && on >= 0) h.servers.put(slot + 9, server);
            }
        }
        // dolny pasek: discord / zamknij / sklep
        int last = size - 9;
        h.inv.setItem(last + 3, hideAttr(pane(Material.AMETHYST_SHARD, "&#5865F2&lDISCORD", List.of("&7Konkursy, ogloszenia, ekipa.", "", "&#5865F2» Kliknij po link")), false));
        h.inv.setItem(last + 4, pane(Material.BARRIER, "&c&lZamknij", null));
        h.inv.setItem(last + 5, hideAttr(pane(Material.EMERALD, "&a&lSKLEP", List.of("&7Rangi i kosmetyki.", "", "&a» Kliknij po link")), false));
        h.actions.put(last + 3, "discord");
        h.actions.put(last + 4, "zamknij");
        h.actions.put(last + 5, "sklep");
    }

    private void animateSelectors() {
        for (Selector h : new ArrayList<>(openSel.values())) {
            if (!h.owner.isOnline()) { openSel.remove(h.owner.getUniqueId()); continue; }
            h.frame++;
            if (h.hd) {
                if (h.frame % 8 != 0) continue;
                String st = stany();
                if (!st.equals(h.stany)) { openSelector(h.owner); continue; }   // zmienil sie kolor paska - nowy tytul
                fillHd(h);
                continue;
            }
            fillSelector(h);
        }
    }

    @EventHandler
    public void onInvClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Selector) openSel.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof Selector h) {
            e.setCancelled(true);
            String server = h.servers.get(e.getRawSlot());
            if (server != null) { p.closeInventory(); connect(p, server); return; }
            String act = h.actions.get(e.getRawSlot());
            if (act == null) return;
            p.closeInventory();
            if (act.equals("discord") || act.equals("sklep")) {
                for (String line : getConfig().getStringList("maskotki." + act + ".wiadomosc")) p.sendRichMessage(line);
                p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.7f, 1.2f);
            }
            return;
        }
        if (!canBuild(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !canBuild(p)) e.setCancelled(true);
    }

    void connect(Player p, String server) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(server);
            p.sendPluginMessage(this, CH, b.toByteArray());
            p.sendMessage(t("laczenie", "&7Laczenie z &f{SERWER}&7...").replace("{SERWER}", server));
            p.playSound(p.getLocation(), "minecraft:entity.enderman.teleport", 0.6f, 1.2f);
        } catch (IOException ex) {
            getLogger().warning("Nie udalo sie wyslac Connect: " + ex.getMessage());
        }
    }

    // ============================================================ liczba graczy z proxy
    private void pollCounts() {
        Player any = null;
        for (Player p : Bukkit.getOnlinePlayers()) { any = p; break; }
        if (any == null) return;
        Set<String> servers = new HashSet<>();
        ConfigurationSection s = getConfig().getConfigurationSection("tryby");
        if (s != null) for (String id : s.getKeys(false)) servers.add(s.getString(id + ".serwer", id));
        servers.add("ALL");
        ConfigurationSection ms = getConfig().getConfigurationSection("maskotki");
        if (ms != null) for (String id : ms.getKeys(false)) { String sv = ms.getString(id + ".serwer"); if (sv != null && !sv.isEmpty()) servers.add(sv); }
        ConfigurationSection bs = getConfig().getConfigurationSection("banery");
        if (bs != null) for (String id : bs.getKeys(false)) { String sv = bs.getString(id + ".serwer"); if (sv != null && !sv.isEmpty()) servers.add(sv); }
        for (String srv : servers) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(b);
                out.writeUTF("PlayerCount");
                out.writeUTF(srv);
                any.sendPluginMessage(this, CH, b.toByteArray());
            } catch (IOException ignored) { }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] msg) {
        if (!CH.equals(channel)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(msg))) {
            if (!"PlayerCount".equals(in.readUTF())) return;
            String server = in.readUTF();
            counts.put(server, in.readInt());
        } catch (IOException ignored) { }
    }

    // ============================================================ portale + hologramy
    private void spawnHolos() {
        removeHolos();
        ConfigurationSection s = getConfig().getConfigurationSection("portale");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            Location h = holoLoc(id);
            if (h == null) continue;
            TextDisplay td = h.getWorld().spawn(h, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setText(holoText(id));
            });
            holos.add(td);
        }
    }

    private void removeHolos() {
        for (TextDisplay d : holos) if (d != null && d.isValid()) d.remove();
        holos.clear();
    }

    private void refreshHolos() {
        ConfigurationSection s = getConfig().getConfigurationSection("portale");
        if (s == null) return;
        int i = 0;
        for (String id : s.getKeys(false)) {
            if (holoLoc(id) == null) continue;
            if (i < holos.size() && holos.get(i).isValid()) holos.get(i).setText(holoText(id));
            i++;
        }
    }

    private String holoText(String id) {
        String server = getConfig().getString("portale." + id + ".serwer", id);
        int online = counts.getOrDefault(server, -1);
        return c(getConfig().getString("portale." + id + ".napis", "&#399EFC&l" + server.toUpperCase())
            + "\n&7Online: &f" + (online < 0 ? "?" : online));
    }

    private Location holoLoc(String id) {
        double[] a = box(id, "poz1"), b = box(id, "poz2");
        if (a == null || b == null) return null;
        World w = Bukkit.getWorld(getConfig().getString("portale." + id + ".swiat", "world"));
        if (w == null) return null;
        return new Location(w, (a[0] + b[0]) / 2 + 0.5, Math.max(a[1], b[1]) + 1.6, (a[2] + b[2]) / 2 + 0.5);
    }

    private double[] box(String id, String k) {
        String v = getConfig().getString("portale." + id + "." + k);
        if (v == null) return null;
        String[] p = v.split(",");
        if (p.length != 3) return null;
        try { return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())}; }
        catch (NumberFormatException e) { return null; }
    }

    private double fxAngle;

    /** Wir czasteczek w kazdym portalu. */
    private void portalFx() {
        if (!getConfig().getBoolean("efekty-portali", true) || Bukkit.getOnlinePlayers().isEmpty()) return;
        fxAngle += 0.35;
        ConfigurationSection s = getConfig().getConfigurationSection("portale");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            double[] a = box(id, "poz1"), b = box(id, "poz2");
            if (a == null || b == null) continue;
            World w = Bukkit.getWorld(s.getString(id + ".swiat", "world"));
            if (w == null) continue;
            double cx = (a[0] + b[0]) / 2 + 0.5, cz = (a[2] + b[2]) / 2 + 0.5, y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]) + 1;
            double rx = Math.abs(a[0] - b[0]) / 2 + 0.3, rz = Math.abs(a[2] - b[2]) / 2 + 0.3, h = y1 - y0;
            for (int k = 0; k < 3; k++) {
                double ang = fxAngle + k * 2.094, t = ((fxAngle * 0.15 + k / 3.0) % 1.0);
                double r = 0.4 + 0.6 * t;
                Location l = new Location(w, cx + Math.cos(ang) * rx * r, y0 + h * (0.5 + 0.45 * Math.sin(ang * 0.7)), cz + Math.sin(ang) * rz * r);
                w.spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.PORTAL, l, 3, 0.1, 0.1, 0.1, 0.4);
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        // pustka
        if (to.getY() < getConfig().getDouble("pustka-y", 0)) {
            Location sp = spawn();
            if (sp != null) { p.teleport(sp); p.playSound(sp, "minecraft:entity.enderman.teleport", 0.6f, 1.0f); }
            return;
        }
        // podwojny skok: znowu dostepny po wyladowaniu
        if (getConfig().getBoolean("podwojny-skok", true) && !p.getAllowFlight() && p.getGameMode() == GameMode.ADVENTURE
            && p.isOnGround()) p.setAllowFlight(true);
        // portale
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        ConfigurationSection s = getConfig().getConfigurationSection("portale");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            double[] a = box(id, "poz1"), b = box(id, "poz2");
            if (a == null || b == null) continue;
            if (!to.getWorld().getName().equals(s.getString(id + ".swiat", "world"))) continue;
            int x = to.getBlockX(), y = to.getBlockY(), z = to.getBlockZ();
            if (x < Math.min(a[0], b[0]) || x > Math.max(a[0], b[0]) || y < Math.min(a[1], b[1]) - 1 || y > Math.max(a[1], b[1])
                || z < Math.min(a[2], b[2]) || z > Math.max(a[2], b[2])) continue;
            Long last = portalCd.get(p.getUniqueId());
            if (last != null && System.currentTimeMillis() - last < 3000) return;
            portalCd.put(p.getUniqueId(), System.currentTimeMillis());
            Vector back = e.getFrom().toVector().subtract(to.toVector()).setY(0);
            if (back.lengthSquared() > 0.0001) p.setVelocity(back.normalize().multiply(0.8).setY(0.3));
            connect(p, s.getString(id + ".serwer", id));
            return;
        }
    }

    @EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking()) intro.skip(e.getPlayer());
    }

    @EventHandler
    public void onDoubleJump(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.ADVENTURE && p.getGameMode() != GameMode.SURVIVAL) return;
        if (!getConfig().getBoolean("podwojny-skok", true)) return;
        e.setCancelled(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        Vector v = p.getLocation().getDirection().multiply(getConfig().getDouble("skok.sila", 1.2)).setY(getConfig().getDouble("skok.wysokosc", 0.9));
        p.setVelocity(v);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.05, 0.3, 0.05);
        p.playSound(p.getLocation(), "minecraft:entity.breeze.jump", 0.6f, 1.2f);
    }

    // ============================================================ wejscie / wyjscie
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("ukryj-wiadomosc-wejscia", true)) e.setJoinMessage(null);
        if (intro.wants(p)) intro.play(p); else prepare(p);
        hud.join(p);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) hud.updateAll(); }, 10L);
        for (UUID id : hidden) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) h.hidePlayer(this, p);
        }
        p.sendTitle(t("tytul", "&#399EFC&lWESTMC.EU"), t("podtytul", "&7Witaj, &f{GRACZ}&7!").replace("{GRACZ}", p.getName()), 10, 50, 15);
        for (String line : getConfig().getStringList("powitanie")) p.sendMessage(c(line.replace("{GRACZ}", p.getName())));
        p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.4f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation().add(0, 1, 0), 60, 0.6, 0.8, 0.6, 0.08);
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (getConfig().getBoolean("ukryj-wiadomosc-wejscia", true)) e.setQuitMessage(null);
        hidden.remove(e.getPlayer().getUniqueId());
        hud.quit(e.getPlayer());
        intro.stop(e.getPlayer());
        portalCd.remove(e.getPlayer().getUniqueId());
    }

    // ============================================================ klik w maskotke / baner
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityClick(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        org.bukkit.entity.Entity en = e.getRightClicked();
        String m = maskotki.idOf(en);
        if (m != null) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) maskotki.click(e.getPlayer(), m);
            return;
        }
        String b = banery.idOf(en);
        if (b != null) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) bannerClick(e.getPlayer(), b);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (banery.idOf(e.getEntity()) == null && maskotki.idOf(e.getEntity()) == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) {
            String m = maskotki.idOf(e.getEntity());
            if (m != null) maskotki.click(p, m); else bannerClick(p, banery.idOf(e.getEntity()));
        }
    }

    @EventHandler
    public void onHangingBreak(org.bukkit.event.hanging.HangingBreakEvent e) {
        if (banery.idOf(e.getEntity()) != null) e.setCancelled(true);
    }

    private final Map<UUID, Long> clickCd = new HashMap<>();

    private void bannerClick(Player p, String id) {
        Long last = clickCd.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 800) return;
        clickCd.put(p.getUniqueId(), System.currentTimeMillis());
        String wzor = banery.wzorOf(id);
        if (wzor == null) return;
        ConfigurationSection c = getConfig().getConfigurationSection("banery." + wzor);
        if (c == null) return;
        String srv = c.getString("klik-serwer", "");
        if (!srv.isEmpty()) { connect(p, srv); return; }
        for (String line : c.getStringList("klik-wiadomosc")) p.sendRichMessage(line);
        if (!c.getStringList("klik-wiadomosc").isEmpty()) p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.7f, 1.2f);
    }

    // ============================================================ czat wylaczony na lobby
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        if (!getConfig().getBoolean("czat.wylaczony", true) || e.getPlayer().hasPermission("westlobby.czat")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(c(getConfig().getString("czat.wiadomosc", "&cCzat na lobby jest wylaczony. &7Wejdz na tryb, aby pisac!")));
    }

    // ============================================================ ochrona
    private boolean canBuild(Player p) { return p.hasPermission("westlobby.buduj") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (!canBuild(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!canBuild(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!canBuild(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (!canBuild(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && !canBuild(p)) e.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onWeather(WeatherChangeEvent e) { if (e.toWeatherState()) e.setCancelled(true); }
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) { Location sp = spawn(); if (sp != null) p.teleport(sp); }
    }

    // ============================================================ komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("spawn")) {
            if (s instanceof Player p) { Location sp = spawn(); if (sp != null) p.teleport(sp); }
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("tryby")) {
            if (s instanceof Player p) openSelector(p);
            return true;
        }
        if (!s.hasPermission("westlobby.admin")) { s.sendMessage(c("&cBrak uprawnien.")); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase()) {
            case "ustawspawn" -> {
                if (!(s instanceof Player p)) return true;
                Location l = p.getLocation();
                getConfig().set("spawn.swiat", l.getWorld().getName());
                getConfig().set("spawn.x", l.getX()); getConfig().set("spawn.y", l.getY()); getConfig().set("spawn.z", l.getZ());
                getConfig().set("spawn.yaw", (double) l.getYaw()); getConfig().set("spawn.pitch", (double) l.getPitch());
                getConfig().set("pustka-y", Math.floor(l.getY() - 40));
                saveConfig();
                s.sendMessage(c("&aUstawiono spawn lobby. &7(powrot z pustki ponizej Y=" + (int) Math.floor(l.getY() - 40) + ")"));
            }
            case "portal" -> {
                if (!(s instanceof Player p) || a.length < 3) { help(s); return true; }
                String id = a[1].toLowerCase(), base = "portale." + id + ".";
                Location l = p.getLocation();
                switch (a[2].toLowerCase()) {
                    case "poz1", "poz2" -> {
                        getConfig().set(base + "swiat", l.getWorld().getName());
                        getConfig().set(base + a[2].toLowerCase(), l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
                        s.sendMessage(c("&aZapisano &f" + a[2] + " &aportalu &f" + id + "&a."));
                    }
                    case "serwer" -> {
                        if (a.length < 4) { help(s); return true; }
                        getConfig().set(base + "serwer", a[3]);
                        s.sendMessage(c("&aPortal &f" + id + " &aprowadzi na serwer &f" + a[3] + "&a."));
                    }
                    case "napis" -> {
                        String txt = String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length));
                        getConfig().set(base + "napis", txt);
                        s.sendMessage(c("&aUstawiono napis portalu: " + txt));
                    }
                    case "usun" -> { getConfig().set("portale." + id, null); s.sendMessage(c("&aUsunieto portal &f" + id)); }
                    case "auto" -> {
                        if (a.length < 4) { help(s); return true; }
                        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
                        int[] mn = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, mx = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                        int found = 0;
                        for (int x = bx - 8; x <= bx + 8; x++) for (int y = by - 3; y <= by + 10; y++) for (int z = bz - 8; z <= bz + 8; z++) {
                            if (l.getWorld().getBlockAt(x, y, z).getType() != Material.NETHER_PORTAL) continue;
                            found++;
                            mn[0] = Math.min(mn[0], x); mn[1] = Math.min(mn[1], y); mn[2] = Math.min(mn[2], z);
                            mx[0] = Math.max(mx[0], x); mx[1] = Math.max(mx[1], y); mx[2] = Math.max(mx[2], z);
                        }
                        if (found == 0) { s.sendMessage(c("&cNie widze bloku portalu w poblizu (8 kratek). Podejdz blizej bramy.")); return true; }
                        getConfig().set(base + "swiat", l.getWorld().getName());
                        getConfig().set(base + "poz1", mn[0] + "," + mn[1] + "," + mn[2]);
                        getConfig().set(base + "poz2", mx[0] + "," + mx[1] + "," + mx[2]);
                        getConfig().set(base + "serwer", a[3]);
                        s.sendMessage(c("&aPortal &f" + id + " &austawiony (" + found + " blokow) -> serwer &f" + a[3] + "&a."));
                    }
                    default -> { help(s); return true; }
                }
                saveConfig();
                spawnHolos();
            }
            case "logo" -> {
                if (!(s instanceof Player p)) return true;
                Location l = p.getLocation();
                getConfig().set("logo.swiat", l.getWorld().getName());
                getConfig().set("logo.x", l.getX()); getConfig().set("logo.y", l.getY()); getConfig().set("logo.z", l.getZ());
                saveConfig();
                logo.build();
                s.sendMessage(c("&aLogo 3D postawione nad toba."));
            }
            case "intro" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length > 1 && a[1].equalsIgnoreCase("srodek")) {
                    Location l = p.getLocation();
                    getConfig().set("intro.srodek", l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
                    saveConfig();
                    s.sendMessage(c("&aSrodek przelotu kamery ustawiony tutaj."));
                } else intro.play(p);
            }
            case "baner" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length < 2) { s.sendMessage(c("&f/lobby baner <gildie|duels|wkrotce|sklep|discord> [szer wys] &7| &f/lobby baner usun")); return true; }
                if (a[1].equalsIgnoreCase("usun")) { s.sendMessage(c(banery.remove(p))); return true; }
                String wz = a[1].toLowerCase();
                if (!new java.io.File(new java.io.File(getDataFolder(), "banery"), getConfig().getString("banery." + wz + ".obraz", wz + ".png")).exists()) {
                    s.sendMessage(c("&cNie ma obrazka banera &f" + wz + "&c. Dostepne: gildie, duels, wkrotce, sklep, discord (albo wrzuc wlasny PNG do plugins/WestLobby/banery/)."));
                    return true;
                }
                int fw = 0, fh = 0;
                if (a.length >= 4) {
                    try { fw = Integer.parseInt(a[2]); fh = Integer.parseInt(a[3]); } catch (NumberFormatException ex) { s.sendMessage(c("&cRozmiar to liczby, np. 4 3")); return true; }
                    if (fw < 1 || fh < 1 || fw > 8 || fh > 6) { s.sendMessage(c("&cRozmiar od 1x1 do 8x6.")); return true; }
                }
                s.sendMessage(c(banery.place(p, wz, fw, fh)));
            }
            case "maskotka", "postac", "npc" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length < 2) { s.sendMessage(c("&f/lobby postac <gildie|duels|wkrotce|discord|sklep> &7| &f/lobby postac usun")); return true; }
                s.sendMessage(c(a[1].equalsIgnoreCase("usun") ? maskotki.remove(p) : maskotki.place(p, a[1].toLowerCase())));
            }
            case "napis" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length < 2) { s.sendMessage(c("&f/lobby napis <gildie|duels|wkrotce|discord|sklep> [skala] &7| &f/lobby napis usun")); return true; }
                if (a[1].equalsIgnoreCase("usun")) { s.sendMessage(c(napisy.remove(p))); return true; }
                double sk = 1.5;
                if (a.length > 2) try { sk = Math.max(0.3, Math.min(8, Double.parseDouble(a[2].replace(',', '.')))); } catch (NumberFormatException ex) { s.sendMessage(c("&cSkala to liczba, np. 1.5")); return true; }
                s.sendMessage(c(napisy.place(p, a[1].toLowerCase(), sk)));
            }
            case "przeladuj" -> {
                reloadConfig();
                napisy.respawnAll();
                hud.updateAll();
                banery.load();
                maskotki.respawnAll();
                spawnHolos();
                logo.build();
                for (Player p : Bukkit.getOnlinePlayers()) giveItems(p);
                s.sendMessage(c("&aPrzeladowano WestLobby."));
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(c("&#399EFC&lWestLobby"));
        s.sendMessage(c("&f/lobby ustawspawn &7- spawn w miejscu, gdzie stoisz"));
        s.sendMessage(c("&f/lobby portal <nazwa> auto <serwer> &7- stoisz przy bramie, portal ustawia sie sam"));
        s.sendMessage(c("&f/lobby portal <nazwa> poz1|poz2 &7- recznie: naroza portalu (stoisz w rogu)"));
        s.sendMessage(c("&f/lobby portal <nazwa> serwer <serwer z proxy>"));
        s.sendMessage(c("&f/lobby portal <nazwa> napis <tekst> &7| &f/lobby portal <nazwa> usun"));
        s.sendMessage(c("&f/lobby logo &7- logo 3D w miejscu, gdzie stoisz (srodek napisu)"));
        s.sendMessage(c("&f/lobby intro srodek &7- srodek przelotu kamery | &f/lobby intro &7- podglad"));
        s.sendMessage(c("&f/lobby baner <gildie|duels|wkrotce|sklep|discord> &7- patrzysz na sciane z jednego koloru (np. 4x3 welny)"));
        s.sendMessage(c("&f/lobby baner <wzor> <szer> <wys> &7- recznie, patrzysz na LEWY DOLNY blok | &f/lobby baner usun"));
        s.sendMessage(c("&f/lobby postac <gildie|duels|wkrotce|discord|sklep> &7- postac stanie tam, gdzie stoisz, patrzac tam, gdzie patrzysz | &f/lobby postac usun"));
        s.sendMessage(c("&f/lobby napis <gildie|duels|wkrotce|discord|sklep> [skala] &7- duzy napis 2 kratki przed toba | &f/lobby napis usun"));
        s.sendMessage(c("&f/lobby przeladuj"));
    }
}
