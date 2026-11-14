package pl.westmc.lobby;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Scoreboard i TAB lobby w stylu HD (grafiki z paczki + liczby wpisywane na zywo). */
final class Hud {
    private final WestLobby pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private static final int LINII = 15;

    Hud(WestLobby pl) { this.pl = pl; }

    private boolean sbOn() { return pl.getConfig().getBoolean("hud.scoreboard", true); }
    private boolean tabOn() { return pl.getConfig().getBoolean("hud.tab", true); }

    // ============================================================ rangi
    /** Ranga gracza: uprawnienie westlobby.ranga.<id> (ustawione wprost, nie z OP) albo nick na liscie w configu. */
    String ranga(Player p) {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("rangi");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(id);
            if (r == null) continue;
            String perm = "westlobby.ranga." + id;
            if (p.isPermissionSet(perm) && p.hasPermission(perm)) return r.getString("nazwa", id);
            for (String n : r.getStringList("gracze")) if (n.equalsIgnoreCase(p.getName())) return r.getString("nazwa", id);
        }
        return pl.getConfig().getString("ranga-domyslna", "&7Gracz");
    }

    // ============================================================ scoreboard
    private Component tytul() {
        Glify.Tytul t = Glify.siatka(new Glify.Tytul(), 0, Glify.TABLICA, Glify.TABLICA_KOL, 3, 3);
        t.idz(132);
        return Glify.lobby(t.tekst());
    }

    private Component linia(int at, String tekst) {
        if (tekst == null) return Component.empty();
        return Component.text(Glify.spacja(at)).append(Glify.kolor(tekst));
    }

    private String liczba(String srv) {
        int on = pl.online(srv);
        return on < 0 ? "&7&l?" : "&a&l" + on;
    }

    void join(Player p) {
        if (sbOn()) {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("westlobby", Criteria.DUMMY, tytul());
            o.numberFormat(NumberFormat.blank());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < LINII; i++) o.getScore("l" + i).setScore(LINII - i);
            boards.put(p.getUniqueId(), sb);
            p.setScoreboard(sb);
        }
        update(p);
    }

    void quit(Player p) { boards.remove(p.getUniqueId()); }

    void updateAll() { for (Player p : Bukkit.getOnlinePlayers()) update(p); }

    void update(Player p) {
        String ranga = ranga(p);
        if (sbOn()) {
            Scoreboard sb = boards.get(p.getUniqueId());
            Objective o = sb == null ? null : sb.getObjective("westlobby");
            if (o != null) {
                String[] t = new String[LINII];
                t[5] = "&f" + p.getName();
                t[6] = ranga;
                t[9] = liczba(pl.getConfig().getString("hud.serwer-gildie", "gildie"));
                t[10] = liczba(pl.getConfig().getString("hud.serwer-duels", "duels"));
                t[11] = pl.getConfig().getString("hud.wkrotce", "&7???");
                int all = pl.online("ALL");
                t[13] = "&a&l" + (all < 0 ? Bukkit.getOnlinePlayers().size() : all);
                int[] at = new int[LINII];
                at[5] = at[6] = 40;
                at[9] = at[10] = at[11] = at[13] = 98;
                for (int i = 0; i < LINII; i++) o.getScore("l" + i).customName(t[i] == null ? Component.empty() : linia(at[i], t[i]));
            }
        }
        if (tabOn()) {
            Glify.Tytul g = Glify.siatka(new Glify.Tytul(), 0, Glify.TAB_GORA, Glify.TAB_KOL, 2, 4);
            g.idz(240);
            int all = pl.online("ALL");
            Component head = Component.text("").append(Glify.lobby(g.tekst()));   // korzen bez czcionki lobby, inaczej tekst nizej to kwadraty
            for (int i = 0; i < 7; i++) head = head.append(Component.newline());
            head = head.append(Component.newline()).append(Glify.kolor(pl.getConfig().getString("hud.tab-linia",
                "&7Jestes na &#3AA0FF&lLOBBY &8• &7Graczy na sieci&8: &a&l{ONLINE}").replace("{ONLINE}", String.valueOf(all < 0 ? Bukkit.getOnlinePlayers().size() : all))))
                .append(Component.newline());
            Glify.Tytul f = Glify.siatka(new Glify.Tytul(), 0, Glify.TAB_DOL, Glify.TAB_KOL, 1, 4);
            f.idz(240);
            Component foot = Component.newline().append(Glify.lobby(f.tekst())).append(Component.newline()).append(Component.newline())
                .append(Component.newline()).append(Glify.kolor(pl.getConfig().getString("hud.tab-podpowiedz", "&7Wybierz tryb gry &fkompasem &8(&fPPM&8)")));
            p.sendPlayerListHeaderAndFooter(head, foot);
            String r = ranga.replaceAll("(?i)[&§][0-9a-fk-or]", "").replaceAll("&#[0-9a-fA-F]{6}", "").trim();
            p.playerListName(r.isEmpty() || r.equalsIgnoreCase("gracz")
                ? Glify.kolor("&7" + p.getName())
                : Glify.kolor(ranga + " &f" + p.getName()));
        }
    }
}
