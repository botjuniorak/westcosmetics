package pl.westmc.smp;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Szafa: pety (chodza za graczem), kostiumy (glowa/plecy - nie zajmuja zbroi), papugi (na ramieniu).
 * Wszystko to ItemDisplay z modelami z paczki, przestawiane co tick (bez "pasazerow", zeby nie psuc teleportow).
 */
final class Szafa implements Listener {
    private final WestSMP pl;
    private final File plik;
    private YamlConfiguration dane;
    private final Map<UUID, Stan> stany = new HashMap<>();
    private static final Key FONT = Key.key("westmc", "smp");
    private static final int[] SLOTY = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    Szafa(WestSMP pl) {
        this.pl = pl;
        plik = new File(pl.getDataFolder(), "szafa.yml");
        dane = YamlConfiguration.loadConfiguration(plik);
    }

    /** Wybrane rzeczy i encje jednego gracza. */
    static final class Stan {
        String pet, glowa, plecy, papuga;
        boolean lewe;
        ItemDisplay ePet, eGlowa, ePlecy, ePapuga;
        TextDisplay ePetNazwa;
        Location petPos;
        float petYaw;
        long t;
    }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) wczytaj(p);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    void stop() {
        for (Stan s : stany.values()) usunEncje(s);
        zapisz();
    }

    private void zapisz() { try { dane.save(plik); } catch (Exception e) { pl.getLogger().warning("szafa.yml: " + e.getMessage()); } }

    private void wczytaj(Player p) {
        Stan s = new Stan();
        String b = p.getUniqueId() + ".";
        s.pet = dane.getString(b + "pet"); s.glowa = dane.getString(b + "glowa"); s.plecy = dane.getString(b + "plecy");
        s.papuga = dane.getString(b + "papuga"); s.lewe = dane.getBoolean(b + "lewe", false);
        stany.put(p.getUniqueId(), s);
        odswiez(p, s);
    }

    private void zapiszGracza(Player p, Stan s) {
        String b = p.getUniqueId().toString();
        dane.set(b + ".pet", s.pet); dane.set(b + ".glowa", s.glowa); dane.set(b + ".plecy", s.plecy);
        dane.set(b + ".papuga", s.papuga); dane.set(b + ".lewe", s.lewe);
        zapisz();
    }

    // ===================================================================== encje
    private ItemStack model(String key) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(key));
        it.setItemMeta(m);
        return it;
    }

    private ItemDisplay spawn(Player p, String key) {
        return p.getWorld().spawn(p.getLocation(), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(model(key));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTeleportDuration(2);
            d.setViewRange(pl.zasieg("pety", 64));
            d.setShadowRadius(0f);
            d.setRotation(0f, 0f);
        });
    }

    private void usunEncje(Stan s) {
        for (Entity e : new Entity[]{s.ePet, s.eGlowa, s.ePlecy, s.ePapuga, s.ePetNazwa}) if (e != null && e.isValid()) e.remove();
        s.ePet = s.eGlowa = s.ePlecy = s.ePapuga = null; s.ePetNazwa = null;
    }

    /** Postaw encje od nowa wg wyboru (po zmianie w szafie, wejsciu, zmianie swiata). */
    void odswiez(Player p, Stan s) {
        usunEncje(s);
        if (!p.isOnline()) return;
        if (s.pet != null && istnieje("pety", s.pet)) {
            s.ePet = spawn(p, "westmc:pet/" + s.pet);
            s.petPos = p.getLocation().clone();
            s.ePetNazwa = p.getWorld().spawn(p.getLocation(), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                t.setShadowed(true);
                t.setTeleportDuration(2);
                t.setViewRange(pl.zasieg("pety", 64) * 0.7f);
                t.setText(WestSMP.c(pl.getConfig().getString("szafa.pety." + s.pet + ".nazwa", s.pet)) + "\n" + WestSMP.c("&7" + p.getName()));
                t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.AxisAngle4f()));
            });
        }
        if (s.glowa != null && istnieje("kostiumy", s.glowa)) { s.eGlowa = spawn(p, "westmc:kostium/" + s.glowa); p.hideEntity(pl, s.eGlowa); }
        if (s.plecy != null && istnieje("kostiumy", s.plecy)) { s.ePlecy = spawn(p, "westmc:kostium/" + s.plecy); }
        if (s.papuga != null && istnieje("papugi", s.papuga)) s.ePapuga = spawn(p, "westmc:papuga/" + s.papuga);
    }

    private boolean istnieje(String rodzaj, String id) { return pl.getConfig().isConfigurationSection("szafa." + rodzaj + "." + id); }

    // ===================================================================== ruch co tick
    private static float rad(double d) { return (float) Math.toRadians(d); }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stan s = stany.get(p.getUniqueId());
            if (s == null) continue;
            s.t++;
            boolean ukryty = p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
            Location loc = p.getLocation();
            float body = p.getBodyYaw();
            float head = loc.getYaw(), pitch = loc.getPitch();
            double sneak = p.isSneaking() ? -0.3 : 0;
            boolean idzie = p.getVelocity().clone().setY(0).lengthSquared() > 0.003 || p.isSprinting();
            // ---- kostium glowa: wokol szyi, obraca sie z glowa
            if (s.eGlowa != null && s.eGlowa.isValid()) {
                Location at = loc.clone().add(0, 1.5 + sneak * 0.9, 0); at.setYaw(0); at.setPitch(0);
                s.eGlowa.teleport(at);
                Matrix4f m = new Matrix4f().rotateY(rad(-head)).rotateY((float) Math.PI).rotateX(rad(-pitch)).rotateY((float) -Math.PI);
                if ("aureola".equals(s.glowa)) m.translate(0, (float) (0.03 * Math.sin(s.t * 0.12)), 0);
                pokaz(s.eGlowa, m, ukryty);
            }
            // ---- kostium plecy: z cialem
            if (s.ePlecy != null && s.ePlecy.isValid()) {
                Location at = loc.clone().add(0, 1.5 + sneak, 0); at.setYaw(0); at.setPitch(0);
                s.ePlecy.teleport(at);
                Matrix4f m = new Matrix4f().rotateY(rad(-body));
                if (p.isSneaking()) m.rotateX(rad(28));
                if (s.plecy.startsWith("skrzydla")) {
                    float flap = (float) ((idzie ? 9 : 3) * Math.sin(s.t * (idzie ? 0.45 : 0.12)));
                    m.rotateY(rad(flap * 0.4f));
                }
                pokaz(s.ePlecy, m, ukryty);
            }
            // ---- papuga na ramieniu
            if (s.ePapuga != null && s.ePapuga.isValid()) {
                Location at = loc.clone().add(0, 1.5 + sneak, 0); at.setYaw(0); at.setPitch(0);
                s.ePapuga.teleport(at);
                float side = s.lewe ? -1 : 1;   // prawa reka postaci = +x w ukladzie modelu
                float look = (float) (25 * Math.sin(s.t * 0.03) * (s.t % 400 < 120 ? 1 : 0));
                Matrix4f m = new Matrix4f().rotateY(rad(-body)).rotateY((float) Math.PI).translate(side * 0.38f, (float) (idzie ? 0.02 * Math.abs(Math.sin(s.t * 0.4)) : 0), 0.02f)
                    .rotateY(rad(look)).scale(0.55f).rotateY((float) -Math.PI);
                pokaz(s.ePapuga, m, ukryty);
            }
            // ---- pet: idzie za graczem
            if (s.ePet != null && s.ePet.isValid()) {
                if (s.petPos == null || s.petPos.getWorld() != loc.getWorld() || s.petPos.distanceSquared(loc) > 20 * 20) s.petPos = loc.clone();
                boolean lata = pl.getConfig().getBoolean("szafa.pety." + s.pet + ".lata", false);
                double br = Math.toRadians(body);
                Vector boczek = new Vector(Math.cos(br), 0, Math.sin(br)).multiply(-0.9);       // obok, z lewej
                Vector tyl = new Vector(Math.sin(br), 0, -Math.cos(br)).multiply(0.7);          // troche z tylu
                Location cel = loc.clone().add(boczek).add(tyl);
                Vector d = cel.toVector().subtract(s.petPos.toVector()); d.setY(0);
                double dist = d.length();
                boolean rusza = dist > 0.15;
                if (rusza) {
                    double krok = Math.min(dist, Math.max(0.12, dist * 0.25));
                    s.petPos.add(d.normalize().multiply(krok));
                    s.petYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                } else {
                    float doGracza = (float) Math.toDegrees(Math.atan2(-(loc.getX() - s.petPos.getX()), loc.getZ() - s.petPos.getZ()));
                    s.petYaw += wrap(doGracza - s.petYaw) * 0.15f;
                }
                double y = lata ? loc.getY() + 0.9 + 0.15 * Math.sin(s.t * 0.12) : ziemia(s.petPos.getWorld(), s.petPos.getX(), loc.getY(), s.petPos.getZ());
                if (Double.isNaN(y)) y = loc.getY();
                s.petPos.setY(y);
                Location at = s.petPos.clone(); at.setYaw(0); at.setPitch(0);
                s.ePet.teleport(at);
                boolean westus = "westus".equals(s.pet);
                float sk = westus ? 0.38f : 0.75f;
                float hop = rusza && !lata ? (float) (0.08 * Math.abs(Math.sin(s.t * 0.55))) : 0f;
                Matrix4f m = new Matrix4f().translate(0, (westus ? 0.5f * sk : 0f) + hop, 0).rotateY(rad(-s.petYaw)).rotateY((float) Math.PI)
                    .scale(sk).rotateY((float) -Math.PI);
                
                pokaz(s.ePet, m, ukryty);
                if (s.ePetNazwa != null && s.ePetNazwa.isValid()) s.ePetNazwa.teleport(at.clone().add(0, lata ? 1.05 : (westus ? 1.3 : 1.05), 0));
            }
        }
    }

    private void pokaz(ItemDisplay d, Matrix4f m, boolean ukryty) {
        if (ukryty) m = new Matrix4f().scale(0f);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformationMatrix(m);
    }

    private static float wrap(float a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

    private double ziemia(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.5); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.getType().isSolid() && !b.isPassable()) {
                double top = b.getBoundingBox().getMaxY();
                return Double.isFinite(top) && top > by ? top : by + 1;
            }
        }
        return Double.NaN;
    }

    // ===================================================================== zdarzenia
    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(pl, () -> { if (e.getPlayer().isOnline()) wczytaj(e.getPlayer()); }, 20L); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { Stan s = stany.remove(e.getPlayer().getUniqueId()); if (s != null) usunEncje(s); }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { Stan s = stany.get(e.getPlayer().getUniqueId()); if (s != null) Bukkit.getScheduler().runTaskLater(pl, () -> odswiez(e.getPlayer(), s), 5L); }

    @EventHandler public void onTp(PlayerTeleportEvent e) {
        Stan s = stany.get(e.getPlayer().getUniqueId());
        if (s == null || e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 32 * 32) Bukkit.getScheduler().runTaskLater(pl, () -> odswiez(e.getPlayer(), s), 3L);
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Stan s = stany.get(e.getPlayer().getUniqueId());
        if (s != null && s.papuga != null && Math.random() < 0.35)
            Bukkit.getScheduler().runTask(pl, () -> e.getPlayer().getWorld().playSound(e.getPlayer().getLocation(), Sound.ENTITY_PARROT_AMBIENT, 0.6f, 1.2f));
    }

    // ===================================================================== menu
    static final class Menu implements InventoryHolder {
        Inventory inv; String tab; int strona;
        final Map<Integer, String> akcje = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static String spacja(int n) {
        char[] neg = {'', '', '', '', '', '', '', '', ''};
        char[] pos = {'', '', '', '', '', '', '', ''};
        StringBuilder b = new StringBuilder();
        char[] t = n < 0 ? neg : pos; n = Math.abs(n);
        for (int i = t.length - 1; i >= 0; i--) { int v = 1 << i; while (n >= v) { b.append(t[i]); n -= v; } }
        return b.toString();
    }

    private Component tytul() {
        // tlo 176 x 222: 3 kolumny (60/60/56) x 4 wiersze, poczatek tytulu = x 8
        int[] kol = {60, 60, 56};
        StringBuilder b = new StringBuilder(spacja(-8));
        int x = -8;
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 4; r++) {
                b.append((char) (0xE000 + r * 3 + c));
                if (r < 3) b.append(spacja(-(kol[c] + 1)));
            }
            x += kol[c] + 1;
            b.append(spacja(-1));
            x -= 1;
        }
        b.append(spacja(-(x)));
        return Component.text(b.toString()).font(FONT).color(NamedTextColor.WHITE);
    }

    void open(Player p, String tab, int strona) {
        Menu h = new Menu();
        h.tab = tab; h.strona = strona;
        h.inv = Bukkit.createInventory(h, 54, tytul());
        fill(p, h);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.8f, 1.1f);
    }

    private ItemStack ikona(String model, String nazwa, List<String> lore, boolean glint) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(model));
        m.setDisplayName(WestSMP.c(nazwa));
        if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(WestSMP.c(s)); m.setLore(l); }
        if (glint) m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private boolean ma(Player p, String rodzaj, String id) {
        if (pl.getConfig().getBoolean("szafa." + rodzaj + "." + id + ".darmowe", false)) return true;
        if (p.hasPermission("westsmp.szafa.wszystko")) return true;
        String r = switch (rodzaj) { case "pety" -> "pet"; case "kostiumy" -> "kostium"; default -> "papuga"; };
        return p.hasPermission("westsmp.szafa." + r + "." + id);
    }

    private void fill(Player p, Menu h) {
        h.inv.clear(); h.akcje.clear();
        Stan s = stany.computeIfAbsent(p.getUniqueId(), k -> new Stan());
        String[][] tabs = {{"pety", "&#4EE08F&lPETY", "0,1"}, {"kostiumy", "&#C08BFF&lKOSTIUMY", "3,4,5"}, {"papugi", "&#FFB03A&lPAPUGI", "7,8"}};
        for (String[] t : tabs) for (String sl : t[2].split(",")) {
            int slot = Integer.parseInt(sl);
            h.inv.setItem(slot, ikona("westmc:pusty", t[1] + (t[0].equals(h.tab) ? " &8(otwarte)" : ""), List.of("&7Kliknij, aby przejsc"), false));
            h.akcje.put(slot, "tab:" + t[0]);
        }
        ConfigurationSection sec = pl.getConfig().getConfigurationSection("szafa." + h.tab);
        List<String> ids = sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
        String ns = switch (h.tab) { case "pety" -> "pet"; case "kostiumy" -> "kostium"; default -> "papuga"; };
        int od = h.strona * SLOTY.length;
        for (int i = 0; i < SLOTY.length && od + i < ids.size(); i++) {
            String id = ids.get(od + i);
            boolean odbl = ma(p, h.tab, id);
            String miejsce = sec.getString(id + ".miejsce", "");
            boolean zalozone = switch (h.tab) { case "pety" -> id.equals(s.pet); case "papugi" -> id.equals(s.papuga); default -> id.equals(s.glowa) || id.equals(s.plecy); };
            List<String> lore = new ArrayList<>();
            lore.add("&8&m                        ");
            if (!miejsce.isEmpty()) lore.add("&7Miejsce: &f" + (miejsce.equals("glowa") ? "glowa" : "plecy"));
            if (h.tab.equals("pety") && sec.getBoolean(id + ".lata", false)) lore.add("&7Lata nad ziemia");
            lore.add("");
            if (!odbl) { lore.add("&c&l✖ ZABLOKOWANE"); lore.add(pl.getConfig().getString("szafa.sklep-tekst", "")); }
            else if (zalozone) { lore.add("&a&l✔ ZALOZONE"); lore.add("&7Kliknij, aby zdjac"); }
            else lore.add("&#FFC93D» Kliknij, aby zalozyc");
            String nazwa = sec.getString(id + ".nazwa", id);
            h.inv.setItem(SLOTY[i], ikona("westmc:" + ns + "/" + id, (odbl ? "" : "&8") + nazwa, lore, zalozone));
            h.akcje.put(SLOTY[i], "wybierz:" + id);
        }
        h.inv.setItem(46, ikona("minecraft:barrier", "&c&lZdejmij wszystko", List.of("&7Zdejmuje peta, kostiumy i papuge"), false));
        h.akcje.put(46, "zdejmij");
        if (h.strona > 0) { h.inv.setItem(48, ikona("minecraft:arrow", "&f« Poprzednia strona", null, false)); h.akcje.put(48, "prev"); }
        h.inv.setItem(49, ikona("westmc:przycisk_zamknij", "&c&lZamknij", null, false)); h.akcje.put(49, "zamknij");
        if (od + SLOTY.length < ids.size()) { h.inv.setItem(50, ikona("minecraft:arrow", "&fNastepna strona »", null, false)); h.akcje.put(50, "next"); }
        if (h.tab.equals("papugi")) { h.inv.setItem(52, ikona("minecraft:feather", "&#FFB03A&lRamie: &f" + (s.lewe ? "lewe" : "prawe"), List.of("&7Kliknij, aby zmienic"), false)); h.akcje.put(52, "ramie"); }
        else h.inv.setItem(52, ikona("westmc:logo", "&#399EFC&lWESTMC.EU", List.of("&7Szafa: pety, kostiumy i papugi", "&7Nowosci w &#FFC93D/sklep"), false));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String a = h.akcje.get(e.getRawSlot());
        if (a == null) return;
        Stan s = stany.computeIfAbsent(p.getUniqueId(), k -> new Stan());
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        if (a.startsWith("tab:")) { h.tab = a.substring(4); h.strona = 0; fill(p, h); return; }
        switch (a) {
            case "zamknij" -> { p.closeInventory(); return; }
            case "prev" -> { h.strona = Math.max(0, h.strona - 1); fill(p, h); return; }
            case "next" -> { h.strona++; fill(p, h); return; }
            case "ramie" -> { s.lewe = !s.lewe; zapiszGracza(p, s); fill(p, h); return; }
            case "zdejmij" -> { s.pet = s.glowa = s.plecy = s.papuga = null; zapiszGracza(p, s); odswiez(p, s); fill(p, h); return; }
            default -> { }
        }
        String id = a.substring("wybierz:".length());
        if (!ma(p, h.tab, id)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            p.sendMessage(WestSMP.c("&cTo jest zablokowane. " + pl.getConfig().getString("szafa.sklep-tekst", "")));
            return;
        }
        switch (h.tab) {
            case "pety" -> s.pet = id.equals(s.pet) ? null : id;
            case "papugi" -> s.papuga = id.equals(s.papuga) ? null : id;
            default -> {
                if (pl.getConfig().getString("szafa.kostiumy." + id + ".miejsce", "glowa").equals("plecy")) s.plecy = id.equals(s.plecy) ? null : id;
                else s.glowa = id.equals(s.glowa) ? null : id;
            }
        }
        zapiszGracza(p, s);
        odswiez(p, s);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        fill(p, h);
    }
}
