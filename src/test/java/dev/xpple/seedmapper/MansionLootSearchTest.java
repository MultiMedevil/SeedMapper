package dev.xpple.seedmapper;

import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.TerrainNoise;
import dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument;
import dev.xpple.seedmapper.feature.MansionLootSearch;
import dev.xpple.seedmapper.feature.WoodlandMansions;
import dev.xpple.seedmapper.support.NativeLibrary;
import dev.xpple.seedmapper.support.VanillaMansions;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MansionLootSearchTest {

    private static final long SEED = 6306952185402203104L;
    private static final int RADIUS = 25_000;

    private static int version;
    private static int vexTrim;
    private static MemorySegment terrainNoise;

    @BeforeAll
    public static void setUp() {
        NativeLibrary.load();
        VanillaMansions.bootstrap();
        version = Cubiomes.MC_NEWEST();
        vexTrim = ItemAndEnchantmentsPredicateArgument.getItem("vex_armor_trim_smithing_template").item();
        terrainNoise = terrainNoise(SEED);
    }

    private static MemorySegment terrainNoise(long seed) {
        MemorySegment terrainNoise = TerrainNoise.allocate(Arena.global());
        Cubiomes.setupTerrainNoise(terrainNoise, version, 0);
        Cubiomes.initTerrainNoise(terrainNoise, seed, Cubiomes.DIM_OVERWORLD());
        return terrainNoise;
    }

    private record Found(MansionLootSearch.Mansion mansion, List<MansionLootSearch.Match> matches) {
    }

    private static List<Found> search(int centerX, int centerZ, int radius, int amount, AtomicReference<MansionLootSearch.Result> result) {
        List<Found> found = new ArrayList<>();
        result.set(MansionLootSearch.search(terrainNoise, SEED, version, 0, centerX, centerZ, radius, amount, vexTrim, _ -> true, (mansion, matches) -> found.add(new Found(mansion, matches))));
        return found;
    }

    @Test
    public void testFindsEveryMatchNearestFirst() {
        final int centerX = 150, centerZ = -320;
        MansionLootSearch.clearCache();
        AtomicReference<MansionLootSearch.Result> result = new AtomicReference<>();
        List<Found> found = search(centerX, centerZ, RADIUS, Integer.MAX_VALUE, result);
        assertTrue(result.get().radiusExhausted());
        assertFalse(found.isEmpty());

        double previous = 0;
        for (Found f : found) {
            double distance = MansionLootSearch.horizontalDistance(f.mansion.start().origin(), centerX, centerZ);
            assertTrue(distance >= previous, "Mansions have to be reported nearest first");
            assertTrue(distance <= RADIUS);
            previous = distance;
        }

        // compare with an exhaustive scan of every region around the center
        int regionSize = WoodlandMansions.regionSizeInBlocks(version);
        int regions = RADIUS / regionSize + 2;
        List<BlockPos> expectedChests = new ArrayList<>();
        int mansions = 0, items = 0;
        for (int regionX = -regions; regionX <= regions; regionX++) {
            for (int regionZ = -regions; regionZ <= regions; regionZ++) {
                WoodlandMansions.Start start = WoodlandMansions.findStart(terrainNoise, version, SEED, regionX, regionZ);
                if (start == null || MansionLootSearch.horizontalDistance(start.origin(), centerX, centerZ) > RADIUS) {
                    continue;
                }
                mansions++;
                for (WoodlandMansions.Chest chest : WoodlandMansions.getChests(SEED, version, start.chunkX(), start.chunkZ(), start.origin().getY())) {
                    List<WoodlandMansions.LootStack> loot = WoodlandMansions.generateLoot(chest, version);
                    assertNotNull(loot);
                    if (loot.stream().anyMatch(stack -> stack.item() == vexTrim)) {
                        expectedChests.add(chest.pos());
                        items++;
                    }
                }
            }
        }
        List<BlockPos> actualChests = found.stream().flatMap(f -> f.matches.stream()).map(match -> match.chest().pos()).toList();
        assertEquals(expectedChests.size(), actualChests.size());
        assertTrue(actualChests.containsAll(expectedChests));
        assertEquals(mansions, result.get().mansionsChecked());
        assertEquals(items, result.get().itemsFound());
        assertEquals(expectedChests.size(), result.get().chestsFound());
        assertEquals(found.size(), result.get().mansionsWithMatches());
        System.out.println("Search around " + centerX + ", " + centerZ + ": " + mansions + " mansions within " + RADIUS + " blocks, " + expectedChests.size() + " chests with a vex armor trim");
    }

    @Test
    public void testStopsWhenEnoughItemsWereFound() {
        AtomicReference<MansionLootSearch.Result> all = new AtomicReference<>();
        List<Found> everything = search(0, 0, RADIUS, Integer.MAX_VALUE, all);
        AtomicReference<MansionLootSearch.Result> one = new AtomicReference<>();
        List<Found> first = search(0, 0, RADIUS, 1, one);

        assertEquals(1, first.size());
        assertFalse(one.get().radiusExhausted());
        assertTrue(one.get().itemsFound() >= 1);
        // the nearest mansion with a match is the one that is reported
        assertEquals(everything.getFirst().mansion.start(), first.getFirst().mansion.start());
        assertTrue(one.get().mansionsChecked() <= all.get().mansionsChecked());
    }

    @Test
    public void testSearchIsReproducible() {
        AtomicReference<MansionLootSearch.Result> first = new AtomicReference<>();
        List<Found> cached = search(-4000, 9000, RADIUS, Integer.MAX_VALUE, first);
        MansionLootSearch.clearCache();
        AtomicReference<MansionLootSearch.Result> second = new AtomicReference<>();
        List<Found> recomputed = search(-4000, 9000, RADIUS, Integer.MAX_VALUE, second);
        assertEquals(first.get(), second.get());
        assertEquals(cached, recomputed);
    }

    @Test
    public void testNothingWithinTinyRadius() {
        AtomicReference<MansionLootSearch.Result> result = new AtomicReference<>();
        assertTrue(search(0, 0, 16, Integer.MAX_VALUE, result).isEmpty());
        assertEquals(new MansionLootSearch.Result(0, 0, 0, 0, true), result.get());
    }

    @Test
    public void testSearchCanBeCancelled() throws InterruptedException {
        // a different seed, so that nothing is cached yet
        MemorySegment otherTerrainNoise = terrainNoise(SEED + 1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                MansionLootSearch.search(otherTerrainNoise, SEED + 1, version, 0, 0, 0, MansionLootSearch.MAX_RADIUS, Integer.MAX_VALUE, vexTrim, _ -> true, (_, _) -> {
                });
                outcome.set(new AssertionError("The search finished before it was cancelled"));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        thread.start();
        Thread.sleep(300);
        thread.interrupt();
        thread.join(10_000);
        assertFalse(thread.isAlive(), "The search did not stop after it was cancelled");
        assertInstanceOf(CancellationException.class, outcome.get());
    }

    @Test
    public void testUnsupportedVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MansionLootSearch.search(terrainNoise, SEED, Cubiomes.MC_1_19(), 0, 0, 0, RADIUS, 1, vexTrim, _ -> true, (_, _) -> {
        }));
    }

    @Test
    public void testMansionsOfRegionAreCached() {
        MansionLootSearch.clearCache();
        Optional<MansionLootSearch.Mansion> first = MansionLootSearch.getMansion(terrainNoise, SEED, version, 0, -1, -4);
        Optional<MansionLootSearch.Mansion> second = MansionLootSearch.getMansion(terrainNoise, SEED, version, 0, -1, -4);
        assertSame(first, second);
    }
}
