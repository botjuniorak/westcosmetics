package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Eventowki: przedmioty specjalne z wlasnymi ikonami HD i dzialaniem. */
final class Eventowki implements Listener, CommandExecutor {
    private final WestSMP pl;
    final NamespacedKey KEY;
    private boolean wlasne;   // zabezpieczenie przed petla przy sztucznych BlockBreakEvent
    private final Map<UUID, Long> cdLuk = new HashMap<>();
    private final Set<UUID> skoczyl = new HashSet<>();
    private final Set<UUID> lot = new HashSet<>();   // komu MY wlaczylismy allowFlight

    Eventowki(WestSMP pl) { this.pl = pl; KEY = new NamespacedKey(pl, "eventowka"); }

    // ===================================================================== przedmioty
    private static final Object[][] TYPY = {
        // id, material, nazwa, rzadkosc
        {"kilof", Material.NETHERITE_PICKAXE, "&#C08BFF&lKilof Tytana", "&#C08BFF&lLEGENDARNA"},
        {"siekiera", Material.NETHERITE_AXE, "&#FFC93D&lSiekiera Drwala", "&#FFC93D&lEPICKA"},
        {"rzucak", Material.SNOWBALL, "&#FF7A5A&lRzucak", "&#FF7A5A&lRZADKA"},
        {"totem", Material.PAPER, "&#5AAEFF&lTotem Ochrony", "&#5AAEFF&lLEGENDARNA"},
        {"luk", Material.BOW, "&#FFF27A&lLuk Burzy", "&#FFF27A&lEPICKA"},
        {"miecz", Material.NETHERITE_SWORD, "&#FF3A5A&lMiecz Wampira", "&#FF3A5A&lLEGENDARNA"},
        {"buty", Material.LEATHER_BOOTS, "&#4EE08F&lButy Skoczka", "&#4EE08F&lRZADKA"},
        {"perla", Material.ENDER_PEARL, "&#3FE0C8&lPerla Teleportu", "&#3FE0C8&lEPICKA"},
        {"czarna_dziura", Material.PAPER, "&#A46BFF&lCzarna Dziura", "&#A46BFF&lLEGENDARNA"},
        {"meteor", Material.PAPER, "&#FF7A2A&lMeteoryt", "&#FF7A2A&lEPICKA"},
        {"kula", Material.PAPER, "&#3AD8FF&lSpeed Orb", "&#3AD8FF&lRZADKA"},
        {"zegarek", Material.PAPER, "&#FFD84A&lTime Watch", "&#FFD84A&lLEGENDARNA"},
        {"kostka", Material.PAPER, "&#FF4AD8&lKostka Chaosu", "&#FF4AD8&lEPICKA"},
    };

    private static List<String> opis(String id) {
        return switch (id) {
            case "kilof" -> List.of("&7Kopie &f3x3 &7bloki naraz.");
            case "siekiera" -> List.of("&7Scina &fcale drzewo &7jednym ciosem.");
            case "rzucak" -> List.of("&7Rzuc - wybucha i &fodrzuca graczy&7.", "&7Nie niszczy blokow.");
            case "totem" -> List.of("&7Trzymaj w ekwipunku - po smierci", "&fzachowasz wszystkie rzeczy&7. (1 raz)");
            case "luk" -> List.of("&7Trafiona strzala &fprzywoluje piorun&7.");
            case "miecz" -> List.of("&7Kazde trafienie &fleczy cie&7.");
            case "buty" -> List.of("&7Kliknij &fspacje w powietrzu&7,", "&7zeby wykonac podwojny skok.");
            case "perla" -> List.of("&7Perla &fbez czasu odnowienia&7.");
            case "czarna_dziura" -> List.of("&7PPM - tworzy &fczarna dziure&7,", "&7ktora wciaga graczy przez 5s.");
            case "meteor" -> List.of("&7PPM - z nieba spada &fmeteoryt", "&7i rani graczy w promieniu 4 kratek.");
            case "kula" -> List.of("&7PPM - &fsuper szybkosc &7i &fwysoki skok", "&7przez 10 sekund.");
            case "zegarek" -> List.of("&7Uderz gracza, aby &fzatrzymac go", "&fw czasie &7na 3 sekundy.");
            case "kostka" -> List.of("&7Uderz gracza, aby &fpomieszac", "&7mu pasek szybkiego wyboru.");
            default -> List.of();
        };
    }

    ItemStack item(String id, int ilosc) {
        for (Object[] t : TYPY) {
            if (!t[0].equals(id)) continue;
            ItemStack it = new ItemStack((Material) t[1], Math.max(1, ilosc));
            ItemMeta m = it.getItemMeta();
            m.setItemModel(NamespacedKey.fromString("westmc:event/" + id));
            m.setDisplayName(WestSMP.c((String) t[2]));
            List<String> lore = new ArrayList<>();
            lore.add(WestSMP.c("&8&m                         "));
            for (String s : opis(id)) lore.add(WestSMP.c(s));
            lore.add("");
            lore.add(WestSMP.c("&7Rzadkosc: " + t[3]));
            lore.add(WestSMP.c("&8Eventowka WestMC"));
            m.setLore(lore);
            m.setEnchantmentGlintOverride(true);
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
            if (m instanceof LeatherArmorMeta lm) lm.setColor(Color.fromRGB(0x2FBF6A));
            it.setItemMeta(m);
            return it;
        }
        return null;
    }

