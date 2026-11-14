package pl.westmc.czolg;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Czolg administracji: model 3D z paczki (kadlub, gasienice, wieza, lufa), jazda WASD,
 * wieza za wzrokiem, strzal (LPM / spacja) z wybuchem i odrzutem graczy - bez niszczenia blokow.
 */
public final class WestCzolg extends JavaPlugin implements Listener {
    NamespacedKey key;
    final List<Czolg> czolgi = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "czolg");
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Czolg c : new ArrayList<>(czolgi)) c.tick(); }, 1L, 1L);
    }

    @Override
    public void onDisable() { for (Czolg c : new ArrayList<>(czolgi)) c.usun(); }

    static String c(String s) { return s.replace('&', '§'); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("usun")) {
            Czolg best = null; double bd = 15 * 15;
            for (Czolg c : czolgi) {
                if (c.pos.getWorld() != p.getWorld()) continue;
                double d = c.pos.distanceSquared(p.getLocation());
                if (d < bd) { bd = d; best = c; }
            }
            if (best == null) p.sendMessage(c("&cNie ma czolgu w poblizu."));
            else { best.usun(); p.sendMessage(c("&aUsunieto czolg.")); }
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("usunwszystkie")) {
            int n = czolgi.size();
            for (Czolg c : new ArrayList<>(czolgi)) c.usun();
            p.sendMessage(c("&aUsunieto czolgi: " + n));
            return true;
        }
        if (p.getVehicle() != null) { p.sendMessage(c("&cNajpierw wysiadz (SHIFT).")); return true; }
        Czolg c = new Czolg(this, p.getLocation());
        czolgi.add(c);
        c.wsiadz(p);
        p.sendMessage(c("&6&lCZOLG &8» &fW/S &7jazda, &fA/D &7skret, &fLPM/SPACJA &7strzal, &fSHIFT &7wysiadka."));
        return true;
    }

    Czolg czolgOf(Entity e) {
        String v = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (v == null) return null;
        for (Czolg c : czolgi) if (c.id.toString().equals(v)) return c;
        return null;
    }

    Czolg czolgKierowcy(Player p) {
        for (Czolg c : czolgi) if (p.getUniqueId().equals(c.kierowca)) return c;
        return null;
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Czolg c = czolgOf(e.getRightClicked());
        if (c == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("westczolg.admin")) { p.sendMessage(c("&cTym czolgiem jezdzi tylko administracja.")); return; }
        if (c.kierowca != null) { p.sendMessage(c("&cKtos juz jedzie tym czolgiem.")); return; }
        if (p.getVehicle() != null) return;
        c.wsiadz(p);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Czolg c = czolgKierowcy(e.getPlayer());
        if (c != null) c.strzal();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Czolg c = czolgKierowcy(e.getPlayer());
        if (c != null) c.wysiadl();
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (czolgOf(e.getEntity()) != null) e.setCancelled(true);
    }

    // =====================================================================================
    static final class Czolg {
        final WestCzolg pl;
        final UUID id = UUID.randomUUID();
        Location pos;              // srodek dolu czolgu
        float yaw;                 // kadlub (stopnie MC)
        float wiezaYaw, lufaPitch; // wieza: stopnie MC (swiat), lufa: stopnie w gore
        double v;                  // predkosc
        UUID kierowca;
        int odrzut;                // klatki animacji odrzutu lufy
        long ostatniStrzal;
        boolean skok;
        float fall;
        final float s;
        final float yOff;
        ItemDisplay root, kadlub, gasienice, wieza, lufa;
        Interaction hitbox;
        final Set<Entity> wszystkie = new HashSet<>();

        Czolg(WestCzolg pl, Location at) {
            this.pl = pl;
            this.s = (float) pl.getConfig().getDouble("wielkosc", 2.0);
            this.yOff = 20f / 16f * s - 0.6f;    // fotel: kierowca wystaje z wlazu
            pos = at.clone(); pos.setPitch(0);
            yaw = at.getYaw(); wiezaYaw = yaw;
            World w = at.getWorld();
            root = w.spawn(seat(), ItemDisplay.class, d -> { prep(d); d.setTeleportDuration(2); });
            kadlub = czesc("kadlub"); gasienice = czesc("gasienice"); wieza = czesc("wieza"); lufa = czesc("lufa");
            hitbox = w.spawn(pos.clone(), Interaction.class, i -> {
                i.setPersistent(false);
                i.setInteractionWidth(2.6f * s / 2f);
                i.setInteractionHeight(1.6f * s / 2f);
                i.getPersistentDataContainer().set(pl.key, PersistentDataType.STRING, id.toString());
            });
            for (Entity e : List.of(kadlub, gasienice, wieza, lufa)) root.addPassenger(e);
            wszystkie.addAll(List.of(root, kadlub, gasienice, wieza, lufa, hitbox));
            pose(true);
        }

        private Location seat() { return pos.clone().add(0, yOff, 0); }

        private void prep(ItemDisplay d) {
            d.setPersistent(false);
            d.setViewRange(2f);
            d.setShadowRadius(0f);
            d.setRotation(0f, 0f);
            d.getPersistentDataContainer().set(pl.key, PersistentDataType.STRING, id.toString());
        }

        private ItemDisplay czesc(String n) {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setItemModel(NamespacedKey.fromString("westmc:czolg/" + n));
            it.setItemMeta(m);
            return pos.getWorld().spawn(seat(), ItemDisplay.class, d -> {
                prep(d);
                d.setItemStack(it);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBrightness(null);
            });
        }

        void wsiadz(Player p) {
            kierowca = p.getUniqueId();
            root.addPassenger(p);
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
        }

        void wysiadl() { kierowca = null; v = 0; }

        void usun() {
            Player p = kierowca == null ? null : Bukkit.getPlayer(kierowca);
            if (p != null) p.leaveVehicle();
            for (Entity e : wszystkie) if (e.isValid()) e.remove();
            pl.czolgi.remove(this);
        }

        // ------------------------------------------------------------------ fizyka
        private boolean pelny(Block b) { return b.getType().isSolid() && !b.isPassable(); }

        /** Wysokosc podloza pod punktem (szuka od y+1.6 w dol do y-4), NaN gdy brak. */
        private double ziemia(World w, double x, double y, double z) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            for (int by = (int) Math.floor(y + 1.6); by >= (int) Math.floor(y) - 4; by--) {
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
            double max = cfg.getDouble("predkosc", 0.32), maxT = cfg.getDouble("predkosc-tyl", 0.14);
            float turn = (float) cfg.getDouble("skret", 3.0);
            boolean fwd = false, back = false, left = false, right = false, jump = false;
            if (p != null) {
                var in = p.getCurrentInput();
                fwd = in.isForward(); back = in.isBackward(); left = in.isLeft(); right = in.isRight(); jump = in.isJump();
                // wieza za wzrokiem
                float target = p.getLocation().getYaw();
                float diff = wrap(target - wiezaYaw);
                wiezaYaw += Math.max(-4.5f, Math.min(4.5f, diff));
                lufaPitch += (Math.max(-8f, Math.min(22f, -p.getLocation().getPitch())) - lufaPitch) * 0.3f;
                if (jump && !skok) strzal();
                skok = jump;
            }
            if (fwd) v = Math.min(max, v + 0.025);
            else if (back) v = Math.max(-maxT, v - 0.02);
            else v *= 0.86;
            if (Math.abs(v) < 0.003) v = 0;
            if (left) { yaw -= turn; wiezaYaw -= turn; }
            if (right) { yaw += turn; wiezaYaw += turn; }

            World w = pos.getWorld();
            double r = Math.toRadians(yaw);
            Vector dir = new Vector(-Math.sin(r), 0, Math.cos(r));
            if (v != 0) {
                double half = 1.45 * s / 2.0 * Math.signum(v);
                double nx = pos.getX() + dir.getX() * v, nz = pos.getZ() + dir.getZ() * v;
                // przeszkoda przed czolgiem: max 1 kratka w gore
                double gFront = ziemia(w, nx + dir.getX() * half, pos.getY(), nz + dir.getZ() * half);
                if (!Double.isNaN(gFront) && gFront - pos.getY() > 1.05) v = 0;
                else { pos.setX(nx); pos.setZ(nz); }
            }
            double g = ziemia(w, pos.getX(), pos.getY(), pos.getZ());
            if (Double.isNaN(g) || g < pos.getY() - 0.01) { fall = Math.min(1.2f, fall + 0.08f); pos.setY(Double.isNaN(g) ? pos.getY() - fall : Math.max(g, pos.getY() - fall)); }
            else { fall = 0; pos.setY(pos.getY() + (g - pos.getY()) * 0.5); }
            if (pos.getY() < w.getMinHeight() - 10) { usun(); return; }

            root.teleport(seat(), TeleportFlag.EntityState.RETAIN_PASSENGERS);
            hitbox.teleport(pos.clone());

            // kurz spod gasienic + przepychanie graczy
            if (Math.abs(v) > 0.05) {
                double sx = Math.cos(r) * 1.0 * s / 2, sz = Math.sin(r) * 1.0 * s / 2;
                Location back2 = pos.clone().subtract(dir.clone().multiply(1.3 * s / 2 * Math.signum(v)));
                w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, back2.clone().add(sx, 0.2, sz), 0, 0, 0.03, 0, 1);
                w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, back2.clone().add(-sx, 0.2, -sz), 0, 0, 0.03, 0, 1);
                if (System.currentTimeMillis() % 6 == 0) w.playSound(pos, Sound.ENTITY_MINECART_RIDING, 0.5f, 0.5f);
                for (Entity e : w.getNearbyEntities(pos.clone().add(dir.clone().multiply(1.2 * s / 2)), 1.4 * s / 2, 1.5, 1.4 * s / 2)) {
                    if (e instanceof LivingEntity && !wszystkie.contains(e) && e != p)
                        e.setVelocity(dir.clone().multiply(Math.abs(v) * 2.2 * Math.signum(v)).setY(0.25));
                }
            }
            if (odrzut > 0) odrzut--;
            pose(false);
        }

        private static float wrap(float a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

        // ------------------------------------------------------------------ uklad czesci
        private static float rad(double d) { return (float) Math.toRadians(d); }

        /** Macierz w ukladzie encji (yaw 0): przod modelu = -z, ItemDisplay obraca model o 180 stopni. */
        private Matrix4f baza() { return new Matrix4f().translate(0, -yOff, 0).rotateY(rad(-yaw)).rotateY((float) Math.PI); }

        private Matrix4f wiezaM() {
            float k = s / 16f;
            return baza().translate(0, 12 * k, -1 * k).rotateY(rad(-(wiezaYaw - yaw)));
        }

        private Matrix4f lufaM() {
            float k = s / 16f;
            float kick = odrzut > 0 ? (odrzut / 6f) * 2.5f * k : 0;
            return wiezaM().translate(0, 4 * k, -11 * k).rotateX(rad(lufaPitch)).translate(0, 0, kick);
        }

        private void pose(boolean first) {
            float k = s / 16f;
            set(kadlub, baza().translate(0, 0, -1 * k).scale(s).rotateY((float) -Math.PI), first);
            set(gasienice, baza().scale(s).rotateY((float) -Math.PI), first);
            set(wieza, new Matrix4f(wiezaM()).scale(s).rotateY((float) -Math.PI), first);
            set(lufa, new Matrix4f(lufaM()).scale(s).rotateY((float) -Math.PI), first);
        }

        private void set(ItemDisplay d, Matrix4f m, boolean first) {
            if (d == null || !d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(first ? 0 : 2);
            d.setTransformationMatrix(m);
        }

        // ------------------------------------------------------------------ strzal
        void strzal() {
            var cfg = pl.getConfig();
            long now = System.currentTimeMillis();
            if (now - ostatniStrzal < cfg.getLong("strzal.co-ile-ms", 1800)) return;
            ostatniStrzal = now;
            Player p = kierowca == null ? null : Bukkit.getPlayer(kierowca);
            float k = s / 16f;
            Matrix4f m = lufaM();
            Vector3f wylot = m.transformPosition(new Vector3f(0, 0, -23 * k));   // wylot lufy (przod modelu = -z)
            Vector3f kier = m.transformDirection(new Vector3f(0, 0, -1)).normalize();
            Location start = seat().add(wylot.x, wylot.y, wylot.z);
            Vector dir = new Vector(kier.x, kier.y, kier.z);
            World w = pos.getWorld();
            odrzut = 6;
            w.playSound(start, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.6f);
            w.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 2f, 0.5f);
            w.spawnParticle(Particle.FLAME, start, 25, 0.25, 0.25, 0.25, 0.08);
            w.spawnParticle(Particle.LARGE_SMOKE, start, 20, 0.3, 0.3, 0.3, 0.05);
            w.spawnParticle(Particle.FLASH, start, 1, 0, 0, 0, 0);
            double zasieg = cfg.getDouble("strzal.zasieg", 80);
            Set<Entity> skip = new HashSet<>(wszystkie);
            if (p != null) skip.add(p);
            RayTraceResult hit = w.rayTrace(start, dir, zasieg, FluidCollisionMode.NEVER, true, 0.6, e -> !skip.contains(e) && e instanceof LivingEntity);
            Location end = hit != null ? hit.getHitPosition().toLocation(w) : start.clone().add(dir.clone().multiply(zasieg));
            // smuga pocisku
            double len = end.distance(start);
            for (double t = 1; t < len; t += 1.2) w.spawnParticle(Particle.SMOKE, start.clone().add(dir.clone().multiply(t)), 1, 0.03, 0.03, 0.03, 0);
            if (hit == null) return;
            // wybuch bez niszczenia blokow
            w.spawnParticle(Particle.EXPLOSION_EMITTER, end, 1);
            w.spawnParticle(Particle.LAVA, end, 12, 0.5, 0.3, 0.5, 0);
            w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, end, 8, 0.6, 0.3, 0.6, 0.02);
            w.playSound(end, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.8f);
            double rad = cfg.getDouble("strzal.promien", 5), sila = cfg.getDouble("strzal.sila", 1.8), up = cfg.getDouble("strzal.w-gore", 0.7);
            double dmg = cfg.getDouble("strzal.obrazenia", 0);
            boolean admin = cfg.getBoolean("strzal.odrzuca-admina", false);
            for (Entity e : w.getNearbyEntities(end, rad, rad, rad)) {
                if (!(e instanceof LivingEntity le) || wszystkie.contains(e)) continue;
                if (e == p && !admin) continue;
                Vector d = e.getLocation().toVector().subtract(end.toVector());
                double dist = Math.max(0.5, d.length());
                double f = sila * (1 - Math.min(1, dist / (rad + 0.5))) + 0.35;
                if (d.lengthSquared() < 1e-4) d = new Vector(0, 1, 0);
                e.setVelocity(d.normalize().multiply(f).setY(up + f * 0.25));
                if (dmg > 0) le.damage(dmg, p);
            }
        }
    }
}
