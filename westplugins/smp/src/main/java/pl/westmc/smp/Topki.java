package pl.westmc.smp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Topki: /topki (czat) + hologramowe tablice HD (3 TextDisplaye: tlo z paczki, nicki, wartosci).
 * Statystyki graczy (czas gry, zabojstwa) trzymamy w topki.yml - aktualizowane przy wyjsciu i co odswiezenie.
 */
final class Topki implements CommandExecutor, TabCompleter, Listener {
    static final String[] TYPY = {"bogacze", "czas", "klany", "zabojstwa"};
    private static final String[] TYTULY = {"&#FFC23D&lNAJBOGATSI", "&#3ADFFF&lCZAS GRY", "&#C08BFF&lTOP KLANY", "&#FF5A68&lZABOJSTWA"};
    private static final String[] KOLORY = {"&#FFC23D", "&#3ADFFF", "&#C08BFF", "&#FF5A68"};
    private static final int W = 150, LINII = 14;
    private final WestSMP pl;
    private final File plik;
    private final YamlConfiguration d;
    private final NamespacedKey KLUCZ;
    private final Map<String, TextDisplay[]> tablice = new HashMap<>();
    private final Map<String, List<String[]>> wyniki = new HashMap<>();
    private BukkitTask task, pilnuj;

    Topki(WestSMP pl) {
        this.pl = pl;
        plik = new File(pl.getDataFolder(), "topki.yml");
        d = YamlConfiguration.loadConfiguration(plik);
        KLUCZ = new NamespacedKey(pl, "topka");
    }

    private void zapisz() { try { d.save(plik); } catch (Exception e) { pl.getLogger().warning("topki.yml: " + e.getMessage()); } }

    void start() {
        long co = Math.max(20, pl.getConfig().getLong("topki.odswiezanie-sekund", 300)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(pl, this::odswiez, 40L, co);
        pilnuj = Bukkit.getScheduler().runTaskTimer(pl, this::pilnuj, 100L, 100L);
    }

    void stop() {
        if (task != null) task.cancel();
        if (pilnuj != null) pilnuj.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) staty(p);
        zapisz();
        for (TextDisplay[] t : tablice.values()) usunEnc(t);
        tablice.clear();
    }

