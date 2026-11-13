package dev.xpple.seedmapper.feature;

import net.minecraft.core.BlockPos;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Searches the woodland mansions around a position for chests that contain a
 * certain item, nearest mansion first.
 */
public final class MansionLootSearch {

    private MansionLootSearch() {
    }

    /// The largest search radius in blocks. A search of this size takes a few seconds.
    public static final int MAX_RADIUS = 250_000;
    public static final int DEFAULT_RADIUS = 50_000;

    private static final int MAX_CACHED_REGIONS = 200_000;

    /**
     * A mansion with its loot chests.
     */
    public record Mansion(WoodlandMansions.Start start, List<WoodlandMansions.Chest> chests) {
    }

    /**
     * A chest that contains the item that is searched for.
     *
     * @param count the number of items in the chest
     */
    public record Match(WoodlandMansions.Chest chest, int count) {
    }

    /**
     * The outcome of a search.
     *
     * @param itemsFound the total number of items in the matching chests
     * @param chestsFound the number of matching chests
     * @param mansionsWithMatches the number of mansions that have at least one matching chest
     * @param mansionsChecked the number of mansions whose chests were examined
     * @param radiusExhausted whether the whole search radius was covered, {@code false} if the
     * search stopped early because enough items were found
     */
    public record Result(int itemsFound, int chestsFound, int mansionsWithMatches, int mansionsChecked, boolean radiusExhausted) {
    }

    public interface Listener {
        /**
         * Called for every mansion that has at least one matching chest, in order of increasing distance.
         */
        void onMansion(Mansion mansion, List<Match> matches);

        /**
         * Called from time to time while the search is running.
         *
         * @param searchedRadius all mansions within this many blocks have been found
         */
        default void onProgress(int searchedRadius) {
        }
    }

    private record CacheKey(long seed, int version, int generatorFlags, int regionX, int regionZ) {
    }

    // which mansion a region has does not change, so remember it across searches
    private static final Map<CacheKey, Optional<Mansion>> CACHE = new ConcurrentHashMap<>();

    /**
     * Get the mansion of a region together with its chests.
     *
     * @param terrainNoise the terrain noise, initialised for the overworld of the world
     * @return the mansion, or empty if the region does not have one
     */
    public static Optional<Mansion> getMansion(MemorySegment terrainNoise, long seed, int version, int generatorFlags, int regionX, int regionZ) {
        CacheKey key = new CacheKey(seed, version, generatorFlags, regionX, regionZ);
        Optional<Mansion> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        WoodlandMansions.Start start = WoodlandMansions.findStart(terrainNoise, version, seed, regionX, regionZ);
        Optional<Mansion> mansion;
        if (start == null) {
            mansion = Optional.empty();
        } else {
            mansion = Optional.of(new Mansion(start, WoodlandMansions.getChests(seed, version, start.chunkX(), start.chunkZ(), start.origin().getY())));
        }
        if (CACHE.size() >= MAX_CACHED_REGIONS) {
            CACHE.clear();
        }
        CACHE.put(key, mansion);
        return mansion;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static double horizontalDistance(BlockPos pos, int x, int z) {
        double dx = pos.getX() - x;
        double dz = pos.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Search for chests with an item.
     *
     * <p>The search can be cancelled by interrupting the thread it runs on.
     *
     * @param terrainNoise the terrain noise, initialised for the overworld of the world
     * @param centerX the X-coordinate to search around
     * @param centerZ the Z-coordinate to search around
     * @param radius the search radius in blocks, at most {@link #MAX_RADIUS}
     * @param amount the search stops as soon as this many items have been found
     * @param item the global Cubiomes item ID to search for
     * @param itemStackPredicate an additional condition on the native item stack
     * @throws CancellationException if the thread was interrupted
     * @throws IllegalArgumentException if mansion loot is not supported for the version
     */
    public static Result search(MemorySegment terrainNoise, long seed, int version, int generatorFlags, int centerX, int centerZ, int radius, int amount, int item, Predicate<MemorySegment> itemStackPredicate, Listener listener) {
        if (!WoodlandMansions.isLootSupported(version)) {
            throw new IllegalArgumentException("Mansion loot is not supported for this version");
        }
        radius = Math.clamp(radius, 0, MAX_RADIUS);
        final int regionSize = WoodlandMansions.regionSizeInBlocks(version);
        final int centerRegionX = Math.floorDiv(centerX, regionSize);
        final int centerRegionZ = Math.floorDiv(centerZ, regionSize);
        final int rings = Math.ceilDiv(radius, regionSize) + 1;

        record Candidate(Mansion mansion, double distance) {
        }
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(Comparator.comparingDouble(Candidate::distance));

        int itemsFound = 0, chestsFound = 0, mansionsWithMatches = 0, mansionsChecked = 0;

        for (int ring = 0; ring <= rings; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                // only the outline of the square, the inside was covered by the previous rings
                int stepZ = Math.abs(dx) == ring ? 1 : Math.max(2 * ring, 1);
                for (int dz = -ring; dz <= ring; dz += stepZ) {
                    checkCancelled();
                    Optional<Mansion> mansion = getMansion(terrainNoise, seed, version, generatorFlags, centerRegionX + dx, centerRegionZ + dz);
                    if (mansion.isEmpty()) {
                        continue;
                    }
                    double distance = horizontalDistance(mansion.get().start().origin(), centerX, centerZ);
                    if (distance <= radius) {
                        candidates.add(new Candidate(mansion.get(), distance));
                    }
                }
            }

            // every mansion that has not been seen yet is at least this far away
            final double safeDistance = ring == rings ? Double.POSITIVE_INFINITY : (double) ring * regionSize;
            while (!candidates.isEmpty() && candidates.peek().distance() <= safeDistance) {
                checkCancelled();
                Mansion mansion = candidates.poll().mansion();
                mansionsChecked++;
                List<Match> matches = new ArrayList<>();
                for (WoodlandMansions.Chest chest : mansion.chests()) {
                    int count = WoodlandMansions.countItems(chest, version, item, itemStackPredicate);
                    if (count > 0) {
                        matches.add(new Match(chest, count));
                        itemsFound += count;
                    }
                }
                if (matches.isEmpty()) {
                    continue;
                }
                chestsFound += matches.size();
                mansionsWithMatches++;
                listener.onMansion(mansion, matches);
                if (itemsFound >= amount) {
                    return new Result(itemsFound, chestsFound, mansionsWithMatches, mansionsChecked, false);
                }
            }
            listener.onProgress((int) Math.min(radius, (long) ring * regionSize));
        }
        return new Result(itemsFound, chestsFound, mansionsWithMatches, mansionsChecked, true);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
