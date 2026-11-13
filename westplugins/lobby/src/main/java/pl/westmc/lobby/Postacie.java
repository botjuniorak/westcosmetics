package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Postacie trybow ze skinow (paczka WestMc): glowa, tulow, rece i nogi to osobne modele,
 * wiec postac sie rusza: glowa patrzy na najblizszego gracza, oddycha, a co kilka sekund robi swoj ruch
 * (rycerz unosi miecz, wojownik tnie, mag czaruje). Klik = przejscie na serwer.
 *
 * Uklad "modelu": przod = -z, prawa reka postaci = +x, jednostka = 1 piksel skina (32 px wysokosci).
 */
final class Postacie {
    private final WestLobby pl;
    private final Map<String, Postac> spawned = new HashMap<>();
    private final Map<UUID, Long> cd = new HashMap<>();
    private long tick;

    Postacie(WestLobby pl) { this.pl = pl; }

    private static final String[] CZESCI = {"nogi", "tulow", "glowa", "reka_p", "reka_l"};
    private static final float[][] PIVOT = {{0, 12, 0}, {0, 24, 0}, {0, 24, 0}, {5, 22, 0}, {-5, 22, 0}};

    static final class Postac {
        String id, typ, skin, anim;
        Location base;
        float s;                 // wielkosc (1 = jak gracz)
        final ItemDisplay[] czesci = new ItemDisplay[5];
        ItemDisplay miecz;
        TextDisplay nazwa;
        Interaction klik;
        final List<Entity> all = new ArrayList<>();
        float headYaw, headPitch;   // aktualne (wygladzone)
        int offset;
    }

    // ============================================================ spawn / despawn
    void respawnAll() {
        despawnAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-maskotki");
        if (s == null) return;
        for (String id : s.getKeys(false)) spawn(id);
    }

    void despawnAll() {
        for (Postac p : spawned.values()) for (Entity e : p.all) if (e != null && e.isValid()) e.remove();
        spawned.clear();
    }

