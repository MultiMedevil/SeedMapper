package dev.xpple.seedmapper;

import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.StructureSaltConfig;
import dev.xpple.seedmapper.feature.WoodlandMansions;
import dev.xpple.seedmapper.support.NativeLibrary;
import dev.xpple.seedmapper.support.VanillaMansions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the mansion generation of SeedMapper with the game itself. The game
 * code and the game data on the test classpath are the reference, see
 * {@link VanillaMansions}.
 */
public class WoodlandMansionGenerationTest {

    // seeds that are always part of the test, in addition to random ones
    private static final long[] FIXED_SEEDS = {6306952185402203104L, 0L, 1L, -1L, 12345L, Long.MIN_VALUE, Long.MAX_VALUE};

    private static final int RANDOM_MANSIONS = 3000;

    private static int version;

    @BeforeAll
    public static void setUp() {
        NativeLibrary.load();
        VanillaMansions.bootstrap();
        version = Cubiomes.MC_NEWEST();
    }

    private record Location(long seed, int chunkX, int chunkZ, int y) {
    }

    private static List<Location> locations() {
        List<Location> locations = new ArrayList<>();
        Random random = new Random(20261113L);
        for (long seed : FIXED_SEEDS) {
            for (int i = 0; i < 20; i++) {
                locations.add(new Location(seed, random.nextInt(-100_000, 100_000), random.nextInt(-100_000, 100_000), random.nextInt(60, 200)));
            }
        }
        for (int i = 0; i < RANDOM_MANSIONS; i++) {
            // the world border is at 1.875 million chunks
            locations.add(new Location(random.nextLong(), random.nextInt(-1_875_000, 1_875_000), random.nextInt(-1_875_000, 1_875_000), random.nextInt(60, 250)));
        }
        return locations;
    }

    @Test
    public void testRotationMatchesVanilla() {
        for (Location location : locations()) {
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(location.seed, location.chunkX, location.chunkZ);
            Rotation expected = Rotation.getRandom(random);
            assertEquals(expected.ordinal(), Cubiomes.getMansionRotation(location.seed, location.chunkX, location.chunkZ), () -> "Rotation at " + location);
        }
    }

    @Test
    public void testRoomsMatchVanilla() {
        Map<String, Integer> templateCounts = new TreeMap<>();
        // which combinations of floor, room size, rotation and mirroring have been compared
        Set<String> placements = new HashSet<>();
        int rooms = 0;

        for (Location location : locations()) {
            VanillaMansions.Mansion expected = VanillaMansions.generate(location.seed, location.chunkX, location.chunkZ, location.y);
            List<VanillaMansions.Piece> expectedRooms = expected.rooms();
            List<WoodlandMansions.Room> actualRooms = WoodlandMansions.getRooms(location.seed, version, location.chunkX, location.chunkZ, location.y);

            assertEquals(expectedRooms.size(), actualRooms.size(), () -> "Number of rooms at " + location);
            for (int i = 0; i < expectedRooms.size(); i++) {
                VanillaMansions.Piece expectedRoom = expectedRooms.get(i);
                WoodlandMansions.Room actualRoom = actualRooms.get(i);
                int index = i;
                assertEquals(VanillaMansions.roomTemplatePath(expectedRoom.template()), actualRoom.template(), () -> "Template of room " + index + " at " + location);
                assertEquals(expectedRoom.rotation(), actualRoom.rotation(), () -> "Rotation of room " + index + " (" + expectedRoom.template() + ") at " + location);
                assertEquals(expectedRoom.mirror(), actualRoom.mirror(), () -> "Mirror of room " + index + " (" + expectedRoom.template() + ") at " + location);
                assertEquals(expectedRoom.position(), actualRoom.position(), () -> "Position of room " + index + " (" + expectedRoom.template() + ") at " + location);

                templateCounts.merge(expectedRoom.template(), 1, Integer::sum);
                int relativeRotation = (expectedRoom.rotation() - expected.rotation().ordinal()) & 3;
                placements.add(actualRoom.floor() + ":" + expectedRoom.template().substring(0, 5) + ":" + relativeRotation + ":" + expectedRoom.mirror());
                rooms++;
            }
        }

        System.out.println("Compared " + rooms + " rooms of " + locations().size() + " mansions with vanilla");
        System.out.println("Room templates seen: " + templateCounts);
        System.out.println("Distinct placements (floor:size:rotation:mirror) seen: " + placements.size());
        // every room template has to be covered
        Set<String> unseen = new HashSet<>();
        for (int i = 0; i < Cubiomes.MANSION_ROOM_TEMPLATE_COUNT(); i++) {
            String name = Cubiomes.getMansionTemplateName(i).getString(0).substring("woodland_mansion/".length());
            if (!templateCounts.containsKey(name)) {
                unseen.add(name);
            }
        }
        assertEquals(Set.of(), unseen, "Room templates that were never generated");
    }

    @Test
    public void testNoContainersOutsideOfRooms() {
        // the native code only looks at rooms, so no other piece may touch the decoration random
        Location location = locations().getFirst();
        VanillaMansions.Mansion mansion = VanillaMansions.generate(location.seed, location.chunkX, location.chunkZ, location.y);
        List<VanillaMansions.Piece> others = mansion.pieces().stream().filter(piece -> !piece.isRoom()).toList();
        assertFalse(others.isEmpty());
        VanillaMansions.Mansion withoutRooms = new VanillaMansions.Mansion(mansion.seed(), mansion.chunkX(), mansion.chunkZ(), mansion.rotation(), mansion.origin(), others);
        assertTrue(VanillaMansions.lootChests(withoutRooms, 0, 0).isEmpty());
    }

