package pl.westmc.panel.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Przechwytuje wykrycia Grima (zdarzenie ac.grim.grimac.api.events.FlagEvent) bez twardej zaleznosci od Grima. */
final class GrimHook implements Listener {
    private final Guard g;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    private GrimHook(Guard g) { this.g = g; }

    @SuppressWarnings("unchecked")
    static boolean register(Guard g) {
        try {
            Class<? extends Event> cls = (Class<? extends Event>) Class.forName("ac.grim.grimac.api.events.FlagEvent", true,
                Bukkit.getPluginManager().getPlugin("GrimAC") != null ? Bukkit.getPluginManager().getPlugin("GrimAC").getClass().getClassLoader() : GrimHook.class.getClassLoader());
            GrimHook h = new GrimHook(g);
            Bukkit.getPluginManager().registerEvent(cls, h, EventPriority.MONITOR, (l, e) -> h.handle(e), g.pl, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void handle(Event e) {
        try {
            Object user = call(e, "getUser", "getPlayer");
            Object check = call(e, "getCheck");
            String name = String.valueOf(call(user, "getName"));
            Object uuid = call(user, "getUniqueId", "getUUID");
            String checkName = String.valueOf(call(check, "getCheckName", "getDisplayName", "getName"));
            Object vlObj = call(check, "getViolations");
            Object verbose = call(e, "getVerbose");
            int vl = vlObj instanceof Number n ? (int) Math.round(n.doubleValue()) : 0;
            String k = name + ":" + checkName;
            long now = System.currentTimeMillis();
            Long last = lastSent.get(k);
            if (last != null && now - last < 10000) return;    // max 1 alert na 10 s na gracza i check
            lastSent.put(k, now);
            if (lastSent.size() > 5000) lastSent.clear();
            g.pl.acAlert(name, checkName, vl, verbose == null ? "" : String.valueOf(verbose), "Grim");
            if (uuid instanceof UUID) { /* VL prowadzi sam Grim */ }
        } catch (Throwable ignored) {
            // inna wersja API Grima - nie przerywamy gry
        }
    }

    private static Object call(Object o, String... names) throws Exception {
        if (o == null) return null;
        for (String n : names) {
            try {
                Method m = o.getClass().getMethod(n);
                return m.invoke(o);
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
}
