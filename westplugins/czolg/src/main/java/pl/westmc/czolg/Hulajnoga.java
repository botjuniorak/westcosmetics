package pl.westmc.czolg;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Hulajnoga elektryczna WestMC: jazda WASD, skret kierownicy, krecace sie kola, przechyl w zakretach, skok na spacji. */
final class Hulajnoga {
    final WestCzolg pl;
    final UUID id = UUID.randomUUID();
    final UUID wlasciciel;
    Location pos;
    float yaw, steer, lean;
    double v, vy;
    float kolo;
    UUID kierowca;
    long pusta;          // od kiedy nikt nie jedzie
    final float s, yOff;
    ItemDisplay root, rama, kier, koloP, koloT;
    Interaction hitbox;
    final Set<Entity> wszystkie = new HashSet<>();

    Hulajnoga(WestCzolg pl, Location at, UUID wlasciciel) {
        this.pl = pl;
        this.wlasciciel = wlasciciel;
        s = (float) pl.getConfig().getDouble("hulajnoga.wielkosc", 0.4);
        yOff = (float) pl.getConfig().getDouble("hulajnoga.wysokosc-siedzenia", 0.45);
        pos = at.clone(); pos.setPitch(0); yaw = at.getYaw();
        World w = at.getWorld();
        root = w.spawn(seat(), ItemDisplay.class, d -> { prep(d); d.setTeleportDuration(2); });
        rama = czesc("rama"); kier = czesc("kier"); koloP = czesc("kolo_p"); koloT = czesc("kolo_t");
        hitbox = w.spawn(pos.clone(), Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.1f);
            i.getPersistentDataContainer().set(pl.key, PersistentDataType.STRING, id.toString());
        });
        for (Entity e : List.of(rama, kier, koloP, koloT)) root.addPassenger(e);
        wszystkie.addAll(List.of(root, rama, kier, koloP, koloT, hitbox));
        pusta = System.currentTimeMillis();
        pose(true);
    }

    private Location seat() { return pos.clone().add(0, yOff, 0); }

    private void prep(ItemDisplay d) {
        d.setPersistent(false);
        d.setViewRange(1.5f);
        d.setShadowRadius(0f);
        d.setRotation(0f, 0f);
        d.getPersistentDataContainer().set(pl.key, PersistentDataType.STRING, id.toString());
    }

    private ItemDisplay czesc(String n) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("westmc:hulajnoga/" + n));
        it.setItemMeta(m);
        return pos.getWorld().spawn(seat(), ItemDisplay.class, d -> {
            prep(d);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        });
    }

    void wsiadz(Player p) {
        kierowca = p.getUniqueId();
        root.addPassenger(p);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.6f);
    }

    void wysiadl() { kierowca = null; pusta = System.currentTimeMillis(); }

    void usun() {
        Player p = kierowca == null ? null : Bukkit.getPlayer(kierowca);
        if (p != null) p.leaveVehicle();
        for (Entity e : wszystkie) if (e.isValid()) e.remove();
        pl.hulajnogi.remove(this);
    }

    private boolean pelny(Block b) { return b.getType().isSolid() && !b.isPassable(); }

    private double ziemia(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (pelny(b)) {
                double top = b.getBoundingBox().getMaxY();
                return Double.isFinite(top) && top > by ? top : by + 1;
            }
        }
        return Double.NaN;
    }

    void tick() {
        if (!root.isValid()) { usun(); return; }
        Player p = kierowca == null ? null : Bukkit.getPlayer(kierowca);
        if (p != null && (!p.isOnline() || p.getVehicle() != root)) { wysiadl(); p = null; }
        var cfg = pl.getConfig();
        if (p == null && System.currentTimeMillis() - pusta > cfg.getLong("hulajnoga.znika-po-sekundach", 120) * 1000L) { usun(); return; }
        double max = cfg.getDouble("hulajnoga.predkosc", 0.45);
        boolean fwd = false, back = false, left = false, right = false, jump = false;
        if (p != null) {
            var in = p.getCurrentInput();
            fwd = in.isForward(); back = in.isBackward(); left = in.isLeft(); right = in.isRight(); jump = in.isJump();
        }
        if (fwd) v = Math.min(max, v + 0.02);
        else if (back) v = Math.max(-0.12, v - 0.04);
        else v *= 0.95;
        if (Math.abs(v) < 0.004) v = 0;
        float turnIn = (right ? 1 : 0) - (left ? 1 : 0);
        float rate = (float) (2.0 + 4.5 * Math.min(1, Math.abs(v) / 0.2));
        yaw += turnIn * rate * (v < 0 ? -1 : 1) * (v == 0 ? 0.5f : 1f);
        steer += (-turnIn * 28f - steer) * 0.35f;
        lean += ((float) (turnIn * 10 * Math.min(1, Math.abs(v) / 0.25)) - lean) * 0.25f;

        World w = pos.getWorld();
        double r = Math.toRadians(yaw);
        Vector dir = new Vector(-Math.sin(r), 0, Math.cos(r));
        if (v != 0) {
            double nx = pos.getX() + dir.getX() * v, nz = pos.getZ() + dir.getZ() * v;
            double gF = ziemia(w, nx + dir.getX() * 0.55 * Math.signum(v), pos.getY(), nz + dir.getZ() * 0.55 * Math.signum(v));
            if (!Double.isNaN(gF) && gF - pos.getY() > 1.05) v = 0;
            else { pos.setX(nx); pos.setZ(nz); }
        }
        double g = ziemia(w, pos.getX(), pos.getY(), pos.getZ());
        boolean naZiemi = !Double.isNaN(g) && pos.getY() - g < 0.05;
        if (jump && naZiemi && vy <= 0) { vy = 0.42; w.playSound(pos, Sound.ENTITY_IRON_GOLEM_STEP, 0.6f, 1.6f); }
        if (vy > 0 || Double.isNaN(g) || pos.getY() > g + 0.01) {
            vy -= 0.06;
            double ny = pos.getY() + vy;
            if (!Double.isNaN(g) && ny <= g) { ny = g; vy = 0; }
            pos.setY(ny);
        } else pos.setY(pos.getY() + (g - pos.getY()) * 0.6);
        if (pos.getY() < w.getMinHeight() - 10) { usun(); return; }

        root.teleport(seat(), TeleportFlag.EntityState.RETAIN_PASSENGERS);
        hitbox.teleport(pos.clone());

        float k = s / 16f;
        float spin = (float) Math.max(-0.9, Math.min(0.9, v / (5.5 * k)));
        kolo -= spin;
        if (Math.abs(v) > 0.3 && naZiemi && System.nanoTime() % 3 == 0) {
            Location tyl = pos.clone().subtract(dir.clone().multiply(0.45));
            w.spawnParticle(Particle.ELECTRIC_SPARK, tyl.add(0, 0.1, 0), 1, 0.05, 0.02, 0.05, 0.02);
        }
        if (Math.abs(v) > 0.05 && System.nanoTime() % 9 == 0) w.playSound(pos, Sound.BLOCK_BEACON_AMBIENT, 0.25f, (float) (1.2 + Math.abs(v)));
        pose(false);
    }

    private static float rad(double d) { return (float) Math.toRadians(d); }

    private Matrix4f baza() { return new Matrix4f().translate(0, -yOff, 0).rotateY(rad(-yaw)).rotateY((float) Math.PI).rotateZ(rad(lean)); }

    private void pose(boolean first) {
        float k = s / 16f;
        set(rama, baza().translate(0, 6 * k, 9 * k).scale(s).rotateY((float) -Math.PI), first);
        Matrix4f kierM = baza().translate(0, 22 * k, -11.75f * k).rotateY(rad(steer));
        set(kier, new Matrix4f(kierM).scale(s).rotateY((float) -Math.PI), first);
        set(koloP, new Matrix4f(kierM).translate(0, -16.5f * k, -2.25f * k).rotateX(kolo).scale(s).rotateY((float) -Math.PI), first);
        set(koloT, baza().translate(0, 5.5f * k, 22 * k).rotateX(kolo).scale(s).rotateY((float) -Math.PI), first);
    }

    private void set(ItemDisplay d, Matrix4f m, boolean first) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(first ? 0 : 2);
        d.setTransformationMatrix(m);
    }
}
