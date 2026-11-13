package pl.westmc.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Banery z map w niewidzialnych ramkach (np. 4x3). Obraz z plugins/WestLobby/banery/<wzor>.png,
 * a w dolnej "pigulce" plugin dopisuje na zywo np. ONLINE: 45.
 */
final class Banery {
    static final String[] WZORY = {"gildie", "duels", "wkrotce", "sklep", "discord"};
    private final WestLobby pl;
    private final Map<String, Placed> placed = new HashMap<>();

    Banery(WestLobby pl) { this.pl = pl; }

    static final class Placed {
        String id, wzor;
        int w, h;
        final List<Integer> maps = new ArrayList<>();
        BufferedImage[] tiles;
        int version, lastHash = Integer.MIN_VALUE;
    }

    static final class Tile extends MapRenderer {
        final Placed b; final int idx; int drawn = -1;
        Tile(Placed b, int idx) { super(false); this.b = b; this.idx = idx; }
        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (drawn == b.version || b.tiles == null || b.tiles[idx] == null) return;
            canvas.drawImage(0, 0, b.tiles[idx]);
            drawn = b.version;
        }
    }

    // ============================================================ pliki obrazkow
    void saveImages() {
        File dir = new File(pl.getDataFolder(), "banery");
        if (!dir.exists() && !dir.mkdirs()) return;
        for (String w : WZORY) if (!new File(dir, w + ".png").exists()) pl.saveResource("banery/" + w + ".png", false);
    }

    private BufferedImage base(String wzor) {
        String file = pl.getConfig().getString("banery." + wzor + ".obraz", wzor + ".png");
        try {
            return ImageIO.read(new File(new File(pl.getDataFolder(), "banery"), file));
        } catch (Exception e) {
            pl.getLogger().warning("Nie moge wczytac obrazka banera " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ============================================================ wczytanie po starcie
    void load() {
        placed.clear();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("postawione-banery");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            Placed b = new Placed();
            b.id = id;
            b.wzor = s.getString(id + ".wzor", "gildie");
            b.w = s.getInt(id + ".szerokosc", 4);
            b.h = s.getInt(id + ".wysokosc", 3);
            b.maps.addAll(s.getIntegerList(id + ".mapy"));
            int i = 0;
            for (int mid : b.maps) {
                MapView v = Bukkit.getMap(mid);
                if (v != null) attach(v, b, i);
                i++;
            }
            placed.put(id, b);
            redraw(b, true);
        }
    }

    private void attach(MapView v, Placed b, int idx) {
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.addRenderer(new Tile(b, idx));
    }

    // ============================================================ odswiezanie napisu (online)
    String bottomText(String wzor) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("banery." + wzor);
        String txt = c == null ? "" : c.getString("napis", "");
        String server = c == null ? null : c.getString("serwer");
        if (server != null && !server.isEmpty()) {
            int on = pl.online(server);
            txt = txt.replace("{ONLINE}", on < 0 ? "?" : String.valueOf(on));
        }
        return txt;
    }

    void tick() { for (Placed b : placed.values()) redraw(b, false); }

    private void redraw(Placed b, boolean force) {
        String txt = bottomText(b.wzor);
        int hash = (txt + "|" + b.w + "x" + b.h).hashCode();
        if (!force && hash == b.lastHash) return;
        BufferedImage src = base(b.wzor);
        if (src == null) return;
        int W = b.w * 128, H = b.h * 128;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            src.getWidth() == W && src.getHeight() == H ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, W, H, null);
        g.dispose();
        if (!txt.isEmpty()) {
            int pillW = (int) (W * 0.5), pillH = (int) (H * 0.10);
            int px = Math.max(1, Math.min(pillH * 55 / 100 / 7, pillW * 90 / 100 / Math.max(1, PixelFont.width(txt, 1))));
            Color col;
            try { col = Color.decode(pl.getConfig().getString("banery." + b.wzor + ".kolor-napisu", "#FFFFFF")); }
            catch (Exception e) { col = Color.WHITE; }
            PixelFont.draw(img, txt, W / 2, (int) (H * 0.89) - 7 * px / 2, px, col);
        }
        BufferedImage[] tiles = new BufferedImage[b.w * b.h];
        for (int r = 0; r < b.h; r++)
            for (int c = 0; c < b.w; c++)
                tiles[r * b.w + c] = img.getSubimage(c * 128, r * 128, 128, 128);
        b.tiles = tiles;
        b.lastHash = hash;
        b.version++;
    }

    // ============================================================ stawianie
    /** Patrzysz na sciane z blokow jednego koloru (np. 4x3 welny) -> baner wypelnia ja cala. */
    String place(Player p, String wzor, int fw, int fh) {
        Block target = p.getTargetBlockExact(8);
        BlockFace face = p.getTargetBlockFace(8);
        if (target == null || face == null) return "&cPatrz na sciane (max 8 kratek), na ktorej ma wisiec baner.";
        if (face == BlockFace.UP || face == BlockFace.DOWN) return "&cBaner musi wisiec na scianie (pionowej), nie na podlodze/suficie.";
        int rx = face.getModZ(), rz = -face.getModX();       // "w prawo" patrzac na sciane
        int minU, maxU, minY, maxY;
        if (fw > 0 && fh > 0) {
            int u0 = target.getX() * rx + target.getZ() * rz;
            minU = u0; maxU = u0 + fw - 1; minY = target.getY(); maxY = target.getY() + fh - 1;
        } else {
            Material m = target.getType();
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            int u0 = target.getX() * rx + target.getZ() * rz;
            q.add(new int[]{u0, target.getY()});
            minU = maxU = u0; minY = maxY = target.getY();
            while (!q.isEmpty() && seen.size() < 400) {
                int[] c = q.poll();
                long key = ((long) c[0] << 32) ^ (c[1] & 0xffffffffL);
                if (!seen.add(key)) continue;
                Block bl = at(target, rx, rz, c[0], c[1]);
                if (bl.getType() != m || !bl.getRelative(face).getType().isAir()) continue;
                minU = Math.min(minU, c[0]); maxU = Math.max(maxU, c[0]); minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
                q.add(new int[]{c[0] + 1, c[1]}); q.add(new int[]{c[0] - 1, c[1]}); q.add(new int[]{c[0], c[1] + 1}); q.add(new int[]{c[0], c[1] - 1});
            }
            if (maxU - minU + 1 > 8 || maxY - minY + 1 > 6)
                return "&cSciana z tego bloku jest za duza (max 8x6). Zbuduj tlo z jednego koloru (np. 4x3 welny) albo podaj rozmiar: &f/lobby baner " + wzor + " 4 3 &7(patrzac na lewy dolny blok)";
        }
        int w = maxU - minU + 1, h = maxY - minY + 1;
        World world = target.getWorld();
        String id = "b" + System.currentTimeMillis() % 1000000;
        Placed b = new Placed();
        b.id = id; b.wzor = wzor; b.w = w; b.h = h;
        int miss = 0;
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                Block wall = at(target, rx, rz, minU + c, maxY - r);
                Block front = wall.getRelative(face);
                MapView v = Bukkit.createMap(world);
                b.maps.add(v.getId());
                attach(v, b, r * w + c);
                if (!wall.getType().isSolid() || !front.getType().isAir()) { miss++; continue; }
                ItemStack it = new ItemStack(Material.FILLED_MAP);
                MapMeta mm = (MapMeta) it.getItemMeta();
                mm.setMapView(v);
                it.setItemMeta(mm);
                Location loc = front.getLocation();
                try { world.spawn(loc, GlowItemFrame.class, f -> {
                    f.setFacingDirection(face, true);
                    f.setItem(it, false);
                    f.setVisible(false);
                    f.setFixed(true);
                    f.setInvulnerable(true);
                    f.getPersistentDataContainer().set(pl.keyObj, PersistentDataType.STRING, "baner:" + id);
                }); } catch (IllegalArgumentException ex) { miss++; }
            }
        String base = "postawione-banery." + id + ".";
        pl.getConfig().set(base + "wzor", wzor);
        pl.getConfig().set(base + "szerokosc", w);
        pl.getConfig().set(base + "wysokosc", h);
        pl.getConfig().set(base + "mapy", b.maps);
        pl.saveConfig();
        placed.put(id, b);
        redraw(b, true);
        return "&aBaner &f" + wzor + " &apostawiony (" + w + "x" + h + " map)." + (miss > 0 ? " &e" + miss + " pol pominieto (brak sciany/ cos zaslania)." : "")
            + ((w * 3 != h * 4) ? " &7Wskazowka: najladniej wyglada 4x3." : "");
    }

    private static Block at(Block ref, int rx, int rz, int u, int y) {
        int x = rx != 0 ? u * rx : ref.getX();
        int z = rz != 0 ? u * rz : ref.getZ();
        return ref.getWorld().getBlockAt(x, y, z);
    }

    // ============================================================ usuwanie / klik
    String idOf(Entity e) {
        String v = e.getPersistentDataContainer().get(pl.keyObj, PersistentDataType.STRING);
        return v != null && v.startsWith("baner:") ? v.substring(6) : null;
    }

    String wzorOf(String id) { Placed b = placed.get(id); return b == null ? null : b.wzor; }

    String remove(Player p) {
        String found = null;
        for (Entity e : p.getNearbyEntities(6, 6, 6)) {
            String id = idOf(e);
            if (id != null) { found = id; break; }
        }
        if (found == null) return "&cNie ma banera w poblizu (6 kratek).";
        for (Entity e : p.getWorld().getEntities()) if (found.equals(idOf(e))) e.remove();
        placed.remove(found);
        pl.getConfig().set("postawione-banery." + found, null);
        pl.saveConfig();
        return "&aUsunieto baner.";
    }
}
