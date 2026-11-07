package pl.westmc.panel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Serwer WWW panelu: strona + API. Wbudowany w Jave, bez dodatkowych bibliotek. */
final class Web {
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,4})(s|m|h|d|w|mo)|perm");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_.-]{3,24}");
    private static final long SESSION_MS = 12L * 3600 * 1000;

    private final WestPanel pl;
    private final Store st;
    private final Gson gson = new Gson();
    private final Map<String, int[]> loginFails = new ConcurrentHashMap<>();   // ip -> [ile, minuta]
    private HttpServer http;
    private byte[] indexHtml;

    Web(WestPanel pl, Store st) { this.pl = pl; this.st = st; }

    void start(String bind, int port) throws IOException {
        try (InputStream in = Web.class.getResourceAsStream("/web/index.html")) {
            indexHtml = in == null ? "brak strony".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        }
        http = HttpServer.create(new InetSocketAddress(bind, port), 64);
        http.setExecutor(Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r, "WestPanel-web"); t.setDaemon(true); return t; }));
        http.createContext("/", this::handle);
        http.start();
    }

    void stop() { if (http != null) http.stop(1); }

    // ------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) { send(ex, 200, "text/html; charset=utf-8", indexHtml); return; }
            if (!path.startsWith("/api/")) { json(ex, 404, err("Nie ma takiej strony.")); return; }

            if (path.equals("/api/ingest") && method.equals("POST")) { ingest(ex); return; }

            if (method.equals("POST") && !"1".equals(ex.getRequestHeaders().getFirst("X-WestPanel"))) {
                json(ex, 403, err("Brak nagłówka panelu.")); return;
            }
            switch (path) {
                case "/api/login" -> login(ex);
                case "/api/totp" -> totp(ex);
                case "/api/password" -> password(ex);
                case "/api/logout" -> logout(ex);
                default -> {
                    Store.Session s = session(ex);
                    if (s == null || !s.totpPassed) { json(ex, 401, err("Zaloguj się ponownie.")); return; }
                    Store.Account a = st.accounts.get(s.login.toLowerCase(Locale.ROOT));
                    if (a == null) { json(ex, 401, err("Konto zostało usunięte.")); return; }
                    if (a.mustChangePassword && !path.equals("/api/password")) { json(ex, 403, err("Najpierw zmień hasło startowe.")); return; }
                    api(ex, path, method, a);
                }
            }
        } catch (Exception e) {
            pl.logger().warn("Blad panelu: {}", e.toString());
            try { json(ex, 500, err("Błąd serwera panelu: " + e.getMessage())); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path, String method, Store.Account a) throws IOException {
        int lvl = Store.ROLE_LEVEL.getOrDefault(a.role, 0);
        switch (method + " " + path) {
            case "GET /api/state" -> json(ex, 200, pl.state(a, longParam(ex, "since")));
            case "POST /api/duty" -> {
                a.onDuty = body(ex).get("on").getAsBoolean();
                st.saveAccounts();
                json(ex, 200, Map.of("ok", true));
            }
            case "GET /api/punishments" -> {
                List<Store.Punishment> list;
                synchronized (st) { list = new ArrayList<>(st.punishments); }
                java.util.Collections.reverse(list);
                json(ex, 200, Map.of("items", list.size() > 500 ? list.subList(0, 500) : list));
            }
            case "POST /api/punish" -> punish(ex, a, lvl);
            case "GET /api/reports" -> {
                List<Store.Report> list;
                synchronized (st) { list = new ArrayList<>(st.reports); }
                java.util.Collections.reverse(list);
                json(ex, 200, Map.of("items", list.size() > 300 ? list.subList(0, 300) : list));
            }
            case "POST /api/report" -> reportAction(ex, a);
            case "POST /api/command" -> {
                if (lvl < 3) { json(ex, 403, err("Konsola jest dostępna od rangi Administrator.")); return; }
                JsonObject b = body(ex);
                String server = str(b, "server").toLowerCase(Locale.ROOT);
                String cmd = clean(str(b, "command"), 250);
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isBlank()) { json(ex, 400, err("Wpisz komendę.")); return; }
                if (server.equals("proxy")) pl.runProxyCommand(cmd);
                else if (!st.servers.containsKey(server)) { json(ex, 400, err("Serwer " + server + " nie jest połączony z panelem.")); return; }
                else st.queueCommand(server, cmd);
                st.log(server, "cmd", "[panel] " + a.login + ": /" + cmd);
                json(ex, 200, Map.of("ok", true));
            }
            case "GET /api/accounts" -> {
                if (lvl < 4) { json(ex, 403, err("Kontami ekipy zarządza właściciel.")); return; }
                List<Map<String, Object>> out = new ArrayList<>();
                for (Store.Account x : st.accounts.values()) out.add(st.publicAccount(x));
                json(ex, 200, Map.of("items", out));
            }
            case "POST /api/accounts" -> accounts(ex, a, lvl);
            default -> json(ex, 404, err("Nieznana akcja."));
        }
    }

    // ------------------------------------------------------------ logowanie

    private void login(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        int minute = (int) (System.currentTimeMillis() / 60000);
        int[] f = loginFails.get(ip);
        if (f != null && minute - f[1] < 10 && f[0] >= 5) { json(ex, 429, err("Za dużo prób. Spróbuj za 10 minut.")); return; }

        JsonObject b = body(ex);
        Store.Account a = st.accounts.get(str(b, "login").toLowerCase(Locale.ROOT));
        if (a == null || !Crypto.verify(str(b, "password"), a.passHash)) {
            loginFails.merge(ip, new int[]{1, minute}, (o, n) -> minute - o[1] < 10 ? new int[]{o[0] + 1, o[1]} : n);
            st.log("panel", "panel", "Nieudane logowanie na konto '" + clean(str(b, "login"), 24) + "' z " + ip);
            json(ex, 401, err("Zły login albo hasło."));
            return;
        }
        loginFails.remove(ip);
        Store.Session s = new Store.Session();
        s.token = Crypto.token(32);
        s.login = a.login;
        s.expires = System.currentTimeMillis() + SESSION_MS;
        st.sessions.put(s.token, s);
        ex.getResponseHeaders().add("Set-Cookie", "wp_session=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200" + (pl.secureCookie() ? "; Secure" : ""));

        Map<String, Object> out = new HashMap<>();
        if (a.totpSecret == null) {
            if (a.pendingTotp == null) { a.pendingTotp = Crypto.newTotpSecret(); st.saveAccounts(); }
            out.put("need", "setup");
            out.put("secret", a.pendingTotp);
            out.put("otpauth", "otpauth://totp/" + enc("WestMC Panel:" + a.login) + "?secret=" + a.pendingTotp + "&issuer=" + enc("WestMC Panel"));
        } else {
            out.put("need", "totp");
        }
        json(ex, 200, out);
    }

    private void totp(HttpExchange ex) throws IOException {
        Store.Session s = session(ex);
        if (s == null) { json(ex, 401, err("Sesja wygasła, zaloguj się ponownie.")); return; }
        Store.Account a = st.accounts.get(s.login.toLowerCase(Locale.ROOT));
        String code = str(body(ex), "code");
        boolean ok;
        if (a.totpSecret == null) {
            ok = Crypto.totpOk(a.pendingTotp, code);
            if (ok) { a.totpSecret = a.pendingTotp; a.pendingTotp = null; }
        } else ok = Crypto.totpOk(a.totpSecret, code);
        if (!ok) { json(ex, 401, err("Zły kod. Sprawdź, czy godzina w telefonie ustawia się automatycznie.")); return; }
        s.totpPassed = true;
        a.lastLogin = System.currentTimeMillis();
        st.saveAccounts();
        st.log("panel", "panel", a.login + " zalogował się do panelu.");
        json(ex, 200, Map.of("need", a.mustChangePassword ? "password" : "ok"));
    }

    private void password(HttpExchange ex) throws IOException {
        Store.Session s = session(ex);
        if (s == null || !s.totpPassed) { json(ex, 401, err("Zaloguj się ponownie.")); return; }
        Store.Account a = st.accounts.get(s.login.toLowerCase(Locale.ROOT));
        JsonObject b = body(ex);
        String nw = str(b, "new");
        if (!a.mustChangePassword && !Crypto.verify(str(b, "old"), a.passHash)) { json(ex, 400, err("Obecne hasło się nie zgadza.")); return; }
        if (nw.length() < 10) { json(ex, 400, err("Nowe hasło musi mieć co najmniej 10 znaków.")); return; }
        a.passHash = Crypto.hash(nw);
        a.mustChangePassword = false;
        st.saveAccounts();
        // wyloguj inne sesje tego konta
        st.sessions.values().removeIf(x -> x.login.equalsIgnoreCase(a.login) && !x.token.equals(s.token));
        json(ex, 200, Map.of("ok", true));
    }

    private void logout(HttpExchange ex) throws IOException {
        Store.Session s = session(ex);
        if (s != null) st.sessions.remove(s.token);
        ex.getResponseHeaders().add("Set-Cookie", "wp_session=; Path=/; Max-Age=0");
        json(ex, 200, Map.of("ok", true));
    }

    private Store.Session session(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("wp_session=")) {
                Store.Session s = st.sessions.get(p.substring(11));
                if (s == null) return null;
                if (s.expires < System.currentTimeMillis()) { st.sessions.remove(s.token); return null; }
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ kary (AdvancedBan)

    private void punish(HttpExchange ex, Store.Account a, int lvl) throws IOException {
        JsonObject b = body(ex);
        String nick = str(b, "nick");
        String type = str(b, "type").toLowerCase(Locale.ROOT);
        String dur = str(b, "duration").toLowerCase(Locale.ROOT).replace(" ", "");
        String reason = clean(str(b, "reason"), 120);
        String server = str(b, "server").toLowerCase(Locale.ROOT);
        if (!NICK.matcher(nick).matches()) { json(ex, 400, err("Nick może mieć tylko litery, cyfry i _, do 16 znaków.")); return; }
        if (reason.isBlank()) { json(ex, 400, err("Wpisz powód kary.")); return; }
        boolean timed = type.equals("ban") || type.equals("mute");
        if (timed && !DURATION.matcher(dur).matches()) { json(ex, 400, err("Czas wpisz np. 30m, 6h, 7d albo perm.")); return; }

        long minutes = timed ? minutes(dur) : 0;
        if (lvl < 2 && (type.equals("ban") || (type.equals("mute") && minutes > 60))) { json(ex, 403, err("Pomocnik może dać mute do 1h, kick i ostrzeżenie.")); return; }
        if (lvl < 3 && type.equals("ban") && (minutes < 0 || minutes > 7 * 1440)) { json(ex, 403, err("Moderator może dać bana do 7 dni. Dłuższy nadaje administrator.")); return; }

        String cmd = switch (type) {
            case "ban" -> minutes < 0 ? "ban " + nick + " " + reason : "tempban " + nick + " " + dur + " " + reason;
            case "mute" -> minutes < 0 ? "mute " + nick + " " + reason : "tempmute " + nick + " " + dur + " " + reason;
            case "kick" -> "kick " + nick + " " + reason;
            case "warn" -> "warn " + nick + " " + reason;
            default -> null;
        };
        if (cmd == null) { json(ex, 400, err("Nieznany rodzaj kary.")); return; }

        List<String> targets = new ArrayList<>();
        if (server.isBlank() || server.equals("wszystkie")) targets.addAll(st.servers.keySet());
        else if (st.servers.containsKey(server)) targets.add(server);
        if (targets.isEmpty()) { json(ex, 400, err("Żaden serwer nie jest połączony z panelem.")); return; }
        for (String t : targets) st.queueCommand(t, cmd);

        Store.Punishment p = new Store.Punishment();
        p.id = st.nextId();
        p.time = System.currentTimeMillis();
        p.nick = nick; p.type = type; p.duration = timed ? dur : "—"; p.reason = reason; p.by = a.login;
        p.server = String.join(", ", targets); p.command = "/" + cmd;
        synchronized (st) { st.punishments.add(p); }
        st.savePunishments();
        st.log("panel", "kara", a.login + " → " + type + " " + (timed ? dur + " " : "") + nick + ": " + reason);

        long reportId = b.has("reportId") && !b.get("reportId").isJsonNull() ? b.get("reportId").getAsLong() : 0;
        if (reportId > 0) closeReport(reportId, a.login, "kara: " + type + (timed ? " " + dur : ""));
        json(ex, 200, Map.of("ok", true, "command", p.command, "servers", targets));
    }

    private static long minutes(String dur) {
        if (dur.equals("perm")) return -1;
        var m = DURATION.matcher(dur);
        if (!m.matches()) return 0;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> 0; case "m" -> n; case "h" -> n * 60; case "d" -> n * 1440; case "w" -> n * 10080; default -> n * 43200;
        };
    }

    // ------------------------------------------------------------ zgloszenia

    private void reportAction(HttpExchange ex, Store.Account a) throws IOException {
        JsonObject b = body(ex);
        long id = b.get("id").getAsLong();
        String action = str(b, "action");
        Store.Report r;
        synchronized (st) { r = st.reports.stream().filter(x -> x.id == id).findFirst().orElse(null); }
        if (r == null) { json(ex, 404, err("Nie ma takiego zgłoszenia.")); return; }
        switch (action) {
            case "claim" -> { r.status = "claimed"; r.claimedBy = a.login; }
            case "close" -> closeReport(id, a.login, "bez kary");
            default -> { json(ex, 400, err("Nieznana akcja.")); return; }
        }
        st.saveReports();
        json(ex, 200, Map.of("ok", true));
    }

    private void closeReport(long id, String by, String resolution) {
        synchronized (st) {
            for (Store.Report r : st.reports) if (r.id == id) { r.status = "done"; r.claimedBy = by; r.resolution = resolution; }
        }
        st.saveReports();
    }

    // ------------------------------------------------------------ konta ekipy

    private void accounts(HttpExchange ex, Store.Account me, int lvl) throws IOException {
        if (lvl < 4) { json(ex, 403, err("Kontami ekipy zarządza właściciel.")); return; }
        JsonObject b = body(ex);
        String action = str(b, "action");
        String login = str(b, "login");
        Store.Account a = st.accounts.get(login.toLowerCase(Locale.ROOT));
        switch (action) {
            case "add" -> {
                String role = str(b, "role");
                if (!LOGIN.matcher(login).matches()) { json(ex, 400, err("Login: 3–24 znaki, litery, cyfry, _ . -")); return; }
                if (!Store.ROLE_LEVEL.containsKey(role)) { json(ex, 400, err("Nieznana ranga.")); return; }
                if (a != null) { json(ex, 400, err("Takie konto już istnieje.")); return; }
                String pw = Crypto.tempPassword();
                Store.Account n = WestPanel.newAccount(login, role, pw);
                st.accounts.put(login.toLowerCase(Locale.ROOT), n);
                st.saveAccounts();
                st.log("panel", "panel", me.login + " założył konto " + login + " (" + role + ")");
                json(ex, 200, Map.of("ok", true, "password", pw));
            }
            case "reset" -> {
                if (a == null) { json(ex, 404, err("Nie ma takiego konta.")); return; }
                String pw = Crypto.tempPassword();
                a.passHash = Crypto.hash(pw); a.mustChangePassword = true; a.totpSecret = null; a.pendingTotp = null;
                st.saveAccounts();
                st.sessions.values().removeIf(x -> x.login.equalsIgnoreCase(a.login));
                st.log("panel", "panel", me.login + " zresetował hasło i 2FA konta " + a.login);
                json(ex, 200, Map.of("ok", true, "password", pw));
            }
            case "role" -> {
                String role = str(b, "role");
                if (a == null || !Store.ROLE_LEVEL.containsKey(role)) { json(ex, 400, err("Złe konto albo ranga.")); return; }
                if (a.login.equalsIgnoreCase(me.login)) { json(ex, 400, err("Nie zmieniasz rangi samemu sobie.")); return; }
                a.role = role; st.saveAccounts();
                json(ex, 200, Map.of("ok", true));
            }
            case "delete" -> {
                if (a == null) { json(ex, 404, err("Nie ma takiego konta.")); return; }
                if (a.login.equalsIgnoreCase(me.login)) { json(ex, 400, err("Nie usuwasz własnego konta.")); return; }
                st.accounts.remove(login.toLowerCase(Locale.ROOT));
                st.sessions.values().removeIf(x -> x.login.equalsIgnoreCase(login));
                st.saveAccounts();
                st.log("panel", "panel", me.login + " usunął konto " + login);
                json(ex, 200, Map.of("ok", true));
            }
            default -> json(ex, 400, err("Nieznana akcja."));
        }
    }

    // ------------------------------------------------------------ dane z serwerow (plugin Paper)

    private void ingest(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("X-WestPanel-Key");
        if (key == null || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), pl.ingestKey().getBytes(StandardCharsets.UTF_8))) {
            json(ex, 403, err("Zły klucz serwera.")); return;
        }
        JsonObject b = body(ex);
        String name = str(b, "server").toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,24}")) { json(ex, 400, err("Zła nazwa serwera.")); return; }
        Store.ServerState s = st.servers.computeIfAbsent(name, k -> { Store.ServerState n = new Store.ServerState(); n.name = k; return n; });
        s.tps = b.get("tps").getAsDouble();
        s.mspt = b.get("mspt").getAsDouble();
        s.ramUsed = b.get("ramUsed").getAsLong();
        s.ramMax = b.get("ramMax").getAsLong();
        s.lastSeen = System.currentTimeMillis();
        List<Map<String, Object>> players = new ArrayList<>();
        for (var e : b.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", str(p, "name"));
            m.put("world", str(p, "world"));
            m.put("x", p.get("x").getAsInt());
            m.put("y", p.get("y").getAsInt());
            m.put("z", p.get("z").getAsInt());
            m.put("health", p.get("health").getAsDouble());
            m.put("gamemode", str(p, "gamemode"));
            players.add(m);
        }
        s.players = players;
        s.online = players.size();
        if (b.has("events")) for (var e : b.getAsJsonArray("events")) {
            JsonObject ev = e.getAsJsonObject();
            String type = str(ev, "type");
            if (type.equals("report")) {
                Store.Report r = new Store.Report();
                r.id = st.nextId();
                r.time = System.currentTimeMillis();
                r.from = clean(str(ev, "from"), 16); r.target = clean(str(ev, "target"), 16); r.reason = clean(str(ev, "reason"), 200); r.server = name;
                synchronized (st) { st.reports.add(r); }
                st.saveReports();
                st.log(name, "report", r.from + " zgłosił " + r.target + ": " + r.reason);
                pl.notifyStaff("§c[Zgłoszenie] §f" + r.from + " §7zgłosił §f" + r.target + "§7: " + r.reason);
            } else if (List.of("chat", "cmd", "join", "death", "ac").contains(type)) {
                st.log(name, type, clean(str(ev, "text"), 400));
            }
        }
        json(ex, 200, Map.of("commands", st.drainCommands(name)));
    }

    // ------------------------------------------------------------ pomocnicze

    private JsonObject body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(256 * 1024);
        String s = new String(raw, StandardCharsets.UTF_8);
        return s.isBlank() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    /** Bez znakow nowej linii i kolorow - zeby nikt nie wstrzyknal drugiej komendy. */
    private static String clean(String s, int max) {
        s = s.replaceAll("[\\r\\n\\t§]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long longParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return 0;
        for (String p : q.split("&")) if (p.startsWith(name + "=")) try { return Long.parseLong(p.substring(name.length() + 1)); } catch (NumberFormatException ignored) { }
        return 0;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static Map<String, Object> err(String msg) { return Map.of("error", msg); }

    private void json(HttpExchange ex, int code, Object o) throws IOException {
        send(ex, code, "application/json; charset=utf-8", gson.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data: https://mc-heads.net; connect-src 'self'; frame-ancestors 'none'");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
