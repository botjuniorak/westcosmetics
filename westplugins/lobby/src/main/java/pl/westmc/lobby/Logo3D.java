package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gigantyczny napis z blokow (BlockDisplay), obracajacy sie w powietrzu nad spawnem. */
final class Logo3D {
    private static final Map<Character, String[]> G = Map.ofEntries(
        Map.entry('W', new String[]{"10001", "10001", "10001", "10101", "10101", "10101", "01010"}),
        Map.entry('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"}),
        Map.entry('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"}),
        Map.entry('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"}),
        Map.entry('M', new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"}),
        Map.entry('C', new String[]{"01111", "10000", "10000", "10000", "10000", "10000", "01111"}),
        Map.entry('U', new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"}),
        Map.entry('.', new String[]{"0", "0", "0", "0", "0", "0", "1"}),
        Map.entry(' ', new String[]{"00", "00", "00", "00", "00", "00", "00"}));
    private final WestLobby plugin;
    private final List<BlockDisplay> parts = new ArrayList<>();
    private BukkitTask spin;
    private float yaw;

    Logo3D(WestLobby plugin) { this.plugin = plugin; }

    void remove() {
        if (spin != null) spin.cancel();
        for (BlockDisplay d : parts) if (d.isValid()) d.remove();
        parts.clear();
    }

    void build() {
        remove();
        var c = plugin.getConfig();
        if (!c.getBoolean("logo.wlaczone", true) || !c.contains("logo.x")) return;
        World w = Bukkit.getWorld(c.getString("logo.swiat", "world"));
        if (w == null) return;
        Location center = new Location(w, c.getDouble("logo.x"), c.getDouble("logo.y"), c.getDouble("logo.z"));
        String text = c.getString("logo.tekst", "WESTMC").toUpperCase();
        float px = (float) c.getDouble("logo.wielkosc-piksela", 1.0);
        Material top = mat(c.getString("logo.kolor-gora", "WHITE_CONCRETE"), Material.WHITE_CONCRETE);
        Material mid = mat(c.getString("logo.kolor-srodek", "LIGHT_BLUE_CONCRETE"), Material.LIGHT_BLUE_CONCRETE);
        Material side = mat(c.getString("logo.kolor-boku", "BLUE_CONCRETE"), Material.BLUE_CONCRETE);
        int width = 0;
        for (char ch : text.toCharArray()) { String[] g = G.getOrDefault(ch, G.get(' ')); width += g[0].length() + 1; }
        width -= 1;
        int x0 = 0;
        for (char ch : text.toCharArray()) {
            String[] g = G.getOrDefault(ch, G.get(' '));
            for (int r = 0; r < 7; r++) {
                for (int k = 0; k < g[r].length(); k++) {
                    if (g[r].charAt(k) != '1') continue;
                    float ox = (x0 + k - width / 2f) * px, oy = (6 - r) * px;
                    // glebia (cien z tylu) + lico
                    parts.add(cube(center, side, ox + px * 0.18f, oy - px * 0.18f, -px * 0.35f, px, px * 0.9f));
                    parts.add(cube(center, r < 3 ? top : mid, ox, oy, 0, px, px * 0.5f));
                }
            }
            x0 += g[0].length() + 1;
        }
        float speed = (float) c.getDouble("logo.obrot-stopni-na-sekunde", 12.0);
        if (speed != 0) {
            spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                yaw = (yaw + speed / 4f) % 360f;
                for (BlockDisplay d : parts) if (d.isValid()) d.setRotation(yaw, 0f);
            }, 5L, 5L);
        }
    }

    private BlockDisplay cube(Location center, Material m, float x, float y, float z, float size, float depth) {
        return center.getWorld().spawn(center, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(m.createBlockData());
            d.setTeleportDuration(5);
            d.setViewRange(4.0f);
            d.setTransformation(new Transformation(new Vector3f(x - size / 2f, y, z - depth / 2f),
                new AxisAngle4f(0, 0, 1, 0), new Vector3f(size, size, depth), new AxisAngle4f(0, 0, 1, 0)));
        });
    }

    private static Material mat(String n, Material def) {
        Material m = Material.matchMaterial(n);
        return m == null || !m.isBlock() ? def : m;
    }
}
