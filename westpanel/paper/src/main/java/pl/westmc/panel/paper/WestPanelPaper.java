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
    Guard guard;
    private DataSync sync;
    private int syncTick;

    /** Alert anticheatu: do panelu i do ekipy online (uprawnienie westguard.alerty). */
    void acAlert(String player, String check, int vl, String info, String source) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ac");
        o.addProperty("player", player);
        o.addProperty("check", check);
        o.addProperty("vl", vl);
        o.addProperty("info", info);
        o.addProperty("src", source);
        o.addProperty("text", "[" + source + "] " + player + " · " + check + " (VL " + vl + ")" + (info.isBlank() ? "" : " · " + info));
        events.add(o);
        String msg = "§8[§c§lAC§8] §f" + player + " §7" + check + " §8(§cVL " + vl + "§8) §7" + info;
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("westguard.alerty")) p.sendMessage(msg);
        });
    }

    /** Linki serwera (1.21+): w menu ESC pojawia sie przycisk z Discordem i strona. */
    private void serverLinks() {
        try {
            var links = getServer().getServerLinks();
            for (var l : new java.util.ArrayList<>(links.getLinks())) links.removeLink(l);
            String dc = getConfig().getString("discord", "https://discord.gg/westmceu");
            String www = getConfig().getString("strona", "https://westmc.eu");
            if (dc != null && !dc.isBlank()) links.addLink(org.bukkit.ServerLinks.Type.COMMUNITY, java.net.URI.create(dc));
            if (www != null && !www.isBlank()) links.addLink(org.bukkit.ServerLinks.Type.WEBSITE, java.net.URI.create(www));
        } catch (Throwable t) {
            getLogger().warning("Linki serwera niedostępne: " + t.getMessage());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        url = getConfig().getString("panel", "").replaceAll("/+$", "") + "/api/ingest";
        serverLinks();
        key = getConfig().getString("klucz", "");
        server = getConfig().getString("serwer", "serwer");
        chatLogs = getConfig().getBoolean("logi-czatu", true);
        int every = Math.max(2, Math.min(10, getConfig().getInt("co-ile-sekund", 2)));
        getServer().getPluginManager().registerEvents(this, this);
        sync = new DataSync(getDataFolder().getParentFile());
        guard = new Guard(this);
        getServer().getPluginManager().registerEvents(guard, this);
        Bukkit.getScheduler().runTask(this, () -> {
            boolean grim = guard.cfg.getBoolean("grim", true) && GrimHook.register(guard);
            boolean clicks = false;
            if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
                try { ClickCheck.register(guard); clicks = true; } catch (Throwable t) { getLogger().warning("Autoklik wyłączony: " + t); }
            }
            getLogger().info("WestGuard: x-ray i killaura włączone, autoklik " + (clicks ? "włączony" : "wyłączony (brak packetevents)") + ", Grim " + (grim ? "podpięty" : "nie znaleziony") + ", tryb: " + (guard.punishMode ? "kary" : "alerty") + ".");
        });
        if (key.isBlank() || key.equals("WPISZ-KLUCZ")) {
            getLogger().warning("Wpisz klucz panelu w plugins/WestPanel/config.yml (konsola proxy: panel klucz) i zrestartuj serwer.");
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, every * 20L);
        getLogger().info("Wysyłam dane do panelu jako serwer '" + server + "'.");
    }

    void event(String type, String text) {
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
        JsonObject spawns = new JsonObject();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            Location sp = w.getSpawnLocation();
            JsonArray a = new JsonArray(); a.add(sp.getBlockX()); a.add(sp.getBlockZ());
            spawns.add(w.getName(), a);
        }
        body.add("spawns", spawns);
        body.add("tiles", renderTiles());
        JsonArray ev = new JsonArray();
        List<JsonObject> taken = new ArrayList<>();
        JsonObject e;
        while ((e = events.poll()) != null && taken.size() < 300) { ev.add(e); taken.add(e); }
        body.add("events", ev);
        if (++syncTick % 2 == 0) {
            try {
                JsonArray kopie = sync.backups();
                if (!kopie.isEmpty()) body.add("kopie", kopie);
                JsonObject osiagi = sync.achievements();
                if (osiagi != null) body.add("osiagi", osiagi);
            } catch (Exception ex) { getLogger().warning("Kopie/osiągnięcia do panelu: " + ex.getMessage()); }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-WestPanel-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            sending.set(false);
            if (err != null || res.statusCode() != 200) {
                Bukkit.getScheduler().runTask(this, sync::failed);
                events.addAll(taken);   // sprobuj wyslac zdarzenia nastepnym razem
                if (!warned) {
                    warned = true;
                    String why;
                    if (err != null) {
                        Throwable c = err;
                        while (c.getCause() != null) c = c.getCause();
                        why = c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
                    } else why = res.statusCode() == 403 ? "HTTP 403 - zły klucz" : "HTTP " + res.statusCode();
                    getLogger().warning("Panel nie odpowiada pod " + url + " (" + why + "). Sprawdź adres i klucz w configu.");
                }
                return;
            }
            if (warned) { warned = false; getLogger().info("Połączono z panelem."); }
            Bukkit.getScheduler().runTask(this, sync::ok);
            try {
                JsonObject r = JsonParser.parseString(res.body()).getAsJsonObject();
                List<String> cmds = new ArrayList<>();
                for (var c : r.getAsJsonArray("commands")) cmds.add(c.getAsString());
                if (r.has("need")) {
                    List<String> need = new ArrayList<>();
                    for (var n : r.getAsJsonArray("need")) need.add(n.getAsString());
                    Bukkit.getScheduler().runTask(this, () -> need.forEach(sync::reset));
                }
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

    // ------------------------------------------------------------ teren na mape panelu

    private final Map<String, Long> rendered = new java.util.HashMap<>();   // swiat:cx:cz -> czas
    private static final int TILE_RADIUS = 6;          // chunki wokol gracza
    private static final int TILES_PER_TICK = 10;      // limit na jedna wysylke (lekko dla serwera)
    private static final long RERENDER_MS = 10 * 60 * 1000L;

    /** Kolory wierzcholkow chunkow wokol graczy, jak mapa w grze: 16x16 pikseli RGB na chunk. */
    private JsonArray renderTiles() {
        JsonArray out = new JsonArray();
        long now = System.currentTimeMillis();
        int done = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.World w = p.getWorld();
            if (w.getEnvironment() == org.bukkit.World.Environment.NETHER) continue;
            int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
            for (int r = 0; r <= TILE_RADIUS && done < TILES_PER_TICK; r++) {
                for (int dx = -r; dx <= r && done < TILES_PER_TICK; dx++) {
                    for (int dz = -r; dz <= r && done < TILES_PER_TICK; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int cx = pcx + dx, cz = pcz + dz;
                        if (!w.isChunkLoaded(cx, cz)) continue;
                        String k = w.getName() + ":" + cx + ":" + cz;
                        Long last = rendered.get(k);
                        if (last != null && now - last < RERENDER_MS) continue;
                        rendered.put(k, now);
                        JsonObject t = new JsonObject();
                        t.addProperty("world", w.getName());
                        t.addProperty("cx", cx);
                        t.addProperty("cz", cz);
                        t.addProperty("data", renderChunk(w, cx, cz));
                        out.add(t);
                        done++;
                    }
                }
            }
        }
        if (rendered.size() > 50000) rendered.clear();
        return out;
    }

    private static String renderChunk(org.bukkit.World w, int cx, int cz) {
        byte[] rgb = new byte[16 * 16 * 3];
        int bx = cx << 4, bz = cz << 4;
        for (int x = 0; x < 16; x++) {
            int prevY = w.getHighestBlockYAt(bx + x, bz - 1);
            for (int z = 0; z < 16; z++) {
                org.bukkit.block.Block b = w.getHighestBlockAt(bx + x, bz + z);
                int y = b.getY();
                org.bukkit.Color c = b.getBlockData().getMapColor();
                double shade = y > prevY ? 1.12 : y < prevY ? 0.82 : 1.0;   // cieniowanie jak w mapach
                if (b.getType() == org.bukkit.Material.WATER) shade = 0.9 + Math.min(0.15, (y - 40) / 400.0);
                int i = (z * 16 + x) * 3;
                rgb[i] = (byte) Math.min(255, (int) (c.getRed() * shade));
                rgb[i + 1] = (byte) Math.min(255, (int) (c.getGreen() * shade));
                rgb[i + 2] = (byte) Math.min(255, (int) (c.getBlue() * shade));
                prevY = y;
            }
        }
        return java.util.Base64.getEncoder().encodeToString(rgb);
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
        if (c.getName().equalsIgnoreCase("westguard")) {
            if (!s.hasPermission("westguard.admin")) { s.sendMessage("§cBrak uprawnień."); return true; }
            if (a.length > 0 && a[0].equalsIgnoreCase("przeladuj")) { guard.reload(); s.sendMessage("§aWestGuard przeładowany. Tryb: " + (guard.punishMode ? "kary" : "alerty")); return true; }
            s.sendMessage("§7/westguard przeladuj §8- wczytuje guard.yml");
            return true;
        }
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