    // ================================================================ dane
    private void staty(Player p) {
        String k = "staty." + p.getUniqueId();
        d.set(k + ".nick", p.getName());
        d.set(k + ".czas", p.getStatistic(Statistic.PLAY_ONE_MINUTE));
        d.set(k + ".zabojstwa", p.getStatistic(Statistic.PLAYER_KILLS));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { staty(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { staty(e.getPlayer()); zapisz(); }

    private List<String[]> policz(String typ, int n) {
        List<String[]> l = new ArrayList<>();
        switch (typ) {
            case "bogacze" -> {
                if (pl.kasa.obca == null) {
                    for (String[] r : pl.kasa.top(n)) l.add(new String[]{r[0], pl.kasa.fmt(Double.parseDouble(r[1]))});
                } else {
                    List<Object[]> t = new ArrayList<>();
                    ConfigurationSection s = d.getConfigurationSection("staty");
                    if (s != null) for (String u : s.getKeys(false)) {
                        try { t.add(new Object[]{s.getString(u + ".nick", "?"), pl.kasa.saldo(Bukkit.getOfflinePlayer(UUID.fromString(u)))}); } catch (Exception ignored) { }
                    }
                    t.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
                    for (int i = 0; i < Math.min(n, t.size()); i++) l.add(new String[]{(String) t.get(i)[0], pl.kasa.fmt((double) t.get(i)[1])});
                }
            }
            case "klany" -> {
                if (pl.klany != null) for (String[] r : pl.klany.ranking(n)) l.add(new String[]{"&l" + r[2] + "[" + r[0] + "]", r[1] + " pkt"});
            }
            default -> {
                for (Player p : Bukkit.getOnlinePlayers()) staty(p);
                String pole = typ.equals("czas") ? "czas" : "zabojstwa";
                List<Object[]> t = new ArrayList<>();
                ConfigurationSection s = d.getConfigurationSection("staty");
                if (s != null) for (String u : s.getKeys(false)) t.add(new Object[]{s.getString(u + ".nick", "?"), s.getLong(u + "." + pole)});
                t.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
                for (int i = 0; i < Math.min(n, t.size()); i++) {
                    long v = (long) t.get(i)[1];
                    if (v <= 0) break;
                    l.add(new String[]{(String) t.get(i)[0], typ.equals("czas") ? Hud.czas(v) : String.format(Locale.ROOT, "%,d", v).replace(',', ' ')});
                }
            }
        }
        return l;
    }

    void odswiez() {
        for (String typ : TYPY) wyniki.put(typ, policz(typ, 10));
        zapisz();
        for (String id : new ArrayList<>(tablice.keySet())) wypisz(id);
    }

    // ================================================================ hologramy
    private Location miejsce(String id) {
        String k = "tablice." + id;
        World w = Bukkit.getWorld(d.getString(k + ".swiat", ""));
        if (w == null) return null;
        return new Location(w, d.getDouble(k + ".x"), d.getDouble(k + ".y"), d.getDouble(k + ".z"), (float) d.getDouble(k + ".yaw"), 0);
    }

    private TextDisplay nowy(Location l, String id, float skala, TextDisplay.TextAlignment al) {
        return l.getWorld().spawn(l, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.FIXED);
            t.setAlignment(al);
            t.setLineWidth(1000);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(al != TextDisplay.TextAlignment.CENTER);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setViewRange(pl.getConfig().getInt("topki.zasieg", 128) / 64f);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(skala, skala, skala), new AxisAngle4f()));
            t.getPersistentDataContainer().set(KLUCZ, PersistentDataType.STRING, id);
        });
    }

    private void postaw(String id) {
        usunEnc(tablice.remove(id));
        Location l = miejsce(id);
        if (l == null || !l.isChunkLoaded()) return;
        float skala = (float) pl.getConfig().getDouble("topki.skala", 0.8);
        Vector przod = l.getDirection().setY(0).normalize().multiply(pl.getConfig().getBoolean("topki.odwroc", false) ? -1 : 1);
        TextDisplay tlo = nowy(l.clone(), id, skala, TextDisplay.TextAlignment.CENTER);
        TextDisplay nicki = nowy(l.clone().add(przod.clone().multiply(0.015)), id, skala, TextDisplay.TextAlignment.LEFT);
        TextDisplay wart = nowy(l.clone().add(przod.clone().multiply(0.03)), id, skala, TextDisplay.TextAlignment.RIGHT);
        tablice.put(id, new TextDisplay[]{tlo, nicki, wart});
        wypisz(id);
    }

    private static void usunEnc(TextDisplay[] t) { if (t != null) for (TextDisplay e : t) if (e != null && e.isValid()) e.remove(); }

    private void wypisz(String id) {
        TextDisplay[] t = tablice.get(id);
        if (t == null) return;
        String typ = d.getString("tablice." + id + ".typ", "bogacze");
        int ti = java.util.Arrays.asList(TYPY).indexOf(typ);
        if (ti < 0) ti = 0;
        // tlo: kafelki 60/60/30 x 60/60/20, gorna krawedz 3 px pod gora bloku linii
        Component tlo = Component.text("").append(Tlo.glify(Tlo.siatka(0xE320 + ti * 16, new int[]{60, 60, 30}, 3, 3, W)));
        for (int i = 1; i < LINII; i++) tlo = tlo.append(Component.newline()).append(Component.text(" "));
        t[0].text(tlo);
        List<String[]> w = wyniki.getOrDefault(typ, List.of());
        Component n = Component.text(""), v = Component.text("");
        for (int i = 0; i < LINII; i++) {
            if (i > 0) { n = n.append(Component.newline()); v = v.append(Component.newline()); }
            int r = i - 3;
            if (i == LINII - 1) {   // linia-dystans: ustala szerokosc bloku = W, zeby kolumny trafialy w okienka
                n = n.append(Tlo.glify(Tlo.spacja(W)));
                v = v.append(Tlo.glify(Tlo.spacja(W)));
            } else if (r >= 0 && r < 10 && r < w.size()) {
                String nick = w.get(r)[0], val = w.get(r)[1];
                n = n.append(Tlo.glify(Tlo.spacja(19))).append(Hud.kolor((r < 3 ? "&f" : "&7") + nick));
                v = v.append(Hud.kolor(r < 3 ? KOLORY[ti] + "&l" + val : "&f" + val)).append(Tlo.glify(Tlo.spacja(9)));
            } else {
                n = n.append(Component.text(" "));
                v = v.append(Component.text(" "));
            }
        }
        t[1].text(n);
        t[2].text(v);
    }

    /** Co 5 s: tablice w zaladowanych chunkach, ktore zniknely (chunk byl wyladowany) - stawiamy od nowa. */
    private void pilnuj() {
        ConfigurationSection s = d.getConfigurationSection("tablice");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            TextDisplay[] t = tablice.get(id);
            boolean ok = t != null && t[0].isValid() && t[1].isValid() && t[2].isValid();
            if (!ok) { Location l = miejsce(id); if (l != null && l.isChunkLoaded()) postaw(id); else if (t != null) { usunEnc(t); tablice.remove(id); } }
        }
    }

    // ================================================================ komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        boolean admin = s.hasPermission("westsmp.admin");
        if (admin && sub.equals("postaw") && s instanceof Player p) {
            if (a.length < 2 || !java.util.Arrays.asList(TYPY).contains(a[1].toLowerCase())) { p.sendMessage(WestSMP.c("&7/topki postaw <bogacze|czas|klany|zabojstwa>")); return true; }
            Location l = p.getLocation();
            String id = a[1].toLowerCase() + "_" + Long.toString(System.currentTimeMillis() % 100000, 36);
            String k = "tablice." + id;
            d.set(k + ".typ", a[1].toLowerCase()); d.set(k + ".swiat", l.getWorld().getName());
            d.set(k + ".x", Math.round(l.getX() * 100) / 100.0); d.set(k + ".y", Math.round((l.getY() + pl.getConfig().getDouble("topki.wysokosc", 1.0)) * 100) / 100.0); d.set(k + ".z", Math.round(l.getZ() * 100) / 100.0);
            d.set(k + ".yaw", (double) Math.round(l.getYaw() + 180));
            zapisz();
            if (wyniki.isEmpty()) for (String typ : TYPY) wyniki.put(typ, policz(typ, 10));
            postaw(id);
            p.sendMessage(WestSMP.c("&aPostawiono tablice &f" + a[1] + "&a. Usuwanie: stan obok i wpisz &f/topki usun"));
            return true;
        }
        if (admin && sub.equals("usun") && s instanceof Player p) {
            String best = null; double bd = 36;
            ConfigurationSection sec = d.getConfigurationSection("tablice");
            if (sec != null) for (String id : sec.getKeys(false)) { Location l = miejsce(id); if (l != null && l.getWorld() == p.getWorld() && l.distanceSquared(p.getLocation()) < bd) { bd = l.distanceSquared(p.getLocation()); best = id; } }
            if (best == null) { p.sendMessage(WestSMP.c("&cNie ma tablicy w promieniu 6 kratek.")); return true; }
            usunEnc(tablice.remove(best)); d.set("tablice." + best, null); zapisz();
            p.sendMessage(WestSMP.c("&aUsunieto tablice."));
            return true;
        }
        if (admin && sub.equals("odswiez")) { odswiez(); s.sendMessage(WestSMP.c("&aOdswiezono topki.")); return true; }
        // dla graczy: lista w czacie
        String typ = java.util.Arrays.asList(TYPY).contains(sub) ? sub : null;
        if (typ == null) {
            s.sendMessage(WestSMP.c("&8&m                    &r &#C08BFF&lTOPKI &8&m                    "));
            s.sendMessage(WestSMP.c("&7/topki &fbogacze &8| &fczas &8| &fklany &8| &fzabojstwa"));
            if (admin) s.sendMessage(WestSMP.c("&7Admin: &f/topki postaw <typ>&7, &f/topki usun&7, &f/topki odswiez"));
            return true;
        }
        int ti = java.util.Arrays.asList(TYPY).indexOf(typ);
        List<String[]> w = wyniki.containsKey(typ) ? wyniki.get(typ) : policz(typ, 10);
        s.sendMessage(WestSMP.c("&8&m          &r " + TYTULY[ti] + " &8&m          "));
        String[] med = {"&#FFD84A&l1.", "&#E6EDF5&l2.", "&#E8945A&l3."};
        for (int i = 0; i < w.size(); i++) s.sendMessage(WestSMP.c((i < 3 ? med[i] : "&8" + (i + 1) + ".") + " &f" + w.get(i)[0] + " &8- " + KOLORY[ti] + w.get(i)[1]));
        if (w.isEmpty()) s.sendMessage(WestSMP.c("&7Brak danych."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> r = new ArrayList<>();
        if (a.length == 1) { r.addAll(List.of(TYPY)); if (s.hasPermission("westsmp.admin")) r.addAll(List.of("postaw", "usun", "odswiez")); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("postaw")) r.addAll(List.of(TYPY));
        r.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase()));
        return r;
    }

    /** Sprzatanie po restarcie: stare tablice w zaladowanych swiatach (gdyby jakas zostala). */
    void sprzatnij() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(TextDisplay.class))
            if (e.getPersistentDataContainer().has(KLUCZ, PersistentDataType.STRING)) e.remove();
    }
}