    /** Encje nie sa zapisywane: jesli chunk sie zaladowal na nowo, a postaci nie ma - stawiamy ponownie. */
    void watchdog() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-maskotki");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            Postac p = spawned.get(id);
            if (p != null && p.czesci[0] != null && p.czesci[0].isValid()) continue;
            Location loc = loc(id);
            if (loc == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            spawn(id);
        }
    }

    private Location loc(String id) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("postawione-maskotki." + id);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", "world"));
        if (w == null) return null;
        Location l = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"));
        l.setYaw((float) c.getDouble("yaw"));
        l.setPitch(0f);
        return l;
    }

    private String typ(String id) { return pl.getConfig().getString("postawione-maskotki." + id + ".typ", "gildie"); }

    private ConfigurationSection cfg(String typ) { return pl.getConfig().getConfigurationSection("maskotki." + typ); }

    private String nameText(String typ) {
        ConfigurationSection t = cfg(typ);
        String n = t == null ? typ : t.getString("nazwa", typ);
        String srv = t == null ? null : t.getString("serwer");
        if (srv != null && !srv.isEmpty()) { int on = pl.online(srv); n = n.replace("{ONLINE}", on < 0 ? "?" : String.valueOf(on)); }
        return WestLobby.c(n);
    }

    private ItemStack model(String key) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(key));
        it.setItemMeta(m);
        return it;
    }

    private void spawn(String id) {
        Postac old = spawned.remove(id);
        if (old != null) for (Entity e : old.all) if (e.isValid()) e.remove();
        Location base = loc(id);
        if (base == null) return;
        String typ = typ(id);
        ConfigurationSection t = cfg(typ);
        if (t == null || t.getString("skin", "").isEmpty()) return;   // typ bez skina (np. stary discord/sklep) - pomijamy
        Postac p = new Postac();
        p.id = id; p.typ = typ; p.base = base; p.skin = t.getString("skin");
        p.anim = t.getString("animacja", "brak");
        p.s = (float) t.getDouble("wielkosc", 1.5);
        p.offset = Math.floorMod(id.hashCode(), 120);
        String tag = "maskotka:" + id;
        World w = base.getWorld();
        for (int i = 0; i < 5; i++) {
            ItemStack it = model("westmc:postac/" + p.skin + "_" + CZESCI[i]);
            p.czesci[i] = w.spawn(base, ItemDisplay.class, d -> setup(d, it, tag));
            p.all.add(p.czesci[i]);
        }
        Material m = Material.matchMaterial(t.getString("przedmiot", ""));
        if (m != null && m.isItem() && m != Material.AIR) {
            ItemStack it = new ItemStack(m);
            p.miecz = w.spawn(base, ItemDisplay.class, d -> setup(d, it, tag));
            p.all.add(p.miecz);
        }
        float h = p.s * 2f;
        p.nazwa = w.spawn(base.clone().add(0, h + 0.35, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setText(nameText(typ));
            d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
        });
        p.all.add(p.nazwa);
        p.klik = w.spawn(base, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth(Math.max(0.8f, p.s * 0.75f));
            i.setInteractionHeight(h + 0.1f);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
        });
        p.all.add(p.klik);
        spawned.put(id, p);
        pose(p, true);
    }

    private void setup(ItemDisplay d, ItemStack it, String tag) {
        d.setPersistent(false);
        d.setItemStack(it);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setViewRange(1.5f);
        d.setShadowRadius(0f);
        d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
    }

    // ============================================================ animacja
    void bob() { animate(); }   // zgodnosc ze starym planem (wolane z WestLobby)

    void animate() {
        tick += 2;
        for (Postac p : spawned.values()) {
            if (p.czesci[0] == null || !p.czesci[0].isValid()) continue;
            pose(p, false);
        }
    }

    private static float smooth(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    private static float rad(double deg) { return (float) Math.toRadians(deg); }

    /** Odwrocenie modelu (ItemDisplay obraca przedmiot o 180 stopni) - mozna wylaczyc w configu, jakby postac stala tylem. */
    private boolean flip() { return pl.getConfig().getBoolean("postacie-odwroc", true); }

    private void pose(Postac p, boolean first) {
        float k = p.s / 16f;               // 1 piksel skina w blokach
        long t = tick + p.offset;
        // ---------------- glowa: patrzy na najblizszego gracza
        float tyaw, tpitch;
        Player near = null; double best = 12 * 12;
        Location head = p.base.clone().add(0, 24 * k + 4 * k, 0);
        for (Player o : p.base.getWorld().getPlayers()) {
            double d = o.getEyeLocation().distanceSquared(head);
            if (d < best) { best = d; near = o; }
        }
        if (near != null) {
            Vector d = near.getEyeLocation().toVector().subtract(head.toVector());
            float[] m = toModel(p, d);
            tyaw = (float) Math.toDegrees(Math.atan2(-m[0], -m[2]));
            double hor = Math.sqrt(m[0] * m[0] + m[2] * m[2]);
            tpitch = (float) Math.toDegrees(Math.atan2(-m[1], hor));
            tyaw = Math.max(-65, Math.min(65, tyaw));
            tpitch = Math.max(-30, Math.min(35, tpitch));
        } else {
            tyaw = (float) (25 * Math.sin(t * 0.025));
            tpitch = 5;
        }
        if (first) { p.headYaw = tyaw; p.headPitch = tpitch; }
        p.headYaw += (tyaw - p.headYaw) * 0.35f;
        p.headPitch += (tpitch - p.headPitch) * 0.35f;

        // ---------------- rece: oddychanie + ruch typu postaci
        double breath = Math.sin(t * 0.07);
        float rX = (float) (4 * breath), lX = (float) (-4 * breath), rZ = 4, lZ = -4, wrist = 75, lWrist = 75;
        float bodyBob = (float) (0.25 * Math.sin(t * 0.07));
        int phase = (int) (t % 140);
        Location hand = null; boolean handFx = false;
        switch (p.anim) {
            case "miecz" -> {          // rycerz: unosi miecz do gory
                float up = phase < 16 ? smooth(phase / 16f) : phase < 46 ? 1 : smooth(1 - (phase - 46) / 16f);
                if (phase >= 62) up = 0;
                rX = lerp(rX, 165, up); rZ = lerp(rZ, 8, up); wrist = lerp(75, 15, up);
                handFx = up > 0.95f && phase % 4 == 0;
            }
            case "atak" -> {           // wojownik: dwa ciecia
                int q = phase % 20; boolean on = phase < 40;
                if (on) {
                    if (q < 8) { float a = smooth(q / 8f); rX = lerp(rX, 150, a); wrist = lerp(75, 30, a); rZ = lerp(4, 20, a); }
                    else if (q < 12) { float a = smooth((q - 8) / 4f); rX = lerp(150, 30, a); wrist = lerp(30, 80, a); rZ = lerp(20, -10, a); }
                    else { float a = smooth((q - 12) / 8f); rX = lerp(30, rX, a); wrist = lerp(80, 75, a); rZ = lerp(-10, 4, a); }
                    handFx = q == 10;
                }
            }
            case "magia" -> {          // mag: obie rece do przodu, iskry
                float up = phase < 14 ? smooth(phase / 14f) : phase < 60 ? 1 : smooth(1 - (phase - 60) / 14f);
                if (phase >= 74) up = 0;
                float pulse = (float) (8 * Math.sin(t * 0.3));
                rX = lerp(rX, 100 + pulse, up); lX = lerp(lX, 100 - pulse, up);
                rZ = lerp(rZ, -12, up); lZ = lerp(lZ, 12, up);
                handFx = up > 0.5f;
            }
            default -> { }
        }

        Matrix4f[] mats = new Matrix4f[5];
        for (int i = 0; i < 5; i++) {
            float[] pv = PIVOT[i];
            Matrix4f m = new Matrix4f().translate(pv[0] * k, pv[1] * k + (i == 0 ? 0 : bodyBob * k), pv[2] * k);
            switch (i) {
                case 2 -> m.rotateY(rad(p.headYaw)).rotateX(rad(-p.headPitch));
                case 3 -> m.rotateX(rad(rX)).rotateZ(rad(rZ));
                case 4 -> m.rotateX(rad(lX)).rotateZ(rad(lZ));
                default -> { }
            }
            m.scale(p.s);
            mats[i] = m;
        }
        for (int i = 0; i < 5; i++) apply(p.czesci[i], mats[i], first);
        // ---------------- miecz w prawej rece
        Matrix4f arm = new Matrix4f().translate(5 * k, 22 * k + bodyBob * k, 0).rotateX(rad(rX)).rotateZ(rad(rZ));
        if (p.miecz != null && p.miecz.isValid()) {
            Matrix4f sw = new Matrix4f(arm).translate(1 * k, -9 * k, 0).rotateX(rad(wrist)).rotateX(rad(-135)).rotateY(rad(90))
                .scale(p.s * 0.95f).translate(0.28f, 0.34f, 0);
            apply(p.miecz, sw, first);
        }
        // ---------------- czasteczki
        if (handFx && !first) {
            Vector3f tip = new Vector3f();
            if (p.anim.equals("magia")) {
                for (float sx : new float[]{5, -5}) {
                    Matrix4f a = new Matrix4f().translate(sx * k, 22 * k, 0).rotateX(rad(sx > 0 ? rX : lX)).rotateZ(rad(sx > 0 ? rZ : lZ));
                    a.transformPosition(sx > 0 ? 1 * k : -1 * k, -11 * k, 0, tip);
                    Location l = toWorld(p, tip);
                    l.getWorld().spawnParticle(Particle.WITCH, l, 2, 0.08, 0.08, 0.08, 0);
                    l.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0.1, 0.1, 0.1, 0.01);
                }
            } else {
                Matrix4f a = new Matrix4f(arm).translate(1 * k, -9 * k, 0).rotateX(rad(wrist));
                a.transformPosition(0, -14 * k * p.s / p.s, 0, tip);
                Location l = toWorld(p, tip);
                if (p.anim.equals("atak")) l.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
                else {
                    l.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0.05, 0.05, 0.05, 0.02);
                    l.getWorld().spawnParticle(Particle.DUST, l, 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(80, 170, 255), 1.0f));
                }
            }
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Uklad modelu -> uklad encji (z odwroceniem) i zastosowanie macierzy z plynnym przejsciem. */
    private void apply(ItemDisplay d, Matrix4f model, boolean first) {
        if (d == null || !d.isValid()) return;
        Matrix4f m = model;
        if (flip()) m = new Matrix4f().rotateY((float) Math.PI).mul(model).rotateY((float) -Math.PI);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(first ? 0 : 3);
        d.setTransformationMatrix(m);
    }

    /** Kierunek w swiecie -> uklad modelu (przod = -z). */
    private float[] toModel(Postac p, Vector d) {
        double y = Math.toRadians(p.base.getYaw());
        double lx = d.getX() * Math.cos(y) + d.getZ() * Math.sin(y);
        double lz = -d.getX() * Math.sin(y) + d.getZ() * Math.cos(y);
        if (flip()) { lx = -lx; lz = -lz; }
        return new float[]{(float) lx, (float) d.getY(), (float) lz};
    }

    private Location toWorld(Postac p, Vector3f m) {
        double lx = m.x, lz = m.z;
        if (flip()) { lx = -lx; lz = -lz; }
        double y = Math.toRadians(p.base.getYaw());
        double wx = lx * Math.cos(y) - lz * Math.sin(y);
        double wz = lx * Math.sin(y) + lz * Math.cos(y);
        return p.base.clone().add(wx, m.y, wz);
    }

    void refreshNames() {
        for (Postac p : spawned.values()) if (p.nazwa != null && p.nazwa.isValid()) p.nazwa.setText(nameText(p.typ));
    }

    // ============================================================ klik
    String idOf(Entity e) {
        String v = e.getPersistentDataContainer().get(pl.keyObj, PersistentDataType.STRING);
        return v != null && v.startsWith("maskotka:") ? v.substring(9) : null;
    }

    void click(Player p, String id) {
        Long last = cd.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 800) return;
        cd.put(p.getUniqueId(), System.currentTimeMillis());
        ConfigurationSection t = cfg(typ(id));
        if (t == null) return;
        p.playSound(p.getLocation(), t.getString("dzwiek", "minecraft:ui.button.click"), 0.8f, 1.2f);
        String srv = t.getString("serwer", "");
        if (!srv.isEmpty() && !t.getBoolean("wkrotce", false)) { pl.connect(p, srv); return; }
        for (String line : t.getStringList("wiadomosc")) p.sendRichMessage(line);
    }

    // ============================================================ komendy
    String place(Player p, String typ) {
        ConfigurationSection t = cfg(typ);
        if (t == null || t.getString("skin", "").isEmpty()) return "&cTypy postaci: &fgildie&c, &fduels&c, &fwkrotce";
        Location l = p.getLocation();
        String id = typ + (System.currentTimeMillis() % 100000);
        String b = "postawione-maskotki." + id + ".";
        pl.getConfig().set(b + "typ", typ);
        pl.getConfig().set(b + "swiat", l.getWorld().getName());
        pl.getConfig().set(b + "x", l.getBlockX() + 0.5);
        pl.getConfig().set(b + "y", (double) l.getBlockY());
        pl.getConfig().set(b + "z", l.getBlockZ() + 0.5);
        pl.getConfig().set(b + "yaw", (double) Math.round(l.getYaw() / 45f) * 45.0);
        pl.saveConfig();
        spawn(id);
        return "&aPostac &f" + typ + " &apostawiona - patrzy tam, gdzie ty patrzyles. Odsun sie, zeby ja zobaczyc!";
    }

    String remove(Player p) {
        String best = null; double bd = 49;
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-maskotki");
        if (s != null) for (String id : s.getKeys(false)) {
            Location l = loc(id);
            if (l == null || l.getWorld() != p.getWorld()) continue;
            double d = l.distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = id; }
        }
        if (best == null) return "&cNie ma postaci w poblizu (7 kratek).";
        Postac old = spawned.remove(best);
        if (old != null) for (Entity e : old.all) if (e.isValid()) e.remove();
        pl.getConfig().set("postawione-maskotki." + best, null);
        pl.saveConfig();
        return "&aUsunieto postac.";
    }
}
