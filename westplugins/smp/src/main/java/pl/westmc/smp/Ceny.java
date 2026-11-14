package pl.westmc.smp;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Ceny sprzedazy kazdego przedmiotu (ceny.yml - generowane przy pierwszym starcie, do edycji). 0 = nie do sprzedania. */
final class Ceny {
    private final WestSMP pl;
    private final File plik;
    private final Map<Material, Double> ceny = new EnumMap<>(Material.class);

    Ceny(WestSMP pl) { this.pl = pl; plik = new File(pl.getDataFolder(), "ceny.yml"); }

    void wczytaj() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(plik);
        boolean zmiana = false;
        ceny.clear();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
            String k = m.name().toLowerCase(Locale.ROOT);
            if (!y.isSet(k)) { y.set(k, domyslna(m)); zmiana = true; }
            ceny.put(m, y.getDouble(k));
        }
        if (zmiana) {
            y.options().setHeader(java.util.List.of("Ceny sprzedazy (/sell) za 1 sztuke. 0 = nie da sie sprzedac.",
                "Zmieniaj smialo, potem /sell przeladuj. Bloki surowcow = 9x cena sztabki."));
            try { y.save(plik); } catch (Exception e) { pl.getLogger().warning("ceny.yml: " + e.getMessage()); }
        }
    }

    /** Cena za cala stake (z uwzglednieniem zniszczenia narzedzi); 0 = nie do sprzedania. */
    double cena(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        double c = ceny.getOrDefault(it.getType(), 0.0);
        if (c <= 0) return 0;
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            if (pl.eventowki != null && pl.eventowki.id(it) != null) return 0;
            if (m.hasItemModel()) return 0;                                  // klucze, czeki, ksiegi, itemy z paczki
            if (m instanceof BlockStateMeta bs && bs.hasBlockState() && bs.getBlockState() instanceof ShulkerBox sb) {
                for (ItemStack x : sb.getInventory().getContents()) if (x != null && !x.getType().isAir()) return 0;   // pelny shulker - nie
            }
            if (m instanceof BundleMeta b && b.hasItems()) return 0;
            if (m instanceof Damageable d && it.getType().getMaxDurability() > 0 && d.hasDamage())
                c *= Math.max(0, 1 - (double) d.getDamage() / it.getType().getMaxDurability());
        }
        return Math.floor(c * it.getAmount() * 100) / 100.0;
    }

    double zaSztuke(Material m) { return ceny.getOrDefault(m, 0.0); }

    static String fmt(double v) {
        String s = v >= 100 || v == Math.floor(v) ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, "%,.2f", v);
        return s.replace(',', ' ') + "$";
    }

    // ================================================================= ceny domyslne
    private static final Map<String, Double> J = new HashMap<>();
    private static void j(double v, String... n) { for (String s : n) J.put(s, v); }
    static {
        j(100, "DIAMOND"); j(60, "EMERALD"); j(2500, "NETHERITE_INGOT"); j(600, "NETHERITE_SCRAP", "ANCIENT_DEBRIS");
        j(25, "GOLD_INGOT"); j(2.5, "GOLD_NUGGET"); j(20, "RAW_GOLD"); j(10, "IRON_INGOT"); j(1, "IRON_NUGGET"); j(8, "RAW_IRON");
        j(3, "COPPER_INGOT"); j(2, "RAW_COPPER"); j(4, "COAL"); j(2, "CHARCOAL"); j(2, "REDSTONE"); j(3, "LAPIS_LAZULI", "QUARTZ");
        j(4, "AMETHYST_SHARD"); j(2, "GLOWSTONE_DUST"); j(50, "ECHO_SHARD"); j(3, "PRISMARINE_SHARD"); j(4, "PRISMARINE_CRYSTALS");
        j(5000, "NETHER_STAR", "ENCHANTED_GOLDEN_APPLE"); j(20000, "DRAGON_EGG"); j(50, "DRAGON_BREATH"); j(8000, "ELYTRA");
        j(1500, "TOTEM_OF_UNDYING"); j(1200, "TRIDENT", "MACE"); j(1000, "HEART_OF_THE_SEA", "HEAVY_CORE"); j(100, "NAUTILUS_SHELL", "BREEZE_ROD");
        j(300, "SHULKER_SHELL"); j(600, "SHULKER_BOX"); j(15, "ENDER_PEARL"); j(25, "ENDER_EYE"); j(10, "BLAZE_ROD"); j(5, "BLAZE_POWDER");
        j(40, "GHAST_TEAR"); j(8, "MAGMA_CREAM"); j(4, "SLIME_BALL"); j(3, "GUNPOWDER", "LEATHER"); j(1, "STRING", "BONE", "FEATHER", "FLINT", "RABBIT_HIDE");
        j(0.35, "BONE_MEAL"); j(0.5, "ROTTEN_FLESH", "EGG", "CLAY_BALL", "MELON_SLICE", "SWEET_BERRIES", "ARROW"); j(2, "SPIDER_EYE", "INK_SAC", "APPLE", "CHORUS_FRUIT", "NETHER_WART", "PAPER");
        j(4, "FERMENTED_SPIDER_EYE", "HONEY_BOTTLE"); j(5, "GLOW_INK_SAC"); j(20, "RABBIT_FOOT", "EXPERIENCE_BOTTLE"); j(15, "PHANTOM_MEMBRANE");
        j(500, "WITHER_SKELETON_SKULL"); j(100, "SKELETON_SKULL", "ZOMBIE_HEAD", "CREEPER_HEAD", "PIGLIN_HEAD"); j(1000, "DRAGON_HEAD");
        j(1, "WHEAT", "CACTUS", "COCOA_BEANS", "BEETROOT", "GLOW_BERRIES"); j(0.1, "WHEAT_SEEDS", "BEETROOT_SEEDS", "MELON_SEEDS", "PUMPKIN_SEEDS", "SNOWBALL");
        j(0.8, "CARROT", "POTATO"); j(1.5, "SUGAR_CANE", "SUGAR"); j(3, "PUMPKIN", "BREAD", "HONEYCOMB", "POPPED_CHORUS_FRUIT", "TROPICAL_FISH"); j(4, "MELON", "PUFFERFISH");
        j(0.3, "KELP", "BAMBOO", "DRIED_KELP"); j(250, "GOLDEN_APPLE"); j(8, "BOOK"); j(1.5, "BEEF", "PORKCHOP", "CHICKEN", "MUTTON", "RABBIT", "COD", "SALMON");
        j(2.5, "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_MUTTON", "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON", "BAKED_POTATO");
        j(36, "SLIME_BLOCK"); j(16, "HONEY_BLOCK"); j(5, "OBSIDIAN"); j(8, "CRYING_OBSIDIAN"); j(1, "BRICK", "NETHER_BRICK"); j(0.5, "ICE", "GLASS", "TORCH");
        j(4.5, "PACKED_ICE"); j(40, "BLUE_ICE"); j(50, "SPONGE", "WET_SPONGE"); j(2, "CLAY", "GLOWSTONE", "SEA_LANTERN", "SCULK");
        j(0.2, "STONE", "DEEPSLATE", "SMOOTH_STONE"); j(0.1, "COBBLESTONE", "COBBLED_DEEPSLATE", "GRASS_BLOCK", "SAND", "GRAVEL", "MUD", "SNOW_BLOCK");
        j(0.05, "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "NETHERRACK"); j(0.15, "RED_SAND", "ANDESITE", "DIORITE", "GRANITE", "TUFF", "CALCITE", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL", "DRIPSTONE_BLOCK", "MOSS_BLOCK");
        j(0.3, "END_STONE"); j(0.4, "SANDSTONE", "RED_SANDSTONE", "PRISMARINE"); j(1, "MYCELIUM", "PODZOL"); j(5, "CHEST", "CRAFTING_TABLE"); j(8, "FURNACE");
        j(30, "ANVIL", "ENCHANTING_TABLE", "JUKEBOX"); j(15000, "BEACON"); j(150, "CONDUIT"); j(60, "LODESTONE"); j(500, "RESPAWN_ANCHOR");
        j(30, "CHAINMAIL_HELMET", "CHAINMAIL_BOOTS"); j(45, "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS"); j(12, "SADDLE"); j(20, "NAME_TAG"); j(35, "IRON_HORSE_ARMOR");
        j(80, "GOLDEN_HORSE_ARMOR"); j(250, "DIAMOND_HORSE_ARMOR"); j(15, "BOW", "CROSSBOW", "SHIELD", "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL", "CLOCK", "COMPASS", "SPYGLASS");
    }
    private static final String[] NIE = {"SPAWN_EGG", "COMMAND", "BARRIER", "BEDROCK", "STRUCTURE", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK", "SPAWNER",
        "VAULT", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "PETRIFIED", "FARMLAND", "DIRT_PATH", "TEST_", "WRITTEN_BOOK", "FILLED_MAP",
        "ENCHANTED_BOOK", "POTION", "TIPPED_ARROW", "FIREWORK", "PLAYER_HEAD", "BUNDLE", "SMITHING_TEMPLATE", "POTTERY_SHERD", "MUSIC_DISC", "DISC_FRAGMENT",
        "SUSPICIOUS", "OMINOUS", "TRIAL_KEY", "ARMOR_TRIM", "BANNER_PATTERN", "GOAT_HORN"};
    private static final Map<String, Double> SUROWCE = Map.of("IRON", 10.0, "GOLD", 25.0, "DIAMOND", 100.0, "EMERALD", 60.0, "COPPER", 3.0,
        "COAL", 4.0, "REDSTONE", 2.0, "LAPIS", 3.0, "NETHERITE", 2500.0);

    static double domyslna(Material m) {
        String n = m.name();
        if (n.equals("LIGHT")) return 0;
        for (String x : NIE) if (n.contains(x)) return 0;
        if (J.containsKey(n)) return J.get(n);
        // bloki surowcow
        if (n.startsWith("RAW_") && n.endsWith("_BLOCK")) { String r = "RAW_" + n.substring(4, n.length() - 6); if (J.containsKey(r)) return J.get(r) * 9; }
        if (n.endsWith("_BLOCK")) { String r = n.substring(0, n.length() - 6); if (SUROWCE.containsKey(r)) return SUROWCE.get(r) * 9; }
        if (n.equals("AMETHYST_BLOCK") || n.equals("QUARTZ_BLOCK")) return 12;
        // rudy (silk touch)
        if (n.endsWith("_ORE")) {
            String r = n.replace("DEEPSLATE_", "").replace("NETHER_", "").replace("_ORE", "");
            return switch (r) { case "COAL" -> 4; case "IRON" -> 8; case "GOLD" -> n.contains("NETHER") ? 6 : 20; case "DIAMOND" -> 100; case "EMERALD" -> 60;
                case "LAPIS" -> 15; case "REDSTONE" -> 8; case "COPPER" -> 6; case "QUARTZ" -> 3; default -> 5; };
        }
        // narzedzia i zbroja
        String[] typy = {"SWORD", "PICKAXE", "AXE", "SHOVEL", "HOE", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        int[] ile = {2, 3, 3, 1, 2, 5, 8, 7, 4};
        for (int i = 0; i < typy.length; i++) if (n.endsWith("_" + typy[i])) {
            String mat = n.substring(0, n.length() - typy[i].length() - 1);
            double b = switch (mat) { case "WOODEN" -> 0.5; case "STONE" -> 0.3; case "LEATHER" -> 3; case "GOLDEN" -> 25; case "IRON" -> 10;
                case "DIAMOND" -> 100; case "NETHERITE" -> 100; case "TURTLE" -> 40; default -> 1; };
            double v = b * ile[i];
            return mat.equals("NETHERITE") ? v + 2500 : v;
        }
        // drewno
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) return 2;
        if (n.endsWith("_PLANKS")) return 0.5;
        if (n.endsWith("_SAPLING") || n.endsWith("_PROPAGULE")) return 0.5;
        if (n.endsWith("_LEAVES")) return 0.1;
        if (n.endsWith("_CHEST_BOAT") || n.endsWith("_CHEST_RAFT")) return 6;
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT")) return 3;
        if (n.endsWith("_HANGING_SIGN")) return 2;
        if (n.endsWith("_SIGN")) return 1;
        if (n.endsWith("_FENCE_GATE")) return 2;
        if (n.endsWith("_TRAPDOOR")) return n.startsWith("IRON") ? 40 : n.contains("COPPER") ? 12 : 1.5;
        if (n.endsWith("_DOOR")) return n.startsWith("IRON") ? 20 : n.contains("COPPER") ? 6 : 1;
        if (n.endsWith("_PRESSURE_PLATE")) return n.startsWith("HEAVY") ? 20 : n.startsWith("LIGHT") ? 50 : 1;
        if (n.endsWith("_BUTTON")) return 0.5;
        // kolorowe
        if (n.endsWith("_WOOL")) return 1;
        if (n.endsWith("_CARPET")) return 0.6;
        if (n.endsWith("_BED")) return 4;
        if (n.endsWith("_CONCRETE_POWDER")) return 0.4;
        if (n.endsWith("_CONCRETE") || n.endsWith("_TERRACOTTA") || n.equals("TERRACOTTA")) return n.contains("GLAZED") ? 0.8 : 0.6;
        if (n.endsWith("_STAINED_GLASS")) return 0.6;
        if (n.endsWith("_GLASS_PANE") || n.equals("GLASS_PANE")) return 0.25;
        if (n.endsWith("_CANDLE") || n.equals("CANDLE")) return 1;
        if (n.endsWith("_BANNER")) return 8;
        if (n.endsWith("_DYE")) return 1;
        if (n.endsWith("_SHULKER_BOX")) return 600;
        if (n.contains("COPPER")) return 6;
        // warianty kamienia
        if (n.endsWith("_SLAB")) return 0.1;
        if (n.endsWith("_STAIRS") || n.endsWith("_WALL")) return 0.3;
        if (n.contains("STONE") || n.contains("DEEPSLATE") || n.contains("BRICK") || n.contains("TUFF") || n.contains("BLACKSTONE")) return 0.3;
        if (n.contains("CORAL")) return 1;
        return m.isBlock() ? 0.2 : 1;
    }
}
