package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Kinowe wejscie: kamera (spectator) przelatuje po luku wokol wyspy, potem gracz laduje na spawnie. */
final class Intro {
    private final WestLobby plugin;
    private final Map<UUID, BukkitRunnable> running = new HashMap<>();

    Intro(WestLobby plugin) { this.plugin = plugin; }

    boolean wants(Player p) {
        var c = plugin.getConfig();
        if (!c.getBoolean("intro.wlaczone", true)) return false;
        return !c.getBoolean("intro.tylko-pierwsze-wejscie", false) || !p.hasPlayedBefore();
    }

    void play(Player p) {
        stop(p);
        Location sp = plugin.spawn();
        if (sp == null) { plugin.prepare(p); return; }
        var c = plugin.getConfig();
        double cx = sp.getX(), cy = sp.getY(), cz = sp.getZ();
        String[] mid = c.getString("intro.srodek", "").split(",");
        if (mid.length == 3) try { cx = Double.parseDouble(mid[0]) + 0.5; cy = Double.parseDouble(mid[1]); cz = Double.parseDouble(mid[2]) + 0.5; } catch (NumberFormatException ignored) { }
        final double fx = cx, fy = cy, fz = cz;
        final double radius = c.getDouble("intro.promien", 38), height = c.getDouble("intro.wysokosc", 26);
        final int ticks = Math.max(40, c.getInt("intro.czas-tickow", 110));
        final double arc = Math.toRadians(c.getDouble("intro.kat-przelotu", 220));
        final double start = Math.atan2(sp.getZ() - fz, sp.getX() - fx) + Math.PI - arc / 2;
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        p.sendTitle(WestLobby.c(c.getString("teksty.tytul", "&#399EFC&lWESTMC.EU")),
            WestLobby.c(c.getString("teksty.intro-podtytul", "&7Wojny Gildii &8• &7kucnij, aby pominac")), 10, ticks - 20, 10);
        p.playSound(p.getLocation(), "minecraft:block.beacon.activate", 0.7f, 0.8f);
        BukkitRunnable r = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); running.remove(p.getUniqueId()); return; }
                double k = (double) t / ticks;
                double e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;   // plynny start i koniec
                double a = start + arc * e;
                double rad = radius * (1 - 0.35 * e), hh = height * (1 - 0.55 * e);
                Location cam = new Location(sp.getWorld(), fx + Math.cos(a) * rad, fy + hh, fz + Math.sin(a) * rad);
                double dx = fx - cam.getX(), dz = fz - cam.getZ(), dy = (fy + 4) - cam.getY();
                cam.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                cam.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                p.teleport(cam);
                if (++t > ticks) finish(p, this);
            }
        };
        running.put(p.getUniqueId(), r);
        r.runTaskTimer(plugin, 2L, 1L);
    }

    private void finish(Player p, BukkitRunnable r) {
        r.cancel();
        running.remove(p.getUniqueId());
        plugin.prepare(p);
        p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, p.getLocation().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.1);
    }

    void skip(Player p) {
        BukkitRunnable r = running.get(p.getUniqueId());
        if (r != null) finish(p, r);
    }

    void stop(Player p) {
        BukkitRunnable r = running.remove(p.getUniqueId());
        if (r != null) r.cancel();
    }
}
