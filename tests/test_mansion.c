#include <stdlib.h>

#include "testing.h"
#include "../finders.h"
#include "../features/mansion.h"
#include "../loot/items.h"
#include "../loot/loot_tables.h"

/*
 * The expected values in this file were produced by this implementation after it
 * had been compared with the game: the rooms with WoodlandMansionPieces of 26.1,
 * 26.2 and 26.3, the loot with the loot table of the game data. They guard against
 * regressions; they are not an independent source of truth.
 */

static const uint64_t SEED = 6306952185402203104ULL;

static int count_item(const char *loot_table, uint64_t loot_seed, int version, int item) {
    LootTableContext *ctx;
    if (!init_loot_table_name(&ctx, loot_table, version)) {
        return -1;
    }
    set_loot_prng_type(ctx, JAVA_RANDOM);
    set_loot_seed(ctx, loot_seed);
    generate_loot(ctx);
    int count = 0;
    for (int i = 0; i < ctx->generated_item_count; i++) {
        if (get_global_item_id(ctx, ctx->generated_items[i].item) == item) {
            count += ctx->generated_items[i].count;
        }
    }
    return count;
}

static int test_mansion_salt_config() {
    int ret = 0;

    StructureSaltConfig ssconf;
    ASSERT_NONZERO(ret, getStructureSaltConfig(Mansion, MC_26_3, -1, &ssconf));
    ASSERT_EQ(ret, ssconf.generationStep, 4);
    ASSERT_EQ(ret, ssconf.decoratorIndex, 23);
    ASSERT_NONZERO(ret, getStructureSaltConfig(Mansion, MC_26_2, -1, &ssconf));
    ASSERT_EQ(ret, ssconf.generationStep, 4);
    ASSERT_EQ(ret, ssconf.decoratorIndex, 5);
    ASSERT_NONZERO(ret, getStructureSaltConfig(Mansion, MC_26_1, -1, &ssconf));
    ASSERT_EQ(ret, ssconf.decoratorIndex, 5);
    // not verified against the game, so not offered
    ASSERT_EQ(ret, getStructureSaltConfig(Mansion, MC_1_21_11, -1, &ssconf), 0);
    ASSERT_EQ(ret, getStructureSaltConfig(Mansion, MC_1_20, -1, &ssconf), 0);

    ASSERT_EQ(ret, getLootTableCountForStructure(Mansion, MC_26_3), 1);
    return ret;
}

static int test_mansion_rooms_26_3() {
    int ret = 0;

    const int version = MC_26_3;
    const int chunkX = -41, chunkZ = -279, y = 79;

    ASSERT_EQ(ret, getMansionRotation(SEED, chunkX, chunkZ), 2);

    MansionRoom rooms[MANSION_ROOMS_MAX];
    int roomCount = getMansionRooms(rooms, MANSION_ROOMS_MAX, version, SEED, chunkX, chunkZ, y);
    ASSERT_EQ(ret, roomCount, 39);

    // woodland_mansion/1x2_a4 rot 3 mirror 2 pos -607 79 -4455
    ASSERT_EQ(ret, (int) rooms[0].tmpl, (int) MANSION_1X2_A4);
    ASSERT_EQ(ret, strcmp(getMansionTemplateName(rooms[0].tmpl), "woodland_mansion/1x2_a4"), 0);
    ASSERT_EQ(ret, (int) rooms[0].rot, 3);
    ASSERT_EQ(ret, (int) rooms[0].mirror, (int) MANSION_MIRROR_FRONT_BACK);
    ASSERT_EQ(ret, (int) rooms[0].floor, 0);
    ASSERT_EQ(ret, rooms[0].pos.x, -607);
    ASSERT_EQ(ret, rooms[0].pos.y, 79);
    ASSERT_EQ(ret, rooms[0].pos.z, -4455);
    // woodland_mansion/2x2_a1 rot 1 mirror 1 pos -639 79 -4471
    ASSERT_EQ(ret, (int) rooms[4].tmpl, (int) MANSION_2X2_A1);
    ASSERT_EQ(ret, (int) rooms[4].rot, 1);
    ASSERT_EQ(ret, (int) rooms[4].mirror, (int) MANSION_MIRROR_LEFT_RIGHT);
    ASSERT_EQ(ret, rooms[4].pos.x, -639);
    ASSERT_EQ(ret, rooms[4].pos.z, -4471);

    // the buffer is never overrun, and the Y level only shifts the rooms
    MansionRoom few[3];
    ASSERT_EQ(ret, getMansionRooms(few, 3, version, SEED, chunkX, chunkZ, 0), 3);
    ASSERT_EQ(ret, few[2].pos.x, rooms[2].pos.x);
    ASSERT_EQ(ret, few[2].pos.y, rooms[2].pos.y - y);
    ASSERT_EQ(ret, few[2].pos.z, rooms[2].pos.z);

    ASSERT_TRUE(ret, getMansionTemplateName(-1) == NULL);
    ASSERT_TRUE(ret, getMansionTemplateName(MANSION_ROOM_TEMPLATE_COUNT) == NULL);
    return ret;
}

