package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Postacie z blokow wyswietlanych (bez resource packa):
 *  - tryby: gildie (rycerz), duels (wojownik z dwoma mieczami), wkrotce (tajemnicza postac) - klik = przejscie na serwer
 *  - discord (Westus) i sklep (Zlotek) - klik = klikalny link w czacie
 */
final class Maskotki {
    private final WestLobby pl;
    private final Map<String, List<Entity>> spawned = new HashMap<>();
    private final Map<String, TextDisplay> names = new HashMap<>();
    private boolean up;

    Maskotki(WestLobby pl) { this.pl = pl; }

    static final class Model {
        final List<float[]> boxes = new ArrayList<>();
        final List<String> mats = new ArrayList<>();
        float cx, cz, height;
        Model(float cx, float cz) { this.cx = cx; this.cz = cz; }
        Model b(float x0, float y0, float z0, float x1, float y1, float z1, String mat) {
            boxes.add(new float[]{x0, y0, z0, x1, y1, z1});
            mats.add(mat);
            height = Math.max(height, y1);
            return this;
        }
        /** Plaska naklejka na przodzie (twarz, herb) - minimalnie przed powierzchnia. */
        Model d(float x0, float y0, float x1, float y1, float z, String mat) {
            return b(x0, y0, z, x1, y1, z + 0.12f, mat);
        }
    }

