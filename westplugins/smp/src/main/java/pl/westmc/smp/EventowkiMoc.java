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

    private final java.io.File plikSak;
    private final org.bukkit.configuration.file.YamlConfiguration sak;
    private final NamespacedKey SAK_ID, EX_KILLE, HYDRO;

    EventowkiMoc(WestSMP pl) {
        this.pl = pl;
        plikSak = new java.io.File(pl.getDataFolder(), "sakiewki.yml");
        sak = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(plikSak);
        SAK_ID = new NamespacedKey(pl, "sakiewka_id"); EX_KILLE = new NamespacedKey(pl, "ex_kille"); HYDRO = new NamespacedKey(pl, "hydro");
    }

    private void zapiszSak() { try { sak.save(plikSak); } catch (Exception e) { pl.getLogger().warning("sakiewki.yml: " + e.getMessage()); } }

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
        if (id == null || !(id.equals("czarna_dziura") || id.equals("meteor") || id.equals("kula") || id.equals("hydro") || id.equals("sakiewka"))) return;
        e.setCancelled(true);
        if (!wl(id)) return;
        if (id.equals("sakiewka")) { otworzSakiewke(p, p.getInventory().getItemInMainHand()); return; }
        if (spawn(p.getLocation())) { bar(p, "&cNa spawnie eventowki nie dzialaja."); return; }
        switch (id) {
            case "kula" -> { if (gotowe(p, id, 60)) kula(p); }
            case "hydro" -> { if (gotowe(p, id, 45)) hydroStrzal(p); }
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
        World w = c0.getWorld();
        Location c = c0.clone(); c.setY(Math.floor(c.getY()) + 0.06);
        double r = c("czarna_dziura", "promien", 5), sila = c("czarna_dziura", "sila", 0.35), dmg = c("czarna_dziura", "obrazenia", 1.5);
        float sk = (float) (r * 1.3);
        int ticks = (int) (c("czarna_dziura", "sekund", 5) * 20);
        Location lm = c.clone(); lm.setYaw(0); lm.setPitch(0);
        ItemDisplay model = w.spawn(lm, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER); ItemMeta m = it.getItemMeta(); m.setItemModel(NamespacedKey.fromString("westmc:event/dziura_kolo")); it.setItemMeta(m);
            d.setItemStack(it); d.setPersistent(false); d.setBillboard(Display.Billboard.FIXED); d.setBrightness(new Display.Brightness(15, 15));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) -Math.PI / 2, 1, 0, 0), new Vector3f(0.05f, 0.05f, 0.05f), new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(pl, () -> { model.setInterpolationDelay(0); model.setInterpolationDuration(12);
            model.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) -Math.PI / 2, 1, 0, 0), new Vector3f(sk, sk, sk), new AxisAngle4f())); }, 2L);
        w.playSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
        w.playSound(c, Sound.BLOCK_PORTAL_TRIGGER, 1.2f, 0.6f);
        final int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            n[0] += 2;
            if (n[0] > ticks) {
                task.cancel();
                model.setInterpolationDelay(0); model.setInterpolationDuration(10);
                model.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) -Math.PI / 2, 1, 0, 0), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
                w.spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(0, 0.3, 0), 120, r * 0.4, 0.2, r * 0.4, 0.4);
                w.playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
                Bukkit.getScheduler().runTaskLater(pl, model::remove, 12L);
                return;
            }
            // wir czasteczek po ziemi do srodka
            for (int i = 0; i < 6; i++) {
                double aa = n[0] * 0.25 + i * Math.PI / 3, rr = r * (1 - ((n[0] * 3 + i * 7) % 20) / 20.0);
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(aa) * rr, 0.15, Math.sin(aa) * rr), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(0xA46BFF), 1.3f));
            }
            w.spawnParticle(Particle.PORTAL, c.clone().add(0, 0.4, 0), 12, r * 0.3, 0.1, r * 0.3, 0.6);
            if (n[0] % 20 == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 1.5f, 0.5f);
            for (Player t : w.getPlayers()) {
                if (swoj(p, t) || t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                Location tl = t.getLocation();
                double dx = c.getX() - tl.getX(), dz = c.getZ() - tl.getZ(), d = Math.sqrt(dx * dx + dz * dz);
                if (d > r || Math.abs(tl.getY() - c.getY()) > 4 || spawn(tl)) continue;
                if (d > 0.3) t.setVelocity(t.getVelocity().multiply(0.4).add(new Vector(dx / d, -0.05, dz / d).multiply(sila * (1.3 - d / r * 0.6))));
                if (d < 1.2 && n[0] % 20 == 0) t.damage(dmg, p);
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
        if (id == null || !(id.equals("zegarek") || id.equals("kostka") || id.equals("siekiera_grincha")) || !wl(id)) return;
        if (spawn(p.getLocation()) || spawn(t.getLocation()) || swoj(p, t)) return;
        if (id.equals("siekiera_grincha")) {
            if (!gotowe(p, id, 60)) return;
            t.getWorld().strikeLightningEffect(t.getLocation());
            double h = t.getHealth(), zab = h * c("siekiera_grincha", "procent", 30) / 100.0;
            Bukkit.getScheduler().runTask(pl, () -> { if (t.isValid() && !t.isDead()) t.setHealth(Math.max(0.5, t.getHealth() - zab)); });
            t.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, t.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0);
            bar(p, "&#4EE08F&lSIEKIERA GRINCHA! &7Zabrano &c" + String.format(java.util.Locale.ROOT, "%.1f", zab / 2) + " ❤ &7graczowi &f" + t.getName());
            return;
        }
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

    // ================================================================= EXCALIBUR
    private int kille(ItemStack it) { return it.getItemMeta().getPersistentDataContainer().getOrDefault(EX_KILLE, org.bukkit.persistence.PersistentDataType.INTEGER, 0); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExDmg(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!"excalibur".equals(pl.eventowki.id(it)) || !wl("excalibur")) return;
        int prog = Math.max(1, pl.getConfig().getInt("eventowki.excalibur.zabojstw-do-pelna", 100));
        e.setDamage(e.getDamage() + Math.min(1, (double) kille(it) / prog) * c("excalibur", "max-bonus", 4.0));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExKill(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k.equals(e.getEntity())) return;
        ItemStack it = k.getInventory().getItemInMainHand();
        if (!"excalibur".equals(pl.eventowki.id(it))) return;
        int n = kille(it) + 1;
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(EX_KILLE, org.bukkit.persistence.PersistentDataType.INTEGER, n);
        List<String> lore = m.getLore() == null ? new ArrayList<>() : new ArrayList<>(m.getLore());
        int i = -1;
        for (int j = 0; j < lore.size(); j++) if (lore.get(j).contains("Zabojstwa:")) { i = j; break; }
        List<String> nowe = pl.eventowki.excaliburLore(n);
        if (i > 0 && i + 2 < lore.size()) { for (int j = 0; j < 4; j++) lore.set(i - 1 + j, nowe.get(j)); m.setLore(lore); }
        it.setItemMeta(m);
        int prog = Math.max(1, pl.getConfig().getInt("eventowki.excalibur.zabojstw-do-pelna", 100));
        if (n == prog) { bar(k, "&#FFD84A&lEXCALIBUR NALADOWANY! &7Pelna moc!"); k.getWorld().playSound(k.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); }
    }

    // ================================================================= SAKIEWKA DROPU
    static final class SakMenu implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv; String id;
        @Override public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }

    private String sakId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(SAK_ID, org.bukkit.persistence.PersistentDataType.STRING);
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> zawartosc(String id) {
        List<ItemStack> r = new ArrayList<>();
        List<?> l = sak.getList(id);
        if (l != null) for (Object o : l) r.add(o instanceof ItemStack i ? i : null);
        while (r.size() < 54) r.add(null);
        return r;
    }

    private void otworzSakiewke(Player p, ItemStack it) {
        String id = sakId(it);
        if (id == null) {   // stara sakiewka bez id
            id = UUID.randomUUID().toString();
            ItemMeta m = it.getItemMeta(); m.getPersistentDataContainer().set(SAK_ID, org.bukkit.persistence.PersistentDataType.STRING, id); it.setItemMeta(m);
        }
        SakMenu h = new SakMenu(); h.id = id;
        h.inv = Bukkit.createInventory(h, 54, Component.text(WestSMP.c("&#E8A95A&lSakiewka Dropu")));
        List<ItemStack> z = zawartosc(id);
        for (int i = 0; i < 54; i++) h.inv.setItem(i, z.get(i));
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_DROP_CONTENTS, 1f, 1f);
    }

    @EventHandler
    public void onSakClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SakMenu h)) return;
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack i : h.inv.getContents()) l.add(i);
        sak.set(h.id, l); zapiszSak();
    }

    @EventHandler
    public void onSakClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SakMenu)) return;
        ItemStack c = e.getCurrentItem(), cur = e.getCursor();
        // nie wolno wkladac sakiewki do sakiewki
        if ("sakiewka".equals(pl.eventowki.id(c)) && e.getClickedInventory() != e.getInventory() && e.isShiftClick()) e.setCancelled(true);
        if ("sakiewka".equals(pl.eventowki.id(cur)) && e.getClickedInventory() == e.getInventory()) e.setCancelled(true);
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getClickedInventory() == e.getInventory()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSakDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k.equals(e.getEntity()) || e.getKeepInventory() || !wl("sakiewka")) return;
        ItemStack s = null;
        for (ItemStack i : k.getInventory().getContents()) if ("sakiewka".equals(pl.eventowki.id(i))) { s = i; break; }
        if (s == null) return;
        String id = sakId(s);
        if (id == null) { id = UUID.randomUUID().toString(); ItemMeta m = s.getItemMeta(); m.getPersistentDataContainer().set(SAK_ID, org.bukkit.persistence.PersistentDataType.STRING, id); s.setItemMeta(m); }
        // jesli ktos ma ja otwarta - zapisz najpierw
        for (Player o : Bukkit.getOnlinePlayers()) if (o.getOpenInventory().getTopInventory().getHolder() instanceof SakMenu h && h.id.equals(id)) o.closeInventory();
        List<ItemStack> z = zawartosc(id);
        int ile = 0;
        java.util.Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack d = it.next();
            int wolne = z.indexOf(null);
            if (wolne < 0) break;
            z.set(wolne, d); it.remove(); ile++;
        }
        sak.set(id, z); zapiszSak();
        if (ile > 0) { bar(k, "&#E8A95A&lSAKIEWKA! &7Przedmioty &f" + e.getEntity().getName() + " &7trafily do sakiewki (&f" + ile + "&7)"); k.playSound(k.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1f, 1f); }
    }

    // ================================================================= HYDRO KLATKA
    private void hydroStrzal(Player p) {
        org.bukkit.entity.Snowball sb = p.launchProjectile(org.bukkit.entity.Snowball.class, p.getLocation().getDirection().multiply(1.8));
        sb.setItem(new ItemStack(Material.HEART_OF_THE_SEA));
        sb.getPersistentDataContainer().set(HYDRO, org.bukkit.persistence.PersistentDataType.STRING, p.getUniqueId().toString());
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1f, 1.4f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.8f, 1.2f);
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (!sb.isValid()) { task.cancel(); return; }
            sb.getWorld().spawnParticle(Particle.BUBBLE_POP, sb.getLocation(), 4, 0.1, 0.1, 0.1, 0.02);
            sb.getWorld().spawnParticle(Particle.DRIPPING_WATER, sb.getLocation(), 2, 0.1, 0.1, 0.1, 0);
        }, 1L, 1L);
    }

    private final Map<Block, Long> klatki = new HashMap<>();

    @EventHandler
    public void onHydroHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        String w = e.getEntity().getPersistentDataContainer().get(HYDRO, org.bukkit.persistence.PersistentDataType.STRING);
        if (w == null) return;
        final Location c = e.getHitBlock() != null ? e.getHitBlock().getLocation().add(0.5, 1, 0.5) : e.getEntity().getLocation();
        if (spawn(c)) return;
        c.getWorld().spawnParticle(Particle.SPLASH, c, 200, 3, 0.5, 3, 0.3);
        c.getWorld().playSound(c, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.5f, 1f);
        c.getWorld().playSound(c, Sound.BLOCK_CONDUIT_ACTIVATE, 1.5f, 1.2f);
        double r = c("hydro", "promien", 5);
        int wys = (int) c("hydro", "wysokosc", 5);
        long ms = (long) (c("hydro", "sekund", 10) * 1000);
        Material[] wzor = {Material.LIGHT_BLUE_GLAZED_TERRACOTTA, Material.CYAN_GLAZED_TERRACOTTA, Material.BLUE_GLAZED_TERRACOTTA};
        List<Block> nowe = new ArrayList<>();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int R = (int) Math.ceil(r);
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            boolean sciana = d > r - 1 && d <= r, wnetrze = d <= r;
            if (!wnetrze) continue;
            for (int dy = 0; dy <= wys; dy++) {
                Material m;
                if (dy == wys) m = (dx == 0 && dz == 0) ? Material.SEA_LANTERN : ((dx + dz) % 3 == 0 ? Material.LIGHT_BLUE_STAINED_GLASS : Material.PRISMARINE_BRICKS);
                else if (!sciana) continue;
                else if (dy == 0) m = Material.DARK_PRISMARINE;
                else if (dy == wys - 1) m = Material.PRISMARINE_BRICKS;
                else if (dy == 2 && (Math.abs(dx) + Math.abs(dz)) % 3 == 0) m = Material.LIGHT_BLUE_STAINED_GLASS;
                else if (dy == 1 && (dx * 7 + dz * 3) % 5 == 0) m = Material.SEA_LANTERN;
                else m = wzor[Math.floorMod(dx + dz + dy, 3)];
                Block b = c.getWorld().getBlockAt(cx + dx, cy + dy, cz + dz);
                if (!b.getType().isAir() || spawn(b.getLocation())) continue;
                b.setType(m, false);
                klatki.put(b, System.currentTimeMillis() + ms);
                nowe.add(b);
            }
        }
        Player p = Bukkit.getPlayer(UUID.fromString(w));
        for (Player t : c.getWorld().getPlayers()) {
            if (p != null && swoj(p, t)) continue;
            Location tl = t.getLocation();
            if (Math.hypot(tl.getX() - c.getX(), tl.getZ() - c.getZ()) > r || Math.abs(tl.getY() - c.getY()) > wys) continue;
            t.showTitle(Title.title(Component.text(WestSMP.c("&#3AD8FF&lHYDRO KLATKA!")), Component.text(WestSMP.c("&7Jestes uwieziony na &f" + ms / 1000 + "s")),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
        }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            for (Block b : nowe) { if (klatki.remove(b) != null) b.setType(Material.AIR, false); }
            c.getWorld().spawnParticle(Particle.SPLASH, c.clone().add(0, 1, 0), 200, r * 0.6, 1.5, r * 0.6, 0.3);
            c.getWorld().playSound(c, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.5f, 1f);
        }, ms / 50);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKlatkaBreak(org.bukkit.event.block.BlockBreakEvent e) { if (klatki.containsKey(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKlatkaBoom(org.bukkit.event.entity.EntityExplodeEvent e) { e.blockList().removeIf(klatki::containsKey); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKlatkaBoom2(org.bukkit.event.block.BlockExplodeEvent e) { e.blockList().removeIf(klatki::containsKey); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKlatkaTlok(org.bukkit.event.block.BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (klatki.containsKey(b)) { e.setCancelled(true); return; } }

    /** Przy wylaczeniu serwera - usun wszystkie klatki. */
    void stop() { for (Block b : klatki.keySet()) b.setType(Material.AIR, false); klatki.clear(); }
}
