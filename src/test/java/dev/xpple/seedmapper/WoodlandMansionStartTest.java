package dev.xpple.seedmapper;

import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.Pos;
import com.github.cubiomes.TerrainNoise;
import com.google.gson.JsonElement;
import dev.xpple.seedmapper.feature.StructureChecks;
import dev.xpple.seedmapper.feature.WoodlandMansions;
import dev.xpple.seedmapper.support.NativeLibrary;
import dev.xpple.seedmapper.support.VanillaMansions;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares where SeedMapper says mansions generate, and at which Y level, with
 * the world generator of the game on the test classpath: the structure placement,
 * the surface height of the noise chunk generator (aquifers included) and the
 * biome source.
 */
public class WoodlandMansionStartTest {

    private static final long[] SEEDS = {6306952185402203104L, 1L, -4712345678901234567L};
    // regions around the origin that are compared for every seed, in each direction
    private static final int REGION_RADIUS = 20;

    private static int version;
    private static HolderLookup.Provider registries;
    private static Holder<NoiseGeneratorSettings> noiseSettings;
    private static MultiNoiseBiomeSource biomeSource;
    private static NoiseBasedChunkGenerator chunkGenerator;
    private static RandomSpreadStructurePlacement placement;
    private static Set<String> mansionBiomes;

    @BeforeAll
    public static void setUp() {
        NativeLibrary.load();
        VanillaMansions.bootstrap();
        version = Cubiomes.MC_NEWEST();

        registries = VanillaRegistries.createWorldLookup();
        noiseSettings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        biomeSource = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        chunkGenerator = new NoiseBasedChunkGenerator(biomeSource, noiseSettings);
        placement = (RandomSpreadStructurePlacement) registries.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(BuiltinStructureSets.WOODLAND_MANSIONS).value().placement();

        assertEquals("#minecraft:has_structure/woodland_mansion", VanillaMansions.readData("worldgen/structure/mansion.json").get("biomes").getAsString());
        mansionBiomes = new HashSet<>();
        for (JsonElement biome : VanillaMansions.readData("tags/worldgen/biome/has_structure/woodland_mansion.json").getAsJsonArray("values")) {
            mansionBiomes.add(biome.getAsString());
        }
    }

