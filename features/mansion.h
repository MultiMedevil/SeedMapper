#ifndef MANSION_H_
#define MANSION_H_

#include "../finders.h"
#include "mansion_templates.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Woodland mansion generation.
 *
 * A mansion is built from a 11x11 grid per floor. The grid (corridors, rooms,
 * the staircase to the third floor) and the room templates that are placed in
 * it only depend on the world seed and the chunk position of the structure
 * start; they do not depend on the terrain. The terrain only decides the Y level
 * the whole building is placed at, and whether the mansion generates at all.
 *
 * The generation matches the game for versions 26.1 to 26.3, which is what it was
 * verified against. getStructureSaltConfig does not provide a salt config for
 * older versions.
 *
 * All positions returned by the functions below are absolute block positions,
 * given the Y level of the mansion (see getMansionBaseY in terrainnoise.h). Pass
 * baseY = 0 to obtain Y offsets relative to the mansion origin instead.
 */

enum {
    // an upper bound for the number of rooms: three floors, 7 usable rows of 11 cells
    MANSION_ROOMS_MAX = 3 * 7 * 11,
    // an upper bound for the number of loot chests (at most two per room)
    MANSION_CHESTS_MAX = 2 * MANSION_ROOMS_MAX,
};

enum MansionMirror {
    MANSION_MIRROR_NONE,
    MANSION_MIRROR_LEFT_RIGHT,
    MANSION_MIRROR_FRONT_BACK,
};

STRUCT(MansionRoom)
{
    Pos3 pos;           // position of the template origin
    uint8_t tmpl;       // enum MansionRoomTemplate, index into g_mansion_templates
    uint8_t floor;      // 0, 1 or 2
    uint8_t rot;        // 0:0, 1:cw90, 2:cw180, 3:cw270=ccw90
    uint8_t mirror;     // enum MansionMirror
};

STRUCT(MansionChest)
{
    Pos3 pos;               // block position of the chest
    uint64_t lootSeed;      // LootTableSeed of the chest
    const char *lootTable;  // always "chests/woodland_mansion"
    const char *room;       // name of the room template the chest belongs to
    int roomIndex;          // index into the list produced by getMansionRooms
    uint8_t floor;          // 0, 1 or 2
};

STRUCT(MansionStart)
{
    Pos3 pos;   // the origin of the mansion, pos.y is the Y level to pass as baseY
    int rot;    // 0:0, 1:cw90, 2:cw180, 3:cw270=ccw90
    int minY;   // the lowest Y level the origin could have, equal to pos.y if the Y level is exact
};

/**
 * Determine whether a mansion generates with its structure start in the given
 * chunk, and at which Y level. The chunk has to be a valid mansion position
 * already (see getStructurePos). Only versions since 1.18 are supported.
 *
 * The game places the mansion at the Y level of the lowest of four surface
 * blocks, requires that level to be at least 60, and checks the biome there. The
 * surface heights are computed with sampleWorldSurfaceHeight. If the terrain at
 * one of the four positions is below the sea level, the result relies on that
 * position being covered by water. This is almost always the case, but it is not
 * verified. start->minY is then less than start->pos.y, and the Y level (and for
 * minY < 60 even the existence of the mansion) should be treated as an estimate.
 * The X- and Z-coordinates, the layout and the loot do not depend on the Y level.
 *
 * Aquifers are not simulated. In rare cases an aquifer puts water on top of the
 * terrain above the sea level at one of the four positions, which makes the
 * mansion generate higher than this function reports (and start->minY says).
 *
 * @param tn the terrain noise, initialised for the overworld
 * @param chunkX, chunkZ the chunk position of the structure start
 * @param start the output
 * @return nonzero if the mansion generates
 */
int getMansionStart(TerrainNoise *tn, int chunkX, int chunkZ, MansionStart *start);

/**
 * Get the rotation of the mansion whose structure start is in the given chunk.
 *
 * @return 0:0, 1:cw90, 2:cw180, 3:cw270=ccw90
 */
int getMansionRotation(uint64_t seed, int chunkX, int chunkZ);

/**
 * Get the name of a room template.
 *
 * @param tmpl an enum MansionRoomTemplate value
 * @return the template path, e.g. "woodland_mansion/1x2_se1", or NULL if out of range
 */
const char *getMansionTemplateName(int tmpl);

/**
 * Generate the rooms of the mansion whose structure start is in the given chunk.
 * The rooms are returned in the order in which the game adds them to the piece
 * list. Other pieces (walls, roofs, corridors, carpets, the entrance) contain
 * neither loot nor containers and are not returned.
 *
 * @param rooms the output buffer
 * @param n the size of the output buffer, MANSION_ROOMS_MAX is always sufficient
 * @param mc the Minecraft version
 * @param seed the world seed
 * @param chunkX, chunkZ the chunk position of the structure start
 * @param baseY the Y level of the mansion origin
 * @return the number of rooms that were written
 */
int getMansionRooms(MansionRoom *rooms, int n, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY);

/**
 * Compute the loot chests of the mansion whose structure start is in the given
 * chunk, together with the loot seed the game assigns to each of them during
 * world generation.
 *
 * @param chests the output buffer
 * @param n the size of the output buffer, MANSION_CHESTS_MAX is always sufficient
 * @param ssconf the structure salt config from getStructureSaltConfig
 * @param mc the Minecraft version
 * @param seed the world seed
 * @param chunkX, chunkZ the chunk position of the structure start
 * @param baseY the Y level of the mansion origin
 * @return the number of chests that were written
 */
int getMansionChests(MansionChest *chests, int n, StructureSaltConfig ssconf, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY);

/**
 * Like getMansionChests, but writes one Piece per room that has at least one
 * loot chest, in the format used by getStructurePieces.
 *
 * @return the number of pieces that were written
 */
int getMansionLoot(Piece *list, int n, StructureSaltConfig ssconf, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY);

#ifdef __cplusplus
}
#endif

#endif //MANSION_H_
