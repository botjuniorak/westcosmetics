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

@Plugin(id = "westpanel", name = "WestPanel", version = "1.0.0", description = "Panel administracji WestMC", authors = {"WestMC"})
public final class WestPanel {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private Store store;
    private Web web;
    private Properties cfg;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

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
        proxy.getScheduler().buildTask(this, () -> store.recordHistory(proxy.getPlayerCount())).repeat(5, TimeUnit.MINUTES).schedule();
        store.recordHistory(proxy.getPlayerCount());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (web != null) web.stop();
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
        return out;
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
