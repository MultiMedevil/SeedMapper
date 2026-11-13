package dev.xpple.seedmapper.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolls a chest loot table straight from the JSON file of the vanilla data pack,
 * with the game's own random source.
 *
 * <p>The game needs a running server to roll a loot table, so the roll itself is
 * restated here: pools in order, a uniform number of rolls, a weighted choice
 * between the entries of the pool, and then the functions of the chosen entry.
 * Only what the woodland mansion table uses is implemented, anything else is
 * rejected. Everything about the table itself (entries, weights, counts, the
 * enchantment tag and the enchantment levels) is read from the game data.
 */
public final class VanillaLootRoller {

    public record Stack(String item, int count, String enchantment, int enchantmentLevel) {
    }

    private record Entry(String item, int weight, List<JsonObject> functions) {
    }

    private record Pool(JsonElement rolls, List<Entry> entries, int totalWeight) {
    }

    private final List<Pool> pools = new ArrayList<>();

    public VanillaLootRoller(String lootTable) {
        JsonObject table = VanillaMansions.readData("loot_table/" + lootTable + ".json");
        if (!"minecraft:chest".equals(table.get("type").getAsString())) {
            throw new IllegalArgumentException("Not a chest loot table");
        }
        for (JsonElement poolElement : table.getAsJsonArray("pools")) {
            JsonObject pool = poolElement.getAsJsonObject();
            require(!pool.has("conditions") && !pool.has("functions") && !pool.has("modifier"), "pool conditions and functions");
            require(!pool.has("bonus_rolls") || pool.get("bonus_rolls").getAsDouble() == 0.0, "bonus rolls");
            List<Entry> entries = new ArrayList<>();
            int totalWeight = 0;
            for (JsonElement entryElement : pool.getAsJsonArray("entries")) {
                JsonObject entry = entryElement.getAsJsonObject();
                require(!entry.has("conditions"), "entry conditions");
                require(!entry.has("quality") || entry.get("quality").getAsInt() == 0, "entry quality");
                String type = entry.get("type").getAsString();
                String item;
                if (type.equals("minecraft:empty")) {
                    item = null;
                } else if (type.equals("minecraft:item")) {
                    item = entry.get("name").getAsString();
                } else {
                    throw new UnsupportedOperationException("Entry type " + type);
                }
                List<JsonObject> functions = new ArrayList<>();
                // called "functions" until 26.2 and "modifier" since 26.3
                JsonElement functionsElement = entry.has("functions") ? entry.get("functions") : entry.get("modifier");
                if (functionsElement instanceof JsonArray array) {
                    array.forEach(function -> functions.add(function.getAsJsonObject()));
                } else if (functionsElement instanceof JsonObject object) {
                    functions.add(object);
                }
                int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
                entries.add(new Entry(item, weight, functions));
                totalWeight += weight;
            }
            this.pools.add(new Pool(pool.get("rolls"), entries, totalWeight));
        }
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new UnsupportedOperationException("Unsupported in this loot table: " + what);
        }
    }

    // ConstantValue and UniformGenerator
    private static int number(JsonElement provider, RandomSource random) {
        if (provider.isJsonPrimitive()) {
            return Mth.floor(provider.getAsFloat());
        }
        JsonObject object = provider.getAsJsonObject();
        require(!object.has("type") || object.get("type").getAsString().equals("minecraft:uniform"), "number provider " + object);
        return Mth.nextInt(random, Mth.floor(object.get("min").getAsFloat()), Mth.floor(object.get("max").getAsFloat()));
    }

    private static List<String> expandEnchantmentTag(String tag) {
        List<String> result = new ArrayList<>();
        JsonObject json = VanillaMansions.readData("tags/enchantment/" + tag.substring("#minecraft:".length()) + ".json");
        for (JsonElement value : json.getAsJsonArray("values")) {
            String entry = value.getAsString();
            for (String enchantment : entry.startsWith("#") ? expandEnchantmentTag(entry) : List.of(entry)) {
                if (!result.contains(enchantment)) {
                    result.add(enchantment);
                }
            }
        }
        return result;
    }

    private static int[] enchantmentLevels(String enchantment) {
        JsonObject json = VanillaMansions.readData("enchantment/" + enchantment.substring("minecraft:".length()) + ".json");
        return new int[]{1, json.get("max_level").getAsInt()};
    }

    /**
     * Roll the loot table like {@code LootTable#getRandomItems(LootParams, long)} does for a chest
     * that is opened without luck.
     */
    public List<Stack> roll(long lootSeed) {
        RandomSource random = RandomSource.create(lootSeed);
        List<Stack> stacks = new ArrayList<>();
        for (Pool pool : this.pools) {
            int rolls = number(pool.rolls, random);
            for (int roll = 0; roll < rolls; roll++) {
                Entry chosen;
                if (pool.entries.size() == 1) {
                    chosen = pool.entries.getFirst();
                } else {
                    int choice = random.nextInt(pool.totalWeight);
                    chosen = null;
                    for (Entry entry : pool.entries) {
                        choice -= entry.weight;
                        if (choice < 0) {
                            chosen = entry;
                            break;
                        }
                    }
                }
                if (chosen == null || chosen.item == null) {
                    continue;
                }
                int count = 1;
                String enchantment = null;
                int enchantmentLevel = 0;
                for (JsonObject function : chosen.functions) {
                    String type = (function.has("function") ? function.get("function") : function.get("type")).getAsString();
                    switch (type) {
                        case "minecraft:set_count" -> {
                            require(!function.has("add") || !function.get("add").getAsBoolean(), "set_count with add");
                            count = number(function.get("count"), random);
                        }
                        case "minecraft:enchant_randomly" -> {
                            require(chosen.item.equals("minecraft:book"), "enchant_randomly on something else than a book");
                            List<String> options = expandEnchantmentTag(function.get("options").getAsString());
                            enchantment = options.get(random.nextInt(options.size()));
                            int[] levels = enchantmentLevels(enchantment);
                            enchantmentLevel = Mth.nextInt(random, levels[0], levels[1]);
                        }
                        default -> throw new UnsupportedOperationException("Loot function " + type);
                    }
                }
                stacks.add(new Stack(chosen.item, count, enchantment, enchantmentLevel));
            }
        }
        return stacks;
    }
}