    static final Map<String, Model> MODELS = new HashMap<>();
    static {
        // ----- roboty: Westus (discord) i Zlotek (sklep)
        for (String typ : new String[]{"discord", "sklep"}) {
            boolean s = typ.equals("sklep");
            String head = s ? "lime_concrete" : "light_blue_concrete", body = s ? "green_concrete" : "blue_concrete";
            Model m = new Model(7f, 4.5f);
            m.b(3, 0, 2, 6, 5, 6, body).b(8, 0, 2, 11, 5, 6, body)
             .b(3, 0, 1, 6, 2, 2, "black_concrete").b(8, 0, 1, 11, 2, 2, "black_concrete")
             .b(2, 5, 1, 12, 13, 7, body)
             .b(5, 8, 7, 9, 12, 8, "yellow_concrete").d(6, 9, 8, 11, 8, "white_concrete")
             .b(0, 7, 2, 2, 13, 6, head).b(12, 7, 2, 14, 13, 6, head)
             .b(0, 5, 2, 2, 7, 6, "white_concrete").b(12, 5, 2, 14, 7, 6, "white_concrete")
             .b(1, 13, 0, 13, 23, 9, head)
             .b(2, 15, 9, 12, 21, 9.6f, "black_concrete")
             .d(3, 17, 5, 20, 9.6f, s ? "white_concrete" : "light_blue_concrete").d(9, 17, 11, 20, 9.6f, s ? "white_concrete" : "light_blue_concrete")
             .d(5, 16, 9, 17, 9.6f, s ? "white_concrete" : "light_blue_concrete")
             .b(-1, 15, 2, 1, 21, 7, "yellow_concrete").b(13, 15, 2, 15, 21, 7, "yellow_concrete")
             .b(0, 23, 3, 14, 24, 6, "yellow_concrete")
             .b(6.5f, 24, 4, 7.5f, 26, 5, "light_gray_concrete");
            if (s) m.b(4.5f, 26, 4, 9.5f, 31, 5, "gold_block").d(6, 27, 8, 30, 5, "yellow_concrete");   // moneta
            else m.b(5, 26, 3, 9, 29, 6, "light_blue_concrete");
            MODELS.put(typ, m);
        }
        // ----- czlowiek: wspolny szkielet (jak skin MC: nogi 4x12x4, tulow 8x12x4, rece 4x12x4, glowa 8x8x8)
        // przod = +z; tulow z 0..4, glowa z -2..6
        Model k = new Model(4f, 2f);   // RYCERZ - wojny gildii
        k.b(0, 0, 0, 4, 12, 4, "light_gray_concrete").b(4, 0, 0, 8, 12, 4, "light_gray_concrete")
         .b(-0.2f, 0, -0.2f, 4, 2.5f, 4.4f, "gray_concrete").b(4, 0, -0.2f, 8.2f, 2.5f, 4.4f, "gray_concrete")
         .b(0, 12, 0, 8, 24, 4, "blue_concrete")
         .b(-0.1f, 12, -0.1f, 8.1f, 13.2f, 4.1f, "yellow_concrete")
         .d(3, 15, 5, 22, 4.1f, "yellow_concrete").d(1.5f, 18.5f, 6.5f, 20, 4.1f, "yellow_concrete")
         .b(-4, 12, 0, 0, 22, 4, "light_gray_concrete").b(8, 12, 0, 12, 22, 4, "light_gray_concrete")
         .b(-5, 20.5f, -0.6f, 0.2f, 25, 4.6f, "light_blue_concrete").b(7.8f, 20.5f, -0.6f, 13, 25, 4.6f, "light_blue_concrete")
         .b(-4.1f, 12, -0.1f, 0.1f, 14, 4.1f, "gray_concrete").b(7.9f, 12, -0.1f, 12.1f, 14, 4.1f, "gray_concrete")
         .b(0, 24, -2, 8, 32, 6, "light_gray_concrete")
         .d(1, 27.5f, 7, 28.5f, 6, "black_concrete").d(3.5f, 24.8f, 4.5f, 27.5f, 6, "black_concrete")
         .b(-0.1f, 31, -2.1f, 8.1f, 32.1f, 6.1f, "yellow_concrete")
         .b(3, 32, -1, 5, 36, 3, "red_concrete").b(3, 33, -4, 5, 36, -1, "red_concrete")
         // miecz diamentowy w prawej rece, ostrzem do gory
         .b(9.4f, 9, 4, 10.6f, 10, 6, "yellow_concrete")
         .b(9.4f, 10, 4.4f, 10.6f, 14, 5.6f, "brown_concrete")
         .b(7, 14, 4, 13, 15.5f, 6, "yellow_concrete")
         .b(9, 15.5f, 4.5f, 11, 38, 5.5f, "light_blue_concrete")
         .b(9.6f, 15.5f, 4.4f, 10.4f, 38, 5.6f, "cyan_concrete")
         .b(9.4f, 38, 4.5f, 10.6f, 39.5f, 5.5f, "light_blue_concrete")
         // tarcza na lewej rece
         .b(-5.2f, 12, 4, -0.5f, 22, 4.8f, "blue_concrete").d(-4, 16, -1.7f, 21, 4.8f, "yellow_concrete").d(-5.2f, 18, -0.5f, 19.5f, 4.8f, "yellow_concrete");
        MODELS.put("gildie", k);

        Model w = new Model(4f, 2f);   // WOJOWNIK - duels
        String skin = "white_terracotta";
        w.b(0, 0, 0, 4, 12, 4, "black_concrete").b(4, 0, 0, 8, 12, 4, "black_concrete")
         .b(-0.2f, 0, -0.2f, 4, 2, 4.4f, "gray_concrete").b(4, 0, -0.2f, 8.2f, 2, 4.4f, "gray_concrete")
         .b(0, 12, 0, 8, 24, 4, "red_concrete")
         .b(-0.1f, 12, -0.1f, 8.1f, 13, 4.1f, "black_concrete")
         .d(2, 16, 6, 21, 4.1f, "white_concrete").d(3, 17, 5, 20, 4.1f, "red_concrete")
         .b(-4, 12, 0, 0, 20, 4, skin).b(8, 12, 0, 12, 20, 4, skin)
         .b(-4.1f, 20, -0.1f, 0, 24, 4.1f, "red_concrete").b(8, 20, -0.1f, 12.1f, 24, 4.1f, "red_concrete")
         .b(-4.1f, 12, -0.1f, 0.1f, 14, 4.1f, "black_concrete").b(7.9f, 12, -0.1f, 12.1f, 14, 4.1f, "black_concrete")
         .b(0, 24, -2, 8, 32, 6, skin)
         .b(-0.1f, 30, -2.1f, 8.1f, 32.1f, 6.1f, "brown_concrete").b(-0.1f, 26, -2.1f, 8.1f, 30, -1, "brown_concrete")
         .b(-0.15f, 29, -2.15f, 8.15f, 30, 6.15f, "red_concrete").b(2, 27, -4, 3.5f, 30, -2, "red_concrete")
         .d(1, 27, 3, 28, 6, "white_concrete").d(5, 27, 7, 28, 6, "white_concrete")
         .d(2, 27, 3, 28, 6.12f, "black_concrete").d(5, 27, 6, 28, 6.12f, "black_concrete")
         .d(1, 28.3f, 3, 28.8f, 6, "brown_concrete").d(5, 28.3f, 7, 28.8f, 6, "brown_concrete")
         .d(3, 25, 5, 25.8f, 6, "brown_terracotta");
        for (float sx : new float[]{-2.6f, 9.4f}) {   // dwa miecze (zelazny i zloty)
            boolean left = sx < 0;
            String blade = left ? "white_concrete" : "yellow_concrete", edge = left ? "light_gray_concrete" : "gold_block";
            w.b(sx, 10, 4.4f, sx + 1.2f, 14, 5.6f, "brown_concrete")
             .b(sx - 2.4f, 14, 4.2f, sx + 3.6f, 15.3f, 5.8f, "black_concrete")
             .b(sx - 0.4f, 15.3f, 4.5f, sx + 1.6f, 33, 5.5f, blade)
             .b(sx + 0.2f, 15.3f, 4.4f, sx + 1, 33, 5.6f, edge)
             .b(sx, 33, 4.5f, sx + 1.2f, 34.5f, 5.5f, blade);
        }
        MODELS.put("duels", w);

        Model q = new Model(4f, 2f);   // TAJEMNICZA POSTAC - wkrotce
        q.b(-0.5f, 0, -0.5f, 8.5f, 24, 4.5f, "purple_concrete")
         .b(-0.6f, 0, -0.6f, 8.6f, 1.2f, 4.6f, "yellow_concrete")
         .d(3.5f, 1.2f, 4.5f, 22, 4.5f, "yellow_concrete")
         .b(-4.5f, 11, -0.3f, -0.4f, 24, 4.3f, "purple_concrete").b(8.4f, 11, -0.3f, 12.5f, 24, 4.3f, "purple_concrete")
         .b(-4.3f, 11, 4.3f, -0.6f, 14, 4.5f, "magenta_concrete").b(8.6f, 11, 4.3f, 12.3f, 14, 4.5f, "magenta_concrete")
         .b(-0.8f, 24, -2.8f, 8.8f, 33, 6.4f, "purple_concrete")
         .b(2.5f, 33, -1.5f, 5.5f, 35, 2.5f, "purple_concrete")
         .b(0.5f, 25, 6.4f, 7.5f, 31.5f, 6.5f, "black_concrete")
         .d(1.8f, 28, 3.2f, 29.2f, 6.5f, "yellow_concrete").d(4.8f, 28, 6.2f, 29.2f, 6.5f, "yellow_concrete");
        String[] qm = {"01110", "10001", "00001", "00110", "00100", "00000", "00100"};
        for (int r = 0; r < qm.length; r++)
            for (int c = 0; c < 5; c++)
                if (qm[r].charAt(c) == '1') q.b(1.5f + c * 1.0f, 44 - r * 1.0f, 1.5f, 2.5f + c * 1.0f, 45 - r * 1.0f, 2.5f, "yellow_concrete");
        MODELS.put("wkrotce", q);
    }