    String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    private boolean wylaczona(String id) { return !pl.getConfig().getBoolean("eventowki." + id + ".wlaczona", true); }

    // ===================================================================== komenda
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("westsmp.eventowki.admin")) { s.sendMessage(WestSMP.c("&cBrak uprawnien.")); return true; }
        if (a.length >= 3 && a[0].equalsIgnoreCase("daj")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage(WestSMP.c("&cGracz offline.")); return true; }
            int n = 1;
            try { if (a.length > 3) n = Integer.parseInt(a[3]); } catch (NumberFormatException ignored) { }
            ItemStack it = item(a[2].toLowerCase(), n);
            if (it == null) { s.sendMessage(WestSMP.c("&cNie ma takiej eventowki. &7/eventowka lista")); return true; }
            t.getInventory().addItem(it).values().forEach(r -> t.getWorld().dropItemNaturally(t.getLocation(), r));
            s.sendMessage(WestSMP.c("&aDano &f" + a[2] + " &ax" + n + " &agraczowi &f" + t.getName()));
            return true;
        }
        StringBuilder b = new StringBuilder("&7Eventowki: ");
        for (Object[] t : TYPY) b.append("&f").append(t[0]).append("&7, ");
        s.sendMessage(WestSMP.c(b.substring(0, b.length() - 2)));
        s.sendMessage(WestSMP.c("&7/eventowka daj <gracz> <nazwa> [ilosc]"));
        return true;
    }

    // ===================================================================== kilof 3x3 i siekiera drwala
    private boolean mozeZniszczyc(Player p, Block b) {
        if (b.getType().isAir() || b.getType().getHardness() < 0 || b.getType().getHardness() > 50) return false;
        wlasne = true;
        BlockBreakEvent ev = new BlockBreakEvent(b, p);
        Bukkit.getPluginManager().callEvent(ev);
        wlasne = false;
        return !ev.isCancelled();
    }

    private void zuzyj(Player p, ItemStack tool, int ile) {
        if (p.getGameMode() == GameMode.CREATIVE || !(tool.getItemMeta() instanceof Damageable d)) return;
        d.setDamage(d.getDamage() + ile);
        tool.setItemMeta(d);
        if (d.getDamage() >= tool.getType().getMaxDurability()) { p.getInventory().setItemInMainHand(null); p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (wlasne) return;
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        String id = id(tool);
        if (id == null || p.isSneaking()) return;   // SHIFT = kopanie pojedyncze
        Block b = e.getBlock();
        if (id.equals("kilof") && !wylaczona("kilof")) {
            float pitch = p.getLocation().getPitch();
            BlockFace f = p.getFacing();
            int n = 0;
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                Block o;
                if (Math.abs(pitch) > 50) o = b.getRelative(i, 0, j);
                else if (f == BlockFace.NORTH || f == BlockFace.SOUTH) o = b.getRelative(i, j, 0);
                else o = b.getRelative(0, j, i);
                if (!Tag.MINEABLE_PICKAXE.isTagged(o.getType()) && !Tag.MINEABLE_SHOVEL.isTagged(o.getType())) continue;
                if (!mozeZniszczyc(p, o)) continue;
                o.breakNaturally(tool, true);
                n++;
            }
            zuzyj(p, tool, n);
        } else if (id.equals("siekiera") && !wylaczona("siekiera") && Tag.LOGS.isTagged(b.getType())) {
            int max = pl.getConfig().getInt("eventowki.siekiera.max-blokow", 150);
            ArrayDeque<Block> q = new ArrayDeque<>(); Set<Block> seen = new HashSet<>();
            q.add(b); seen.add(b);
            int n = 0;
            while (!q.isEmpty() && n < max) {
                Block c = q.poll();
                for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    Block o = c.getRelative(dx, dy, dz);
                    if (seen.contains(o) || !Tag.LOGS.isTagged(o.getType())) continue;
                    seen.add(o);
                    if (!mozeZniszczyc(p, o)) continue;
                    q.add(o);
                    o.breakNaturally(tool, true);
                    n++;
                }
            }
            if (n > 0) { p.playSound(b.getLocation(), Sound.BLOCK_CHERRY_WOOD_BREAK, 1f, 0.7f); zuzyj(p, tool, Math.max(1, n / 3)); }
        }
    }

    // ===================================================================== rzucak i luk burzy
    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Snowball sb) || !(sb.getShooter() instanceof Player p)) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!"rzucak".equals(id(it))) it = p.getInventory().getItemInOffHand();
        if (!"rzucak".equals(id(it)) || wylaczona("rzucak")) return;
        sb.setItem(item("rzucak", 1));
        sb.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "rzucak");
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !"luk".equals(id(e.getBow())) || wylaczona("luk")) return;
        long now = System.currentTimeMillis();
        if (now - cdLuk.getOrDefault(p.getUniqueId(), 0L) < pl.getConfig().getLong("eventowki.luk.co-ile-ms", 3000)) {
            p.sendActionBar(WestSMP.c("&eLuk Burzy sie laduje..."));
            return;
        }
        cdLuk.put(p.getUniqueId(), now);
        e.getProjectile().getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "luk");
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        String id = pr.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (id == null) return;
        Location at = e.getHitEntity() != null ? e.getHitEntity().getLocation() : pr.getLocation();
        Player shooter = pr.getShooter() instanceof Player p ? p : null;
        if (id.equals("rzucak")) {
            at.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 0.4, 0.3, 0.4, 0);
            at.getWorld().spawnParticle(Particle.FLAME, at, 25, 0.6, 0.3, 0.6, 0.05);
            at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.3f);
            double r = pl.getConfig().getDouble("eventowki.rzucak.promien", 4), sila = pl.getConfig().getDouble("eventowki.rzucak.sila", 1.4);
            double dmg = pl.getConfig().getDouble("eventowki.rzucak.obrazenia", 0);
            for (Entity en : at.getWorld().getNearbyEntities(at, r, r, r)) {
                if (!(en instanceof LivingEntity le)) continue;
                Vector d = en.getLocation().toVector().subtract(at.toVector());
                if (d.lengthSquared() < 0.01) d = new Vector(0, 1, 0);
                double f = sila * (1 - Math.min(1, d.length() / (r + 0.5))) + 0.3;
                en.setVelocity(d.normalize().multiply(f).setY(0.45 + f * 0.3));
                if (dmg > 0 && en != shooter) le.damage(dmg, shooter);
            }
            pr.remove();
        } else if (id.equals("luk")) {
            if (pl.getConfig().getBoolean("eventowki.luk.prawdziwy-piorun", false)) at.getWorld().strikeLightning(at);
            else {
                at.getWorld().strikeLightningEffect(at);
                double dmg = pl.getConfig().getDouble("eventowki.luk.obrazenia", 5);
                for (Entity en : at.getWorld().getNearbyEntities(at, 2, 2, 2))
                    if (en instanceof LivingEntity le && en != shooter) le.damage(dmg, shooter);
            }
            if (pr instanceof Arrow) pr.remove();
        }
    }

    // ===================================================================== miecz wampira
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !"miecz".equals(id(p.getInventory().getItemInMainHand())) || wylaczona("miecz")) return;
        double heal = Math.min(pl.getConfig().getDouble("eventowki.miecz.max-leczenie", 3), e.getFinalDamage() * pl.getConfig().getDouble("eventowki.miecz.procent", 0.15));
        var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
        p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, e.getEntity().getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 1, 0.2, 0.1, 0.2, 0);
    }

    // ===================================================================== totem ochrony
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (e.getKeepInventory() || wylaczona("totem")) return;
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            if (!"totem".equals(id(inv[i]))) continue;
            ItemStack t = inv[i];
            t.setAmount(t.getAmount() - 1);
            p.getInventory().setItem(i, t.getAmount() <= 0 ? null : t);
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
            e.setDroppedExp(0);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.3);
            Bukkit.getScheduler().runTaskLater(pl, () -> p.sendMessage(WestSMP.c("&#5AAEFF&lTOTEM OCHRONY &8» &7Twoje rzeczy zostaly ocalone!")), 2L);
            return;
        }
    }

    // ===================================================================== buty skoczka
    private boolean maButy(Player p) { return "buty".equals(id(p.getInventory().getBoots())) && !wylaczona("buty"); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        boolean buty = maButy(p);
        if (buty && ((org.bukkit.entity.Entity) p).isOnGround()) {
            skoczyl.remove(p.getUniqueId());
            if (!p.getAllowFlight()) { p.setAllowFlight(true); lot.add(p.getUniqueId()); }
        } else if (!buty && lot.remove(p.getUniqueId())) {
            p.setAllowFlight(false);
        }
    }

    @EventHandler
    public void onFly(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || !maButy(p) || !lot.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        if (skoczyl.contains(p.getUniqueId())) return;
        skoczyl.add(p.getUniqueId());
        double sila = pl.getConfig().getDouble("eventowki.buty.sila", 0.9);
        p.setVelocity(p.getLocation().getDirection().setY(0).normalize().multiply(0.45).setY(sila));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 10, 0.3, 0.05, 0.3, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.3f);
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && skoczyl.contains(p.getUniqueId()) && maButy(p))
            e.setDamage(Math.max(0, e.getDamage() - 4));
    }

    // ===================================================================== perla bez cooldownu
    @EventHandler
    public void onPearl(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        ItemStack it = e.getItem();
        if (!"perla".equals(id(it)) || wylaczona("perla")) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> p.setCooldown(Material.ENDER_PEARL, 0));
    }
}
