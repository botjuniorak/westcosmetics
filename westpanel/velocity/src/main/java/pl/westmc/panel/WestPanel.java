package pl.westmc.panel;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "westpanel", name = "WestPanel", version = "1.5.0", description = "Panel administracji WestMC", authors = {"WestMC"})
public final class WestPanel {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private Store store;
    private Web web;
    private Properties cfg;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private Discord discord;
    private final Map<String, java.util.Deque<Long>> acTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> autoBanned = new ConcurrentHashMap<>();
    private final Map<String, long[]> acDiscord = new ConcurrentHashMap<>();   // gracz -> [ostatnia wysylka, zebrane od tego czasu]

    String cfg(String key) { return cfg.getProperty(key, "").trim(); }

    void discord(String channel, boolean ping, String title, String desc, int color, String[][] fields) {
        if (discord == null) return;
        discord.send(cfg("discord-" + channel), ping ? cfg("discord-ping") : "", title, desc, color, fields);
    }

    /** Kazdy alert anticheatu: Discord (bez spamu) + automatyczny ban po utrzymujacych sie wykryciach. */
    void onAcAlert(Store.AcAlert a) {
        String key = a.player.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        // Discord: pierwszy alert od razu, potem zbiorczo najwyzej co 60 s na gracza
        long[] d = acDiscord.computeIfAbsent(key, k -> new long[]{0, 0});
        synchronized (d) {
            d[1]++;
            if (now - d[0] >= 60000) {
                String more = d[1] > 1 ? " (+" + (d[1] - 1) + " kolejnych w ostatniej minucie)" : "";
                d[0] = now; d[1] = 0;
                discord("anticheat", false, "⚠️ Podejrzany gracz: " + Discord.safe(a.player),
                    "**" + Discord.safe(a.check) + "** · VL " + a.vl + more + (a.info.isBlank() ? "" : "\n" + Discord.safe(a.info)),
                    0xffb02e, new String[][]{{"Serwer", a.server}, {"Źródło", a.src}});
            }
        }

        if (!Boolean.parseBoolean(cfg("auto-ban"))) return;
        for (String skip : cfg("auto-ban-pomijaj").toLowerCase(Locale.ROOT).split("[,; ]+")) {
            if (!skip.isBlank() && a.check.toLowerCase(Locale.ROOT).startsWith(skip)) return;
        }
        Long banned = autoBanned.get(key);
        if (banned != null && now - banned < 10 * 60000) return;

        java.util.Deque<Long> q = acTimes.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        boolean ban;
        String why;
        synchronized (q) {
            q.addLast(now);
            long window = Math.max(30, parse(cfg("auto-ban-okno-sekund"), 120)) * 1000L;
            while (!q.isEmpty() && now - q.peekFirst() > window) q.pollFirst();
            int minAlerts = (int) parse(cfg("auto-ban-min-alertow"), 6);
            long spamWin = parse(cfg("auto-ban-spam-sekund"), 40) * 1000L;
            int spam = 0;
            for (long t : q) if (now - t <= spamWin) spam++;
            long span = q.isEmpty() ? 0 : now - q.peekFirst();
            // utrzymuje sie: min. N alertow, rozlozonych na co najmniej 60 s (nie jeden lag-spike)
            boolean sustained = q.size() >= minAlerts && span >= 60000;
            boolean spamming = spam >= parse(cfg("auto-ban-spam-alertow"), 12);
            ban = sustained || spamming;
            why = spamming ? spam + " alertów w " + (spamWin / 1000) + " s" : q.size() + " alertów w " + (span / 1000) + " s";
            if (ban) q.clear();
        }
        if (!ban) return;
        autoBanned.put(key, now);
        String cmd = cfg("auto-ban-komenda").replace("%player%", a.player);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        java.util.List<String> targets = new java.util.ArrayList<>(store.servers.keySet());
        for (String t : targets) store.queueCommand(t, cmd);
        Store.Punishment p = new Store.Punishment();
        p.id = store.nextId();
        p.time = now;
        p.nick = a.player;
        p.type = cmd.startsWith("ban ") || cmd.startsWith("tempban ") ? "ban" : cmd.split(" ")[0];
        p.duration = cmd.startsWith("tempban ") ? cmd.split(" ")[2] : cmd.startsWith("ban ") ? "perm" : "—";
        p.reason = "Anticheat: " + a.check + " (" + why + ")";
        p.by = "Anticheat";
        p.server = String.join(", ", targets);
        p.command = "/" + cmd;
        synchronized (store) { store.punishments.add(p); }
        store.savePunishments();
        store.log("panel", "kara", "Anticheat → " + a.player + ": /" + cmd + " (" + why + ")");
        notifyStaff("§c[Anticheat] §fAutomatyczny ban: §c" + a.player + " §7(" + a.check + ", " + why + ")");
        discord("kary", true, "🔨 Automatyczny ban: " + Discord.safe(a.player),
            "Wykrycie **" + Discord.safe(a.check) + "** utrzymywało się: " + why + ".\nKomenda: `/" + Discord.safe(cmd) + "`",
            0xff5b6b, new String[][]{{"Serwer", a.server}, {"Źródło", a.src}, {"Ostatnie VL", String.valueOf(a.vl)}});
        if (!cfg("discord-anticheat").equals(cfg("discord-kary"))) {
            discord("anticheat", true, "🔨 Zbanowano: " + Discord.safe(a.player), "Automatycznie po: " + why, 0xff5b6b, null);
        }
    }

