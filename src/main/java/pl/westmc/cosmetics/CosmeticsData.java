package pl.westmc.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kto ma jakie kosmetyki. Zrodla:
 *  1) zdalna lista (url w configu) - odswiezana co 2 minuty,
 *  2) lokalny plik config/westcosmetics.json (sekcja "gracze") - do testow.
 * Format listy: { "gracze": { "Nick": ["skrzydla_anielskie", "cylinder"] } }
 */
public final class CosmeticsData {
    static final Logger LOG = LoggerFactory.getLogger("WestCosmetics");
    static final String DEFAULT_URL = "https://raw.githubusercontent.com/botjuniorak/westcosmetics/main/gracze.json";

    private static volatile Map<String, Set<String>> remote = Map.of();
    private static volatile Map<String, Set<String>> local = Map.of();
    private static volatile String url = DEFAULT_URL;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private CosmeticsData() { }

    public static Set<String> get(String name) {
        if (name == null) return Set.of();
        String key = name.toLowerCase(Locale.ROOT);
        Set<String> l = local.get(key);
        if (l != null) return l;
        return remote.getOrDefault(key, Set.of());
    }

    static void start() {
        loadLocal();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WestCosmetics-sync");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> { loadLocal(); fetch(); }, 0, 120, TimeUnit.SECONDS);
    }

    private static void loadLocal() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("westcosmetics.json");
        try {
            if (!Files.exists(p)) {
                JsonObject o = new JsonObject();
                o.addProperty("_info", "Tu mozesz testowac kosmetyki: wpisz swoj nick w 'gracze'. Dostepne: "
                    + String.join(", ", Cosmetic.ids()));
                o.addProperty("url", DEFAULT_URL);
                o.add("gracze", new JsonObject());
                Gson g = new GsonBuilder().setPrettyPrinting().create();
                Files.createDirectories(p.getParent());
                Files.writeString(p, g.toJson(o), StandardCharsets.UTF_8);
            }
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("url") && !o.get("url").getAsString().isBlank()) url = o.get("url").getAsString().trim();
            local = parse(o);
        } catch (Exception e) {
            LOG.warn("Nie wczytano config/westcosmetics.json: {}", e.toString());
        }
    }

    private static void fetch() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "WestCosmetics")
                .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) { LOG.warn("Lista kosmetykow: HTTP {}", res.statusCode()); return; }
            remote = parse(JsonParser.parseString(res.body()).getAsJsonObject());
            LOG.info("Wczytano kosmetyki {} graczy.", remote.size());
        } catch (Exception e) {
            LOG.warn("Nie pobrano listy kosmetykow: {}", e.toString());
        }
    }

    static Map<String, Set<String>> parse(JsonObject root) {
        Map<String, Set<String>> m = new HashMap<>();
        if (!root.has("gracze") || !root.get("gracze").isJsonObject()) return m;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("gracze").entrySet()) {
            Set<String> s = new HashSet<>();
            if (e.getValue().isJsonArray()) {
                JsonArray a = e.getValue().getAsJsonArray();
                for (JsonElement x : a) s.add(x.getAsString().toLowerCase(Locale.ROOT));
            }
            m.put(e.getKey().toLowerCase(Locale.ROOT), Collections.unmodifiableSet(s));
        }
        return m;
    }
}