static int test_mansion_chests_26_3() {
    int ret = 0;

    const int version = MC_26_3;
    const int chunkX = -41, chunkZ = -279, y = 79;
    StructureSaltConfig ssconf;
    ASSERT_NONZERO(ret, getStructureSaltConfig(Mansion, version, -1, &ssconf));

    MansionChest chests[MANSION_CHESTS_MAX];
    int chestCount = getMansionChests(chests, MANSION_CHESTS_MAX, ssconf, version, SEED, chunkX, chunkZ, y);
    ASSERT_EQ(ret, chestCount, 2);

    ASSERT_EQ(ret, chests[0].pos.x, -611);
    ASSERT_EQ(ret, chests[0].pos.y, 80);
    ASSERT_EQ(ret, chests[0].pos.z, -4452);
    ASSERT_EQ(ret, (int64_t) chests[0].lootSeed, -8366196242350463770LL);
    ASSERT_EQ(ret, strcmp(chests[0].room, "woodland_mansion/1x2_a1"), 0);
    ASSERT_EQ(ret, strcmp(chests[0].lootTable, "chests/woodland_mansion"), 0);
    ASSERT_EQ(ret, (int) chests[0].floor, 0);

    ASSERT_EQ(ret, chests[1].pos.x, -632);
    ASSERT_EQ(ret, chests[1].pos.y, 105);
    ASSERT_EQ(ret, chests[1].pos.z, -4471);
    ASSERT_EQ(ret, (int64_t) chests[1].lootSeed, 8588079541716638204LL);
    ASSERT_EQ(ret, strcmp(chests[1].room, "woodland_mansion/2x2_b5"), 0);
    ASSERT_EQ(ret, (int) chests[1].floor, 2);

    // the first chest has a vex armor trim, the second one does not
    ASSERT_EQ(ret, count_item(chests[0].lootTable, chests[0].lootSeed, version, ITEM_VEX_ARMOR_TRIM_SMITHING_TEMPLATE), 1);
    ASSERT_EQ(ret, count_item(chests[0].lootTable, chests[0].lootSeed, version, ITEM_ENCHANTED_GOLDEN_APPLE), 1);
    ASSERT_EQ(ret, count_item(chests[0].lootTable, chests[0].lootSeed, version, ITEM_STRING), 13);
    ASSERT_EQ(ret, count_item(chests[1].lootTable, chests[1].lootSeed, version, ITEM_VEX_ARMOR_TRIM_SMITHING_TEMPLATE), 0);
    ASSERT_EQ(ret, count_item(chests[1].lootTable, chests[1].lootSeed, version, ITEM_RESIN_CLUMP), 6);

    // the same chests through the generic interface
    StructureVariant sv;
    ASSERT_NONZERO(ret, getVariant(&sv, Mansion, version, SEED, chunkX * 16, chunkZ * 16, -1));
    ASSERT_EQ(ret, (int) sv.rotation, 2);
    sv.y = y;
    Piece pieces[8];
    int pieceCount = getStructurePieces(pieces, 8, Mansion, ssconf, &sv, version, SEED, chunkX * 16, chunkZ * 16);
    ASSERT_EQ(ret, pieceCount, 2);
    ASSERT_EQ(ret, strcmp(pieces[0].name, "woodland_mansion/1x2_a1"), 0);
    ASSERT_EQ(ret, pieces[0].chestCount, 1);
    ASSERT_EQ(ret, pieces[0].chestPoses[0].x, -611);
    ASSERT_EQ(ret, pieces[0].chestPoses[0].z, -4452);
    ASSERT_EQ(ret, (int64_t) pieces[0].lootSeeds[0], -8366196242350463770LL);
    ASSERT_EQ(ret, strcmp(pieces[0].lootTables[0], "chests/woodland_mansion"), 0);
    ASSERT_EQ(ret, pieces[1].chestCount, 1);
    ASSERT_EQ(ret, (int64_t) pieces[1].lootSeeds[0], 8588079541716638204LL);

    // a room with two chests is reported as one piece
    const int chunkX2 = 113, chunkZ2 = -225, y2 = 64;
    chestCount = getMansionChests(chests, MANSION_CHESTS_MAX, ssconf, version, SEED, chunkX2, chunkZ2, y2);
    ASSERT_EQ(ret, chestCount, 4);
    ASSERT_EQ(ret, strcmp(chests[2].room, "woodland_mansion/1x2_se1"), 0);
    ASSERT_EQ(ret, strcmp(chests[3].room, "woodland_mansion/1x2_se1"), 0);
    ASSERT_EQ(ret, chests[2].roomIndex, chests[3].roomIndex);
    ASSERT_EQ(ret, (int64_t) chests[3].lootSeed, -2132322225972099079LL);
    ASSERT_EQ(ret, count_item(chests[3].lootTable, chests[3].lootSeed, version, ITEM_VEX_ARMOR_TRIM_SMITHING_TEMPLATE), 1);
    ASSERT_NONZERO(ret, getVariant(&sv, Mansion, version, SEED, chunkX2 * 16, chunkZ2 * 16, -1));
    sv.y = y2;
    pieceCount = getStructurePieces(pieces, 8, Mansion, ssconf, &sv, version, SEED, chunkX2 * 16, chunkZ2 * 16);
    ASSERT_EQ(ret, pieceCount, 3);
    ASSERT_EQ(ret, pieces[2].chestCount, 2);
    ASSERT_EQ(ret, pieces[2].chestPoses[1].x, 1810);
    ASSERT_EQ(ret, pieces[2].chestPoses[1].z, -3570);

    // the loot table is not offered for unverified versions
    ASSERT_EQ(ret, count_item("chests/woodland_mansion", 1, MC_1_21_11, ITEM_VEX_ARMOR_TRIM_SMITHING_TEMPLATE), -1);
    return ret;
}

