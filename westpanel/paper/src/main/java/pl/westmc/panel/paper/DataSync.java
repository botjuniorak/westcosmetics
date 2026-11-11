package pl.westmc.panel.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Przesyla do panelu dane z WestBackup (kopie ekwipunku) i WestOsiagniecia (poziomy graczy).
 * Czyta ich pliki z dysku - pluginy nie musza o sobie wiedziec. Wysyla tylko to, co sie zmienilo.
 */
final class DataSync {
    private static final int BACKUP_FILES_PER_SEND = 4;
    private final File backups, achData, achCfg;
    private final Map<String, Long> sentBackup = new HashMap<>();
    private long sentAch, lastAchSend;
    private final java.util.List<String> lastIds = new java.util.ArrayList<>();
    private boolean lastAch;

    DataSync(File plugins) {
        backups = new File(plugins, "WestBackup/kopie");
        achData = new File(plugins, "WestOsiagniecia/gracze.yml");
        achCfg = new File(plugins, "WestOsiagniecia/config.yml");
    }

    /** Panel zgubil dane (np. restart proxy) - wyslij wszystko od nowa. */
    void reset(String what) {
        if (what.equals("kopie")) sentBackup.clear();
        if (what.equals("osiagi")) sentAch = 0;
    }

    /** Wysylka sie nie udala - to, co bylo w paczce, poleci nastepnym razem. */
    void failed() {
        for (String id : lastIds) sentBackup.remove(id);
        lastIds.clear();
        if (lastAch) { sentAch = 0; lastAchSend = 0; lastAch = false; }
    }

    void ok() { lastIds.clear(); lastAch = false; }

    // ------------------------------------------------------------ kopie

    JsonArray backups() {
        JsonArray out = new JsonArray();
        lastIds.clear();
        File[] files = backups.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        int n = 0;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            long mod = f.lastModified();
            Long last = sentBackup.get(id);
            if (last != null && last == mod) continue;
            if (n++ >= BACKUP_FILES_PER_SEND) break;
            sentBackup.put(id, mod);
            lastIds.add(id);
            try { out.add(player(id, YamlConfiguration.loadConfiguration(f))); } catch (Exception ignored) { }
        }
        return out;
    }

    private JsonObject player(String uuid, YamlConfiguration y) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("nick", y.getString("nick", "?"));
        o.addProperty("pending", y.getList("do-oddania", List.of()).size());
        JsonArray list = new JsonArray();
        ConfigurationSection s = y.getConfigurationSection("kopie");
        if (s != null) for (String k : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(k);
            if (b == null) continue;
            JsonObject x = new JsonObject();
            try { x.addProperty("nr", Integer.parseInt(k)); } catch (NumberFormatException e) { continue; }
            x.addProperty("time", b.getLong("czas"));
            x.addProperty("cause", b.getString("przyczyna", ""));
            if (b.contains("zabojca")) x.addProperty("killer", b.getString("zabojca"));
            x.addProperty("place", b.getString("miejsce", ""));
            x.addProperty("level", b.getInt("poziom"));
            if (b.contains("zwrocone-przez")) { x.addProperty("restoredBy", b.getString("zwrocone-przez")); x.addProperty("restoredAt", b.getLong("zwrocone-czas")); }
            JsonArray items = new JsonArray();
            List<?> st = b.getList("ekwipunek", List.of());
            for (int i = 0; i < st.size(); i++) if (st.get(i) instanceof ItemStack it) add(items, i, it);
            List<?> ar = b.getList("zbroja", List.of());   // buty, spodnie, klata, helm
            for (int i = 0; i < ar.size(); i++) if (ar.get(i) instanceof ItemStack it) add(items, 36 + i, it);
            ItemStack off = b.getItemStack("druga-reka");
            if (off != null) add(items, 40, off);
            x.add("items", items);
            list.add(x);
        }
        o.add("backups", list);
        return o;
    }

    private static void add(JsonArray arr, int slot, ItemStack it) {
        if (it.getType().isAir()) return;
        JsonObject o = new JsonObject();
        o.addProperty("s", slot);
        o.addProperty("id", it.getType().getKey().getKey());
        o.addProperty("n", it.getAmount());
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            if (m.hasDisplayName()) o.addProperty("name", strip(m.getDisplayName()));
            JsonArray en = new JsonArray();
            for (Map.Entry<Enchantment, Integer> e : m.getEnchants().entrySet()) en.add(e.getKey().getKey().getKey() + " " + e.getValue());
            if (m instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm)
                for (Map.Entry<Enchantment, Integer> e : esm.getStoredEnchants().entrySet()) en.add(e.getKey().getKey().getKey() + " " + e.getValue());
            if (!en.isEmpty()) o.add("ench", en);
            if (m instanceof Damageable d && it.getType().getMaxDurability() > 0 && d.hasDamage()) {
                o.addProperty("dur", Math.round(100.0 * (it.getType().getMaxDurability() - d.getDamage()) / it.getType().getMaxDurability()));
            }
            if (m.hasLore() && m.getLore() != null) {
                JsonArray lore = new JsonArray();
                for (String l : m.getLore().subList(0, Math.min(6, m.getLore().size()))) lore.add(strip(l));
                o.add("lore", lore);
            }
        }
        arr.add(o);
    }

    private static String strip(String s) { return s == null ? "" : s.replaceAll("§[0-9a-fk-orx]", ""); }

    // ------------------------------------------------------------ osiagniecia

    JsonObject achievements() {
        lastAch = false;
        if (!achData.exists() || !achCfg.exists()) return null;
        long mod = Math.max(achData.lastModified(), achCfg.lastModified());
        long now = System.currentTimeMillis();
        if (mod == sentAch || now - lastAchSend < 30000) return null;
        sentAch = mod;
        lastAchSend = now;
        lastAch = true;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(achCfg);
        YamlConfiguration d = YamlConfiguration.loadConfiguration(achData);
        JsonObject out = new JsonObject();
        JsonArray defs = new JsonArray();
        ConfigurationSection s = cfg.getConfigurationSection("osiagniecia");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection a = s.getConfigurationSection(id);
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", a.getString("nazwa", id));
            o.addProperty("desc", a.getString("opis", ""));
            Material m = Material.matchMaterial(a.getString("ikona", "PAPER"));
            o.addProperty("icon", (m == null ? Material.PAPER : m).getKey().getKey());
            JsonArray th = new JsonArray();
            for (long v : a.getLongList("progi")) th.add(v);
            o.add("thresholds", th);
            JsonArray rw = new JsonArray();
            for (String r : a.getStringList("opis-nagrod")) rw.add(r);
            o.add("rewards", rw);
            defs.add(o);
        }
        out.add("defs", defs);
        JsonArray players = new JsonArray();
        for (String uuid : d.getKeys(false)) {
            ConfigurationSection p = d.getConfigurationSection(uuid);
            if (p == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid);
            o.addProperty("nick", p.getString("nick", "?"));
            o.addProperty("streak", p.getInt("najlepsza-seria"));
            o.addProperty("seen", p.getLong("ostatnio"));
            JsonObject lv = new JsonObject(), vals = new JsonObject();
            ConfigurationSection l = p.getConfigurationSection("poziomy");
            if (l != null) for (String k : l.getKeys(false)) lv.addProperty(k, l.getInt(k));
            ConfigurationSection v = p.getConfigurationSection("wartosci");
            if (v != null) for (String k : v.getKeys(false)) vals.addProperty(k, v.getLong(k));
            o.add("levels", lv);
            o.add("values", vals);
            players.add(o);
        }
        out.add("players", players);
        return out;
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
