package pl.westmc.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Eventy SMP:
 *  - SMOK (End): wlasny smok z bossbarem, atakami specjalnymi, ranking obrazen i nagrody dla TOP 3 + uczestnikow, o godzinach z configu.
 *  - MINI EVENTY na czacie (przepisz, matma, rozsypanka, zgadnij liczbe) + DESZCZ NAGROD na spawnie.
 */
final class Eventy implements Listener, CommandExecutor {
    private final WestSMP pl;
    private final Random r = new Random();
    private BukkitTask zegar;
    private String ostatniaMinuta = "";
    // smok
    private EnderDragon smok;
    private BossBar pasek;
    private final Map<UUID, Double> obrazenia = new HashMap<>();
    private int sekundSmoka;
    // mini eventy
    private volatile String odpowiedz;
    private volatile String typMini;
    private int sekundMini, doNastepnego;

    Eventy(WestSMP pl) { this.pl = pl; }

    private FileConfiguration cfg() { return pl.getConfig(); }
    private static void ogloszenie(String s) { for (String l : s.split("\\|")) Bukkit.broadcast(Component.text(WestSMP.c(l))); }

    void start() {
        doNastepnego = cfg().getInt("mini-eventy.co-minut", 20) * 60;
        zegar = Bukkit.getScheduler().runTaskTimer(pl, this::sekunda, 20L, 20L);
    }

    void stop() {
        if (zegar != null) zegar.cancel();
        if (smok != null && smok.isValid()) smok.remove();
        if (pasek != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(pasek);
    }

    private void sekunda() {
        LocalTime t = LocalTime.now();
        String hhmm = String.format("%02d:%02d", t.getHour(), t.getMinute());
        if (!hhmm.equals(ostatniaMinuta)) {
            ostatniaMinuta = hhmm;
            for (String g : cfg().getStringList("smok.godziny")) {
                try {
                    LocalTime gt = LocalTime.parse(g);
                    for (int m : new int[]{10, 5, 1}) if (gt.minusMinutes(m).withSecond(0).toString().equals(hhmm))
                        ogloszenie("&#C08BFF&lSMOK &8» &7Smok pojawi sie w &fEndzie &7za &f" + m + " min&7! Wpisz &f/end");
                    if (gt.toString().equals(hhmm)) startSmoka(null);
                } catch (Exception ignored) { }
            }
        }
        if (smok != null) tikSmoka();
        tikMini();
    }

    // ================================================================= SMOK
    private World end() {
        World w = Bukkit.getWorld(cfg().getString("smok.swiat", "world_the_end"));
        if (w == null) for (World x : Bukkit.getWorlds()) if (x.getEnvironment() == World.Environment.THE_END) return x;
        return w;
    }

    private boolean startSmoka(CommandSender kto) {
        if (smok != null && smok.isValid()) { if (kto != null) kto.sendMessage(WestSMP.c("&cSmok juz lata!")); return false; }
        World w = end();
        if (w == null) { if (kto != null) kto.sendMessage(WestSMP.c("&cNie ma swiata End.")); return false; }
        Location l = new Location(w, 0, cfg().getDouble("smok.wysokosc", 90), 0);
        double hp = Math.min(1024, cfg().getDouble("smok.zycie", 800));
        smok = w.spawn(l, EnderDragon.class, d -> {
            d.setPersistent(false);
            var a = d.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) a.setBaseValue(hp);
            d.setHealth(hp);
            d.customName(Component.text(WestSMP.c(cfg().getString("smok.nazwa", "&#C08BFF&lSmok Westusia"))));
            d.setCustomNameVisible(true);
            d.setPhase(EnderDragon.Phase.CIRCLING);
        });
        obrazenia.clear();
        sekundSmoka = 0;
        pasek = BossBar.bossBar(Component.text(WestSMP.c(cfg().getString("smok.nazwa", "&#C08BFF&lSmok Westusia"))), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
        Component klik = Component.text(WestSMP.c("&#C08BFF&lSMOK &8» &7Smok wylecial w &fEndzie&7! &a&n[KLIKNIJ, ABY DOLACZYC]"))
            .clickEvent(ClickEvent.runCommand("/end"));
        Bukkit.broadcast(klik);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1f);
        return true;
    }