static int test_mansion_start_26_3() {
    int ret = 0;

    const int version = MC_26_3;
    TerrainNoise *tn = malloc(sizeof(TerrainNoise));
    setupTerrainNoise(tn, version, 0);
    initTerrainNoise(tn, SEED, DIM_OVERWORLD);

    StructureConfig sconf;
    ASSERT_NONZERO(ret, getStructureConfig(Mansion, version, &sconf));

    // region (-1, -4) has a mansion on dry land, so its Y level is exact
    Pos pos;
    ASSERT_NONZERO(ret, getStructurePos(Mansion, version, SEED, -1, -4, &pos));
    ASSERT_EQ(ret, pos.x >> 4, -41);
    ASSERT_EQ(ret, pos.z >> 4, -279);
    MansionStart start;
    ASSERT_NONZERO(ret, getMansionStart(tn, pos.x >> 4, pos.z >> 4, &start));
    ASSERT_EQ(ret, start.pos.x, -649);
    ASSERT_EQ(ret, start.pos.y, 79);
    ASSERT_EQ(ret, start.pos.z, -4457);
    ASSERT_EQ(ret, start.rot, 2);
    ASSERT_EQ(ret, start.minY, 79);

    // region (1, 2) has a mansion with a corner below the sea level, so its Y level is an estimate
    ASSERT_NONZERO(ret, getStructurePos(Mansion, version, SEED, 1, 2, &pos));
    ASSERT_NONZERO(ret, getMansionStart(tn, pos.x >> 4, pos.z >> 4, &start));
    ASSERT_EQ(ret, start.pos.x, 1623);
    ASSERT_EQ(ret, start.pos.y, 63);
    ASSERT_EQ(ret, start.pos.z, 3351);
    ASSERT_EQ(ret, start.minY, 58);

    // region (0, 0) does not have a mansion
    ASSERT_NONZERO(ret, getStructurePos(Mansion, version, SEED, 0, 0, &pos));
    ASSERT_EQ(ret, getMansionStart(tn, pos.x >> 4, pos.z >> 4, &start), 0);

    // the surface is the same from whatever height the search starts
    int lowest;
    int height = sampleWorldSurfaceHeight(tn, -649, -4457, 60, &lowest);
    ASSERT_TRUE(ret, height >= 79);
    ASSERT_EQ(ret, lowest, height);
    ASSERT_EQ(ret, sampleWorldSurfaceHeight(tn, -649, -4457, -64, NULL), height);
    // Nothing is searched below the noise cell of the requested height. A column without terrain
    // is assumed to be flooded up to the sea level, but could be empty down to the bottom of the world.
    ASSERT_EQ(ret, sampleWorldSurfaceHeight(tn, -649, -4457, height + 16, &lowest), 63);
    ASSERT_EQ(ret, lowest, -64);

    free(tn);
    return ret;
}

int main(void) {
    int ret = 0;
    ret |= test_mansion_salt_config();
    ret |= test_mansion_rooms_26_3();
    ret |= test_mansion_chests_26_3();
    ret |= test_mansion_start_26_3();
    return ret;
}