    // ============================================================ spawn / despawn
    void respawnAll() {
        despawnAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-maskotki");
        if (s == null) return;
        for (String id : s.getKeys(false)) spawn(id);
    }

    void despawnAll() {
        for (List<Entity> l : spawned.values()) for (Entity e : l) if (e != null && e.isValid()) e.remove();
        spawned.clear();
        names.clear();
    }

    /** Co kilka sekund: jesli chunk sie zaladowal na nowo, a postaci nie ma - postaw (encje nie sa zapisywane). */
    void watchdog() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-maskotki");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            List<Entity> l = spawned.get(id);
            if (l != null && !l.isEmpty() && l.get(0).isValid()) continue;
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

    private String typ(String id) { return pl.getConfig().getString("postawione-maskotki." + id + ".typ", "discord"); }

    private String nameText(String typ) {
        ConfigurationSection t = pl.getConfig().getConfigurationSection("maskotki." + typ);
        String n = t == null ? typ : t.getString("nazwa", typ);
        String srv = t == null ? null : t.getString("serwer");
        if (srv != null && !srv.isEmpty()) { int on = pl.online(srv); n = n.replace("{ONLINE}", on < 0 ? "?" : String.valueOf(on)); }
        return WestLobby.c(n);
    }

    private void spawn(String id) {
        List<Entity> old = spawned.remove(id);
        if (old != null) for (Entity e : old) if (e.isValid()) e.remove();
        Location base = loc(id);
        if (base == null) return;
        String typ = typ(id);
        Model m = MODELS.get(typ);
        if (m == null) return;
        ConfigurationSection t = pl.getConfig().getConfigurationSection("maskotki." + typ);
        float sc = (float) (t == null ? 1.0 : t.getDouble("wielkosc", 1.0)) / 8f;
        boolean bob = t != null && t.getBoolean("bujanie", true);
        String tag = "maskotka:" + id;
        List<Entity> list = new ArrayList<>();
        World w = base.getWorld();
        for (int i = 0; i < m.boxes.size(); i++) {
            float[] b = m.boxes.get(i);
            final String mat = "minecraft:" + m.mats.get(i);
            list.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(Bukkit.createBlockData(mat));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(bob ? 20 : 0);
                d.setTransformation(new Transformation(
                    new Vector3f((b[0] - m.cx) * sc, b[1] * sc, (b[2] - m.cz) * sc),
                    new AxisAngle4f(), new Vector3f((b[3] - b[0]) * sc, (b[4] - b[1]) * sc, (b[5] - b[2]) * sc), new AxisAngle4f()));
                d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
            }));
        }
        float h = m.height * sc;
        String bubble = t == null ? "" : t.getString("dymek", "");
        if (!bubble.isEmpty()) list.add(w.spawn(base.clone().add(0, h + 0.5, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(bob ? 20 : 0);
            d.setText(WestLobby.c(bubble));
            d.setBackgroundColor(Color.fromARGB(230, 255, 255, 255));
            d.setShadowed(false);
            d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
        }));
        TextDisplay name = w.spawn(base.clone().add(0, h + (bubble.isEmpty() ? 0.4 : 1.1), 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(bob ? 20 : 0);
            d.setShadowed(true);
            d.setText(nameText(typ));
            d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
        });
        list.add(name);
        names.put(id, name);
        list.add(w.spawn(base, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth(Math.max(1.0f, 18 * sc));
            i.setInteractionHeight(h + 0.2f);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
        }));
        spawned.put(id, list);
    }

    /** Bujanie gora-dol (tylko postacie z bujanie: true) + odswiezenie liczby graczy w napisach. */
    void bob() {
        up = !up;
        double dy = up ? 0.12 : -0.12;
        for (Map.Entry<String, List<Entity>> en : spawned.entrySet()) {
            ConfigurationSection t = pl.getConfig().getConfigurationSection("maskotki." + typ(en.getKey()));
            if (t == null || !t.getBoolean("bujanie", true)) continue;
            for (Entity e : en.getValue())
                if (e.isValid() && !(e instanceof Interaction)) e.teleport(e.getLocation().add(0, dy, 0));
        }
    }

    void refreshNames() {
        for (Map.Entry<String, TextDisplay> en : names.entrySet())
            if (en.getValue().isValid()) en.getValue().setText(nameText(typ(en.getKey())));
    }

    // ============================================================ klik
    String idOf(Entity e) {
        String v = e.getPersistentDataContainer().get(pl.keyObj, PersistentDataType.STRING);
        return v != null && v.startsWith("maskotka:") ? v.substring(9) : null;
    }

    private final Map<java.util.UUID, Long> cd = new HashMap<>();

    void click(Player p, String id) {
        Long last = cd.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 800) return;
        cd.put(p.getUniqueId(), System.currentTimeMillis());
        ConfigurationSection t = pl.getConfig().getConfigurationSection("maskotki." + typ(id));
        if (t == null) return;
        p.playSound(p.getLocation(), t.getString("dzwiek", "minecraft:entity.allay.ambient_with_item"), 0.8f, 1.2f);
        String srv = t.getString("serwer", "");
        if (!srv.isEmpty() && !t.getBoolean("wkrotce", false)) { pl.connect(p, srv); return; }
        for (String line : t.getStringList("wiadomosc")) p.sendRichMessage(line);
    }

    // ============================================================ komendy
    String place(Player p, String typ) {
        if (!MODELS.containsKey(typ)) return "&cTypy postaci: &fgildie&c, &fduels&c, &fwkrotce&c, &fdiscord&c, &fsklep";
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
        List<Entity> l = spawned.remove(best);
        if (l != null) for (Entity e : l) if (e.isValid()) e.remove();
        names.remove(best);
        pl.getConfig().set("postawione-maskotki." + best, null);
        pl.saveConfig();
        return "&aUsunieto postac.";
    }
}
