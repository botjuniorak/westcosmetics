package pl.westmc.lobby;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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

/** Duze napisy HD (z paczki) stojace w swiecie, z linijka "online" pod spodem. */
final class Napisy {
    private final WestLobby pl;
    private final Map<String, List<TextDisplay>> spawned = new HashMap<>();
    static final Map<String, Integer> BAZA = Map.of("gildie", 0xE050, "duels", 0xE058, "wkrotce", 0xE060, "discord", 0xE068, "sklep", 0xE070);

    Napisy(WestLobby pl) { this.pl = pl; }

    private static String glif(String typ) {
        int b = BAZA.get(typ);
        return "" + (char) b + '' + (char) (b + 1) + '' + (char) (b + 2);
    }

    void respawnAll() {
        despawnAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-napisy");
        if (s != null) for (String id : s.getKeys(false)) spawn(id);
    }

    void despawnAll() {
        for (List<TextDisplay> l : spawned.values()) for (Entity e : l) if (e.isValid()) e.remove();
        spawned.clear();
    }

    void watchdog() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-napisy");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            List<TextDisplay> l = spawned.get(id);
            if (l != null && !l.isEmpty() && l.get(0).isValid()) continue;
            Location loc = loc(id);
            if (loc != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) spawn(id);
        }
    }

    private Location loc(String id) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("postawione-napisy." + id);
        if (c == null) return null;
        World w = Bukkit.getWorld(c.getString("swiat", "world"));
        if (w == null) return null;
        Location l = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"));
        l.setYaw((float) c.getDouble("yaw"));
        l.setPitch(0f);
        return l;
    }

    private String podpis(String typ) {
        ConfigurationSection t = pl.getConfig().getConfigurationSection("napisy." + typ);
        if (t == null) return "";
        String s = t.getString("podpis", "");
        String srv = t.getString("serwer", "");
        if (!srv.isEmpty()) { int on = pl.online(srv); s = s.replace("{ONLINE}", on < 0 ? "?" : String.valueOf(on)); }
        return WestLobby.c(s);
    }

    private void spawn(String id) {
        List<TextDisplay> old = spawned.remove(id);
        if (old != null) for (Entity e : old) if (e.isValid()) e.remove();
        Location base = loc(id);
        if (base == null) return;
        String typ = pl.getConfig().getString("postawione-napisy." + id + ".typ", "gildie");
        if (!BAZA.containsKey(typ)) return;
        float sk = (float) pl.getConfig().getDouble("postawione-napisy." + id + ".skala", 1.5);
        String tag = "napis:" + id;
        List<TextDisplay> l = new ArrayList<>();
        World w = base.getWorld();
        l.add(w.spawn(base, TextDisplay.class, d -> {
            prep(d, tag, sk);
            d.text(Glify.lobby(glif(typ)));
        }));
        String pod = podpis(typ);
        if (!pod.isEmpty()) {
            float sub = sk * 0.55f;
            l.add(w.spawn(base.clone().add(0, -0.32 * sk, 0), TextDisplay.class, d -> {
                prep(d, tag, sub);
                d.setShadowed(true);
                d.setText(pod);
            }));
        }
        spawned.put(id, l);
    }

    private void prep(TextDisplay d, String tag, float scale) {
        d.setPersistent(false);
        d.setBillboard(pl.getConfig().getBoolean("napisy-obracaj", true) ? Display.Billboard.VERTICAL : Display.Billboard.FIXED);
        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        d.setShadowed(false);
        d.setLineWidth(4000);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setViewRange(2f);
        d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        d.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, tag);
    }

    void refresh() {
        for (Map.Entry<String, List<TextDisplay>> en : spawned.entrySet()) {
            if (en.getValue().size() < 2) continue;
            TextDisplay d = en.getValue().get(1);
            if (d.isValid()) d.setText(podpis(pl.getConfig().getString("postawione-napisy." + en.getKey() + ".typ", "gildie")));
        }
    }

    String place(Player p, String typ, double skala) {
        if (!BAZA.containsKey(typ)) return "&cNapisy: &fgildie&c, &fduels&c, &fwkrotce&c, &fdiscord&c, &fsklep";
        Location l = p.getLocation();
        String id = typ + (System.currentTimeMillis() % 100000);
        String b = "postawione-napisy." + id + ".";
        // napis stoi 2 kratki przed toba, na wysokosci oczu, przodem do ciebie
        org.bukkit.util.Vector dir = l.getDirection().setY(0).normalize().multiply(2);
        Location at = l.clone().add(dir);
        pl.getConfig().set(b + "typ", typ);
        pl.getConfig().set(b + "swiat", l.getWorld().getName());
        pl.getConfig().set(b + "x", Math.round(at.getX() * 2) / 2.0);
        pl.getConfig().set(b + "y", Math.round((l.getY() + 1.2) * 4) / 4.0);
        pl.getConfig().set(b + "z", Math.round(at.getZ() * 2) / 2.0);
        pl.getConfig().set(b + "yaw", (double) Math.round((l.getYaw() + 180) / 45f) * 45.0);
        pl.getConfig().set(b + "skala", skala);
        pl.saveConfig();
        spawn(id);
        return "&aNapis &f" + typ + " &apostawiony przed toba (skala " + skala + "). Zly rozmiar? &f/lobby napis usun &ai postaw z inna skala.";
    }

    String remove(Player p) {
        String best = null; double bd = 15 * 15;
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-napisy");
        if (s != null) for (String id : s.getKeys(false)) {
            Location l = loc(id);
            if (l == null || l.getWorld() != p.getWorld()) continue;
            double d = l.distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = id; }
        }
        if (best == null) return "&cNie ma napisu w poblizu (15 kratek).";
        List<TextDisplay> old = spawned.remove(best);
        if (old != null) for (Entity e : old) if (e.isValid()) e.remove();
        pl.getConfig().set("postawione-napisy." + best, null);
        pl.saveConfig();
        return "&aUsunieto napis.";
    }
}
