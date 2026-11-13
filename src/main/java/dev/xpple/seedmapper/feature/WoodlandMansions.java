package dev.xpple.seedmapper.feature;

import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.EnchantInstance;
import com.github.cubiomes.ItemStack;
import com.github.cubiomes.LootTableContext;
import com.github.cubiomes.MansionChest;
import com.github.cubiomes.MansionRoom;
import com.github.cubiomes.MansionStart;
import com.github.cubiomes.Pos;
import com.github.cubiomes.Pos3;
import com.github.cubiomes.StructureConfig;
import com.github.cubiomes.StructureSaltConfig;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Woodland mansion generation: where a mansion generates, which rooms it has,
 * where its loot chests are and what is inside of them.
 *
 * <p>The layout of a mansion and the loot of its chests only depend on the world
 * seed and the chunk of the structure start. The terrain decides whether the
 * mansion generates and at which Y level, see {@link Start}.
 */
public final class WoodlandMansions {

    private WoodlandMansions() {
    }

    public static final String LOOT_TABLE = "chests/woodland_mansion";

    /// The lowest Y level at which the game still generates a mansion.
    public static final int MIN_Y = 60;

    /**
     * The start of a mansion.
     *
     * @param chunkX the chunk X-coordinate of the structure start
     * @param chunkZ the chunk Z-coordinate of the structure start
     * @param origin the position the mansion is built from, its Y level is the level of the ground floor
     * @param rotation 0: none, 1: 90 degrees clockwise, 2: 180 degrees, 3: 90 degrees counterclockwise
     * @param lowestY the lowest Y level the mansion could have. This differs from the Y level of the
     * origin if the terrain below one of the corners the game samples is under the sea level. The
     * Y level then relies on that corner being covered by water, which is not verified.
     */
    public record Start(int chunkX, int chunkZ, BlockPos origin, int rotation, int lowestY) {
        /// Whether the Y level of the mansion (and so of everything in it) was computed exactly.
        public boolean isYExact() {
            return this.lowestY == this.origin.getY();
        }

        /// Whether the mansion generates regardless of the uncertainty in its Y level.
        public boolean isCertainToGenerate() {
            return this.lowestY >= MIN_Y;
        }
    }

    /**
     * A room of a mansion.
     *
     * @param template the structure template, for example {@code woodland_mansion/1x2_se1}
     * @param floor 0, 1 or 2
     * @param rotation 0: none, 1: 90 degrees clockwise, 2: 180 degrees, 3: 90 degrees counterclockwise
     * @param mirror 0: none, 1: left-right, 2: front-back
     * @param position the position of the origin of the template
     */
    public record Room(String template, int floor, int rotation, int mirror, BlockPos position) {
    }

    /**
     * A loot chest of a mansion.
     *
     * @param pos the position of the chest
     * @param lootSeed the loot seed the chest receives during world generation
     * @param lootTable the loot table of the chest
     * @param room the structure template of the room the chest is in
     * @param floor 0, 1 or 2
     */
    public record Chest(BlockPos pos, long lootSeed, String lootTable, String room, int floor) {
    }

    /**
     * An item stack in a chest.
     *
     * @param item the global Cubiomes item ID
     * @param count the size of the stack
     * @param enchantments pairs of Cubiomes enchantment ID and level
     */
    public record LootStack(int item, int count, List<int[]> enchantments) {
    }

    /// Whether mansion loot can be computed for the given version. This is the case for the
    /// versions whose mansion generation was verified against the game, which are 26.1 and later.
    public static boolean isLootSupported(int version) {
        try (Arena arena = Arena.ofConfined()) {
            return saltConfig(arena, version) != null;
        }
    }

    private static @Nullable MemorySegment saltConfig(Arena arena, int version) {
        MemorySegment structureSaltConfig = StructureSaltConfig.allocate(arena);
        // the salt of a mansion does not depend on the biome
        if (Cubiomes.getStructureSaltConfig(Cubiomes.Mansion(), version, -1, structureSaltConfig) == 0) {
            return null;
        }
        return structureSaltConfig;
    }