    @Test
    public void testDecoratorSaltMatchesVanilla() {
        int[] expected = VanillaMansions.decoratorSalt("mansion");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment structureSaltConfig = StructureSaltConfig.allocate(arena);
            assertNotEquals(0, Cubiomes.getStructureSaltConfig(Cubiomes.Mansion(), version, -1, structureSaltConfig));
            assertEquals(expected[0], StructureSaltConfig.generationStep(structureSaltConfig), "Generation step");
            assertEquals(expected[1], StructureSaltConfig.decoratorIndex(structureSaltConfig), "Decorator index");
        }
    }

    @Test
    public void testChestsMatchVanilla() {
        int[] salt = VanillaMansions.decoratorSalt("mansion");
        int chests = 0;
        int mansionsWithChests = 0;
        Map<String, Integer> roomCounts = new TreeMap<>();
        Comparator<BlockPos> order = Comparator.<BlockPos>comparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getY()).thenComparingInt(pos -> pos.getZ());

        for (Location location : locations()) {
            VanillaMansions.Mansion mansion = VanillaMansions.generate(location.seed, location.chunkX, location.chunkZ, location.y);
            List<VanillaMansions.Chest> expected = new ArrayList<>(VanillaMansions.lootChests(mansion, salt[1], salt[0]));
            List<WoodlandMansions.Chest> actual = new ArrayList<>(WoodlandMansions.getChests(location.seed, version, location.chunkX, location.chunkZ, location.y));
            expected.sort(Comparator.comparing(VanillaMansions.Chest::position, order));
            actual.sort(Comparator.comparing(WoodlandMansions.Chest::pos, order));

            assertEquals(expected.size(), actual.size(), () -> "Number of chests at " + location);
            for (int i = 0; i < expected.size(); i++) {
                VanillaMansions.Chest expectedChest = expected.get(i);
                WoodlandMansions.Chest actualChest = actual.get(i);
                assertEquals(expectedChest.position(), actualChest.pos(), () -> "Chest position at " + location);
                assertEquals(expectedChest.lootSeed(), actualChest.lootSeed(), () -> "Loot seed of the chest at " + expectedChest.position() + " at " + location);
                assertEquals(VanillaMansions.roomTemplatePath(expectedChest.template()), actualChest.room(), () -> "Room of the chest at " + expectedChest.position() + " at " + location);
                assertEquals(WoodlandMansions.LOOT_TABLE, actualChest.lootTable());
                roomCounts.merge(expectedChest.template(), 1, Integer::sum);
                chests++;
            }
            if (!expected.isEmpty()) {
                mansionsWithChests++;
            }
        }
        System.out.println("Compared " + chests + " chests of " + mansionsWithChests + " mansions with the reference");
        System.out.println("Chests per room template: " + roomCounts);
        assertTrue(chests > 1000);
        // all nine room templates with loot chests have to be covered
        assertEquals(Set.of("1x1_a4", "1x1_as1", "1x2_a1", "1x2_b3", "1x2_b4", "1x2_d2", "1x2_s1", "1x2_se1", "2x2_b5"), roomCounts.keySet());
    }

    @Test
    public void testGenerationIsDeterministic() {
        Map<Location, List<WoodlandMansions.Chest>> first = new HashMap<>();
        List<Location> locations = locations().subList(0, 200);
        for (Location location : locations) {
            first.put(location, WoodlandMansions.getChests(location.seed, version, location.chunkX, location.chunkZ, location.y));
        }
        for (Location location : locations) {
            assertEquals(first.get(location), WoodlandMansions.getChests(location.seed, version, location.chunkX, location.chunkZ, location.y));
            for (WoodlandMansions.Chest chest : first.get(location)) {
                List<WoodlandMansions.LootStack> loot = WoodlandMansions.generateLoot(chest, version);
                List<WoodlandMansions.LootStack> again = WoodlandMansions.generateLoot(chest, version);
                assertNotNull(loot);
                assertNotNull(again);
                assertEquals(loot.size(), again.size());
                for (int i = 0; i < loot.size(); i++) {
                    assertEquals(loot.get(i).item(), again.get(i).item());
                    assertEquals(loot.get(i).count(), again.get(i).count());
                }
            }
        }
    }

    @Test
    public void testYLevelOnlyShiftsTheMansion() {
        for (Location location : locations().subList(0, 100)) {
            List<WoodlandMansions.Chest> low = WoodlandMansions.getChests(location.seed, version, location.chunkX, location.chunkZ, 60);
            List<WoodlandMansions.Chest> high = WoodlandMansions.getChests(location.seed, version, location.chunkX, location.chunkZ, 100);
            assertEquals(low.size(), high.size());
            for (int i = 0; i < low.size(); i++) {
                assertEquals(low.get(i).pos().above(40), high.get(i).pos());
                assertEquals(low.get(i).lootSeed(), high.get(i).lootSeed());
            }
        }
    }
}
