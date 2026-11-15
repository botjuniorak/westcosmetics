package pl.westmc.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Eventowki "mocy": Czarna Dziura, Meteoryt, Speed Orb, Time Watch, Kostka Chaosu. Nie dzialaja na spawnie. */
final class EventowkiMoc implements Listener {
    private final WestSMP pl;
    private final Map<String, Long> cd = new HashMap<>();
    private final Map<UUID, Long> zamrozeni = new HashMap<>();

    EventowkiMoc(WestSMP pl) { this.pl = pl; }

    private double c(String id, String k, double d) { return pl.getConfig().getDouble("eventowki." + id + "." + k, d); }
    private boolean wl(String id) { return pl.getConfig().getBoolean("eventowki." + id + ".wlaczona", true); }
    private boolean spawn(Location l) { return pl.narzedzia != null && pl.narzedzia.naSpawnie(l); }
    private void bar(Player p, String s) { p.sendActionBar(Component.text(WestSMP.c(s))); }

    /** true = mozna uzyc (i ustawia odnowienie). */
    private boolean gotowe(Player p, String id, double domyslnie) {
        String k = p.getUniqueId() + id;
        long now = System.currentTimeMillis(), za = cd.getOrDefault(k, 0L) - now;
        if (za > 0) { bar(p, "&cOdnowienie: &f" + (za / 1000 + 1) + "s"); return false; }
        cd.put(k, now + (long) (c(id, "odnowienie", domyslnie) * 1000));
        return true;
    }

    private boolean swoj(Player a, Player b) {
        if (a.equals(b)) return true;
        if (pl.klany == null) return false;
        String k = pl.klany.klan(a);
        return k != null && k.equals(pl.klany.klan(b));
    }