    /// Whether the mansion loot table of the given version can yield an item.
    public static boolean lootTableHasItem(int version, int item) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lootTableContextPtr = arena.allocate(Cubiomes.C_POINTER);
            if (Cubiomes.init_loot_table_name(lootTableContextPtr, arena.allocateFrom(LOOT_TABLE), version) == 0) {
                return false;
            }
            MemorySegment lootTableContext = lootTableContextPtr.get(ValueLayout.ADDRESS, 0).reinterpret(LootTableContext.sizeof());
            return Cubiomes.has_item(lootTableContext, item) != 0;
        }
    }

    /// The side length of a mansion region in blocks. Every region has one position where a mansion can generate.
    public static int regionSizeInBlocks(int version) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment structureConfig = StructureConfig.allocate(arena);
            if (Cubiomes.getStructureConfig(Cubiomes.Mansion(), version, structureConfig) == 0) {
                return -1;
            }
            return StructureConfig.regionSize(structureConfig) << 4;
        }
    }

    /**
     * Find the mansion of a region, if one generates there.
     *
     * @param terrainNoise the terrain noise, initialised for the overworld of the world
     * @param regionX the region X-coordinate
     * @param regionZ the region Z-coordinate
     * @return the start of the mansion, or {@code null} if the region does not have a mansion
     */
    public static @Nullable Start findStart(MemorySegment terrainNoise, int version, long seed, int regionX, int regionZ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment structurePos = Pos.allocate(arena);
            if (Cubiomes.getStructurePos(Cubiomes.Mansion(), version, seed, regionX, regionZ, structurePos) == 0) {
                return null;
            }
            return getStart(terrainNoise, Pos.x(structurePos) >> 4, Pos.z(structurePos) >> 4);
        }
    }

    /**
     * Determine whether a mansion generates with its structure start in the given
     * chunk. The chunk has to be the mansion position of its region.
     *
     * @param terrainNoise the terrain noise, initialised for the overworld of the world
     * @return the start of the mansion, or {@code null} if no mansion generates there
     */
    public static @Nullable Start getStart(MemorySegment terrainNoise, int chunkX, int chunkZ) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment start = MansionStart.allocate(arena);
            if (Cubiomes.getMansionStart(terrainNoise, chunkX, chunkZ, start) == 0) {
                return null;
            }
            MemorySegment pos = MansionStart.pos(start);
            return new Start(chunkX, chunkZ, new BlockPos(Pos3.x(pos), Pos3.y(pos), Pos3.z(pos)), MansionStart.rot(start), MansionStart.minY(start));
        }
    }

    /**
     * Generate the rooms of a mansion, in the order in which the game generates them.
     *
     * @param y the Y level of the mansion, see {@link Start#origin()}
     */
    public static List<Room> getRooms(long seed, int version, int chunkX, int chunkZ, int y) {
        try (Arena arena = Arena.ofConfined()) {
            int max = Cubiomes.MANSION_ROOMS_MAX();
            MemorySegment rooms = MansionRoom.allocateArray(max, arena);
            int count = Cubiomes.getMansionRooms(rooms, max, version, seed, chunkX, chunkZ, y);
            List<Room> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MemorySegment room = MansionRoom.asSlice(rooms, i);
                MemorySegment pos = MansionRoom.pos(room);
                String template = Cubiomes.getMansionTemplateName(Byte.toUnsignedInt(MansionRoom.tmpl(room))).getString(0);
                result.add(new Room(template, MansionRoom.floor(room), MansionRoom.rot(room), MansionRoom.mirror(room), new BlockPos(Pos3.x(pos), Pos3.y(pos), Pos3.z(pos))));
            }
            return result;
        }
    }

    /**
     * Compute the loot chests of a mansion and the loot seed of each of them.
     *
     * @param y the Y level of the mansion, see {@link Start#origin()}
     * @return the chests, empty if mansion loot is not supported for the version
     */
    public static List<Chest> getChests(long seed, int version, int chunkX, int chunkZ, int y) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment structureSaltConfig = saltConfig(arena, version);
            if (structureSaltConfig == null) {
                return List.of();
            }
            int max = Cubiomes.MANSION_CHESTS_MAX();
            MemorySegment chests = MansionChest.allocateArray(max, arena);
            int count = Cubiomes.getMansionChests(chests, max, structureSaltConfig, version, seed, chunkX, chunkZ, y);
            List<Chest> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MemorySegment chest = MansionChest.asSlice(chests, i);
                MemorySegment pos = MansionChest.pos(chest);
                result.add(new Chest(
                    new BlockPos(Pos3.x(pos), Pos3.y(pos), Pos3.z(pos)),
                    MansionChest.lootSeed(chest),
                    MansionChest.lootTable(chest).reinterpret(Long.MAX_VALUE).getString(0),
                    MansionChest.room(chest).reinterpret(Long.MAX_VALUE).getString(0),
                    MansionChest.floor(chest)
                ));
            }
            return result;
        }
    }

    /**
     * Generate the contents of a chest.
     *
     * @return the item stacks in the order in which they are generated, or {@code null}
     * if the loot table is not available for the version
     */
    public static @Nullable List<LootStack> generateLoot(Chest chest, int version) {
        List<LootStack> result = new ArrayList<>();
        boolean available = forEachItemStack(chest, version, (lootTableContext, itemStack) -> {
            MemorySegment enchantments = ItemStack.enchantments(itemStack);
            int enchantmentCount = ItemStack.enchantment_count(itemStack);
            List<int[]> enchantmentList = new ArrayList<>(enchantmentCount);
            for (int i = 0; i < enchantmentCount; i++) {
                MemorySegment enchantInstance = EnchantInstance.asSlice(enchantments, i);
                enchantmentList.add(new int[]{EnchantInstance.enchantment(enchantInstance), EnchantInstance.level(enchantInstance)});
            }
            result.add(new LootStack(Cubiomes.get_global_item_id(lootTableContext, ItemStack.item(itemStack)), ItemStack.count(itemStack), enchantmentList));
        });
        return available ? result : null;
    }

    /**
     * Count how many items of a kind a chest contains.
     *
     * @param item the global Cubiomes item ID
     * @param itemStackPredicate an additional condition on the native item stack
     * @return the number of items, or -1 if the loot table is not available for the version
     */
    public static int countItems(Chest chest, int version, int item, Predicate<MemorySegment> itemStackPredicate) {
        int[] count = {0};
        boolean available = forEachItemStack(chest, version, (lootTableContext, itemStack) -> {
            if (Cubiomes.get_global_item_id(lootTableContext, ItemStack.item(itemStack)) == item && itemStackPredicate.test(itemStack)) {
                count[0] += ItemStack.count(itemStack);
            }
        });
        return available ? count[0] : -1;
    }

    @FunctionalInterface
    public interface ItemStackConsumer {
        void accept(MemorySegment lootTableContext, MemorySegment itemStack);
    }

    /**
     * Roll the loot of a chest and pass every generated item stack to the consumer.
     * The native item stacks are only valid during the call.
     *
     * @return {@code false} if the loot table is not available for the version
     */
    public static boolean forEachItemStack(Chest chest, int version, ItemStackConsumer consumer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lootTableContextPtr = arena.allocate(Cubiomes.C_POINTER);
            if (Cubiomes.init_loot_table_name(lootTableContextPtr, arena.allocateFrom(chest.lootTable()), version) == 0) {
                return false;
            }
            MemorySegment lootTableContext = lootTableContextPtr.get(ValueLayout.ADDRESS, 0).reinterpret(LootTableContext.sizeof());
            // the loot tables are shared, so only one thread may roll loot at a time
            synchronized (WoodlandMansions.class) {
                Cubiomes.set_loot_prng_type(lootTableContext, Cubiomes.JAVA_RANDOM());
                Cubiomes.set_loot_seed(lootTableContext, chest.lootSeed());
                Cubiomes.generate_loot(lootTableContext);
                int lootCount = LootTableContext.generated_item_count(lootTableContext);
                MemorySegment generatedItems = LootTableContext.generated_items(lootTableContext);
                for (int i = 0; i < lootCount; i++) {
                    consumer.accept(lootTableContext, ItemStack.asSlice(generatedItems, i));
                }
            }
            return true;
        }
    }
}
