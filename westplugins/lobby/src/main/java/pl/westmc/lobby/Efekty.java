package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Efekty lobby: lewitujace kule energii z blyskawicami, krysztaly lodu, aury pod NPC, snieg, muzyka. Ustawienia w sekcji fx. */
final class Efekty implements org.bukkit.command.CommandExecutor {
    private final JavaPlugin pl;
    Efekty(JavaPlugin pl) { this.pl = pl; }
    private static final String TAG = "westlobby";

    private static final class Fx {
        String type; Location loc; double size; float phase; ItemDisplay display;
    }

    private final List<Fx> fx = new ArrayList<>();
    private final Random rnd = new Random();
    private long tick, nextBolt, nextMusic;

    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    void start() {
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::loop, 20L, 1L);
    }

    void stop() { despawnAll(); }

    private org.bukkit.configuration.file.FileConfiguration getConfig() { return pl.getConfig(); }
    private void saveConfig() { pl.saveConfig(); }

    // ------------------------------------------------------------ zapis/odczyt

    void load() {
        despawnAll();
        fx.clear();
        for (Map<?, ?> m : getConfig().getMapList("fx.efekty")) {
            World w = Bukkit.getWorld(String.valueOf(m.get("swiat")));
            if (w == null) continue;
            Fx f = new Fx();
            f.type = String.valueOf(m.get("typ"));
            f.loc = new Location(w, num(m.get("x")), num(m.get("y")), num(m.get("z")));
            f.size = m.containsKey("wielkosc") ? num(m.get("wielkosc")) : 3;
            f.phase = rnd.nextFloat() * 6.28f;
            fx.add(f);
        }
        // stare encje (np. po crashu) sprzatamy
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) if (e.getScoreboardTags().contains(TAG)) e.remove();
        for (Fx f : fx) spawn(f);

    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o)); }

    private void save() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fx f : fx) {
            Map<String, Object> m = new HashMap<>();
            m.put("typ", f.type); m.put("swiat", f.loc.getWorld().getName());
            m.put("x", round(f.loc.getX())); m.put("y", round(f.loc.getY())); m.put("z", round(f.loc.getZ()));
            m.put("wielkosc", f.size);
            out.add(m);
        }
        getConfig().set("fx.efekty", out);
        saveConfig();
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    // ------------------------------------------------------------ encje

    private void spawn(Fx f) {
        if (f.type.equals("aura")) return;
        f.loc.getChunk().setForceLoaded(true);
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("westmc", f.type));
        it.setItemMeta(m);
        f.display = f.loc.getWorld().spawn(f.loc, ItemDisplay.class, d -> {
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(4f);
            d.setTeleportDuration(0);
            d.setTransformation(transform(f, 0));
        });
    }

    private void despawnAll() {
        for (Fx f : fx) if (f.display != null) { f.display.remove(); f.display = null; }
    }

    private Transformation transform(Fx f, long t) {
        float s = (float) f.size;
        boolean orb = f.type.equals("kula");
        float speed = orb ? 0.035f : 0.012f;              // radiany na tick
        float angle = (float) ((t * speed + f.phase) % (Math.PI * 2));
        float bob = (float) Math.sin(t * 0.05 + f.phase) * (orb ? 0.25f : 0.08f);
        AxisAngle4f rot = new AxisAngle4f(angle, 0, 1, 0);
        AxisAngle4f tilt = orb ? new AxisAngle4f((float) Math.sin(t * 0.02 + f.phase) * 0.35f, 1, 0, 0) : new AxisAngle4f(0, 1, 0, 0);
        return new Transformation(new Vector3f(0, bob + (orb ? 0 : s / 2f), 0), rot, new Vector3f(s, s, s), tilt);
    }

    // ------------------------------------------------------------ petla efektow

    private void loop() {
        tick++;
        // obrot: nowy cel co 20 tickow, klient plynnie interpoluje
        if (tick % 20 == 0) for (Fx f : fx) {
            if (f.display == null || !f.display.isValid()) { if (!f.type.equals("aura") && f.loc.isChunkLoaded()) spawn(f); continue; }
            f.display.setInterpolationDelay(0);
            f.display.setInterpolationDuration(20);
            f.display.setTransformation(transform(f, tick + 20));
        }
        if (tick % 4 == 0) ambient();
        if (getConfig().getBoolean("fx.blyskawice", true) && tick >= nextBolt) {
            List<Integer> r = getConfig().getIntegerList("fx.blyskawice-co");
            int a = r.size() > 0 ? r.get(0) : 3, b = r.size() > 1 ? r.get(1) : 7;
            nextBolt = tick + 20L * (a + rnd.nextInt(Math.max(1, b - a + 1)));
            bolt();
        }
        World lobby = Bukkit.getWorld(getConfig().getString("spawn.swiat", "world"));
        if (lobby == null) return;
        if (getConfig().getBoolean("fx.snieg", true) && tick % 5 == 0) {
            int n = getConfig().getInt("fx.snieg-ilosc", 22);
            for (Player p : lobby.getPlayers()) {
                Location l = p.getLocation();
                for (int i = 0; i < n; i++) {
                    Location s = l.clone().add(rnd.nextGaussian() * 9, 4 + rnd.nextDouble() * 9, rnd.nextGaussian() * 9);
                    p.spawnParticle(Particle.SNOWFLAKE, s, 0, (rnd.nextDouble() - 0.5) * 0.08, -0.06 - rnd.nextDouble() * 0.05, (rnd.nextDouble() - 0.5) * 0.08, 1);
                }
            }
        }
        String music = getConfig().getString("fx.muzyka", "");
        if (music != null && !music.isBlank() && tick >= nextMusic) {
            nextMusic = tick + 20L * Math.max(30, getConfig().getInt("fx.muzyka-co-sekund", 240));
            float vol = (float) getConfig().getDouble("fx.muzyka-glosnosc", 0.6);
            for (Player p : lobby.getPlayers()) { p.stopSound(SoundCategory.MUSIC); p.playSound(p, music, SoundCategory.MUSIC, vol, 1f); }
        }
    }

    private void ambient() {
        for (Fx f : fx) {
            World w = f.loc.getWorld();
            if (w.getPlayers().isEmpty()) continue;
            switch (f.type) {
                case "kula" -> {
                    Location c = f.loc.clone().add(0, f.size * 0.0, 0);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c, 3, f.size * 0.35, f.size * 0.35, f.size * 0.35, 0.02);
                    w.spawnParticle(Particle.END_ROD, c, 1, f.size * 0.4, f.size * 0.4, f.size * 0.4, 0.01);
                }
                case "krysztal" -> {
                    if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.DUST, f.loc.clone().add(0, f.size * 0.6, 0), 2, f.size * 0.25, f.size * 0.4, f.size * 0.25, 0,
                        new Particle.DustOptions(Color.fromRGB(170, 230, 255), 1.0f));
                }
                case "aura" -> {
                    double t = tick * 0.15;
                    for (int k = 0; k < 2; k++) {
                        double a = t + k * Math.PI;
                        double h = (tick % 40) / 40.0 * 2.2;
                        Location p = f.loc.clone().add(Math.cos(a) * 0.9, 0.1 + h, Math.sin(a) * 0.9);
                        w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(80, 190, 255), 1.1f));
                    }
                    if (tick % 8 == 0) w.spawnParticle(Particle.END_ROD, f.loc.clone().add(0, 0.2, 0), 2, 0.6, 0.05, 0.6, 0.01);
                }
                default -> { }
            }
        }
    }

    /** Wyladowanie miedzy dwiema kulami - postrzepiona linia z czasteczek + cichy dzwiek. */
    private void bolt() {
        List<Fx> orbs = new ArrayList<>();
        for (Fx f : fx) if (f.type.equals("kula") && !f.loc.getWorld().getPlayers().isEmpty()) orbs.add(f);
        if (orbs.size() < 2) return;
        double range = getConfig().getDouble("fx.blyskawice-zasieg", 30);
        Fx a = orbs.get(rnd.nextInt(orbs.size()));
        List<Fx> near = new ArrayList<>();
        for (Fx o : orbs) if (o != a && o.loc.getWorld() == a.loc.getWorld() && o.loc.distance(a.loc) <= range) near.add(o);
        if (near.isEmpty()) return;
        Fx b = near.get(rnd.nextInt(near.size()));
        Vector from = a.loc.toVector(), to = b.loc.toVector();
        int seg = 14;
        Vector prev = from.clone();
        Particle.DustOptions core = new Particle.DustOptions(Color.fromRGB(235, 250, 255), 1.3f), glow = new Particle.DustOptions(Color.fromRGB(70, 180, 255), 2.2f);
        World w = a.loc.getWorld();
        for (int i = 1; i <= seg; i++) {
            Vector p = from.clone().add(to.clone().subtract(from).multiply(i / (double) seg));
            if (i < seg) p.add(new Vector(rnd.nextGaussian() * 0.45, rnd.nextGaussian() * 0.45, rnd.nextGaussian() * 0.45));
            double len = p.distance(prev);
            int steps = (int) Math.ceil(len / 0.18);
            for (int s = 0; s <= steps; s++) {
                Vector q = prev.clone().add(p.clone().subtract(prev).multiply(s / (double) Math.max(1, steps)));
                Location l = q.toLocation(w);
                w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, core);
                if (s % 2 == 0) w.spawnParticle(Particle.DUST, l, 1, 0.05, 0.05, 0.05, 0, glow);
            }
            prev = p;
        }
        w.spawnParticle(Particle.FLASH, a.loc, 1);
        w.spawnParticle(Particle.ELECTRIC_SPARK, b.loc, 25, 0.5, 0.5, 0.5, 0.2);
        w.playSound(a.loc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.AMBIENT, 0.6f, 1.6f);
        w.playSound(b.loc, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.AMBIENT, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------ komenda

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { s.sendMessage(c("&b/lobbyfx kula [wielkosc] &7| &bkrysztal [wielkosc] &7| &baura &7| &busun &7| &blista &7| &bprzeladuj")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!s.hasPermission("westlobby.admin")) { s.sendMessage(c("&cBrak uprawnien.")); return true; }
        if (sub.equals("przeladuj")) { pl.reloadConfig(); load(); s.sendMessage(c("&aPrzeładowano (" + fx.size() + " efektów).")); return true; }
        if (sub.equals("lista")) {
            int i = 1;
            for (Fx f : fx) s.sendMessage(c("&7" + (i++) + ". &b" + f.type + " &7" + f.loc.getWorld().getName() + " " + f.loc.getBlockX() + " " + f.loc.getBlockY() + " " + f.loc.getBlockZ() + " &8(" + f.size + ")"));
            if (fx.isEmpty()) s.sendMessage(c("&7Brak efektów."));
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        switch (sub) {
            case "kula", "krysztal", "aura" -> {
                Fx f = new Fx();
                f.type = sub;
                f.size = a.length > 1 ? Double.parseDouble(a[1]) : sub.equals("kula") ? 3 : 4;
                Location l = p.getLocation();
                f.loc = new Location(l.getWorld(), round(l.getX()), round(l.getY() + (sub.equals("kula") ? 1.5 : 0)), round(l.getZ()));
                f.phase = rnd.nextFloat() * 6.28f;
                fx.add(f);
                spawn(f);
                save();
                p.sendMessage(c("&aPostawiono &b" + sub + "&a. Usuniesz: &f/lobbyfx usun &7(stań obok)"));
            }
            case "usun" -> {
                Fx best = null; double bd = 36;
                for (Fx f : fx) if (f.loc.getWorld() == p.getWorld()) { double d = f.loc.distanceSquared(p.getLocation()); if (d < bd) { bd = d; best = f; } }
                if (best == null) { p.sendMessage(c("&cNie ma efektu w promieniu 6 kratek.")); return true; }
                if (best.display != null) best.display.remove();
                fx.remove(best); save();
                p.sendMessage(c("&7Usunięto &b" + best.type + "&7."));
            }
            default -> p.sendMessage(c("&cNieznana opcja."));
        }
        return true;
    }
}
