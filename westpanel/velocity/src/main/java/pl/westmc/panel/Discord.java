package pl.westmc.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/** Wysylka na Discord przez webhooki. Kolejka z odstepem, zeby nie wpasc w limit Discorda. */
final class Discord {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final LinkedBlockingQueue<Object[]> queue = new LinkedBlockingQueue<>(500);
    private final WestPanel pl;

    Discord(WestPanel pl) {
        this.pl = pl;
        Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "WestPanel-discord"); t.setDaemon(true); return t; }).submit(this::loop);
    }

    /** Bez @everyone/@here i wzmianek z nickow graczy. */
    static String safe(String s) {
        return s == null ? "" : s.replace("@", "@​").replace("`", "'");
    }

    /**
     * @param url   webhook (pusty = nic nie wysylamy)
     * @param ping  tekst nad wiadomoscia, np. "<@&ID_ROLI>" albo "@here" (pusty = bez pingu)
     * @param color kolor paska, np. 0xff5b6b
     */
    void send(String url, String ping, String title, String description, int color, String[][] fields) {
        if (url == null || url.isBlank() || !url.startsWith("https://")) return;
        JsonObject body = new JsonObject();
        body.addProperty("username", "WestMC Panel");
        if (ping != null && !ping.isBlank()) body.addProperty("content", ping);
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        parse.add("roles"); parse.add("everyone"); parse.add("users");
        allowed.add("parse", parse);
        body.add("allowed_mentions", allowed);
        JsonObject e = new JsonObject();
        e.addProperty("title", title);
        e.addProperty("description", description);
        e.addProperty("color", color);
        e.addProperty("timestamp", Instant.now().toString());
        if (fields != null) {
            JsonArray fs = new JsonArray();
            for (String[] f : fields) {
                JsonObject o = new JsonObject();
                o.addProperty("name", f[0]);
                o.addProperty("value", f[1].isBlank() ? "—" : f[1]);
                o.addProperty("inline", true);
                fs.add(o);
            }
            e.add("fields", fs);
        }
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "WestMC · panel administracji");
        e.add("footer", footer);
        JsonArray embeds = new JsonArray();
        embeds.add(e);
        body.add("embeds", embeds);
        queue.offer(new Object[]{url, body.toString()});
    }

    private void loop() {
        while (true) {
            try {
                Object[] job = queue.take();
                HttpRequest req = HttpRequest.newBuilder(URI.create((String) job[0]))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString((String) job[1]))
                    .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 429) {
                    queue.offer(job);
                    Thread.sleep(3000);
                } else if (res.statusCode() >= 300) {
                    pl.logger().warn("Discord odrzucił wiadomość (HTTP {}). Sprawdź adres webhooka.", res.statusCode());
                }
                Thread.sleep(700);
            } catch (InterruptedException ie) {
                return;
            } catch (Exception ex) {
                pl.logger().warn("Nie wysłano na Discord: {}", ex.toString());
            }
        }
    }
}