    private void tikSmoka() {
        if (!smok.isValid()) { koniecSmoka(false); return; }
        sekundSmoka++;
        double max = smok.getAttribute(Attribute.MAX_HEALTH).getValue();
        pasek.progress((float) Math.max(0, Math.min(1, smok.getHealth() / max)));
        pasek.name(Component.text(WestSMP.c(cfg().getString("smok.nazwa", "&#C08BFF&lSmok Westusia") + " &8• &f" + (int) Math.ceil(smok.getHealth()) + "&7/&f" + (int) max + " &c❤")));
        for (Player p : Bukkit.getOnlinePlayers()) { if (p.getWorld().equals(smok.getWorld())) p.showBossBar(pasek); else p.hideBossBar(pasek); }
        int co = Math.max(5, cfg().getInt("smok.atak-co-sekund", 12));
        if (sekundSmoka % co == 0) atak();
        if (sekundSmoka >= cfg().getInt("smok.limit-minut", 30) * 60) {
            ogloszenie("&#C08BFF&lSMOK &8» &7Smok odlecial... Nikt go nie pokonal na czas!");
            smok.remove(); koniecSmoka(false);
        }
    }

    private List<Player> gracze() {
        List<Player> l = new ArrayList<>();
        for (Player p : smok.getWorld().getPlayers()) if (p.getLocation().distanceSquared(new Location(p.getWorld(), 0, p.getY(), 0)) < 120 * 120 && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) l.add(p);
        return l;
    }

    private void doGraczy(List<Player> l, String tekst) { for (Player p : l) p.sendActionBar(Component.text(WestSMP.c(tekst))); }

