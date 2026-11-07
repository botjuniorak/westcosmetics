package pl.westmc.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Wszystkie dane panelu: konta, kary, zgloszenia (pliki JSON) + logi i stan serwerow (w pamieci). */
final class Store {
    // ------------------------------------------------------------ modele
    static final class Account {
        String login;
        String role;            // pomocnik / moderator / admin / wlasciciel
        String passHash;
        String totpSecret;      // null = 2FA jeszcze nie aktywne
        String pendingTotp;     // sekret pokazany przy pierwszym logowaniu, zatwierdzany kodem
        boolean mustChangePassword;
        boolean onDuty;
        long created, lastLogin;
    }

    static final class Punishment {
        long id, time;
        String nick, type, duration, reason, by, server, command;
    }

    static final class Report {
        long id, time;
        String from, target, reason, server, status = "open", claimedBy, resolution;
    }

    static final class LogLine {
        long id, time;
        String server, type, text;
    }

    static final class ServerState {
        String name;
        double tps, mspt;
        long ramUsed, ramMax, lastSeen;
        int online;
        List<Map<String, Object>> players = new ArrayList<>();
    }

    static final class Session {
        String token, login, csrf;
        long expires;
        boolean totpPassed;
    }

    static final Map<String, Integer> ROLE_LEVEL = Map.of("pomocnik", 1, "moderator", 2, "admin", 3, "wlasciciel", 4);

    // ------------------------------------------------------------ stan
    private final Path dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    final Map<String, Account> accounts = new ConcurrentHashMap<>();
    final List<Punishment> punishments = new ArrayList<>();
    final List<Report> reports = new ArrayList<>();
    final Map<String, Session> sessions = new ConcurrentHashMap<>();
    final Map<String, ServerState> servers = new ConcurrentHashMap<>();
    final Map<String, Deque<String>> commandQueues = new ConcurrentHashMap<>();
    final Deque<LogLine> logs = new ArrayDeque<>();
    final Deque<int[]> history = new ArrayDeque<>();   // [minuta od epoki, gracze online], co 5 min, 24 h
    private final AtomicLong logSeq = new AtomicLong();
    private long nextId = 1;

    Store(Path dir) { this.dir = dir; }

    // ------------------------------------------------------------ zapis/odczyt
    synchronized void load() throws IOException {
        Files.createDirectories(dir);
        List<Account> acc = read("konta.json", new TypeToken<List<Account>>() { });
        if (acc != null) for (Account a : acc) accounts.put(a.login.toLowerCase(Locale.ROOT), a);
        List<Punishment> p = read("kary.json", new TypeToken<List<Punishment>>() { });
        if (p != null) punishments.addAll(p);
        List<Report> r = read("zgloszenia.json", new TypeToken<List<Report>>() { });
        if (r != null) reports.addAll(r);
        for (Punishment x : punishments) nextId = Math.max(nextId, x.id + 1);
        for (Report x : reports) nextId = Math.max(nextId, x.id + 1);
    }

    private <T> T read(String file, TypeToken<T> type) throws IOException {
        Path f = dir.resolve(file);
        if (!Files.exists(f)) return null;
        return gson.fromJson(Files.readString(f, StandardCharsets.UTF_8), type.getType());
    }

    synchronized void saveAccounts() { write("konta.json", new ArrayList<>(accounts.values())); }
    synchronized void savePunishments() { write("kary.json", punishments); }
    synchronized void saveReports() { write("zgloszenia.json", reports); }

    private void write(String file, Object data) {
        try {
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, gson.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Nie zapisano " + file + ": " + e.getMessage(), e);
        }
    }

    synchronized long nextId() { return nextId++; }

    // ------------------------------------------------------------ logi
    void log(String server, String type, String text) {
        LogLine l = new LogLine();
        l.id = logSeq.incrementAndGet();
        l.time = System.currentTimeMillis();
        l.server = server;
        l.type = type;
        l.text = text;
        synchronized (logs) {
            logs.addLast(l);
            while (logs.size() > 3000) logs.removeFirst();
        }
        appendDailyLog(l);
    }

    private void appendDailyLog(LogLine l) {
        try {
            Path d = dir.resolve("logi");
            Files.createDirectories(d);
            String day = java.time.LocalDate.now().toString();
            String line = java.time.LocalTime.now().withNano(0) + " [" + l.server + "] [" + l.type + "] " + l.text + System.lineSeparator();
            Files.writeString(d.resolve(day + ".log"), line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logi plikowe sa dodatkiem; brak miejsca nie moze zatrzymac panelu
        }
    }

    List<LogLine> logsSince(long since, int max) {
        List<LogLine> out = new ArrayList<>();
        synchronized (logs) {
            for (LogLine l : logs) if (l.id > since) out.add(l);
        }
        return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
    }

    // ------------------------------------------------------------ kolejka komend dla serwerow
    void queueCommand(String server, String command) {
        commandQueues.computeIfAbsent(server.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>()).add(command);
    }

    List<String> drainCommands(String server) {
        Deque<String> q = commandQueues.get(server.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        if (q == null) return out;
        synchronized (q) { while (!q.isEmpty()) out.add(q.poll()); }
        return out;
    }

    // ------------------------------------------------------------ historia graczy (wykres 24 h)
    void recordHistory(int online) {
        synchronized (history) {
            history.addLast(new int[]{(int) (System.currentTimeMillis() / 60000), online});
            while (history.size() > 288) history.removeFirst();
        }
    }

    List<int[]> history() { synchronized (history) { return new ArrayList<>(history); } }

    Map<String, Object> publicAccount(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("login", a.login);
        m.put("role", a.role);
        m.put("twofa", a.totpSecret != null);
        m.put("onDuty", a.onDuty);
        m.put("lastLogin", a.lastLogin);
        return m;
    }
}
