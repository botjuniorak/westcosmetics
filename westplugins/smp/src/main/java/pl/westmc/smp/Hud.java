package pl.westmc.smp;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Scoreboard i TAB SMP w stylu HD: grafika z paczki (czcionka westmc:smp) + wartosci wpisywane na zywo. */
final class Hud implements Listener {
    private final WestSMP pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask task;
    private static final int LINII = 15;
    static final int TABLICA = 0xE300, TAB_GORA = 0xE310, TAB_DOL = 0xE318;

    Hud(WestSMP pl) { this.pl = pl; }

    private FileConfiguration cfg() { return pl.getConfig(); }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) join(p);
        task = Bukkit.getScheduler().runTaskTimer(pl, () -> { for (Player p : Bukkit.getOnlinePlayers()) update(p); }, 40L, Math.max(10L, cfg().getLong("hud.odswiezanie-tickow", 40)));
    }

    void stop() { if (task != null) task.cancel(); }

    static Component kolor(String s) { return LegacyComponentSerializer.legacySection().deserialize(WestSMP.c(s)); }

    String ranga(Player p) {
        ConfigurationSection r = cfg().getConfigurationSection("czat.rangi");
        if (r != null) for (String k : r.getKeys(false)) if (p.hasPermission("westsmp.ranga." + k)) return r.getString(k, "");
        return "";
    }

    private Component tytul() {
        return Tlo.glify(Tlo.siatka(TABLICA, new int[]{60, 60, 12}, 3, 3, 132));
    }

    /** Ciemne tlo (znak ze zwyklej czcionki - rysuje sie przed tekstem) + wartosc. */
    private Component linia(int at, String tekst) {
        boolean szeroka = at < 60;
        int x0 = szeroka ? 38 : 96, w = szeroka ? 90 : 32;
        return Component.text(Tlo.spacja(x0))
            .append(Component.text(szeroka ? "" : "").color(TextColor.fromHexString(cfg().getString("hud.tlo-liczb", "#140A2E"))))
            .append(Component.text(Tlo.spacja(at - x0 - w - 1)))
            .append(kolor(tekst));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { join(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { boards.remove(e.getPlayer().getUniqueId()); }

    void join(Player p) {
        if (cfg().getBoolean("hud.scoreboard", true)) {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("westsmp", Criteria.DUMMY, tytul());
            o.numberFormat(NumberFormat.blank());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < LINII; i++) o.getScore("l" + i).setScore(LINII - i);
            boards.put(p.getUniqueId(), sb);
            p.setScoreboard(sb);
        }
        update(p);
    }

    static String czas(long tickow) {
        long m = tickow / 20 / 60;
        return (m / 60) + "h " + String.format("%02d", m % 60) + "m";
    }

    void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        Objective o = sb == null ? null : sb.getObjective("westsmp");
        if (o != null) {
            String[] t = new String[LINII];
            t[5] = "&#FFC23D&l" + pl.kasa.fmt(pl.kasa.saldo(p));
            String klan = pl.klany == null ? null : pl.klany.tagGoly(p);
            t[6] = klan == null ? "&7brak" : "&l" + klan;
            t[9] = "&f" + p.getStatistic(Statistic.PLAYER_KILLS);
            t[10] = "&f" + p.getStatistic(Statistic.DEATHS);
            t[11] = "&f" + (p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600) + "h";
            t[13] = "&a&l" + Bukkit.getOnlinePlayers().size();
            for (int i = 0; i < LINII; i++) {
                int at = i == 5 || i == 6 ? 40 : 98;
                o.getScore("l" + i).customName(t[i] == null ? Component.empty() : linia(at, t[i]));
            }
        }
        if (cfg().getBoolean("hud.tab", true)) {
            Component head = Component.text("").append(Tlo.glify(Tlo.siatka(TAB_GORA, new int[]{60, 60, 60, 60}, 2, 4, 240)));
            for (int i = 0; i < 8; i++) head = head.append(Component.newline());
            head = head.append(kolor(cfg().getString("hud.tab-linia", "&7Jestes na &#C08BFF&lSMP &8• &7Graczy online&8: &a&l{ONLINE}")
                .replace("{ONLINE}", String.valueOf(Bukkit.getOnlinePlayers().size())))).append(Component.newline());
            Component foot = Component.text("").append(Component.newline()).append(Tlo.glify(Tlo.siatka(TAB_DOL, new int[]{60, 60, 60, 60}, 1, 4, 240)))
                .append(Component.newline()).append(Component.newline()).append(Component.newline())
                .append(kolor(cfg().getString("hud.tab-podpowiedz", "&7Szafa: &f/szafa &8• &7Klany: &f/klan &8• &7Sklep: &f/sklep")));
            p.sendPlayerListHeaderAndFooter(head, foot);
            String r = ranga(p);
            String nick = r.isEmpty() ? "&7" + p.getName() : r + "&f" + p.getName();
            String klanTab = pl.klany == null ? "" : pl.klany.tag(p);
            p.playerListName(kolor(cfg().getString("hud.tab-format", "{KLAN}{NICK}").replace("{KLAN}", klanTab).replace("{NICK}", nick)));
        }
    }
}
