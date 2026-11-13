package dev.xpple.seedmapper.command.commands;

import com.github.cubiomes.CanyonCarverConfig;
import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.Generator;
import com.github.cubiomes.ItemStack;
import com.github.cubiomes.LootTableContext;
import com.github.cubiomes.OreVeinParameters;
import com.github.cubiomes.Piece;
import com.github.cubiomes.Pos;
import com.github.cubiomes.Pos3;
import com.github.cubiomes.StrongholdIter;
import com.github.cubiomes.StructureConfig;
import com.github.cubiomes.StructureSaltConfig;
import com.github.cubiomes.StructureVariant;
import com.github.cubiomes.SurfaceNoise;
import com.github.cubiomes.TerrainNoise;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.xpple.seedmapper.command.CommandExceptions;
import dev.xpple.seedmapper.command.CustomClientCommandSource;
import dev.xpple.seedmapper.command.arguments.CanyonCarverArgument;
import dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument;
import dev.xpple.seedmapper.feature.MansionLootSearch;
import dev.xpple.seedmapper.feature.StructureChecks;
import dev.xpple.seedmapper.feature.StructureVariantFeedbackHelper;
import dev.xpple.seedmapper.feature.WoodlandMansions;
import dev.xpple.seedmapper.seedmap.SeedMapData;
import dev.xpple.seedmapper.util.BiomeSeedIdentifier;
import dev.xpple.seedmapper.util.CubiomesHelper;
import dev.xpple.seedmapper.util.ExtraComponentUtils;
import dev.xpple.seedmapper.util.SeedIdentifier;
import dev.xpple.seedmapper.util.SpiralLoop;
import dev.xpple.seedmapper.util.SpiralSpliterator;
import dev.xpple.seedmapper.util.TwoDTree;
import dev.xpple.simplewaypoints.api.SimpleWaypointsAPI;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.mojang.brigadier.arguments.BoolArgumentType.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static dev.xpple.seedmapper.command.arguments.BiomeArgument.*;
import static dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument.*;
import static dev.xpple.seedmapper.command.arguments.StructurePredicateArgument.*;
import static dev.xpple.seedmapper.thread.LocatorThreadHelper.*;
import static dev.xpple.seedmapper.util.ChatBuilder.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public class LocateCommand {

    private static final int BIOME_SEARCH_RADIUS = 25600;
    private static final int BIOME_SEARCH_HORIZONTAL_STEP = 32;
    private static final int BIOME_SEARCH_VERTICAL_STEP = 64;

    private static final int MAX_CHUNK_RADIUS = 6400;

    public static final IntSet LOOT_SUPPORTED_STRUCTURES = IntSet.of(
        Cubiomes.Treasure(),
        Cubiomes.Desert_Pyramid(),
        Cubiomes.End_City(),
        Cubiomes.Igloo(),
        Cubiomes.Jungle_Pyramid(),
        Cubiomes.Ruined_Portal(),
        Cubiomes.Ruined_Portal_N(),
        Cubiomes.Fortress(),
        Cubiomes.Bastion(),
        Cubiomes.Outpost(),
        Cubiomes.Shipwreck(),
        Cubiomes.Stronghold(),
        Cubiomes.Abandoned_Camp(),
        Cubiomes.Mineshaft(),
        Cubiomes.Mansion()
    );

    public static final String DEFAULT_MANSION_LOOT_ITEM = "vex_armor_trim_smithing_template";

    // how often a long-running mansion search reports its progress
    private static final long MANSION_PROGRESS_INTERVAL_NANOS = 5_000_000_000L;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("sm:locate")
            .then(literal("biome")
                .then(argument("biome", biome())
                    .executes(ctx -> submit(() -> locateBiome(CustomClientCommandSource.of(ctx.getSource()), getBiome(ctx, "biome"))))))
            .then(literal("feature")
                .then(argument("structure", structurePredicate())
                    .executes(ctx -> submit(() -> locateStructure(CustomClientCommandSource.of(ctx.getSource()), getStructurePredicate(ctx, "structure"))))
                    .then(argument("variantdata", bool())
                        .executes(ctx -> submit(() -> locateStructure(CustomClientCommandSource.of(ctx.getSource()), getStructurePredicate(ctx, "structure"), getBool(ctx, "variantdata"))))))
                .then(literal("stronghold")
                    .executes(ctx -> submit(() -> locateStronghold(CustomClientCommandSource.of(ctx.getSource()))))))
            .then(literal("loot")
                .then(argument("amount", integer(1))
                    .then(argument("item", itemAndEnchantments())
                        .executes(ctx -> submit(() -> locateLoot(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "amount"), getItemAndEnchantments(ctx, "item")))))))
            .then(literal("mansionloot")
                .then(argument("amount", integer(1))
                    .executes(ctx -> submit(() -> locateMansionLoot(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "amount"), MansionLootSearch.DEFAULT_RADIUS, null)))
                    .then(argument("radius", integer(1, MansionLootSearch.MAX_RADIUS))
                        .executes(ctx -> submit(() -> locateMansionLoot(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "amount"), getInteger(ctx, "radius"), null)))
                        .then(argument("item", itemAndEnchantments())
                            .executes(ctx -> submit(() -> locateMansionLoot(CustomClientCommandSource.of(ctx.getSource()), getInteger(ctx, "amount"), getInteger(ctx, "radius"), getItemAndEnchantments(ctx, "item"))))))))
            .then(literal("slimechunk")
                .executes(ctx -> locateSlimeChunk(CustomClientCommandSource.of(ctx.getSource()))))
            .then(literal("spawn")
                .executes(ctx -> locateSpawn(CustomClientCommandSource.of(ctx.getSource()))))
            .then(literal("orevein")
                .then(literal("copper")
                    .executes(ctx -> submit(() -> locateOreVein(CustomClientCommandSource.of(ctx.getSource()), true))))
                .then(literal("iron")
                    .executes(ctx -> submit(() -> locateOreVein(CustomClientCommandSource.of(ctx.getSource()), false)))))
            .then(literal("canyon")
                .executes(ctx -> submit(() -> locateCanyon(CustomClientCommandSource.of(ctx.getSource()))))));
    }

    private static int locateBiome(CustomClientCommandSource source, int biome) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int dimension = source.getDimension();
        int version = source.getVersion();
        if (Cubiomes.getDimension(biome) != dimension) {
            throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
        }
        if (Cubiomes.biomeExists(version, biome) == 0) {
            throw CommandExceptions.BIOME_WRONG_VERSION_EXCEPTION.create();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment generator = Generator.allocate(arena);
            Cubiomes.setupGenerator(generator, version, source.getGeneratorFlags());
            Cubiomes.applySeed(generator, dimension, seed.seed());

            BlockPos center = BlockPos.containing(source.getPosition());

            int minY = version <= Cubiomes.MC_1_17_1() ? 0 : -64;
            int maxY = version <= Cubiomes.MC_1_17_1() ? 256 : 320;
            int[] ys = Mth.outFromOrigin(center.getY(), minY + 1, maxY + 1, BIOME_SEARCH_VERTICAL_STEP).toArray();

            BlockPos pos = SpiralLoop.spiral(center.getX(), center.getZ(), BIOME_SEARCH_RADIUS, BIOME_SEARCH_HORIZONTAL_STEP, (x, z) -> {
                for (int y : ys) {
                    if (Cubiomes.getBiomeAt(generator, 4, QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)) == biome) {
                        return new BlockPos(x, y, z);
                    }
                }
                return null;
            });
            if (pos == null) {
                throw CommandExceptions.NO_BIOME_FOUND_EXCEPTION.create(BIOME_SEARCH_RADIUS);
            }

            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.biome.foundAt", ExtraComponentUtils.formatXYZ(pos.getX(), pos.getY(), pos.getZ()))));
            return Command.SINGLE_SUCCESS;
        }
    }

    private static int locateStructure(CustomClientCommandSource source, StructureAndPredicate structureAndPredicate) throws CommandSyntaxException {
        return locateStructure(source, structureAndPredicate, false);
    }

    private static int locateStructure(CustomClientCommandSource source, StructureAndPredicate structureAndPredicate, boolean variantData) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int version = source.getVersion();
        int dimension = source.getDimension();
        try (Arena arena = Arena.ofConfined()) {
            int structure = structureAndPredicate.structure();
            MemorySegment structureConfig = StructureConfig.allocate(arena);
            int config = Cubiomes.getStructureConfig(structure, version, structureConfig);
            if (config == 0) {
                throw CommandExceptions.INCOMPATIBLE_PARAMETERS_EXCEPTION.create();
            }
            if (StructureConfig.dim(structureConfig) != dimension) {
                throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
            }

            MemorySegment terrainNoise = TerrainNoise.allocate(arena);
            Cubiomes.setupTerrainNoise(terrainNoise, version, source.getGeneratorFlags());
            Cubiomes.initTerrainNoise(terrainNoise, seed.seed(), dimension);
            MemorySegment generator = TerrainNoise.g(terrainNoise);

            // currently only used for end cities
            MemorySegment surfaceNoise = SurfaceNoise.allocate(arena);
            Cubiomes.initSurfaceNoise(surfaceNoise, dimension, seed.seed());

            // check if the structure will generate at a position
            StructureChecks.GenerationCheck generationCheck = StructureChecks.getGenerationCheck(structure);
            // check if the structure at that location also matches our requirements
            PiecesPredicate piecesPredicate = structureAndPredicate.piecesPredicate();
            StructureChecks.PiecesPredicateCheck piecesPredicateCheck = StructureChecks.getPiecesPredicateCheck(structure);
            VariantPredicate variantPredicate = structureAndPredicate.variantPredicate();
            StructureChecks.VariantPredicateCheck variantPredicateCheck = StructureChecks.getVariantPredicateCheck(structure);

            BlockPos center = BlockPos.containing(source.getPosition());
            int regionSize = StructureConfig.regionSize(structureConfig) << 4;
            MemorySegment structurePos = Pos.allocate(arena);
            MemorySegment pieces = Piece.allocateArray(StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, arena);
            MemorySegment structureVariant = StructureVariant.allocate(arena);
            Boolean found = SpiralLoop.spiral(center.getX() / regionSize, center.getZ() / regionSize, Level.MAX_LEVEL_SIZE / regionSize, (x, z) -> {
                if (!generationCheck.check(terrainNoise, x, z, structurePos)) {
                    return null;
                }
                if (!piecesPredicateCheck.check(piecesPredicate, pieces, generator, structurePos)) {
                    return null;
                }
                if (!variantPredicateCheck.check(variantPredicate, structureVariant, generator, structurePos)) {
                    return null;
                }
                return true;
            });
            if (found == null || !found) {
                throw CommandExceptions.NO_STRUCTURE_FOUND_EXCEPTION.create(Level.MAX_LEVEL_SIZE);
            }

            int structureXPos = Pos.x(structurePos);
            int structureZPos = Pos.z(structurePos);
            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.feature.structure.foundAt", ExtraComponentUtils.formatXZ(structureXPos, structureZPos))));

            if (structure == Cubiomes.End_City()) {
                int numPieces = Cubiomes.getEndCityPieces(pieces, seed.seed(), structureXPos >> 4, structureZPos >> 4);
                IntStream.range(0, numPieces)
                    .mapToObj(i -> Piece.asSlice(pieces, i))
                    .filter(piece -> Piece.type(piece) == Cubiomes.END_SHIP())
                    .findAny() // only one ship per end city
                    .map(Piece::pos)
                    .map(city -> new BlockPos(Pos3.x(city), Pos3.y(city) + 60, Pos3.z(city)))
                    .ifPresent(city -> source.getClient().schedule(() -> source.sendFeedback(Component.literal(" - ")
                        .append(Component.translatable("command.locate.feature.structure.endCity.hasShip", ExtraComponentUtils.formatXYZ(city.getX(), city.getY(), city.getZ()))))));
            } else if (structure == Cubiomes.Fortress()) {
                int numPieces = Cubiomes.getFortressPieces(pieces, StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, version, seed.seed(), structureXPos >> 4, structureZPos >> 4);
                IntStream.range(0, numPieces)
                    .mapToObj(i -> Piece.asSlice(pieces, i))
                    .filter(piece -> Piece.type(piece) == Cubiomes.BRIDGE_SPAWNER())
                    .map(Piece::pos)
                    .map(monsterThrone -> new BlockPos(Pos3.x(monsterThrone), Pos3.y(monsterThrone) + 10, Pos3.z(monsterThrone)))
                    .forEach(spawnerPos -> source.getClient().schedule(() -> source.sendFeedback(Component.literal(" - ")
                        .append(Component.translatable("command.locate.feature.structure.fortress.hasSpawner", ExtraComponentUtils.formatXYZ(spawnerPos.getX(), spawnerPos.getY(), spawnerPos.getZ()))))));
            }

            if (!variantData) {
                return Command.SINGLE_SUCCESS;
            }
            int biome = Cubiomes.getBiomeAt(generator, 4, structureXPos >> 2, 320 >> 2, structureZPos >> 2);
            Cubiomes.getVariant(structureVariant, structure, version, seed.seed(), structureXPos, structureZPos, biome);

            List<Component> components = StructureVariantFeedbackHelper.get(structure, structureVariant);
            if (components.isEmpty()) {
                return Command.SINGLE_SUCCESS;
            }
            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.feature.structure.variantData")));
            components.forEach(component -> source.getClient().schedule(() -> source.sendFeedback(Component.literal(" - ").append(component))));
            return Command.SINGLE_SUCCESS;
        }
    }

    private static int locateStronghold(CustomClientCommandSource source) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int dimension = source.getDimension();
        if (dimension != Cubiomes.DIM_OVERWORLD()) {
            throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
        }
        int version = source.getVersion();
        int generatorFlags = source.getGeneratorFlags();

        BlockPos position = BlockPos.containing(source.getPosition());

        TwoDTree tree = SeedMapData.strongholdDataCache.get().computeIfAbsent(new BiomeSeedIdentifier(seed.seed(), version, generatorFlags), _ -> calculateStrongholds(seed.seed(), version, generatorFlags));

        BlockPos pos = tree.nearestTo(position.atY(0));
        assert pos != null;

        source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.feature.stronghold.success", ExtraComponentUtils.formatXZ(pos.getX(), pos.getZ()))));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pieces = Piece.allocateArray(StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, arena);
            MemorySegment ssconf = StructureSaltConfig.allocate(arena);
            int numPieces;
            boolean lootSupported;
            // biome is not used for strongholds
            //noinspection AssignmentUsedAsCondition
            if (lootSupported = Cubiomes.getStructureSaltConfig(Cubiomes.Stronghold(), version, -1, ssconf) != 0) {
                numPieces = Cubiomes.getStrongholdLoot(pieces, StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, ssconf, version, seed.seed(), pos.getX() >> 4, pos.getZ() >> 4);
            } else {
                numPieces = Cubiomes.getStrongholdPieces(pieces, StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, version, seed.seed(), pos.getX() >> 4, pos.getZ() >> 4);
            }
            MemorySegment piece = IntStream.range(0, numPieces)
                .mapToObj(i -> Piece.asSlice(pieces, i))
                .filter(p -> Piece.type(p) == Cubiomes.SH_PORTAL_ROOM())
                .findAny().orElseThrow(); // every stronghold has a portal
            MemorySegment portalRoomPos = Piece.pos(piece);
            int portalRoomX = Pos3.x(portalRoomPos);
            int portalRoomZ = Pos3.z(portalRoomPos);
            if (lootSupported) {
                int eyesBitPack = Piece.additionalData(piece);
                int eyes = Integer.bitCount(eyesBitPack);
                source.getClient().schedule(() -> source.sendFeedback(Component.literal(" - ")
                    .append(Component.translatable("command.locate.feature.stronghold.portal", ExtraComponentUtils.formatXZ(portalRoomX, portalRoomZ), accent(Integer.toString(eyes))))));
            } else {
                source.getClient().schedule(() -> source.sendFeedback(Component.literal(" - ")
                    .append(Component.translatable("command.locate.feature.stronghold.portalNoEyes", ExtraComponentUtils.formatXZ(portalRoomX, portalRoomZ)))));
            }
            return numPieces;
        }
    }

    public static TwoDTree calculateStrongholds(long seed, int version, int generatorFlags) {
        TwoDTree tree = new TwoDTree();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment strongholdIter = StrongholdIter.allocate(arena);
            Cubiomes.initFirstStronghold(arena, strongholdIter, version, seed);
            MemorySegment generator = Generator.allocate(arena);
            Cubiomes.setupGenerator(generator, version, generatorFlags);
            Cubiomes.applySeed(generator, Cubiomes.DIM_OVERWORLD(), seed);

            final int count = version <= Cubiomes.MC_1_8() ? 3 : 128;
            for (int i = 0; i < count; i++) {
                if (Cubiomes.nextStronghold(strongholdIter, generator) == 0) {
                    break;
                }
                MemorySegment pos = StrongholdIter.pos(strongholdIter);
                tree.insert(new BlockPos(Pos.x(pos), 0, Pos.z(pos)));
            }
        }
        tree.balance();
        return tree;
    }

    private static int locateSlimeChunk(CustomClientCommandSource source) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        if (source.getDimension() != Cubiomes.DIM_OVERWORLD()) {
            throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
        }
        ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        ChunkPos pos = SpiralLoop.spiral(center.x(), center.z(), MAX_CHUNK_RADIUS, (x, z) -> {
            if (Cubiomes.isSlimeChunk(seed.seed(), x, z) == 0) {
                return null;
            }
            return new ChunkPos(x, z);
        });
        assert pos != null;

        int blockPosX = (pos.x() << 4) + 9;
        int blockPosZ = (pos.z() << 4) + 9;
        source.sendFeedback(Component.translatable("command.locate.feature.slimeChunk.foundAt",
            ExtraComponentUtils.formatXZ(blockPosX, blockPosZ, Component.translatable("command.locate.feature.slimeChunk.copy")),
            ExtraComponentUtils.formatXZ(pos.x(), pos.z(), Component.translatable("command.locate.feature.slimeChunk.copyChunk"))
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static int locateLoot(CustomClientCommandSource source, int amount, EnchantedItem itemPredicate) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int version = source.getVersion();
        if (version <= Cubiomes.MC_1_12()) {
            throw CommandExceptions.LOOT_NOT_SUPPORTED_EXCEPTION.create();
        }
        int dimension = source.getDimension();
        int generatorFlags = source.getGeneratorFlags();
        // mansions are handled separately, because the exact position of their chests is known
        boolean searchMansions = WoodlandMansions.isLootSupported(version) && WoodlandMansions.lootTableHasItem(version, itemPredicate.item());

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment terrainNoise = TerrainNoise.allocate(arena);
            Cubiomes.setupTerrainNoise(terrainNoise, version, generatorFlags);
            Cubiomes.initTerrainNoise(terrainNoise, seed.seed(), dimension);
            MemorySegment generator = TerrainNoise.g(terrainNoise);

            // currently only used for end cities
            MemorySegment surfaceNoise = SurfaceNoise.allocate(arena);
            Cubiomes.initSurfaceNoise(surfaceNoise, dimension, seed.seed());

            BlockPos center = BlockPos.containing(source.getPosition());

            record StructureIterationState(MemorySegment structureConfig, StructureChecks.GenerationCheck generationCheck, SpiralSpliterator iterator) {
            }
            List<StructureIterationState> structureStates = LOOT_SUPPORTED_STRUCTURES.intStream()
                .boxed()
                .<MemorySegment>mapMulti((s, consumer) -> {
                    MemorySegment structureConfig = StructureConfig.allocate(arena);
                    if (Cubiomes.getStructureConfig(s, version, structureConfig) != 0) {
                        consumer.accept(structureConfig);
                    }
                })
                .filter(sconf -> StructureConfig.dim(sconf) == dimension)
                .filter(sconf -> StructureConfig.structType(sconf) != Cubiomes.Mansion() || searchMansions)
                .sorted(Comparator.comparingInt(StructureConfig::regionSize))
                .map(sconf -> {
                    int regionSize = StructureConfig.regionSize(sconf) << 4;
                    return new StructureIterationState(sconf, StructureChecks.getGenerationCheck(StructureConfig.structType(sconf)), new SpiralSpliterator(center.getX() / regionSize, center.getZ() / regionSize, Level.MAX_LEVEL_SIZE / regionSize));
                })
                .collect(Collectors.toList());

            MemorySegment ltcPtr = arena.allocate(Cubiomes.C_POINTER);
            // count how many loot tables each structure has
            Map<Integer, Integer> lootTableCount = structureStates.stream()
                .collect(Collectors.toMap(state -> (int) StructureConfig.structType(state.structureConfig), state -> Cubiomes.getLootTableCountForStructure(StructureConfig.structType(state.structureConfig), version)));
            // ignore chests with loot tables that do not contain the desired item
            Map<Integer, Set<String>> ignoredLootTables = new HashMap<>();

            MemorySegment structurePos = Pos.allocate(arena);
            MemorySegment pieces = Piece.allocateArray(StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, arena);
            MemorySegment structureVariant = StructureVariant.allocate(arena);
            MemorySegment structureSaltConfig = StructureSaltConfig.allocate(arena);
            int[] found = {0};
            int[] mansionChests = {0};

            /*
             * To locate loot closest to the player, all structures
             * must be considered. That means we must cycle over
             * the structures and incrementally find the desired
             * loot. However, structures have different region
             * sizes. That means some structures appear more often
             * than others. To this end, the below code intends to
             * loop over structures with smaller region sizes more
             * often.
             */
            for (int stateIndex = 0, radius = 0;; stateIndex++) {
                if (structureStates.isEmpty()) {
                    throw CommandExceptions.LOOT_NOT_AVAILABLE_EXCEPTION.create();
                }
                if (stateIndex >= structureStates.size()) {
                    stateIndex = 0;
                    radius++;
                }
                StructureIterationState state = structureStates.get(stateIndex);
                MemorySegment structureConfig = state.structureConfig;
                int structure = StructureConfig.structType(structureConfig);

                int regionSize = StructureConfig.regionSize(structureConfig) << 4;
                int maxRegionSize = StructureConfig.regionSize(structureStates.getLast().structureConfig) << 4;

                int previouslyFound = found[0];
                List<BlockPos> aggregatedLootPositions = new ArrayList<>();
                while (true) {
                    if (state.iterator.getX() * regionSize > center.getX() + radius * maxRegionSize
                        || state.iterator.getZ() * regionSize > center.getZ() + radius * maxRegionSize) {
                        break;
                    }

                    checkCancelled();
                    boolean exhausted = !state.iterator.tryAdvance(pos -> {
                        if (structure == Cubiomes.Mansion()) {
                            Optional<MansionLootSearch.Mansion> mansion = MansionLootSearch.getMansion(terrainNoise, seed.seed(), version, generatorFlags, pos.x(), pos.z());
                            if (mansion.isEmpty()) {
                                return;
                            }
                            List<MansionLootSearch.Match> matches = new ArrayList<>();
                            for (WoodlandMansions.Chest chest : mansion.get().chests()) {
                                int count = WoodlandMansions.countItems(chest, version, itemPredicate.item(), itemPredicate.enchantmensPredicate());
                                if (count > 0) {
                                    matches.add(new MansionLootSearch.Match(chest, count));
                                    found[0] += count;
                                }
                            }
                            if (!matches.isEmpty()) {
                                sendMansionMatches(source, center, itemPredicate.item(), mansion.get(), matches, mansionChests);
                            }
                            return;
                        }
                        if (!state.generationCheck.check(terrainNoise, pos.x(), pos.z(), structurePos)) {
                            return;
                        }
                        int posX = Pos.x(structurePos);
                        int posZ = Pos.z(structurePos);
                        int biome = Cubiomes.getBiomeAt(generator, 4, posX >> 2, 320 >> 2, posZ >> 2);
                        Cubiomes.getVariant(structureVariant, structure, version, seed.seed(), posX, posZ, biome);
                        biome = StructureVariant.biome(structureVariant) != -1 ? StructureVariant.biome(structureVariant) : biome;
                        if (Cubiomes.getStructureSaltConfig(structure, version, biome, structureSaltConfig) == 0) {
                            return;
                        }
                        int numPieces = Cubiomes.getStructurePieces(pieces, StructureChecks.MAX_END_CITY_AND_FORTRESS_PIECES, structure, structureSaltConfig, structureVariant, version, seed.seed(), posX, posZ);
                        if (numPieces <= 0) {
                            return;
                        }
                        int foundInStructure = 0;
                        for (int i = 0; i < numPieces; i++) {
                            MemorySegment piece = Piece.asSlice(pieces, i);
                            int chestCount = Piece.chestCount(piece);
                            if (chestCount == 0) {
                                continue;
                            }
                            MemorySegment lootTables = Piece.lootTables(piece);
                            MemorySegment lootSeeds = Piece.lootSeeds(piece);
                            for (int j = 0; j < chestCount; j++) {
                                MemorySegment lootTable = lootTables.getAtIndex(ValueLayout.ADDRESS, j).reinterpret(Long.MAX_VALUE);
                                MemorySegment lootTableContext;
                                if (Cubiomes.init_loot_table_name(ltcPtr, lootTable, version) == 0) {
                                    lootTableContext = null;
                                } else {
                                    lootTableContext = ltcPtr.get(ValueLayout.ADDRESS, 0).reinterpret(LootTableContext.sizeof());
                                }
                                if (lootTableContext == null || Cubiomes.has_item(lootTableContext, itemPredicate.item()) == 0) {
                                    Set<String> structureIgnoredLootTables = ignoredLootTables.computeIfAbsent(structure, _ -> new HashSet<>());
                                    structureIgnoredLootTables.add(lootTable.getString(0));
                                    // if structure has no loot tables with the desired item, remove structure from state loop
                                    if (structureIgnoredLootTables.size() == lootTableCount.get(structure)) {
                                        return;
                                    }
                                    continue;
                                }
                                Cubiomes.set_loot_prng_type(lootTableContext, Cubiomes.JAVA_RANDOM());
                                Cubiomes.set_loot_seed(lootTableContext, lootSeeds.getAtIndex(Cubiomes.C_LONG_LONG, j));
                                Cubiomes.generate_loot(lootTableContext);
                                int lootCount = LootTableContext.generated_item_count(lootTableContext);
                                for (int k = 0; k < lootCount; k++) {
                                    MemorySegment itemStack = ItemStack.asSlice(LootTableContext.generated_items(lootTableContext), k);
                                    if (Cubiomes.get_global_item_id(lootTableContext, ItemStack.item(itemStack)) == itemPredicate.item() && itemPredicate.enchantmensPredicate().test(itemStack)) {
                                        foundInStructure += ItemStack.count(itemStack);
                                    }
                                }
                            }
                        }
                        if (foundInStructure > 0) {
                            found[0] += foundInStructure;
                            aggregatedLootPositions.add(new BlockPos(posX, 0, posZ));
                        }
                    });
                    if (exhausted) {
                        structureStates.remove(stateIndex);
                        break;
                    }
                    if (ignoredLootTables.getOrDefault(structure, Collections.emptySet()).size() == lootTableCount.get(structure)) {
                        structureStates.remove(stateIndex);
                        break;
                    }
                    if (found[0] >= amount) {
                        break;
                    }
                }
                int newlyFound = found[0] - previouslyFound;
                // matches in mansions have been reported chest by chest already
                if (newlyFound > 0 && !aggregatedLootPositions.isEmpty()) {
                    String structureName = Cubiomes.struct2str(StructureConfig.structType(structureConfig)).getString(0);
                    source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.loot.foundAtStructure", accent(String.valueOf(newlyFound)), structureName, ExtraComponentUtils.formatXZCollection(aggregatedLootPositions))));
                }
                if (found[0] >= amount) {
                    break;
                }
            }
            String itemName = Cubiomes.global_id2item_name(itemPredicate.item(), version).getString(0);
            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.loot.totalFound", accent(String.valueOf(found[0])), itemName)));
            return found[0];
        }
    }

    private static int locateMansionLoot(CustomClientCommandSource source, int amount, int radius, @Nullable EnchantedItem item) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int version = source.getVersion();
        if (source.getDimension() != Cubiomes.DIM_OVERWORLD()) {
            throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
        }
        if (!WoodlandMansions.isLootSupported(version)) {
            throw CommandExceptions.MANSION_LOOT_NOT_SUPPORTED_EXCEPTION.create();
        }
        EnchantedItem itemPredicate = item != null ? item : ItemAndEnchantmentsPredicateArgument.getItem(DEFAULT_MANSION_LOOT_ITEM);
        if (itemPredicate == null) {
            throw CommandExceptions.UNKNOWN_ITEM_EXCEPTION.create(DEFAULT_MANSION_LOOT_ITEM);
        }
        Component itemName = itemName(itemPredicate.item());
        if (!WoodlandMansions.lootTableHasItem(version, itemPredicate.item())) {
            throw CommandExceptions.MANSION_LOOT_ITEM_NOT_AVAILABLE_EXCEPTION.create(itemName);
        }
        int generatorFlags = source.getGeneratorFlags();
        BlockPos center = BlockPos.containing(source.getPosition());
        String versionString = Cubiomes.mc2str(version).getString(0);

        source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.mansionLoot.searching",
            accent(String.valueOf(radius)), accent(Component.empty().append(itemName)), ExtraComponentUtils.formatNumber(seed.seed()), accent(versionString))));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment terrainNoise = TerrainNoise.allocate(arena);
            Cubiomes.setupTerrainNoise(terrainNoise, version, generatorFlags);
            Cubiomes.initTerrainNoise(terrainNoise, seed.seed(), Cubiomes.DIM_OVERWORLD());

            int[] chestNumber = {0};
            long[] lastProgress = {System.nanoTime()};
            MansionLootSearch.Result result;
            try {
                result = MansionLootSearch.search(terrainNoise, seed.seed(), version, generatorFlags, center.getX(), center.getZ(), radius, amount, itemPredicate.item(), itemPredicate.enchantmensPredicate(), new MansionLootSearch.Listener() {
                    @Override
                    public void onMansion(MansionLootSearch.Mansion mansion, List<MansionLootSearch.Match> matches) {
                        sendMansionMatches(source, center, itemPredicate.item(), mansion, matches, chestNumber);
                        lastProgress[0] = System.nanoTime();
                    }

                    @Override
                    public void onProgress(int searchedRadius) {
                        long now = System.nanoTime();
                        if (now - lastProgress[0] >= MANSION_PROGRESS_INTERVAL_NANOS && searchedRadius < radius) {
                            lastProgress[0] = now;
                            source.getClient().schedule(() -> source.sendFeedback(base(Component.translatable("command.locate.mansionLoot.progress", String.valueOf(searchedRadius)))));
                        }
                    }
                });
            } catch (CancellationException _) {
                // stopped with /sm:stoptask
                return 0;
            }

            if (result.itemsFound() == 0) {
                source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.mansionLoot.noneFound",
                    accent(String.valueOf(radius)), accent(Component.empty().append(itemName)), accent(String.valueOf(result.mansionsChecked())))));
                return 0;
            }
            source.getClient().schedule(() -> {
                source.sendFeedback(Component.translatable("command.locate.mansionLoot.totalFound",
                    accent(String.valueOf(result.itemsFound())), accent(Component.empty().append(itemName)), accent(String.valueOf(result.chestsFound())), accent(String.valueOf(result.mansionsWithMatches())), accent(String.valueOf(result.mansionsChecked()))));
                source.sendFeedback(dark(Component.translatable("command.locate.mansionLoot.disclaimer")));
            });
            return result.itemsFound();
        }
    }

    private static Component itemName(int item) {
        return new net.minecraft.world.item.ItemStack(ItemAndEnchantmentsPredicateArgument.ITEM_ID_TO_MC.get(item)).getItemName();
    }

    /**
     * Report a mansion and its chests that contain the desired item. This can be called from any thread.
     *
     * @param chestNumber the running number of the chests that have been reported so far
     */
    private static void sendMansionMatches(CustomClientCommandSource source, BlockPos center, int item, MansionLootSearch.Mansion mansion, List<MansionLootSearch.Match> matches, int[] chestNumber) {
        WoodlandMansions.Start start = mansion.start();
        BlockPos origin = start.origin();
        List<Component> lines = new ArrayList<>();

        MutableComponent mansionLine = Component.translatable("command.locate.mansionLoot.mansion",
            ExtraComponentUtils.formatXYZ(origin.getX(), origin.getY(), origin.getZ(), Component.translatable("command.locate.mansionLoot.copyMansion")),
            accent(String.valueOf(Math.round(MansionLootSearch.horizontalDistance(origin, center.getX(), center.getZ())))));
        if (!start.isCertainToGenerate()) {
            mansionLine.append(" ").append(hover(warn(Component.translatable("command.locate.mansionLoot.mayNotGenerate")), base(Component.translatable("command.locate.mansionLoot.mayNotGenerate.hover"))));
        } else if (!start.isYExact()) {
            mansionLine.append(" ").append(hover(warn(Component.translatable("command.locate.mansionLoot.yEstimated")), base(Component.translatable("command.locate.mansionLoot.yEstimated.hover"))));
        }
        lines.add(mansionLine);

        Component itemName = itemName(item);
        for (MansionLootSearch.Match match : matches) {
            BlockPos pos = match.chest().pos();
            int number = ++chestNumber[0];
            MutableComponent chestLine = Component.literal(" - ").append(Component.translatable("command.locate.mansionLoot.chest",
                accent(String.valueOf(number)),
                ExtraComponentUtils.formatXYZ(pos.getX(), pos.getY(), pos.getZ(), Component.translatable("command.locate.mansionLoot.copyChest")),
                accent(String.valueOf(Math.round(MansionLootSearch.horizontalDistance(pos, center.getX(), center.getZ())))),
                Component.translatable("command.locate.mansionLoot.loot", accent(String.valueOf(match.count())), itemName)));
            String waypointName = "mansion_chest_%d_%d_%d".formatted(pos.getX(), pos.getY(), pos.getZ());
            chestLine.append(" ").append(run(hover(format(Component.translatable("command.locate.mansionLoot.waypoint"), net.minecraft.ChatFormatting.UNDERLINE), base(Component.translatable("command.locate.mansionLoot.waypoint.hover"))), () -> addWaypoint(source, waypointName, pos)));
            lines.add(chestLine);
        }
        source.getClient().schedule(() -> lines.forEach(source::sendFeedback));
    }

    private static void addWaypoint(CustomClientCommandSource source, String name, BlockPos pos) {
        SimpleWaypointsAPI waypointsApi = SimpleWaypointsAPI.getInstance();
        String identifier = waypointsApi.getWorldIdentifier(source.getClient());
        if (identifier == null) {
            source.sendError(Component.translatable("commands.exceptions.couldNotDetermineWorldIdentifier"));
            return;
        }
        try {
            waypointsApi.addWaypoint(identifier, CubiomesHelper.getMinecraftDimension(Cubiomes.DIM_OVERWORLD()), name, pos);
            source.sendFeedback(Component.translatable("command.locate.mansionLoot.waypointAdded", accent(name)));
        } catch (CommandSyntaxException e) {
            source.sendError(Component.empty().append(e.getRawMessage() instanceof Component component ? component : Component.literal(e.getMessage())));
        }
    }

    private static int locateSpawn(CustomClientCommandSource source) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment generator = Generator.allocate(arena);
            Cubiomes.setupGenerator(generator, source.getVersion(), source.getGeneratorFlags());
            Cubiomes.applySeed(generator, source.getDimension(), seed.seed());
            MemorySegment pos = Cubiomes.getSpawn(arena, generator);

            source.sendFeedback(Component.translatable("command.locate.spawn.success", ExtraComponentUtils.formatXZ(Pos.x(pos), Pos.z(pos))));
            return Command.SINGLE_SUCCESS;
        }
    }

    private static int locateOreVein(CustomClientCommandSource source, boolean wantCopperVein) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int version = source.getVersion();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parameters = OreVeinParameters.allocate(arena);
            if (Cubiomes.initOreVeinNoise(parameters, seed.seed(), version) == 0) {
                throw CommandExceptions.ORE_VEIN_WRONG_VERSION_EXCEPTION.create();
            }
            ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
            BlockPos pos = SpiralLoop.spiral(center.x(), center.z(), MAX_CHUNK_RADIUS, (chunkX, chunkZ) -> {
                int minX = chunkX << 4;
                int minZ = chunkZ << 4;

                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                        for (int y = -60; y <= 50; y++) {
                            int block = Cubiomes.getOreVeinBlockAt(minX + x, y, minZ + z, parameters);
                            if (block == -1) {
                                continue;
                            }
                            boolean isCopperVein = block == Cubiomes.RAW_COPPER_BLOCK() || block == Cubiomes.COPPER_ORE() || block == Cubiomes.GRANITE();
                            if (wantCopperVein == isCopperVein) {
                                return new BlockPos(minX + x, y, minZ + z);
                            }
                        }
                    }
                }
                return null;
            });
            if (pos == null) {
                throw CommandExceptions.NO_ORE_VEIN_FOUND_EXCEPTION.create(MAX_CHUNK_RADIUS);
            }

            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.oreVein.foundAt", ExtraComponentUtils.formatXYZ(pos.getX(), pos.getY(), pos.getZ(), Component.translatable("command.locate.oreVein.copy")))));

            return Command.SINGLE_SUCCESS;
        }
    }

    private static int locateCanyon(CustomClientCommandSource source) throws CommandSyntaxException {
        SeedIdentifier seed = source.getSeed().getSecond();
        int dimension = source.getDimension();
        if (dimension != Cubiomes.DIM_OVERWORLD()) {
            throw CommandExceptions.INVALID_DIMENSION_EXCEPTION.create();
        }
        int version = source.getVersion();
        if (version < Cubiomes.MC_1_13()) {
            throw CommandExceptions.CANYON_WRONG_VERSION_EXCEPTION.create();
        }
        ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        try (Arena arena = Arena.ofConfined()) {
            ToIntBiFunction<Integer, Integer> biomeFunction;
            int generatorFlags = source.getGeneratorFlags();
            if (version > Cubiomes.MC_1_17_1()) {
                biomeFunction = (_, _) -> -1;
            } else {
                MemorySegment generator = Generator.allocate(arena);
                Cubiomes.setupGenerator(generator, version, generatorFlags);
                Cubiomes.applySeed(generator, dimension, seed.seed());
                biomeFunction = (chunkX, chunkZ) -> Cubiomes.getBiomeAt(generator, 4, chunkX << 2, 0, chunkZ << 2);
            }
            MemorySegment ccc = CanyonCarverConfig.allocate(arena);
            MemorySegment rnd = arena.allocate(Cubiomes.C_LONG_LONG);
            ChunkPos pos = SpiralLoop.spiral(center.x(), center.z(), MAX_CHUNK_RADIUS, (chunkX, chunkZ) -> {
                for (int canyonCarver : CanyonCarverArgument.CANYON_CARVERS.values()) {
                    if (Cubiomes.getCanyonCarverConfig(canyonCarver, version, ccc) == 0) {
                        continue;
                    }
                    int biome = biomeFunction.applyAsInt(chunkX, chunkZ);
                    if (Cubiomes.isViableCanyonBiome(canyonCarver, biome) == 0) {
                        continue;
                    }
                    if (Cubiomes.checkCanyonStart(seed.seed(), chunkX, chunkZ, ccc, rnd) == 0) {
                        continue;
                    }
                    return new ChunkPos(chunkX, chunkZ);
                }
                return null;
            });
            if (pos == null) {
                throw CommandExceptions.NO_CANYON_FOUND_EXCEPTION.create(MAX_CHUNK_RADIUS);
            }
            source.getClient().schedule(() -> source.sendFeedback(Component.translatable("command.locate.canyon.foundAt", ExtraComponentUtils.formatXZ(SectionPos.sectionToBlockCoord(pos.x()), SectionPos.sectionToBlockCoord(pos.z()), Component.translatable("command.locate.canyon.copy")))));
            return Command.SINGLE_SUCCESS;
        }
    }
}
