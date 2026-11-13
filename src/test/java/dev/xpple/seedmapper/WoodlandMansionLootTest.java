package dev.xpple.seedmapper;

import com.github.cubiomes.Cubiomes;
import dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument;
import dev.xpple.seedmapper.feature.WoodlandMansions;
import dev.xpple.seedmapper.support.NativeLibrary;
import dev.xpple.seedmapper.support.VanillaLootRoller;
import dev.xpple.seedmapper.support.VanillaMansions;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the mansion loot of SeedMapper with the loot table of the game data
 * on the test classpath, see {@link VanillaLootRoller}.
 */
public class WoodlandMansionLootTest {

    private static final String VEX_TRIM = "minecraft:vex_armor_trim_smithing_template";
    private static final int ROLLS = 200_000;

    private static int version;
    private static int vexTrim;

    @BeforeAll
    public static void setUp() {
        NativeLibrary.load();
        VanillaMansions.bootstrap();
        version = Cubiomes.MC_NEWEST();
        ItemAndEnchantmentsPredicateArgument.EnchantedItem item = ItemAndEnchantmentsPredicateArgument.getItem(VEX_TRIM);
        assertNotNull(item);
        vexTrim = item.item();
    }

    private static WoodlandMansions.Chest chest(long lootSeed) {
        return new WoodlandMansions.Chest(BlockPos.ZERO, lootSeed, WoodlandMansions.LOOT_TABLE, "", 0);
    }

    private static String itemName(int item) {
        return Cubiomes.global_id2item_name(item, version).getString(0);
    }

    @Test
    public void testLootTableIsAvailable() {
        assertTrue(WoodlandMansions.isLootSupported(version));
        assertTrue(WoodlandMansions.lootTableHasItem(version, vexTrim));
        assertEquals(VEX_TRIM, itemName(vexTrim));
        assertTrue(WoodlandMansions.isLootSupported(Cubiomes.MC_26_1()));
        assertTrue(WoodlandMansions.isLootSupported(Cubiomes.MC_26_2()));
        // older versions have not been verified against the game and are not offered
        for (int unsupported : new int[]{Cubiomes.MC_1_21(), Cubiomes.MC_1_20(), Cubiomes.MC_1_19(), Cubiomes.MC_1_17(), Cubiomes.MC_1_12()}) {
            assertFalse(WoodlandMansions.isLootSupported(unsupported));
            assertNull(WoodlandMansions.generateLoot(chest(1L), unsupported));
            assertTrue(WoodlandMansions.getChests(1L, unsupported, 0, 0, 64).isEmpty());
        }
    }

    @Test
    public void testLootMatchesVanillaLootTable() {
        VanillaLootRoller roller = new VanillaLootRoller(WoodlandMansions.LOOT_TABLE);
        Random random = new Random(26_3L);
        int vexTrims = 0, books = 0, stacks = 0;
        Set<String> items = new TreeSet<>();
        TreeMap<String, Integer> enchantments = new TreeMap<>();

        for (int i = 0; i < ROLLS; i++) {
            // the game treats a loot seed of zero as "no seed"
            long lootSeed = i == 0 ? 6306952185402203104L : random.nextLong();
            List<VanillaLootRoller.Stack> expected = roller.roll(lootSeed);
            List<WoodlandMansions.LootStack> actual = WoodlandMansions.generateLoot(chest(lootSeed), version);
            assertNotNull(actual);
            assertEquals(expected.size(), actual.size(), () -> "Number of item stacks for loot seed " + lootSeed);
            for (int j = 0; j < expected.size(); j++) {
                VanillaLootRoller.Stack expectedStack = expected.get(j);
                WoodlandMansions.LootStack actualStack = actual.get(j);
                int index = j;
                assertEquals(expectedStack.item(), itemName(actualStack.item()), () -> "Item " + index + " for loot seed " + lootSeed);
                assertEquals(expectedStack.count(), actualStack.count(), () -> "Count of item " + index + " for loot seed " + lootSeed);
                if (expectedStack.enchantment() == null) {
                    assertTrue(actualStack.enchantments().isEmpty(), () -> "Unexpected enchantment on item " + index + " for loot seed " + lootSeed);
                } else {
                    assertEquals(1, actualStack.enchantments().size(), () -> "Enchantments of item " + index + " for loot seed " + lootSeed);
                    int[] enchantment = actualStack.enchantments().getFirst();
                    String name = ItemAndEnchantmentsPredicateArgument.ENCHANTMENT_ID_TO_MC.get(enchantment[0]).identifier().toString();
                    assertEquals(expectedStack.enchantment(), name, () -> "Enchantment of item " + index + " for loot seed " + lootSeed);
                    assertEquals(expectedStack.enchantmentLevel(), enchantment[1], () -> "Enchantment level of item " + index + " for loot seed " + lootSeed);
                    enchantments.merge(name, 1, Integer::sum);
                    books++;
                }
                items.add(expectedStack.item());
                stacks++;
            }
            int expectedTrims = (int) expected.stream().filter(stack -> stack.item().equals(VEX_TRIM)).count();
            assertEquals(expectedTrims, WoodlandMansions.countItems(chest(lootSeed), version, vexTrim, _ -> true));
            vexTrims += expectedTrims;
        }

        System.out.println("Compared " + stacks + " item stacks of " + ROLLS + " chests with the vanilla loot table");
        System.out.println("Chests with a vex armor trim: " + vexTrims + ", enchanted books: " + books + " with " + enchantments.size() + " different enchantments");
        System.out.println("Items seen: " + items);
        // the trim is one of two equally likely entries of its pool
        assertEquals(0.5, vexTrims / (double) ROLLS, 0.01);
        // every enchantment a book can receive has to be covered
        assertEquals(40, enchantments.size());
        assertEquals(25, items.size());
    }

    @Test
    public void testEveryItemExistsInTheGame() {
        // also verifies that the native item list and the game's item registry agree
        assertEquals(Cubiomes.NUM_ITEMS(), ItemAndEnchantmentsPredicateArgument.ITEM_ID_TO_MC.size());
    }
}