    private void atak() {
        List<Player> l = gracze();
        if (l.isEmpty()) return;
        switch (r.nextInt(4)) {
            case 0 -> { // deszcz ognia
                doGraczy(l, "&#FF5A68&lDESZCZ OGNIA! &7Uciekaj spod fioletowych kul!");
                for (Player p : l) for (int i = 0; i < 3; i++) {
                    Location z = p.getLocation().add(r.nextGaussian() * 4, 22 + r.nextInt(6), r.nextGaussian() * 4);
                    DragonFireball f = p.getWorld().spawn(z, DragonFireball.class);
                    f.setShooter(smok);
                    f.setDirection(new Vector(0, -1, 0));
                }
            }
            case 1 -> { // sludzy
                doGraczy(l, "&#C08BFF&lSLUDZY SMOKA! &7Pokonaj je, zanim cie otocza!");
                for (Player p : l.subList(0, Math.min(l.size(), 6))) for (int i = 0; i < 2; i++) {
                    Location z = p.getLocation().add(r.nextInt(7) - 3, 1, r.nextInt(7) - 3);
                    p.getWorld().spawn(z, Endermite.class, m -> { m.customName(Component.text(WestSMP.c("&5Sluga Smoka"))); m.setPersistent(false); m.setTarget(p); });
                }
            }
            case 2 -> { // piorun w losowego
                Player p = l.get(r.nextInt(l.size()));
                doGraczy(l, "&#FFC23D&lGNIEW SMOKA! &7Piorun uderzyl w &f" + p.getName());
                p.getWorld().strikeLightning(p.getLocation());
            }
            default -> { // fala uderzeniowa
                doGraczy(l, "&#3ADFFF&lFALA UDERZENIOWA! &7Smok odrzuca wszystkich!");
                for (Player p : l) {
                    Vector v = p.getLocation().toVector().setY(0).normalize().multiply(1.6).setY(0.9);
                    if (Double.isFinite(v.getX())) p.setVelocity(v);
                    p.getWorld().spawnParticle(Particle.SONIC_BOOM, p.getLocation().add(0, 1, 0), 1);
                }
                smok.getWorld().playSound(smok.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.6f);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDmg(EntityDamageByEntityEvent e) {
        if (smok == null) return;
        Entity cel = e.getEntity();
        if (cel instanceof org.bukkit.entity.EnderDragonPart part) cel = part.getParent();
        if (cel != smok) return;
        Player p = e.getDamager() instanceof Player x ? x : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player y ? y : null);
        if (p != null) obrazenia.merge(p.getUniqueId(), e.getFinalDamage(), Double::sum);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (smok == null || e.getEntity() != smok) return;
        koniecSmoka(true);
    }

    private void koniecSmoka(boolean zabity) {
        if (pasek != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(pasek);
        smok = null;
        if (!zabity) return;
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(obrazenia.entrySet());
        l.sort(Collections.reverseOrder(Map.Entry.comparingByValue()));
        StringBuilder sb = new StringBuilder("&8&m                              |&#C08BFF&l  SMOK POKONANY!|");
        String[] med = {"&#FFD84A&l1.", "&#E6EDF5&l2.", "&#E8945A&l3."};
        for (int i = 0; i < Math.min(3, l.size()); i++)
            sb.append(" ").append(med[i]).append(" &f").append(Bukkit.getOfflinePlayer(l.get(i).getKey()).getName()).append(" &8- &c").append((int) Math.round(l.get(i).getValue())).append(" obrazen|");
        sb.append("&8&m                              ");
        ogloszenie(sb.toString());
        double min = cfg().getDouble("smok.min-obrazen", 20);
        for (int i = 0; i < l.size(); i++) {
            Player p = Bukkit.getPlayer(l.get(i).getKey());
            if (p == null) continue;
            String grupa = i < 3 ? "top" + (i + 1) : (l.get(i).getValue() >= min ? "uczestnik" : null);
            if (grupa == null) continue;
            nagroda(p, cfg().getStringList("smok.nagrody." + grupa));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    /** Nagrody: "kasa:5000", "komenda:lp user {GRACZ} ...", albo przedmiot jak w sklepie ("klucz:epicka 1", "DIAMOND 16", "eventowka:kilof"). */
    void nagroda(Player p, List<String> lista) {
        for (String s : lista) {
            try {
                if (s.startsWith("kasa:")) { double v = Double.parseDouble(s.substring(5).trim()); pl.kasa.dodaj(p, v); p.sendMessage(WestSMP.c("&a+ &#FFC93D" + pl.kasa.fmt(v))); }
                else if (s.startsWith("komenda:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), s.substring(8).trim().replace("{GRACZ}", p.getName()));
                else { ItemStack it = pl.sklepy.przedmiot(s); p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x)); }
            } catch (Exception ex) { pl.getLogger().warning("Zla nagroda '" + s + "': " + ex.getMessage()); }
        }
    }

    // ================================================================= MINI EVENTY
    private void tikMini() {
        if (typMini != null) {
            if (--sekundMini <= 0) {
                ogloszenie("&#3ADFFF&lMINI EVENT &8» &7Nikt nie zgadl! Odpowiedz: &f" + odpowiedz);
                typMini = null; odpowiedz = null;
            }
            return;
        }
        if (!cfg().getBoolean("mini-eventy.wlaczone", true)) return;
        if (--doNastepnego > 0) return;
        doNastepnego = cfg().getInt("mini-eventy.co-minut", 20) * 60;
        if (Bukkit.getOnlinePlayers().size() < cfg().getInt("mini-eventy.min-graczy", 3)) return;
        List<String> typy = new ArrayList<>(List.of("przepisz", "matma", "rozsypanka", "zgadnij"));
        if (cfg().getBoolean("mini-eventy.deszcz-nagrod-automatycznie", false)) typy.add("deszcz");
        startMini(typy.get(r.nextInt(typy.size())));
    }

    private String losoweSlowo() {
        List<String> s = cfg().getStringList("mini-eventy.slowa");
        if (s.isEmpty()) s = List.of("westmc", "klany", "diament", "smok", "eventowka", "kowadlo");
        return s.get(r.nextInt(s.size())).toLowerCase();
    }

    private boolean startMini(String typ) {
        if (typMini != null) return false;
        if (typ.equals("deszcz")) { deszcz(); return true; }
        String nag = "&#3ADFFF&lMINI EVENT &8» ";
        switch (typ) {
            case "przepisz" -> { odpowiedz = losoweSlowo() + r.nextInt(100); ogloszenie(nag + "&7Kto pierwszy przepisze: &f&l" + odpowiedz); }
            case "matma" -> { int a = 5 + r.nextInt(45), b = 2 + r.nextInt(9), c = 2 + r.nextInt(30); odpowiedz = String.valueOf(a * b + c); ogloszenie(nag + "&7Kto pierwszy policzy: &f&l" + a + " x " + b + " + " + c + " &7= ?"); }
            case "rozsypanka" -> {
                String w = losoweSlowo(); List<Character> ch = new ArrayList<>(); for (char x : w.toCharArray()) ch.add(x);
                String m; int prob = 0; do { Collections.shuffle(ch, r); StringBuilder b = new StringBuilder(); ch.forEach(b::append); m = b.toString(); } while (m.equals(w) && ++prob < 10);
                odpowiedz = w; ogloszenie(nag + "&7Uloz slowo z liter: &f&l" + m.toUpperCase());
            }
            case "zgadnij" -> { odpowiedz = String.valueOf(1 + r.nextInt(100)); ogloszenie(nag + "&7Zgadnij liczbe od &f1 &7do &f100&7! Pisz na czacie."); }
            default -> { return false; }
        }
        typMini = typ;
        sekundMini = cfg().getInt("mini-eventy.czas-sekund", 60);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        String o = odpowiedz, typ = typMini;
        if (o == null || typ == null) return;
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        if (typ.equals("zgadnij")) {
            try {
                int g = Integer.parseInt(msg), cel = Integer.parseInt(o);
                if (g != cel) { p.sendActionBar(Component.text(WestSMP.c(g < cel ? "&7Szukana liczba jest &aWIEKSZA" : "&7Szukana liczba jest &cMNIEJSZA"))); return; }
            } catch (NumberFormatException ex) { return; }
        } else if (!msg.equalsIgnoreCase(o)) return;
        synchronized (this) { if (odpowiedz == null) return; odpowiedz = null; typMini = null; }
        Bukkit.getScheduler().runTask(pl, () -> {
            ogloszenie("&#3ADFFF&lMINI EVENT &8» &f" + p.getName() + " &7wygrywa! &8(&f" + o + "&8)");
            if (p.isOnline()) { nagroda(p, cfg().getStringList("mini-eventy.nagrody")); p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f); }
        });
    }

    /** Deszcz nagrod na spawnie: przez X sekund z nieba spadaja przedmioty z listy. */
    private void deszcz() {
        Location sp = pl.teleporty.warp("spawn");
        if (sp == null) { pl.getLogger().warning("Deszcz nagrod: brak warpu spawn (/setwarp spawn)."); return; }
        List<String> lista = cfg().getStringList("mini-eventy.deszcz.przedmioty");
        if (lista.isEmpty()) lista = List.of("DIAMOND 1", "GOLDEN_APPLE 1", "EMERALD 3", "klucz:zwykla 1");
        final List<String> l = lista;
        int sek = cfg().getInt("mini-eventy.deszcz.sekund", 20), promien = cfg().getInt("mini-eventy.deszcz.promien", 12);
        ogloszenie("&#FFC23D&lDESZCZ NAGROD &8» &7Na &fspawnie &7z nieba spadaja nagrody przez &f" + sek + "s&7! &f/spawn");
        final int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (++t[0] > sek * 2) { task.cancel(); return; }
            for (int i = 0; i < cfg().getInt("mini-eventy.deszcz.na-pol-sekundy", 2); i++) {
                Location z = sp.clone().add(r.nextInt(promien * 2 + 1) - promien, 15, r.nextInt(promien * 2 + 1) - promien);
                Item it = sp.getWorld().dropItem(z, pl.sklepy.przedmiot(l.get(r.nextInt(l.size()))));
                it.setGlowing(true);
                sp.getWorld().spawnParticle(Particle.FIREWORK, z, 6, 0.2, 0.2, 0.2, 0.02);
            }
        }, 0L, 10L);
    }

    // ================================================================= komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (cmd.getName().equalsIgnoreCase("smok")) {
            if (sub.equals("start") && s.hasPermission("westsmp.admin")) { if (startSmoka(s)) s.sendMessage(WestSMP.c("&aSmok wypuszczony!")); return true; }
            if (sub.equals("stop") && s.hasPermission("westsmp.admin")) { if (smok != null && smok.isValid()) smok.remove(); koniecSmoka(false); s.sendMessage(WestSMP.c("&aSmok usuniety.")); return true; }
            if (smok != null && smok.isValid()) s.sendMessage(WestSMP.c("&#C08BFF&lSMOK &8» &7Smok lata teraz w &fEndzie&7! Zycie: &c" + (int) smok.getHealth() + " ❤"));
            else s.sendMessage(WestSMP.c("&#C08BFF&lSMOK &8» &7Smok pojawia sie o: &f" + String.join("&7, &f", cfg().getStringList("smok.godziny"))));
            if (s.hasPermission("westsmp.admin")) s.sendMessage(WestSMP.c("&7Admin: &f/smok start&7, &f/smok stop"));
            return true;
        }
        // /minievent
        if (!s.hasPermission("westsmp.admin")) { s.sendMessage(WestSMP.c("&cBrak uprawnien.")); return true; }
        if (sub.isEmpty()) { s.sendMessage(WestSMP.c("&7/minievent <przepisz|matma|rozsypanka|zgadnij|deszcz|stop>")); return true; }
        if (sub.equals("stop")) { typMini = null; odpowiedz = null; s.sendMessage(WestSMP.c("&aZatrzymano.")); return true; }
        s.sendMessage(WestSMP.c(startMini(sub) ? "&aWystartowano mini event." : "&cTrwa juz inny event albo zly typ."));
        return true;
    }
}
