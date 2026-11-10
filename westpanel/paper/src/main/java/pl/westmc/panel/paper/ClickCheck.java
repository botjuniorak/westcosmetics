package pl.westmc.panel.paper;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autoklik: czasy klikniec biora sie z pakietow (watek sieci), a nie z tickow serwera,
 * wiec mozna zmierzyc prawdziwa rownosc klikania. Klasa ladowana tylko, gdy jest packetevents.
 */
final class ClickCheck extends PacketListenerAbstract {
    private static final int SAMPLE = 30;

    private static final class Clicks {
        final long[] times = new long[64];
        int n;
        long diggingUntil;
    }

    private final Guard g;
    private final Map<UUID, Clicks> clicks = new ConcurrentHashMap<>();

    ClickCheck(Guard g) {
        super(PacketListenerPriority.MONITOR);
        this.g = g;
    }

    static void register(Guard g) {
        PacketEvents.getAPI().getEventManager().registerListener(new ClickCheck(g));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getUser() == null || e.getUser().getUUID() == null) return;
        UUID id = e.getUser().getUUID();
        if (e.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(e);
            Clicks c = clicks.computeIfAbsent(id, k -> new Clicks());
            if (w.getAction() == DiggingAction.START_DIGGING) c.diggingUntil = Long.MAX_VALUE;
            else c.diggingUntil = System.nanoTime() / 1_000_000 + 250;
            return;
        }
        if (e.getPacketType() != PacketType.Play.Client.ANIMATION) return;
        if (!g.cfg.getBoolean("autoklik.wlaczony", true)) return;
        long now = System.nanoTime() / 1_000_000;
        Clicks c = clicks.computeIfAbsent(id, k -> new Clicks());
        if (now < c.diggingUntil) return;                       // kopanie tez macha reka - pomijamy
        synchronized (c) {
            c.times[c.n % c.times.length] = now;
            c.n++;
            if (c.n < SAMPLE) return;
            int cps = 0;
            for (long t : c.times) if (now - t <= 1000) cps++;
            int maxCps = g.cfg.getInt("autoklik.max-cps", 20);
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
            if (p.hasPermission("westguard.pomijaj")) return;
            if (cps > maxCps) g.flag(id, p.getName(), "autoklik", 1, cps + " CPS");
            if (cps >= g.cfg.getInt("autoklik.min-cps-regularnosc", 9) && c.n % 10 == 0) {
                double[] iv = new double[SAMPLE - 1];
                for (int i = 0; i < SAMPLE - 1; i++) {
                    long a = c.times[(c.n - SAMPLE + i) % c.times.length], b = c.times[(c.n - SAMPLE + i + 1) % c.times.length];
                    iv[i] = b - a;
                }
                double mean = 0; for (double v : iv) mean += v; mean /= iv.length;
                if (mean > 200) return;                         // przerwy - to nie seria klikniec
                double var = 0; for (double v : iv) var += (v - mean) * (v - mean);
                double sd = Math.sqrt(var / iv.length);
                if (sd < g.cfg.getDouble("autoklik.max-odchylenie-ms", 9)) {
                    g.flag(id, p.getName(), "autoklik", 1, String.format(java.util.Locale.ROOT, "%d CPS, odchylenie %.1f ms (zbyt równo)", cps, sd));
                }
            }
        }
    }
}
