package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Modele 3D skrzyn na spawnie: blok skrzyni zamienia sie w niewidzialna bariere, na nim stoi model 3D (ItemDisplay),
 * nad nim napis, dookola czasteczki. Oryginalny blok jest zapamietany i wraca po /skrzynie ustaw usun.
 */
final class SkrzynieModele {
    private final WestSMP pl;
    private final NamespacedKey KLUCZ;
    private final Map<String, Entity[]> enc = new HashMap<>();
    private BukkitTask task;
    private int tik;

    SkrzynieModele(WestSMP pl) { this.pl = pl; KLUCZ = new NamespacedKey(pl, "skrzynia3d"); }

    private boolean wl() { return pl.getConfig().getBoolean("skrzynie.modele-3d", true); }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(KLUCZ, PersistentDataType.STRING)) e.remove();
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tik, 40L, 10L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Entity[] t : enc.values()) for (Entity e : t) if (e != null && e.isValid()) e.remove();
        enc.clear();
    }

    static Block blok(String key) {
        String[] x = key.split(",");
        if (x.length < 4) return null;
        World w = Bukkit.getWorld(x[0]);
        if (w == null) for (World ww : Bukkit.getWorlds()) if (ww.getName().replace('.', '_').equals(x[0])) w = ww;
        if (w == null) return null;
        try { return w.getBlockAt(Integer.parseInt(x[1]), Integer.parseInt(x[2]), Integer.parseInt(x[3])); } catch (NumberFormatException e) { return null; }
    }

    /** Wywolywane po /skrzynie ustaw <typ> (yaw = gdzie ma patrzec przod skrzyni). */
    void ustawiono(String key, String typ, float yaw, Block b) {
        usunEnc(key);
        if (typ == null) {   // usun - przywroc blok
            String org = pl.getConfig().getString("skrzynie-modele." + key + ".blok");
            if (org != null) try { b.setBlockData(Bukkit.createBlockData(org)); } catch (Exception ignored) { }
            pl.getConfig().set("skrzynie-modele." + key, null);
            pl.saveConfig();
            return;
        }
        if (!pl.getConfig().isSet("skrzynie-modele." + key + ".blok")) pl.getConfig().set("skrzynie-modele." + key + ".blok", b.getBlockData().getAsString());
        pl.getConfig().set("skrzynie-modele." + key + ".yaw", (double) yaw);
        pl.saveConfig();
    }

    private void usunEnc(String key) {
        Entity[] t = enc.remove(key);
        if (t != null) for (Entity e : t) if (e != null && e.isValid()) e.remove();
    }

    private static final Map<String, Color> KOLORY = Map.of("zwykla", Color.fromRGB(0x5AAEFF), "epicka", Color.fromRGB(0xC08BFF), "legendarna", Color.fromRGB(0xFF8A2A));

    private void tik() {
        tik++;
        ConfigurationSection s = pl.getConfig().getConfigurationSection("skrzynie-bloki");
        if (s == null || !wl()) { if (!enc.isEmpty()) stop2(); return; }
        for (String key : s.getKeys(false)) {
            String typ = s.getString(key);
            Block b = blok(key);
            if (b == null || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) { usunEnc(key); continue; }
            if (b.getType() != Material.BARRIER) {
                if (!pl.getConfig().isSet("skrzynie-modele." + key + ".blok")) { pl.getConfig().set("skrzynie-modele." + key + ".blok", b.getBlockData().getAsString()); pl.saveConfig(); }
                b.setType(Material.BARRIER, false);
            }
            Entity[] t = enc.get(key);
            if (t == null || !t[0].isValid() || !t[1].isValid()) { usunEnc(key); t = postaw(key, typ, b); enc.put(key, t); }
            Color k = KOLORY.getOrDefault(typ, Color.WHITE);
            Location c = b.getLocation().add(0.5, 0.6, 0.5);
            double a = tik * 0.4;
            for (int i = 0; i < 2; i++) {
                double aa = a + i * Math.PI;
                b.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(aa) * 0.75, Math.sin(tik * 0.2 + i) * 0.3, Math.sin(aa) * 0.75), 1, 0, 0, 0, 0, new Particle.DustOptions(k, 1.1f));
            }
            if (tik % 4 == 0) b.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(0, 0.4, 0), 1, 0.3, 0.2, 0.3, 0.01);
        }
        // usuniete z configu
        for (String key : new java.util.ArrayList<>(enc.keySet())) if (!s.isSet(key)) usunEnc(key);
    }

    private void stop2() { for (String k : new java.util.ArrayList<>(enc.keySet())) usunEnc(k); }

    private Entity[] postaw(String key, String typ, Block b) {
        float yaw = (float) pl.getConfig().getDouble("skrzynie-modele." + key + ".yaw", 0) + (pl.getConfig().getBoolean("skrzynie.odwroc-model", false) ? 180 : 0);
        float sk = (float) pl.getConfig().getDouble("skrzynie.wielkosc-modelu", 1.0);
        Location l = b.getLocation().add(0.5, 0, 0.5);
        l.setYaw(yaw);
        ItemDisplay m = b.getWorld().spawn(l, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER); ItemMeta im = it.getItemMeta(); im.setItemModel(NamespacedKey.fromString("westmc:skrzynia3d/" + typ)); it.setItemMeta(im);
            d.setItemStack(it); d.setPersistent(false);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sk, sk, sk), new AxisAngle4f()));
            d.setViewRange(1.5f);
            d.getPersistentDataContainer().set(KLUCZ, PersistentDataType.STRING, key);
        });
        TextDisplay napis = b.getWorld().spawn(b.getLocation().add(0.5, 1.15 + 0.3 * sk, 0.5), TextDisplay.class, d -> {
            String nz = pl.getConfig().getString("skrzynie." + typ + ".nazwa", typ);
            d.text(net.kyori.adventure.text.Component.text(WestSMP.c(nz + "\n" + pl.getConfig().getString("skrzynie.napis-pod", "&7PPM &8- &fotworz &8| &7LPM &8- &fnagrody"))));
            d.setBillboard(Display.Billboard.CENTER); d.setPersistent(false); d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(90, 10, 6, 24));
            d.setBrightness(new Display.Brightness(15, 15));
            d.getPersistentDataContainer().set(KLUCZ, PersistentDataType.STRING, key);
        });
        return new Entity[]{m, napis};
    }

    /** Animacja przy otwieraniu: skrzynia "podskakuje". */
    void puls(String key) {
        Entity[] t = enc.get(key);
        if (t == null || !(t[0] instanceof ItemDisplay d)) return;
        float sk = (float) pl.getConfig().getDouble("skrzynie.wielkosc-modelu", 1.0);
        d.setInterpolationDelay(0); d.setInterpolationDuration(3);
        d.setTransformation(new Transformation(new Vector3f(0, 0.15f, 0), new AxisAngle4f(), new Vector3f(sk * 1.12f, sk * 1.12f, sk * 1.12f), new AxisAngle4f()));
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (d.isValid()) { d.setInterpolationDelay(0); d.setInterpolationDuration(5);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sk, sk, sk), new AxisAngle4f())); } }, 4L);
    }
}