    private static long parse(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    @Inject
    public WestPanel(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dir = dir;
    }

    Logger logger() { return logger; }
    String ingestKey() { return cfg.getProperty("klucz-serwerow"); }
    boolean secureCookie() { return Boolean.parseBoolean(cfg.getProperty("https", "false")); }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dir);
            loadConfig();
            store = new Store(dir);
            store.load();
            store.loadTiles();
            discord = new Discord(this);
            web = new Web(this, store);
            int port = Integer.parseInt(cfg.getProperty("port", "8123").trim());
            web.start(cfg.getProperty("adres", "0.0.0.0").trim(), port);
            logger.info("Panel WestMC działa na porcie {}.", port);
            if (store.accounts.isEmpty()) logger.info("Brak kont. Załóż pierwsze konto w konsoli proxy: panel konto <login> wlasciciel");
        } catch (Exception ex) {
            logger.error("Nie uruchomiono panelu: {}", ex.toString());
            return;
        }
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("panel").plugin(this).build(), new PanelCommand());
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("ss").aliases("sprawdz").plugin(this).build(), new SsCommand());
        proxy.getScheduler().buildTask(this, () -> store.recordHistory(proxy.getPlayerCount())).repeat(5, TimeUnit.MINUTES).schedule();
        store.recordHistory(proxy.getPlayerCount());
        proxy.getScheduler().buildTask(this, store::saveTiles).repeat(5, TimeUnit.MINUTES).schedule();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (web != null) web.stop();
        if (store != null) store.saveTiles();
    }

    private void loadConfig() throws IOException {
        Path f = dir.resolve("config.properties");
        cfg = new Properties();
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { cfg.load(r); }
        boolean changed = false;
        if (!cfg.containsKey("port")) { cfg.setProperty("port", "8123"); changed = true; }
        if (!cfg.containsKey("adres")) { cfg.setProperty("adres", "0.0.0.0"); changed = true; }
        if (!cfg.containsKey("klucz-serwerow")) { cfg.setProperty("klucz-serwerow", Crypto.token(24)); changed = true; }
        if (!cfg.containsKey("https")) { cfg.setProperty("https", "false"); changed = true; }
        if (!cfg.containsKey("bluemap")) { cfg.setProperty("bluemap", ""); changed = true; }
        String[][] defaults = {
            {"discord-anticheat", ""}, {"discord-zgloszenia", ""}, {"discord-kary", ""}, {"discord-ping", ""},
            {"auto-ban", "false"}, {"auto-ban-okno-sekund", "120"}, {"auto-ban-min-alertow", "6"},
            {"auto-ban-spam-alertow", "12"}, {"auto-ban-spam-sekund", "40"},
            {"auto-ban-komenda", "tempban %player% 30d Cheaty (Anticheat)"}, {"auto-ban-pomijaj", "xray"},
            {"link-panelu", "http://159.69.142.97:20001"}
        };
        for (String[] d : defaults) if (!cfg.containsKey(d[0])) { cfg.setProperty(d[0], d[1]); changed = true; }
        if (changed) try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            cfg.store(w, "WestPanel. port = port panelu WWW; klucz-serwerow wpisz w config pluginu WestPanel na serwerach; https=true gdy panel stoi za domena z HTTPS");
        }
    }

    // ------------------------------------------------------------ logi z proxy

    @Subscribe
    public void onJoin(PostLoginEvent e) {
        joinedAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (store != null) store.log("proxy", "join", e.getPlayer().getUsername() + " wszedł na serwer");
    }

    @Subscribe
    public void onQuit(DisconnectEvent e) {
        joinedAt.remove(e.getPlayer().getUniqueId());
        if (store != null) store.log("proxy", "join", e.getPlayer().getUsername() + " wyszedł z serwera");
    }

    @Subscribe
    public void onSwitch(ServerConnectedEvent e) {
        if (store != null) store.log("proxy", "join", e.getPlayer().getUsername() + " → " + e.getServer().getServerInfo().getName());
    }

    void runProxyCommand(String cmd) {
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), cmd);
    }

    /** Wiadomosc w grze dla ekipy (uprawnienie westpanel.powiadomienia). */
    /** Adres panelu dla graczy (link do programu). Z configu, a jak pusty - z naglowka Host. */
    String panelLink(String host) {
        String l = cfg("link-panelu");
        if (!l.isEmpty()) return l.replaceAll("/+$", "");
        if (host != null && host.matches("[A-Za-z0-9.:\\-]+")) return (secureCookie() ? "https://" : "http://") + host;
        return "http://localhost:" + cfg.getProperty("port", "8123").trim();
    }

    /** Wezwanie na sprawdzanie: wiadomosc i tytul dla gracza, info dla ekipy. */
    void ssNotify(String nick, String code, String by) {
        String link = panelLink(null) + "/ss";
        proxy.getPlayer(nick).ifPresent(p -> {
            p.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("SPRAWDZANIE", net.kyori.adventure.text.format.NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.BOLD),
                Component.text("Kod: " + code + " · nie wychodź z serwera", net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(8), java.time.Duration.ofMillis(800))));
            p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                "\n§c§l⚠ ZOSTAŁEŚ WEZWANY NA SPRAWDZANIE\n§7Administrator §f" + by + " §7chce sprawdzić, czy nie masz cheatów.\n"
                + "§71. Pobierz program: §b§n" + link + "§r\n§72. Uruchom §fWestSS.exe §7i wpisz kod: §e§l" + code + "\n"
                + "§8Wyjście z serwera lub odmowa = ban. Kod ważny 30 minut.\n")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
        });
        notifyStaff("§c[SS] §f" + by + " §7wezwał §f" + nick + " §7na sprawdzanie. Kod: §e" + code);
    }

    void ssResult(Store.SsCheck c, int cheats, int sus) {
        String col = cheats > 0 ? "§c" : sus > 0 ? "§e" : "§a";
        notifyStaff("§c[SS] §7Wynik sprawdzania §f" + c.nick + "§7: " + col + c.result.toUpperCase(Locale.ROOT) + " §8(cheaty: " + cheats + ", podejrzane: " + sus + ") §7- szczegóły w panelu");
        List<String> top = new ArrayList<>();
        if (c.report != null && c.report.has("findings")) for (var f : c.report.getAsJsonArray("findings")) {
            var o = f.getAsJsonObject();
            String lvl = o.get("level").getAsString();
            if (lvl.equals("info") || top.size() >= 6) continue;
            top.add((lvl.equals("cheat") ? "🔴 " : "🟡 ") + o.get("text").getAsString());
        }
        discord("anticheat", cheats > 0, "🔎 Sprawdzanie: " + Discord.safe(c.nick) + " — " + c.result.toUpperCase(Locale.ROOT),
            top.isEmpty() ? "Nie znaleziono śladów cheatów." : Discord.safe(String.join("\n", top)),
            cheats > 0 ? 0xff5b6b : sus > 0 ? 0xffc23d : 0x3ad08b,
            new String[][]{{"Wezwał", Discord.safe(c.by)}, {"Cheaty", String.valueOf(cheats)}, {"Podejrzane", String.valueOf(sus)}});
    }

    void notifyStaff(String legacy) {
        Component c = LegacyComponentSerializer.legacySection().deserialize(legacy);
        for (Player p : proxy.getAllPlayers()) if (p.hasPermission("westpanel.powiadomienia")) p.sendMessage(c);
    }

    static Store.Account newAccount(String login, String role, String password) {
        Store.Account a = new Store.Account();
        a.login = login;
        a.role = role;
        a.passHash = Crypto.hash(password);
        a.mustChangePassword = true;
        a.created = System.currentTimeMillis();
        return a;
    }

    // ------------------------------------------------------------ stan dla strony

    Map<String, Object> state(Store.Account me, long since) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("me", store.publicAccount(me));
        long now = System.currentTimeMillis();

        List<Map<String, Object>> servers = new ArrayList<>();
        Map<String, Object> px = new LinkedHashMap<>();
        px.put("name", "proxy"); px.put("online", proxy.getPlayerCount()); px.put("up", true);
        px.put("tps", null); px.put("ramUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()); px.put("ramMax", Runtime.getRuntime().maxMemory());
        servers.add(px);
        for (var reg : proxy.getAllServers()) {
            String name = reg.getServerInfo().getName().toLowerCase(Locale.ROOT);
            Store.ServerState s = store.servers.get(name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("online", reg.getPlayersConnected().size());
            boolean up = s != null && now - s.lastSeen < 15000;
            m.put("up", up);
            m.put("connected", s != null);
            m.put("tps", s == null ? null : s.tps);
            m.put("mspt", s == null ? null : s.mspt);
            m.put("ramUsed", s == null ? null : s.ramUsed);
            m.put("ramMax", s == null ? null : s.ramMax);
            servers.add(m);
        }
        out.put("servers", servers);

        // gracze: lista z proxy + pozycje z serwerow
        Map<String, Map<String, Object>> pos = new java.util.HashMap<>();
        for (Store.ServerState s : store.servers.values()) for (Map<String, Object> p : s.players) pos.put(((String) p.get("name")).toLowerCase(Locale.ROOT), p);
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getUsername());
            m.put("uuid", p.getUniqueId().toString());
            m.put("server", p.getCurrentServer().map(c -> c.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("—"));
            m.put("ping", p.getPing());
            Long j = joinedAt.get(p.getUniqueId());
            m.put("sessionMin", j == null ? 0 : (now - j) / 60000);
            m.put("staff", p.hasPermission("westpanel.powiadomienia"));
            Map<String, Object> ps = pos.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (ps != null) { m.put("world", ps.get("world")); m.put("x", ps.get("x")); m.put("z", ps.get("z")); m.put("health", ps.get("health")); m.put("gamemode", ps.get("gamemode")); }
            players.add(m);
        }
        out.put("players", players);

        List<Store.LogLine> logs = store.logsSince(since, since == 0 ? 300 : 500);
        out.put("logs", logs);
        long open;
        synchronized (store) { open = store.reports.stream().filter(r -> !"done".equals(r.status)).count(); }
        out.put("reportsOpen", open);
        out.put("onDuty", store.accounts.values().stream().filter(a -> a.onDuty).count());
        List<int[]> h = store.history();
        out.put("history", h);
        out.put("bluemap", cfg.getProperty("bluemap", ""));
        out.put("spawns", store.spawns);
        long hourAgo = now - 3600_000L;
        long ac;
        synchronized (store) { ac = store.acAlerts.stream().filter(x -> x.time > hourAgo).count(); }
        out.put("acHour", ac);
        return out;
    }

    // ------------------------------------------------------------ komenda /ss <nick> (ekipa w grze)

    private final class SsCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation inv) { return inv.source().hasPermission("westpanel.ss"); }

        @Override
        public void execute(Invocation inv) {
            var src = inv.source();
            String[] a = inv.arguments();
            if (a.length < 1 || !a[0].matches("[A-Za-z0-9_]{3,16}")) { src.sendMessage(Component.text("Użycie: /ss <nick>  - wzywa gracza na sprawdzanie programem WestSS")); return; }
            String by = src instanceof Player p ? p.getUsername() : "konsola";
            Store.SsCheck c = store.newSs(a[0], by);
            store.log("proxy", "panel", by + " wezwał " + a[0] + " na sprawdzanie (kod " + c.code + ")");
            ssNotify(a[0], c.code, by);
            if (proxy.getPlayer(a[0]).isEmpty()) src.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§eGracz §f" + a[0] + " §ejest offline - podaj mu kod §f" + c.code + " §ena Discordzie."));
        }

        @Override
        public List<String> suggest(Invocation inv) {
            String pre = inv.arguments().length > 0 ? inv.arguments()[0].toLowerCase(Locale.ROOT) : "";
            List<String> out = new ArrayList<>();
            for (Player p : proxy.getAllPlayers()) if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getUsername());
            return out;
        }
    }

    // ------------------------------------------------------------ komenda /panel (konsola proxy)

    private final class PanelCommand implements SimpleCommand {
        @Override
        public void execute(Invocation inv) {
            var src = inv.source();
            String[] a = inv.arguments();
            if (src instanceof Player) {
                src.sendMessage(Component.text("Komendy panelu działają tylko w konsoli proxy."));
                return;
            }
            if (a.length >= 3 && a[0].equalsIgnoreCase("konto")) {
                String login = a[1], role = a[2].toLowerCase(Locale.ROOT);
                if (!Store.ROLE_LEVEL.containsKey(role)) { src.sendMessage(Component.text("Rangi: pomocnik, moderator, admin, wlasciciel")); return; }
                if (!login.matches("[A-Za-z0-9_.-]{3,24}")) { src.sendMessage(Component.text("Login: 3-24 znaki, litery, cyfry, _ . -")); return; }
                String pw = Crypto.tempPassword();
                Store.Account acc = store.accounts.get(login.toLowerCase(Locale.ROOT));
                if (acc == null) { acc = newAccount(login, role, pw); store.accounts.put(login.toLowerCase(Locale.ROOT), acc); }
                else { acc.role = role; acc.passHash = Crypto.hash(pw); acc.mustChangePassword = true; acc.totpSecret = null; acc.pendingTotp = null; }
                store.saveAccounts();
                src.sendMessage(Component.text("Konto " + login + " (" + role + "). Hasło startowe: " + pw + " - zmienisz je przy pierwszym logowaniu."));
                return;
            }
            if (a.length >= 1 && a[0].equalsIgnoreCase("klucz")) {
                src.sendMessage(Component.text("Klucz serwerów: " + ingestKey()));
                return;
            }
            src.sendMessage(Component.text("panel konto <login> <pomocnik|moderator|admin|wlasciciel>  - zakłada konto lub resetuje hasło i 2FA"));
            src.sendMessage(Component.text("panel klucz  - pokazuje klucz do configu pluginu na serwerach"));
        }
    }
}