    // ================================================================= PPM: dziura, meteor, kula
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        String id = pl.eventowki.id(p.getInventory().getItemInMainHand());
        if (id == null || !(id.equals("czarna_dziura") || id.equals("meteor") || id.equals("kula"))) return;
        e.setCancelled(true);
        if (!wl(id)) return;
        if (spawn(p.getLocation())) { bar(p, "&cNa spawnie eventowki nie dzialaja."); return; }
        switch (id) {
            case "kula" -> { if (gotowe(p, id, 60)) kula(p); }
            case "czarna_dziura", "meteor" -> {
                Location cel = cel(p, id.equals("meteor") ? 40 : 25);
                if (spawn(cel)) { bar(p, "&cNie mozesz celowac w spawn."); return; }
                if (!gotowe(p, id, id.equals("meteor") ? 45 : 60)) return;
                if (id.equals("meteor")) meteor(p, cel); else dziura(p, cel);
            }
            default -> { }
        }
    }

    private Location cel(Player p, int zasieg) {
        Block b = p.getTargetBlockExact(zasieg);
        if (b != null) return b.getLocation().add(0.5, 1, 0.5);
        return p.getEyeLocation().add(p.getLocation().getDirection().multiply(Math.min(15, zasieg)));
    }

    private void kula(Player p) {
        int t = (int) (c("kula", "sekund", 10) * 20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, t, (int) c("kula", "szybkosc", 2), false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, t, (int) c("kula", "skok", 1), false, true, true));
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.8f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.2f);
        bar(p, "&#3AD8FF&lSPEED ORB! &7Szybkosc i wysoki skok na &f" + t / 20 + "s");
        final int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (++n[0] * 2 > t || !p.isOnline()) { task.cancel(); return; }
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.2, 0), 3, 0.25, 0.1, 0.25, 0, new Particle.DustOptions(Color.fromRGB(0x3AD8FF), 1.2f));
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 1, 0.3, 0.4, 0.3, 0);
        }, 0L, 2L);
    }

    private void dziura(Player p, Location c0) {
        Location c = c0.clone().add(0, 1.2, 0);
        World w = c.getWorld();
        double r = c("czarna_dziura", "promien", 8), sila = c("czarna_dziura", "sila", 0.35), dmg = c("czarna_dziura", "obrazenia", 1.5);
        int ticks = (int) (c("czarna_dziura", "sekund", 5) * 20);
        ItemDisplay model = w.spawn(c, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER); ItemMeta m = it.getItemMeta(); m.setItemModel(NamespacedKey.fromString("westmc:event/czarna_dziura")); it.setItemMeta(m);
            d.setItemStack(it); d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.2f, 0.2f, 0.2f), new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(pl, () -> { model.setInterpolationDelay(0); model.setInterpolationDuration(10);
            model.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(2.6f, 2.6f, 2.6f), new AxisAngle4f())); }, 2L);
        w.playSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
        w.playSound(c, Sound.BLOCK_PORTAL_TRIGGER, 1.2f, 0.6f);
        final int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            n[0] += 2;
            if (n[0] > ticks) {
                task.cancel();
                model.setInterpolationDelay(0); model.setInterpolationDuration(8);
                model.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 120, 0.5, 0.5, 0.5, 0.6);
                w.playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
                Bukkit.getScheduler().runTaskLater(pl, model::remove, 10L);
                return;
            }
            double a = n[0] * 0.35;
            for (int i = 0; i < 3; i++) {
                double aa = a + i * 2.094, rr = 3 - (n[0] % 20) * 0.14;
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(aa) * rr, Math.sin(aa * 0.5) * 0.3, Math.sin(aa) * rr), 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(0xA46BFF), 1.4f));
            }
            w.spawnParticle(Particle.PORTAL, c, 25, 1.5, 1, 1.5, 1.2);
            if (n[0] % 20 == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 1.5f, 0.5f);
            for (Player t : w.getPlayers()) {
                if (swoj(p, t) || t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                double d = t.getLocation().distance(c);
                if (d > r || spawn(t.getLocation())) continue;
                Vector v = c.toVector().subtract(t.getLocation().toVector());
                if (v.lengthSquared() < 0.01) continue;
                t.setVelocity(t.getVelocity().multiply(0.5).add(v.normalize().multiply(sila * (1.2 - d / r * 0.6))));
                if (d < 1.8 && n[0] % 20 == 0) t.damage(dmg, p);
            }
        }, 4L, 2L);
    }

    private void meteor(Player p, Location cel) {
        World w = cel.getWorld();
        Location start = cel.clone().add(-10, 28, -6);
        int lot = 24;
        ItemDisplay m = w.spawn(start, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER); ItemMeta im = it.getItemMeta(); im.setItemModel(NamespacedKey.fromString("westmc:event/meteor")); it.setItemMeta(im);
            d.setItemStack(it); d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setBrightness(new Display.Brightness(15, 15)); d.setTeleportDuration(2);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(2.8f, 2.8f, 2.8f), new AxisAngle4f()));
        });
        w.playSound(cel, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.4f);
        for (Player t : w.getPlayers()) if (t.getLocation().distanceSquared(cel) < 40 * 40) bar(t, "&#FF7A2A&lMETEORYT NADLATUJE! &7Uciekaj!");
        final int[] n = {0};
        Vector krok = cel.toVector().subtract(start.toVector()).multiply(1.0 / lot);
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            n[0]++;
            Location l = start.clone().add(krok.clone().multiply(n[0]));
            m.teleport(l);
            w.spawnParticle(Particle.FLAME, l, 12, 0.5, 0.5, 0.5, 0.04);
            w.spawnParticle(Particle.LARGE_SMOKE, l, 4, 0.4, 0.4, 0.4, 0.01);
            w.spawnParticle(Particle.LAVA, l, 2, 0.3, 0.3, 0.3, 0);
            if (n[0] >= lot) {
                task.cancel(); m.remove();
                w.spawnParticle(Particle.EXPLOSION_EMITTER, cel, 2);
                w.spawnParticle(Particle.FLAME, cel, 120, 2, 0.5, 2, 0.15);
                w.spawnParticle(Particle.LAVA, cel, 30, 2, 0.5, 2, 0);
                w.playSound(cel, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
                w.playSound(cel, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f, 0.6f);
                double r = c("meteor", "promien", 4), dmg = c("meteor", "obrazenia", 8);
                for (Player t : w.getPlayers()) {
                    if (swoj(p, t) || t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE || spawn(t.getLocation())) continue;
                    double d = t.getLocation().distance(cel);
                    if (d > r) continue;
                    t.damage(dmg * (1 - d / r * 0.5), p);
                    Vector v = t.getLocation().toVector().subtract(cel.toVector()).setY(0);
                    if (v.lengthSquared() > 0.01) v.normalize(); 
                    t.setVelocity(v.multiply(1.1).setY(0.7));
                    t.setFireTicks(60);
                }
            }
        }, 1L, 1L);
    }

    // ================================================================= uderzenie: zegarek, kostka
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof Player t)) return;
        String id = pl.eventowki.id(p.getInventory().getItemInMainHand());
        if (id == null || !(id.equals("zegarek") || id.equals("kostka")) || !wl(id)) return;
        if (spawn(p.getLocation()) || spawn(t.getLocation()) || swoj(p, t)) return;
        if (id.equals("zegarek")) {
            if (!gotowe(p, id, 40)) return;
            long ms = (long) (c("zegarek", "sekund", 3) * 1000);
            zamrozeni.put(t.getUniqueId(), System.currentTimeMillis() + ms);
            t.showTitle(Title.title(Component.text(WestSMP.c("&#FFD84A&lZATRZYMANY!")), Component.text(WestSMP.c("&7" + p.getName() + " zatrzymal cie w czasie")),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(ms), Duration.ofMillis(300))));
            bar(p, "&#FFD84A&lTIME WATCH! &7Zatrzymales &f" + t.getName());
            final int[] n = {0};
            Bukkit.getScheduler().runTaskTimer(pl, task -> {
                n[0] += 5;
                if (!t.isOnline() || zamrozeni.getOrDefault(t.getUniqueId(), 0L) < System.currentTimeMillis()) { zamrozeni.remove(t.getUniqueId()); task.cancel(); return; }
                t.getWorld().spawnParticle(Particle.SNOWFLAKE, t.getLocation().add(0, 1, 0), 8, 0.4, 0.6, 0.4, 0.01);
                t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, 1, 0), 6, 0.45, 0.7, 0.45, 0, new Particle.DustOptions(Color.fromRGB(0xFFD84A), 1f));
                if (n[0] % 10 == 0) t.getWorld().playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, n[0] % 20 == 0 ? 1.4f : 1.1f);
            }, 0L, 5L);
            t.getWorld().playSound(t.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.6f);
        } else {
            if (!gotowe(p, id, 30)) return;
            List<ItemStack> l = new ArrayList<>();
            for (int i = 0; i < 9; i++) l.add(t.getInventory().getItem(i));
            Collections.shuffle(l);
            for (int i = 0; i < 9; i++) t.getInventory().setItem(i, l.get(i));
            t.showTitle(Title.title(Component.text(WestSMP.c("&#FF4AD8&l?!")), Component.text(WestSMP.c("&7Twoj ekwipunek zostal &fpomieszany&7!")),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
            t.getWorld().spawnParticle(Particle.WITCH, t.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
            t.getWorld().playSound(t.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.3f);
            bar(p, "&#FF4AD8&lKOSTKA CHAOSU! &7Pomieszales ekwipunek &f" + t.getName());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Long za = zamrozeni.get(e.getPlayer().getUniqueId());
        if (za == null) return;
        if (za < System.currentTimeMillis()) { zamrozeni.remove(e.getPlayer().getUniqueId()); return; }
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getY() < t.getY() || f.getZ() != t.getZ()) {
            Location n = f.clone(); n.setYaw(t.getYaw()); n.setPitch(t.getPitch());
            if (t.getY() < f.getY()) n.setY(t.getY());   // spadac moze
            e.setTo(n);
        }
    }
}
