package pl.westmc.panel.paper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wysyla do panelu: graczy z pozycjami, TPS, RAM i zdarzenia; wykonuje komendy z panelu (np. kary AdvancedBan). */
public final class WestPanelPaper extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ConcurrentLinkedQueue<JsonObject> events = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> reportCooldown = new ConcurrentHashMap<>();
    private final AtomicBoolean sending = new AtomicBoolean();
    private boolean warned;
    private String url, key, server;
    private boolean chatLogs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        url = getConfig().getString("panel", "").replaceAll("/+$", "") + "/api/ingest";
        key = getConfig().getString("klucz", "");
        server = getConfig().getString("serwer", "serwer");
        chatLogs = getConfig().getBoolean("logi-czatu", true);
        int every = Math.max(2, Math.min(10, getConfig().getInt("co-ile-sekund", 2)));
        getServer().getPluginManager().registerEvents(this, this);
        if (key.isBlank() || key.equals("WPISZ-KLUCZ")) {
            getLogger().warning("Wpisz klucz panelu w plugins/WestPanel/config.yml (konsola proxy: panel klucz) i zrestartuj serwer.");
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, every * 20L);
        getLogger().info("Wysyłam dane do panelu jako serwer '" + server + "'.");
    }

    private void event(String type, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("text", text);
        events.add(o);
        while (events.size() > 500) events.poll();
    }

    /** Watek glowny: zbiera stan, wysyla asynchronicznie. */
    private void tick() {
        if (!sending.compareAndSet(false, true)) return;
        JsonObject body = new JsonObject();
        body.addProperty("server", server);
        double[] tps = Bukkit.getTPS();
        body.addProperty("tps", Math.min(20.0, Math.round(tps[0] * 10) / 10.0));
        body.addProperty("mspt", Math.round(Bukkit.getAverageTickTime() * 10) / 10.0);
        Runtime rt = Runtime.getRuntime();
        body.addProperty("ramUsed", rt.totalMemory() - rt.freeMemory());
        body.addProperty("ramMax", rt.maxMemory());
        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("world", l.getWorld().getName());
            o.addProperty("x", l.getBlockX());
            o.addProperty("y", l.getBlockY());
            o.addProperty("z", l.getBlockZ());
            o.addProperty("health", Math.round(p.getHealth() * 10) / 10.0);
            o.addProperty("gamemode", p.getGameMode().name());
            players.add(o);
        }
        body.add("players", players);
        JsonArray ev = new JsonArray();
        List<JsonObject> taken = new ArrayList<>();
        JsonObject e;
        while ((e = events.poll()) != null && taken.size() < 300) { ev.add(e); taken.add(e); }
        body.add("events", ev);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-WestPanel-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            sending.set(false);
            if (err != null || res.statusCode() != 200) {
                events.addAll(taken);   // sprobuj wyslac zdarzenia nastepnym razem
                if (!warned) {
                    warned = true;
                    getLogger().warning("Panel nie odpowiada (" + (err != null ? err.getClass().getSimpleName() : "HTTP " + res.statusCode()) + "). Sprawdź adres i klucz w configu.");
                }
                return;
            }
            if (warned) { warned = false; getLogger().info("Połączono z panelem."); }
            try {
                JsonObject r = JsonParser.parseString(res.body()).getAsJsonObject();
                List<String> cmds = new ArrayList<>();
                for (var c : r.getAsJsonArray("commands")) cmds.add(c.getAsString());
                if (!cmds.isEmpty()) Bukkit.getScheduler().runTask(this, () -> {
                    for (String c : cmds) {
                        getLogger().info("Komenda z panelu: /" + c);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                    }
                });
            } catch (Exception ex) {
                getLogger().warning("Zła odpowiedź panelu: " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------ zdarzenia do logow

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (chatLogs) event("chat", "<" + e.getPlayer().getName() + "> " + PlainTextComponentSerializer.plainText().serialize(e.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        String low = m.toLowerCase();
        // hasla z pluginow logowania nie trafiaja do logow
        if (low.startsWith("/login") || low.startsWith("/l ") || low.startsWith("/register") || low.startsWith("/reg ") || low.startsWith("/changepassword")) m = m.split(" ")[0] + " ***";
        event("cmd", e.getPlayer().getName() + " użył " + m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { event("join", e.getPlayer().getName() + " dołączył"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { event("join", e.getPlayer().getName() + " wyszedł"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player k = e.getEntity().getKiller();
        event("death", k != null ? k.getName() + " zabił " + e.getEntity().getName() : e.getEntity().getName() + " zginął");
    }

    // ------------------------------------------------------------ /zglos

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko dla graczy."); return true; }
        if (a.length < 2) { p.sendMessage("§cUżycie: /zglos <nick> <powód>"); return true; }
        if (!a[0].matches("[A-Za-z0-9_]{1,16}")) { p.sendMessage("§cZły nick."); return true; }
        if (a[0].equalsIgnoreCase(p.getName())) { p.sendMessage("§cNie zgłaszasz samego siebie."); return true; }
        long cd = getConfig().getLong("zglos-cooldown", 60) * 1000;
        Long last = reportCooldown.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cd) { p.sendMessage("§cOdczekaj chwilę przed kolejnym zgłoszeniem."); return true; }
        reportCooldown.put(p.getUniqueId(), System.currentTimeMillis());
        JsonObject o = new JsonObject();
        o.addProperty("type", "report");
        o.addProperty("from", p.getName());
        o.addProperty("target", a[0]);
        o.addProperty("reason", String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
        events.add(o);
        p.sendMessage("§aZgłoszenie wysłane do administracji. Dzięki!");
        return true;
    }
}
