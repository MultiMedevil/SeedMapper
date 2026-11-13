package dev.xpple.seedmapper.support;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The reference the mansion tests compare against: the unmodified game code and
 * the unmodified game data that are on the test classpath.
 */
public final class VanillaMansions {

    private static final String TEMPLATE_FOLDER = "woodland_mansion/";

    private static MultiPackResourceManager resourceManager;
    private static StructureTemplateManager templateManager;
    private static Field palettesField;
    private static Field templateNameField;

    private VanillaMansions() {
    }

    public static synchronized void bootstrap() {
        if (templateManager != null) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            resourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
            Path worlds = Files.createTempDirectory("seedmapper-test-worlds");
            worlds.toFile().deleteOnExit();
            LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(worlds).createAccess("mansions");
            templateManager = new StructureTemplateManager(resourceManager, access, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);

            palettesField = StructureTemplate.class.getDeclaredField("palettes");
            palettesField.setAccessible(true);
            templateNameField = net.minecraft.world.level.levelgen.structure.TemplateStructurePiece.class.getDeclaredField("templateName");
            templateNameField.setAccessible(true);
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Piece(String template, int rotation, int mirror, BlockPos position, WoodlandMansionPieces.WoodlandMansionPiece piece) {
        public boolean isRoom() {
            return Character.isDigit(this.template.charAt(0));
        }
    }

    public record Mansion(long seed, int chunkX, int chunkZ, Rotation rotation, BlockPos origin, List<Piece> pieces) {
        public List<Piece> rooms() {
            return this.pieces.stream().filter(Piece::isRoom).toList();
        }
    }

    public record Chest(BlockPos position, long lootSeed, String template) {
    }

    /**
     * Run the game's mansion generation the same way {@code WoodlandMansionStructure}
     * does, with the random source of the structure start.
     */
    public static Mansion generate(long seed, int chunkX, int chunkZ, int y) {
        bootstrap();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        Rotation rotation = Rotation.getRandom(random);
        BlockPos origin = new BlockPos((chunkX << 4) + 7, y, (chunkZ << 4) + 7);
        List<WoodlandMansionPieces.WoodlandMansionPiece> generated = new LinkedList<>();
        WoodlandMansionPieces.generateMansion(templateManager, origin, rotation, generated, random);

        List<Piece> pieces = new ArrayList<>(generated.size());
        for (WoodlandMansionPieces.WoodlandMansionPiece piece : generated) {
            StructurePlaceSettings settings = piece.placeSettings();
            pieces.add(new Piece(templateName(piece), settings.getRotation().ordinal(), settings.getMirror().ordinal(), piece.templatePosition(), piece));
        }
        return new Mansion(seed, chunkX, chunkZ, rotation, origin, pieces);
    }

    private static String templateName(WoodlandMansionPieces.WoodlandMansionPiece piece) {
        try {
            return (String) templateNameField.get(piece);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.StructureBlockInfo> blocks(StructureTemplate template) {
        try {
            List<StructureTemplate.Palette> palettes = (List<StructureTemplate.Palette>) palettesField.get(template);
            if (palettes.size() != 1) {
                throw new IllegalStateException("Expected exactly one palette");
            }
            return palettes.getFirst().blocks();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Replay what happens to the decoration random when the pieces of a mansion are
     * placed chunk by chunk, and collect the loot seed every loot chest receives.
     *
     * <p>This follows {@code StructureStart#placeInChunk}, {@code TemplateStructurePiece#postProcess},
     * {@code StructureTemplate#placeInWorld} and {@code WoodlandMansionPiece#handleDataMarker}. It runs
     * over all pieces of the mansion, with the game's own templates, block order, transformations,
     * bounding boxes and random sources. Only the control flow between them is restated here, because
     * the methods themselves need a world to run in.
     *
     * @param decoratorIndex the index of the mansion among the structures of its generation step
     * @param generationStep the ordinal of the generation step
     */
    public static List<Chest> lootChests(Mansion mansion, int decoratorIndex, int generationStep) {
        BoundingBox total = null;
        for (Piece piece : mansion.pieces()) {
            BoundingBox box = piece.piece().getBoundingBox();
            total = total == null ? box : BoundingBox.encapsulating(total, box);
        }
        List<Chest> chests = new ArrayList<>();
        if (total == null) {
            return chests;
        }
        for (int chunkX = total.minX() >> 4; chunkX <= total.maxX() >> 4; chunkX++) {
            for (int chunkZ = total.minZ() >> 4; chunkZ <= total.maxZ() >> 4; chunkZ++) {
                int minX = chunkX << 4, minZ = chunkZ << 4;
                // ChunkGenerator#getWritableArea
                BoundingBox chunkBox = new BoundingBox(minX, -64 + 1, minZ, minX + 15, 319, minZ + 15);

                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
                long decorationSeed = random.setDecorationSeed(mansion.seed(), minX, minZ);
                random.setFeatureSeed(decorationSeed, decoratorIndex, generationStep);

                for (Piece piece : mansion.pieces()) {
                    if (!piece.piece().getBoundingBox().intersects(chunkBox)) {
                        continue;
                    }
                    StructureTemplate template = piece.piece().template();
                    StructurePlaceSettings settings = piece.piece().placeSettings().copy().setBoundingBox(chunkBox);
                    // every container that is part of the template is given a loot seed
                    for (StructureTemplate.StructureBlockInfo info : blocks(template)) {
                        if (info.nbt() == null || info.state().is(Blocks.STRUCTURE_BLOCK)) {
                            continue;
                        }
                        BlockPos pos = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(piece.position());
                        if (!chunkBox.isInside(pos)) {
                            continue;
                        }
                        if (info.state().getBlock() instanceof EntityBlock entityBlock
                            && entityBlock.newBlockEntity(pos, info.state()) instanceof RandomizableContainer) {
                            if (info.nbt().contains(RandomizableContainer.LOOT_TABLE_TAG)) {
                                throw new IllegalStateException("A mansion template contains a container with a loot table");
                            }
                            random.nextLong();
                        }
                    }
                    // afterwards the data markers of the template are handled
                    for (StructureTemplate.StructureBlockInfo marker : template.filterBlocks(piece.position(), settings, Blocks.STRUCTURE_BLOCK)) {
                        if (marker.nbt() == null || !"DATA".equals(marker.nbt().getStringOr("mode", ""))) {
                            continue;
                        }
                        if (marker.nbt().getStringOr("metadata", "").startsWith("Chest")) {
                            chests.add(new Chest(marker.pos(), random.nextLong(), piece.template()));
                        }
                    }
                }
            }
        }
        return chests;
    }

    /**
     * The salt of a structure for the decoration random, as {@code ChunkGenerator#applyBiomeDecoration}
     * derives it: structures are grouped by generation step in the order in which the registry was loaded.
     *
     * @return the generation step and the index within that step
     */
    public static int[] decoratorSalt(String structure) {
        bootstrap();
        FileToIdConverter converter = FileToIdConverter.registry(Registries.STRUCTURE);
        Map<Identifier, Resource> resources = converter.listMatchingResources(resourceManager);
        String step = null;
        List<String[]> structures = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier id = converter.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                structures.add(new String[]{id.getPath(), json.get("step").getAsString()});
                if (id.getPath().equals(structure)) {
                    step = json.get("step").getAsString();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (step == null) {
            throw new IllegalArgumentException("Unknown structure " + structure);
        }
        int index = 0;
        for (String[] other : structures) {
            if (other[0].equals(structure)) {
                break;
            }
            if (other[1].equals(step)) {
                index++;
            }
        }
        for (GenerationStep.Decoration decoration : GenerationStep.Decoration.values()) {
            if (decoration.getSerializedName().equals(step)) {
                return new int[]{decoration.ordinal(), index};
            }
        }
        throw new IllegalStateException("Unknown generation step " + step);
    }

    /**
     * Read a JSON file from the vanilla data pack.
     *
     * @param path the path below {@code data/minecraft/}, for example {@code loot_table/chests/woodland_mansion.json}
     */
    public static JsonObject readData(String path) {
        bootstrap();
        Resource resource = resourceManager.getResource(Identifier.withDefaultNamespace(path)).orElseThrow(() -> new IllegalArgumentException("No such resource: " + path));
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String roomTemplatePath(String template) {
        return TEMPLATE_FOLDER + template;
    }
}