    /// The Y level `WoodlandMansionStructure#findGenerationPoint` computes, or `Integer.MIN_VALUE` if the mansion does not generate.
    private static int vanillaY(long seed, RandomState randomState, int chunkX, int chunkZ) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        Rotation rotation = Rotation.getRandom(random);
        // Structure#getLowestYIn5by5BoxOffset7Blocks
        int dx = 5, dz = 5;
        if (rotation == Rotation.CLOCKWISE_90) {
            dx = -5;
        } else if (rotation == Rotation.CLOCKWISE_180) {
            dx = -5;
            dz = -5;
        } else if (rotation == Rotation.COUNTERCLOCKWISE_90) {
            dz = -5;
        }
        int x = (chunkX << 4) + 7, z = (chunkZ << 4) + 7;
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
        int y = Integer.MAX_VALUE;
        for (int[] corner : new int[][]{{x, z}, {x, z + dz}, {x + dx, z}, {x + dx, z + dz}}) {
            y = Math.min(y, chunkGenerator.getFirstOccupiedHeight(corner[0], corner[1], Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState));
        }
        if (y < 60) {
            return Integer.MIN_VALUE;
        }
        Holder<Biome> biome = biomeSource.createUncachedResolver(randomState).getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
        String name = biome.unwrapKey().orElseThrow().identifier().toString();
        return mansionBiomes.contains(name) ? y : Integer.MIN_VALUE;
    }

    @Test
    public void testStartsMatchVanillaWorldGeneration() {
        int regions = 0, vanillaMansions = 0, exactMatches = 0, raisedByAquifer = 0, estimates = 0, estimatesCorrect = 0;
        StringBuilder problems = new StringBuilder();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment structurePos = Pos.allocate(arena);
            MemorySegment checkPos = Pos.allocate(arena);
            StructureChecks.GenerationCheck mansionCheck = StructureChecks.getGenerationCheck(Cubiomes.Mansion());
            for (long seed : SEEDS) {
                RandomState randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, noiseSettings.value());
                MemorySegment terrainNoise = TerrainNoise.allocate(arena);
                Cubiomes.setupTerrainNoise(terrainNoise, version, 0);
                Cubiomes.initTerrainNoise(terrainNoise, seed, Cubiomes.DIM_OVERWORLD());

                for (int regionX = -REGION_RADIUS; regionX <= REGION_RADIUS; regionX++) {
                    for (int regionZ = -REGION_RADIUS; regionZ <= REGION_RADIUS; regionZ++) {
                        regions++;
                        // the position of the structure start
                        assertNotEquals(0, Cubiomes.getStructurePos(Cubiomes.Mansion(), version, seed, regionX, regionZ, structurePos));
                        int chunkX = Pos.x(structurePos) >> 4, chunkZ = Pos.z(structurePos) >> 4;
                        ChunkPos expectedChunk = placement.getPotentialStructureChunk(seed, regionX * placement.spacing(), regionZ * placement.spacing());
                        assertEquals(expectedChunk.x(), chunkX, "Chunk X of region " + regionX + ", " + regionZ);
                        assertEquals(expectedChunk.z(), chunkZ, "Chunk Z of region " + regionX + ", " + regionZ);

                        int expectedY = vanillaY(seed, randomState, chunkX, chunkZ);
                        WoodlandMansions.Start start = WoodlandMansions.getStart(terrainNoise, chunkX, chunkZ);
                        // the check used by the seed map and /sm:locate feature agrees
                        assertEquals(start != null, mansionCheck.check(terrainNoise, regionX, regionZ, checkPos));
                        String where = "seed " + seed + " chunk " + chunkX + ", " + chunkZ;
                        if (expectedY != Integer.MIN_VALUE) {
                            vanillaMansions++;
                        }
                        if (start == null) {
                            if (expectedY != Integer.MIN_VALUE) {
                                problems.append("missed the mansion at ").append(where).append(" (y = ").append(expectedY).append(")\n");
                            }
                            continue;
                        }
                        assertEquals(expectedChunk.x(), start.chunkX());
                        assertEquals(expectedChunk.z(), start.chunkZ());
                        assertEquals((chunkX << 4) + 7, start.origin().getX());
                        assertEquals((chunkZ << 4) + 7, start.origin().getZ());
                        if (start.isYExact()) {
                            // an exact result has to be exact
                            if (expectedY == start.origin().getY()) {
                                exactMatches++;
                            } else if (expectedY != Integer.MIN_VALUE && expectedY > start.origin().getY()) {
                                // The one thing that is not simulated: an aquifer can put water on top of the terrain
                                // above the sea level, which lifts the surface. This is rare, and counted below.
                                raisedByAquifer++;
                                System.out.println("Raised by an aquifer at " + where + ": expected " + expectedY + ", got " + start.origin().getY());
                            } else {
                                problems.append("wrong exact Y level at ").append(where).append(": expected ").append(expectedY == Integer.MIN_VALUE ? "no mansion" : expectedY).append(", got ").append(start.origin().getY()).append("\n");
                            }
                        } else {
                            // an estimate may be off, but only within the range it declares
                            estimates++;
                            if (expectedY == start.origin().getY()) {
                                estimatesCorrect++;
                            } else if (expectedY == Integer.MIN_VALUE) {
                                if (start.isCertainToGenerate()) {
                                    problems.append("estimated mansion that does not generate at ").append(where).append("\n");
                                }
                            } else if (expectedY < start.lowestY() || expectedY > start.origin().getY()) {
                                problems.append("estimate out of its range at ").append(where).append(": expected ").append(expectedY).append(", got ").append(start.lowestY()).append("..").append(start.origin().getY()).append("\n");
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Compared " + regions + " mansion regions with vanilla world generation: " + vanillaMansions + " mansions, "
            + exactMatches + " with an exact Y level that matches, " + raisedByAquifer + " raised by an aquifer, "
            + estimatesCorrect + " of " + estimates + " estimated Y levels match");
        assertEquals("", problems.toString());
        assertTrue(vanillaMansions > 50);
        // every mansion of the game was found, and nearly all of them at exactly the right Y level
        assertEquals(vanillaMansions, exactMatches + raisedByAquifer + estimates);
        assertTrue(raisedByAquifer * 30 <= vanillaMansions, "Too many mansions with a wrong Y level");
    }
}
