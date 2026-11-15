package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NPC na spawnie: postacie ze skinow (modele z paczki), patrza na gracza, klik = akcja. */
final class Npc implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final NamespacedKey KEY;
    private final Map<String, N> npc = new HashMap<>();
    private final Map<UUID, Long> cd = new HashMap<>();
    private long tick;
    private static final String[] CZ = {"nogi", "tulow", "glowa", "reka_p", "reka_l"};
    private static final float[][] PIV = {{0, 12, 0}, {0, 24, 0}, {0, 24, 0}, {5, 22, 0}, {-5, 22, 0}};

    static final class N { String id, typ; Location base; float s; ItemDisplay[] cz = new ItemDisplay[5]; TextDisplay nazwa; Interaction klik; List<Entity> all = new ArrayList<>(); float hy, hp; int off; }

    Npc(WestSMP pl) { this.pl = pl; KEY = new NamespacedKey(pl, "npc"); }

    void start() {
        Bukkit.getScheduler().runTaskLater(pl, this::respawnAll, 40L);
        Bukkit.getScheduler().runTaskTimer(pl, this::anim, 60L, 2L);
        Bukkit.getScheduler().runTaskTimer(pl, this::watchdog, 200L, 100L);
    }

    void stop() { for (N n : npc.values()) for (Entity e : n.all) if (e.isValid()) e.remove(); npc.clear(); }

    void respawnAll() {
        stop();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("npc-postawione");
        if (s != null) for (String id : s.getKeys(false)) spawn(id);
    }

    private void watchdog() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("npc-postawione");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            N n = npc.get(id);
            if (n != null && n.cz[0] != null && n.cz[0].isValid()) continue;
            Location l = loc(id);
            if (l != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) spawn(id);
        }
    }

    private Location loc(String id) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("npc-postawione." + id);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", "world"));
        if (w == null) return null;
        Location l = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"));
        l.setYaw((float) c.getDouble("yaw"));
        return l;
    }

    /** Wartosc z configu, a gdy jej brak - z domyslnego configu pluginu. */
    private String wartosc(String typ, String k, String dom) {
        String v = pl.getConfig().getString("npc." + typ + "." + k);
        if (v == null && pl.getConfig().getDefaults() != null) v = pl.getConfig().getDefaults().getString("npc." + typ + "." + k);
        return v == null ? dom : v;
    }

    private void spawn(String id) {
        N old = npc.remove(id);
        if (old != null) for (Entity e : old.all) if (e.isValid()) e.remove();
        Location base = loc(id);
        if (base == null) return;
        String typ = pl.getConfig().getString("npc-postawione." + id + ".typ");
        ConfigurationSection t = pl.getConfig().getConfigurationSection("npc." + typ);
        if (t == null) return;
        N n = new N(); n.id = id; n.typ = typ; n.base = base; n.s = (float) t.getDouble("wielkosc", 1.0); n.off = Math.floorMod(id.hashCode(), 200);
        String tag = "npc:" + id;
        for (int i = 0; i < 5; i++) {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta(); m.setItemModel(NamespacedKey.fromString("westmc:postac/" + t.getString("skin") + "_" + CZ[i])); it.setItemMeta(m);
            n.cz[i] = base.getWorld().spawn(base, ItemDisplay.class, d -> {
                d.setPersistent(false); d.setItemStack(it); d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setViewRange(pl.zasieg("npc", 128)); d.setShadowRadius(0f);
                d.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, tag);
            });
            n.all.add(n.cz[i]);
        }
        float h = 2f * n.s;
        n.nazwa = base.getWorld().spawn(base.clone().add(0, h + 0.3, 0), TextDisplay.class, d -> { d.setViewRange(pl.zasieg("npc", 128));
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setText(WestSMP.c(wartosc(typ, "nazwa", typ)) + "\n" + WestSMP.c(wartosc(typ, "podpis", "&7» Kliknij «")));
        });
        n.all.add(n.nazwa);
        n.klik = base.getWorld().spawn(base, Interaction.class, i -> {
            i.setPersistent(false); i.setInteractionWidth(0.8f * n.s); i.setInteractionHeight(h + 0.1f); i.setResponsive(true);
            i.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, tag);
        });
        n.all.add(n.klik);
        npc.put(id, n);
        pose(n, true);
    }

    private static float rad(double d) { return (float) Math.toRadians(d); }

    private void anim() { tick += 2; for (N n : npc.values()) if (n.cz[0] != null && n.cz[0].isValid()) pose(n, false); }

    private void pose(N n, boolean first) {
        float k = n.s / 16f; long t = tick + n.off;
        Location head = n.base.clone().add(0, 28 * k, 0);
        Player near = null; double best = 10 * 10;
        for (Player o : n.base.getWorld().getPlayers()) { double d = o.getEyeLocation().distanceSquared(head); if (d < best) { best = d; near = o; } }
        float ty = (float) (20 * Math.sin(t * 0.02)), tp = 5;
        if (near != null) {
            Vector d = near.getEyeLocation().toVector().subtract(head.toVector());
            double y = Math.toRadians(n.base.getYaw());
            double lx = -(d.getX() * Math.cos(y) + d.getZ() * Math.sin(y)), lz = -(-d.getX() * Math.sin(y) + d.getZ() * Math.cos(y));
            ty = (float) Math.toDegrees(Math.atan2(-lx, -lz)); tp = (float) Math.toDegrees(Math.atan2(-d.getY(), Math.hypot(lx, lz)));
            ty = Math.max(-65, Math.min(65, ty)); tp = Math.max(-30, Math.min(35, tp));
        }
        if (first) { n.hy = ty; n.hp = tp; }
        n.hy += (ty - n.hy) * 0.35f; n.hp += (tp - n.hp) * 0.35f;
        double br = Math.sin(t * 0.07);
        int ph = (int) (t % 220);
        float wave = ph < 50 && near != null ? (float) Math.sin(ph / 50.0 * Math.PI) : 0f;   // macha, gdy ktos jest blisko
        float rX = (float) (4 * br), lX = (float) (-4 * br), rZ = 4 + wave * (140 + (float) (15 * Math.sin(t * 0.6))), lZ = -4;
        float bob = (float) (0.25 * Math.sin(t * 0.07));
        for (int i = 0; i < 5; i++) {
            Matrix4f m = new Matrix4f().translate(PIV[i][0] * k, PIV[i][1] * k + (i == 0 ? 0 : bob * k), 0);
            if (i == 2) m.rotateY(rad(n.hy)).rotateX(rad(-n.hp));
            if (i == 3) m.rotateX(rad(rX * (1 - wave))).rotateZ(rad(rZ));
            if (i == 4) m.rotateX(rad(lX)).rotateZ(rad(lZ));
            m.scale(n.s);
            Matrix4f f = new Matrix4f().rotateY((float) Math.PI).mul(m).rotateY((float) -Math.PI);
            ItemDisplay d = n.cz[i];
            d.setInterpolationDelay(0); d.setInterpolationDuration(first ? 0 : 3); d.setTransformationMatrix(f);
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        String v = e.getRightClicked().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (v == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Long last = cd.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 700) return;
        cd.put(p.getUniqueId(), System.currentTimeMillis());
        String typ = pl.getConfig().getString("npc-postawione." + v.substring(4) + ".typ");
        ConfigurationSection t = pl.getConfig().getConfigurationSection("npc." + typ);
        if (t == null) return;
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.7f, 1.1f);
        String a = wartosc(typ, "akcja", "");
        if (a.startsWith("menu:teleporty")) pl.teleporty.otworz(p);
        else if (a.startsWith("warp:")) pl.teleporty.teleport(p, a.substring(5));
        else if (a.startsWith("komenda:")) p.performCommand(a.substring(8));
        else if (a.startsWith("szafa")) pl.szafa.open(p, "pety", 0);
        else if (a.startsWith("menu:eventowki")) pl.eventowki.katalog(p);
        for (String line : t.getStringList("wiadomosc")) p.sendMessage(WestSMP.c(line));
    }

    @EventHandler public void onDmg(EntityDamageEvent e) { if (e.getEntity().getPersistentDataContainer().has(KEY)) e.setCancelled(true); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p) || !p.hasPermission("westsmp.admin")) { s.sendMessage(WestSMP.c("&cBrak uprawnien.")); return true; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("przeladuj")) {
            pl.reloadConfig();
            for (String id : new java.util.ArrayList<>(npc.keySet())) spawn(id);
            p.sendMessage(WestSMP.c("&aPrzeladowano NPC z configu (" + npc.size() + ").")); return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("usun")) {
            String best = null; double bd = 49;
            for (N n : npc.values()) if (n.base.getWorld() == p.getWorld()) { double d = n.base.distanceSquared(p.getLocation()); if (d < bd) { bd = d; best = n.id; } }
            if (best == null) { p.sendMessage(WestSMP.c("&cNie ma NPC w poblizu.")); return true; }
            N n = npc.remove(best); for (Entity e : n.all) if (e.isValid()) e.remove();
            pl.getConfig().set("npc-postawione." + best, null); pl.saveConfig();
            p.sendMessage(WestSMP.c("&aUsunieto NPC.")); return true;
        }
        if (a.length < 1 || !pl.getConfig().isConfigurationSection("npc." + a[0].toLowerCase())) {
            ConfigurationSection t = pl.getConfig().getConfigurationSection("npc");
            p.sendMessage(WestSMP.c("&7/npc <typ> &8| &7/npc usun &8- typy: &f" + (t == null ? "" : String.join(", ", t.getKeys(false)))));
            return true;
        }
        Location l = p.getLocation(); String id = a[0].toLowerCase() + (System.currentTimeMillis() % 100000); String b = "npc-postawione." + id + ".";
        pl.getConfig().set(b + "typ", a[0].toLowerCase()); pl.getConfig().set(b + "swiat", l.getWorld().getName());
        pl.getConfig().set(b + "x", l.getBlockX() + 0.5); pl.getConfig().set(b + "y", (double) l.getBlockY()); pl.getConfig().set(b + "z", l.getBlockZ() + 0.5);
        pl.getConfig().set(b + "yaw", (double) Math.round(l.getYaw() / 45f) * 45.0); pl.saveConfig();
        spawn(id);
        p.sendMessage(WestSMP.c("&aPostawiono NPC &f" + a[0] + "&a - patrzy tam, gdzie ty patrzyles."));
        return true;
    }
}
