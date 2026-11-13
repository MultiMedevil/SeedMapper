#include "mansion.h"

#include <limits.h>
#include <string.h>

/*
 * This file follows the generation of WoodlandMansionPieces step by step. The
 * names used below are the ones the game uses.
 *
 *  1. MansionGrid lays out corridors and rooms on a 11x11 grid for the first two
 *     floors (which share one layout) and for the smaller third floor.
 *  2. MansionPiecePlacer walks over the grids and adds the pieces. Only the room
 *     pieces draw from the random source, so nothing else has to be simulated.
 *  3. During chunk decoration every piece is placed chunk by chunk. A chest that
 *     comes from a "Chest..." data marker takes its loot seed from the decoration
 *     random of the chunk it is in. Containers with fixed contents that are part
 *     of a template advance the same random by one nextLong() each.
 */

// directions use the game's 2D data values, so that clockwise is +1
enum {
    DIR_SOUTH, DIR_WEST, DIR_NORTH, DIR_EAST,
    DIR_UP,
    DIR_NONE = -1,
};

// the iteration order of Direction.Plane.HORIZONTAL
static const int g_horizontal[4] = {DIR_NORTH, DIR_EAST, DIR_SOUTH, DIR_WEST};

static inline int stepX(int dir) { return dir == DIR_EAST ? 1 : dir == DIR_WEST ? -1 : 0; }
static inline int stepZ(int dir) { return dir == DIR_SOUTH ? 1 : dir == DIR_NORTH ? -1 : 0; }
static inline int clockwise(int dir) { return (dir + 1) & 3; }
static inline int counterClockwise(int dir) { return (dir + 3) & 3; }
static inline int opposite(int dir) { return (dir + 2) & 3; }
// Rotation#rotate(Direction) for horizontal directions
static inline int rotateDir(int rot, int dir) { return (dir + rot) & 3; }

enum {
    ROT_NONE, ROT_CW90, ROT_CW180, ROT_CCW90,
};

enum {
    GRID_SIZE = 11,

    CLEAR = 0,
    CORRIDOR = 1,
    ROOM = 2,
    START_ROOM = 3,
    TEST_ROOM = 4,
    BLOCKED = 5,

    ROOM_1x1 = 0x10000,
    ROOM_1x2 = 0x20000,
    ROOM_2x2 = 0x40000,
    ROOM_ORIGIN_FLAG = 0x100000,
    ROOM_DOOR_FLAG = 0x200000,
    ROOM_STAIRS_FLAG = 0x400000,
    ROOM_CORRIDOR_FLAG = 0x800000,
    ROOM_TYPE_MASK = 0xF0000,
    ROOM_ID_MASK = 0xFFFF,
};

typedef struct {
    int grid[GRID_SIZE][GRID_SIZE]; // [x][y]
    int valueIfOutside;
} SimpleGrid;

static void gridInit(SimpleGrid *g, int valueIfOutside) {
    memset(g->grid, 0, sizeof(g->grid));
    g->valueIfOutside = valueIfOutside;
}

static inline void gridSet(SimpleGrid *g, int x, int y, int value) {
    if (x >= 0 && x < GRID_SIZE && y >= 0 && y < GRID_SIZE) {
        g->grid[x][y] = value;
    }
}

static void gridFill(SimpleGrid *g, int minX, int minY, int maxX, int maxY, int value) {
    for (int y = minY; y <= maxY; ++y) {
        for (int x = minX; x <= maxX; ++x) {
            gridSet(g, x, y, value);
        }
    }
}

static inline int gridGet(const SimpleGrid *g, int x, int y) {
    if (x >= 0 && x < GRID_SIZE && y >= 0 && y < GRID_SIZE) {
        return g->grid[x][y];
    }
    return g->valueIfOutside;
}

static inline void gridSetIf(SimpleGrid *g, int x, int y, int oldValue, int newValue) {
    if (gridGet(g, x, y) == oldValue) {
        gridSet(g, x, y, newValue);
    }
}

static inline int gridEdgesTo(const SimpleGrid *g, int x, int y, int value) {
    return gridGet(g, x - 1, y) == value || gridGet(g, x + 1, y) == value
        || gridGet(g, x, y + 1) == value || gridGet(g, x, y - 1) == value;
}

typedef struct {
    uint64_t *rnd;
    SimpleGrid baseGrid;
    SimpleGrid thirdFloorGrid;
    SimpleGrid floorRooms[3];
    int entranceX, entranceY;
} MansionGrid;

static inline int isHouse(const SimpleGrid *g, int x, int y) {
    int v = gridGet(g, x, y);
    return v == CORRIDOR || v == ROOM || v == START_ROOM || v == TEST_ROOM;
}

static inline int isRoomId(const MansionGrid *mg, int x, int y, int floor, int roomId) {
    return (gridGet(&mg->floorRooms[floor], x, y) & ROOM_ID_MASK) == roomId;
}

static int get1x2RoomDirection(const MansionGrid *mg, int x, int y, int floor, int roomId) {
    for (int i = 0; i < 4; ++i) {
        int dir = g_horizontal[i];
        if (isRoomId(mg, x + stepX(dir), y + stepZ(dir), floor, roomId)) {
            return dir;
        }
    }
    return DIR_NONE;
}

static void recursiveCorridor(MansionGrid *mg, SimpleGrid *g, int x, int y, int dir, int depth) {
    if (depth <= 0) {
        return;
    }
    gridSet(g, x, y, CORRIDOR);
    gridSetIf(g, x + stepX(dir), y + stepZ(dir), CLEAR, CORRIDOR);

    for (int i = 0; i < 8; ++i) {
        int next = nextInt(mg->rnd, 4);
        if (next == opposite(dir)) {
            continue;
        }
        if (next == DIR_EAST && nextBoolean(mg->rnd)) {
            continue;
        }
        int nx = x + stepX(dir);
        int ny = y + stepZ(dir);
        if (gridGet(g, nx + stepX(next), ny + stepZ(next)) == CLEAR
            && gridGet(g, nx + stepX(next) * 2, ny + stepZ(next) * 2) == CLEAR) {
            recursiveCorridor(mg, g, x + stepX(dir) + stepX(next), y + stepZ(dir) + stepZ(next), next, depth - 1);
            break;
        }
    }

    int cw = clockwise(dir);
    int ccw = counterClockwise(dir);
    gridSetIf(g, x + stepX(cw), y + stepZ(cw), CLEAR, ROOM);
    gridSetIf(g, x + stepX(ccw), y + stepZ(ccw), CLEAR, ROOM);
    gridSetIf(g, x + stepX(dir) + stepX(cw), y + stepZ(dir) + stepZ(cw), CLEAR, ROOM);
    gridSetIf(g, x + stepX(dir) + stepX(ccw), y + stepZ(dir) + stepZ(ccw), CLEAR, ROOM);
    gridSetIf(g, x + stepX(dir) * 2, y + stepZ(dir) * 2, CLEAR, ROOM);
    gridSetIf(g, x + stepX(cw) * 2, y + stepZ(cw) * 2, CLEAR, ROOM);
    gridSetIf(g, x + stepX(ccw) * 2, y + stepZ(ccw) * 2, CLEAR, ROOM);
}

static int cleanEdges(SimpleGrid *g) {
    int changed = 0;
    for (int y = 0; y < GRID_SIZE; ++y) {
        for (int x = 0; x < GRID_SIZE; ++x) {
            if (gridGet(g, x, y) != CLEAR) {
                continue;
            }
            int neighbours = 0;
            neighbours += isHouse(g, x + 1, y);
            neighbours += isHouse(g, x - 1, y);
            neighbours += isHouse(g, x, y + 1);
            neighbours += isHouse(g, x, y - 1);
            if (neighbours >= 3) {
                gridSet(g, x, y, ROOM);
                changed = 1;
            } else if (neighbours == 2) {
                int diagonals = 0;
                diagonals += isHouse(g, x + 1, y + 1);
                diagonals += isHouse(g, x - 1, y + 1);
                diagonals += isHouse(g, x + 1, y - 1);
                diagonals += isHouse(g, x - 1, y - 1);
                if (diagonals <= 1) {
                    gridSet(g, x, y, ROOM);
                    changed = 1;
                }
            }
        }
    }
    return changed;
}

static void identifyRooms(MansionGrid *mg, const SimpleGrid *g, SimpleGrid *rooms) {
    int cells[GRID_SIZE * GRID_SIZE][2];
    int count = 0;
    for (int y = 0; y < GRID_SIZE; ++y) {
        for (int x = 0; x < GRID_SIZE; ++x) {
            if (gridGet(g, x, y) == ROOM) {
                cells[count][0] = x;
                cells[count][1] = y;
                count++;
            }
        }
    }
    // Util#shuffle
    for (int i = count; i > 1; --i) {
        int j = nextInt(mg->rnd, i);
        int tx = cells[i - 1][0], ty = cells[i - 1][1];
        cells[i - 1][0] = cells[j][0];
        cells[i - 1][1] = cells[j][1];
        cells[j][0] = tx;
        cells[j][1] = ty;
    }

    int roomId = 10;
    for (int c = 0; c < count; ++c) {
        int x = cells[c][0];
        int y = cells[c][1];
        if (gridGet(rooms, x, y) != 0) {
            continue;
        }
        int minX = x, maxX = x;
        int minY = y, maxY = y;
        int type = ROOM_1x1;
        if (gridGet(rooms, x + 1, y) == 0 && gridGet(rooms, x, y + 1) == 0 && gridGet(rooms, x + 1, y + 1) == 0
            && gridGet(g, x + 1, y) == ROOM && gridGet(g, x, y + 1) == ROOM && gridGet(g, x + 1, y + 1) == ROOM) {
            maxX++;
            maxY++;
            type = ROOM_2x2;
        } else if (gridGet(rooms, x - 1, y) == 0 && gridGet(rooms, x, y + 1) == 0 && gridGet(rooms, x - 1, y + 1) == 0
            && gridGet(g, x - 1, y) == ROOM && gridGet(g, x, y + 1) == ROOM && gridGet(g, x - 1, y + 1) == ROOM) {
            minX--;
            maxY++;
            type = ROOM_2x2;
        } else if (gridGet(rooms, x - 1, y) == 0 && gridGet(rooms, x, y - 1) == 0 && gridGet(rooms, x - 1, y - 1) == 0
            && gridGet(g, x - 1, y) == ROOM && gridGet(g, x, y - 1) == ROOM && gridGet(g, x - 1, y - 1) == ROOM) {
            minX--;
            minY--;
            type = ROOM_2x2;
        } else if (gridGet(rooms, x + 1, y) == 0 && gridGet(g, x + 1, y) == ROOM) {
            maxX++;
            type = ROOM_1x2;
        } else if (gridGet(rooms, x, y + 1) == 0 && gridGet(g, x, y + 1) == ROOM) {
            maxY++;
            type = ROOM_1x2;
        } else if (gridGet(rooms, x - 1, y) == 0 && gridGet(g, x - 1, y) == ROOM) {
            minX--;
            type = ROOM_1x2;
        } else if (gridGet(rooms, x, y - 1) == 0 && gridGet(g, x, y - 1) == ROOM) {
            minY--;
            type = ROOM_1x2;
        }

        int doorX = nextBoolean(mg->rnd) ? minX : maxX;
        int doorY = nextBoolean(mg->rnd) ? minY : maxY;
        int doorFlag = ROOM_DOOR_FLAG;
        if (!gridEdgesTo(g, doorX, doorY, CORRIDOR)) {
            doorX = doorX == minX ? maxX : minX;
            doorY = doorY == minY ? maxY : minY;
            if (!gridEdgesTo(g, doorX, doorY, CORRIDOR)) {
                doorY = doorY == minY ? maxY : minY;
                if (!gridEdgesTo(g, doorX, doorY, CORRIDOR)) {
                    doorX = doorX == minX ? maxX : minX;
                    doorY = doorY == minY ? maxY : minY;
                    if (!gridEdgesTo(g, doorX, doorY, CORRIDOR)) {
                        doorFlag = 0;
                        doorX = minX;
                        doorY = minY;
                    }
                }
            }
        }

        for (int cy = minY; cy <= maxY; ++cy) {
            for (int cx = minX; cx <= maxX; ++cx) {
                if (cx == doorX && cy == doorY) {
                    gridSet(rooms, cx, cy, ROOM_ORIGIN_FLAG | doorFlag | type | roomId);
                } else {
                    gridSet(rooms, cx, cy, type | roomId);
                }
            }
        }
        roomId++;
    }
}

static void setupThirdFloor(MansionGrid *mg) {
    int candidates[GRID_SIZE * GRID_SIZE][2];
    int count = 0;
    SimpleGrid *secondFloor = &mg->floorRooms[1];
    for (int y = 0; y < GRID_SIZE; ++y) {
        for (int x = 0; x < GRID_SIZE; ++x) {
            int v = gridGet(secondFloor, x, y);
            if ((v & ROOM_TYPE_MASK) == ROOM_1x2 && (v & ROOM_DOOR_FLAG) == ROOM_DOOR_FLAG) {
                candidates[count][0] = x;
                candidates[count][1] = y;
                count++;
            }
        }
    }
    if (count == 0) {
        gridFill(&mg->thirdFloorGrid, 0, 0, GRID_SIZE, GRID_SIZE, BLOCKED);
        return;
    }

    int pick = nextInt(mg->rnd, count);
    int sx = candidates[pick][0];
    int sy = candidates[pick][1];
    int v = gridGet(secondFloor, sx, sy);
    gridSet(secondFloor, sx, sy, v | ROOM_STAIRS_FLAG);
    int dir = get1x2RoomDirection(mg, sx, sy, 1, v & ROOM_ID_MASK);
    int ox = sx + stepX(dir);
    int oy = sy + stepZ(dir);

    for (int y = 0; y < GRID_SIZE; ++y) {
        for (int x = 0; x < GRID_SIZE; ++x) {
            if (!isHouse(&mg->baseGrid, x, y)) {
                gridSet(&mg->thirdFloorGrid, x, y, BLOCKED);
            } else if (x == sx && y == sy) {
                gridSet(&mg->thirdFloorGrid, x, y, START_ROOM);
            } else if (x == ox && y == oy) {
                gridSet(&mg->thirdFloorGrid, x, y, START_ROOM);
                gridSet(&mg->floorRooms[2], x, y, ROOM_CORRIDOR_FLAG);
            }
        }
    }

    int options[4];
    int optionCount = 0;
    for (int i = 0; i < 4; ++i) {
        int d = g_horizontal[i];
        if (gridGet(&mg->thirdFloorGrid, ox + stepX(d), oy + stepZ(d)) == CLEAR) {
            options[optionCount++] = d;
        }
    }
    if (optionCount == 0) {
        gridFill(&mg->thirdFloorGrid, 0, 0, GRID_SIZE, GRID_SIZE, BLOCKED);
        gridSet(secondFloor, sx, sy, v);
        return;
    }

    int corridorDir = options[nextInt(mg->rnd, optionCount)];
    recursiveCorridor(mg, &mg->thirdFloorGrid, ox + stepX(corridorDir), oy + stepZ(corridorDir), corridorDir, 4);
    while (cleanEdges(&mg->thirdFloorGrid)) {
    }
}

static void mansionGridInit(MansionGrid *mg, uint64_t *rnd) {
    mg->rnd = rnd;
    mg->entranceX = 7;
    mg->entranceY = 4;
    const int ex = mg->entranceX, ey = mg->entranceY;

    SimpleGrid *base = &mg->baseGrid;
    gridInit(base, BLOCKED);
    gridFill(base, ex, ey, ex + 1, ey + 1, START_ROOM);
    gridFill(base, ex - 1, ey, ex - 1, ey + 1, ROOM);
    gridFill(base, ex + 2, ey - 2, ex + 3, ey + 3, BLOCKED);
    gridFill(base, ex + 1, ey - 2, ex + 1, ey - 1, CORRIDOR);
    gridFill(base, ex + 1, ey + 2, ex + 1, ey + 3, CORRIDOR);
    gridSet(base, ex - 1, ey - 1, CORRIDOR);
    gridSet(base, ex - 1, ey + 2, CORRIDOR);
    gridFill(base, 0, 0, GRID_SIZE, 1, BLOCKED);
    gridFill(base, 0, 9, GRID_SIZE, GRID_SIZE, BLOCKED);
    recursiveCorridor(mg, base, ex, ey - 2, DIR_WEST, 6);
    recursiveCorridor(mg, base, ex, ey + 3, DIR_WEST, 6);
    recursiveCorridor(mg, base, ex - 2, ey - 1, DIR_WEST, 3);
    recursiveCorridor(mg, base, ex - 2, ey + 2, DIR_WEST, 3);
    while (cleanEdges(base)) {
    }

    gridInit(&mg->floorRooms[0], BLOCKED);
    gridInit(&mg->floorRooms[1], BLOCKED);
    gridInit(&mg->floorRooms[2], BLOCKED);
    identifyRooms(mg, base, &mg->floorRooms[0]);
    identifyRooms(mg, base, &mg->floorRooms[1]);
    gridFill(&mg->floorRooms[0], ex + 1, ey, ex + 1, ey + 1, ROOM_CORRIDOR_FLAG);
    gridFill(&mg->floorRooms[1], ex + 1, ey, ex + 1, ey + 1, ROOM_CORRIDOR_FLAG);

    gridInit(&mg->thirdFloorGrid, BLOCKED);
    setupThirdFloor(mg);
    identifyRooms(mg, &mg->thirdFloorGrid, &mg->floorRooms[2]);
}

typedef struct {
    MansionRoom *rooms;
    int n;
    int count;
    uint64_t *rnd;
    int floor;
} RoomPlacer;

static inline Pos3 relative(Pos3 pos, int dir, int distance) {
    pos.x += stepX(dir) * distance;
    pos.z += stepZ(dir) * distance;
    return pos;
}

static void addRoom(RoomPlacer *placer, int tmpl, Pos3 pos, int rot, int mirror) {
    if (placer->count < placer->n) {
        MansionRoom *room = &placer->rooms[placer->count];
        room->pos = pos;
        room->tmpl = (uint8_t) tmpl;
        room->floor = (uint8_t) placer->floor;
        room->rot = (uint8_t) (rot & 3);
        room->mirror = (uint8_t) mirror;
    }
    placer->count++;
}

// FirstFloorRoomCollection for the first floor, SecondFloorRoomCollection for the other two.
// Note that old versions of the game only chose between four of the five 1x1_b rooms.
static int get1x1(RoomPlacer *p) {
    return p->floor == 0 ? MANSION_1X1_A1 + nextInt(p->rnd, 5) : MANSION_1X1_B1 + nextInt(p->rnd, 5);
}

static int get1x1Secret(RoomPlacer *p) {
    return MANSION_1X1_AS1 + nextInt(p->rnd, 4);
}

static int get1x2SideEntrance(RoomPlacer *p, int isStairs) {
    if (p->floor == 0) {
        return MANSION_1X2_A1 + nextInt(p->rnd, 9);
    }
    return isStairs ? MANSION_1X2_C_STAIRS : MANSION_1X2_C1 + nextInt(p->rnd, 4);
}

static int get1x2FrontEntrance(RoomPlacer *p, int isStairs) {
    if (p->floor == 0) {
        return MANSION_1X2_B1 + nextInt(p->rnd, 5);
    }
    return isStairs ? MANSION_1X2_D_STAIRS : MANSION_1X2_D1 + nextInt(p->rnd, 5);
}

static int get1x2Secret(RoomPlacer *p) {
    return p->floor == 0 ? MANSION_1X2_S1 + nextInt(p->rnd, 2) : MANSION_1X2_SE1 + nextInt(p->rnd, 1);
}

static int get2x2(RoomPlacer *p) {
    return p->floor == 0 ? MANSION_2X2_A1 + nextInt(p->rnd, 4) : MANSION_2X2_B1 + nextInt(p->rnd, 5);
}

static int get2x2Secret(RoomPlacer *p) {
    (void) p;
    return MANSION_2X2_S1;
}

static void addRoom1x1(RoomPlacer *p, Pos3 pos, int rot, int doorDir) {
    int localRot = ROT_NONE;
    int tmpl = get1x1(p);
    if (doorDir != DIR_EAST) {
        if (doorDir == DIR_NORTH) {
            localRot = ROT_CCW90;
        } else if (doorDir == DIR_WEST) {
            localRot = ROT_CW180;
        } else if (doorDir == DIR_SOUTH) {
            localRot = ROT_CW90;
        } else {
            tmpl = get1x1Secret(p);
        }
    }

    // StructureTemplate.getZeroPositionWithTransform(new BlockPos(1, 0, 0), Mirror.NONE, localRot, 7, 7)
    int ox = 1, oz = 0;
    switch (localRot) {
    case ROT_CCW90: oz += 6; break;
    case ROT_CW90: ox += 6; break;
    case ROT_CW180: ox += 6; oz += 6; break;
    default: break;
    }
    // BlockPos#rotate(rot)
    int rx, rz;
    switch (rot) {
    case ROT_CW90: rx = -oz; rz = ox; break;
    case ROT_CW180: rx = -ox; rz = -oz; break;
    case ROT_CCW90: rx = oz; rz = -ox; break;
    default: rx = ox; rz = oz; break;
    }
    pos.x += rx;
    pos.z += rz;
    addRoom(p, tmpl, pos, localRot + rot, MANSION_MIRROR_NONE);
}

static void addRoom1x2(RoomPlacer *p, Pos3 pos, int rot, int roomDir, int doorDir, int isStairs) {
    const int east = rotateDir(rot, DIR_EAST);
    const int south = rotateDir(rot, DIR_SOUTH);
    const int west = rotateDir(rot, DIR_WEST);
    const int north = rotateDir(rot, DIR_NORTH);

    if (doorDir == DIR_EAST && roomDir == DIR_SOUTH) {
        pos = relative(pos, east, 1);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_EAST && roomDir == DIR_NORTH) {
        pos = relative(pos, east, 1);
        pos = relative(pos, south, 6);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot, MANSION_MIRROR_LEFT_RIGHT);
    } else if (doorDir == DIR_WEST && roomDir == DIR_NORTH) {
        pos = relative(pos, east, 7);
        pos = relative(pos, south, 6);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot + ROT_CW180, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_WEST && roomDir == DIR_SOUTH) {
        pos = relative(pos, east, 7);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot, MANSION_MIRROR_FRONT_BACK);
    } else if (doorDir == DIR_SOUTH && roomDir == DIR_EAST) {
        pos = relative(pos, east, 1);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot + ROT_CW90, MANSION_MIRROR_LEFT_RIGHT);
    } else if (doorDir == DIR_SOUTH && roomDir == DIR_WEST) {
        pos = relative(pos, east, 7);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot + ROT_CW90, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_NORTH && roomDir == DIR_WEST) {
        pos = relative(pos, east, 7);
        pos = relative(pos, south, 6);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot + ROT_CW90, MANSION_MIRROR_FRONT_BACK);
    } else if (doorDir == DIR_NORTH && roomDir == DIR_EAST) {
        pos = relative(pos, east, 1);
        pos = relative(pos, south, 6);
        addRoom(p, get1x2SideEntrance(p, isStairs), pos, rot + ROT_CCW90, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_SOUTH && roomDir == DIR_NORTH) {
        pos = relative(pos, east, 1);
        pos = relative(pos, north, 8);
        addRoom(p, get1x2FrontEntrance(p, isStairs), pos, rot, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_NORTH && roomDir == DIR_SOUTH) {
        pos = relative(pos, east, 7);
        pos = relative(pos, south, 14);
        addRoom(p, get1x2FrontEntrance(p, isStairs), pos, rot + ROT_CW180, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_WEST && roomDir == DIR_EAST) {
        pos = relative(pos, east, 15);
        addRoom(p, get1x2FrontEntrance(p, isStairs), pos, rot + ROT_CW90, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_EAST && roomDir == DIR_WEST) {
        pos = relative(pos, west, 7);
        pos = relative(pos, south, 6);
        addRoom(p, get1x2FrontEntrance(p, isStairs), pos, rot + ROT_CCW90, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_UP && roomDir == DIR_EAST) {
        pos = relative(pos, east, 15);
        addRoom(p, get1x2Secret(p), pos, rot + ROT_CW90, MANSION_MIRROR_NONE);
    } else if (doorDir == DIR_UP && roomDir == DIR_SOUTH) {
        pos = relative(pos, east, 1);
        addRoom(p, get1x2Secret(p), pos, rot, MANSION_MIRROR_NONE);
    }
}

static void addRoom2x2(RoomPlacer *p, Pos3 pos, int rot, int sideDir, int doorDir) {
    int east = 0, south = 0;
    int roomRot = rot;
    int mirror = MANSION_MIRROR_NONE;
    if (doorDir == DIR_EAST && sideDir == DIR_SOUTH) {
        east = -7;
    } else if (doorDir == DIR_EAST && sideDir == DIR_NORTH) {
        east = -7;
        south = 6;
        mirror = MANSION_MIRROR_LEFT_RIGHT;
    } else if (doorDir == DIR_NORTH && sideDir == DIR_EAST) {
        east = 1;
        south = 14;
        roomRot = rot + ROT_CCW90;
    } else if (doorDir == DIR_NORTH && sideDir == DIR_WEST) {
        east = 7;
        south = 14;
        roomRot = rot + ROT_CCW90;
        mirror = MANSION_MIRROR_LEFT_RIGHT;
    } else if (doorDir == DIR_SOUTH && sideDir == DIR_WEST) {
        east = 7;
        south = -8;
        roomRot = rot + ROT_CW90;
    } else if (doorDir == DIR_SOUTH && sideDir == DIR_EAST) {
        east = 1;
        south = -8;
        roomRot = rot + ROT_CW90;
        mirror = MANSION_MIRROR_LEFT_RIGHT;
    } else if (doorDir == DIR_WEST && sideDir == DIR_NORTH) {
        east = 15;
        south = 6;
        roomRot = rot + ROT_CW180;
    } else if (doorDir == DIR_WEST && sideDir == DIR_SOUTH) {
        east = 15;
        mirror = MANSION_MIRROR_FRONT_BACK;
    }
    pos = relative(pos, rotateDir(rot, DIR_EAST), east);
    pos = relative(pos, rotateDir(rot, DIR_SOUTH), south);
    addRoom(p, get2x2(p), pos, roomRot, mirror);
}

static void addRoom2x2Secret(RoomPlacer *p, Pos3 pos, int rot) {
    pos = relative(pos, rotateDir(rot, DIR_EAST), 1);
    addRoom(p, get2x2Secret(p), pos, rot, MANSION_MIRROR_NONE);
}

int getMansionRotation(uint64_t seed, int chunkX, int chunkZ) {
    uint64_t rnd = chunkGenerateRnd(seed, chunkX, chunkZ);
    return nextInt(&rnd, 4);
}

int getMansionStart(TerrainNoise *tn, int chunkX, int chunkZ, MansionStart *start) {
    Generator *g = &tn->g;
    const int mc = g->mc;
    const int rot = getMansionRotation(g->seed, chunkX, chunkZ);
    const int x = chunkX * 16 + 7;
    const int z = chunkZ * 16 + 7;
    start->rot = rot;
    start->pos = (Pos3) {x, 0, z};
    start->minY = 0;
    if (mc <= MC_1_17 || g->dim != DIM_OVERWORLD) {
        return 0;
    }

    // The biome is checked at the Y level of the mansion, which is not known yet. Skip the
    // expensive terrain if no Y level can have a suitable biome.
    const int minHeight = 60;
    int anyViable = 0;
    for (int qy = minHeight >> 2; qy <= 319 >> 2; ++qy) {
        int id = getBiomeAt(g, 4, x >> 2, qy, z >> 2);
        if (id >= 0 && isViableFeatureBiome(mc, Mansion, id)) {
            anyViable = 1;
            break;
        }
    }
    if (!anyViable) {
        return 0;
    }

    // Structure#getLowestYIn5by5BoxOffset7Blocks
    int dx = 5, dz = 5;
    if (rot == ROT_CW90) {
        dx = -5;
    } else if (rot == ROT_CW180) {
        dx = -5;
        dz = -5;
    } else if (rot == ROT_CCW90) {
        dz = -5;
    }
    const int corners[4][2] = {{x, z}, {x, z + dz}, {x + dx, z}, {x + dx, z + dz}};
    int y = INT_MAX, lowest = INT_MAX;
    for (int i = 0; i < 4; ++i) {
        // ChunkGenerator#getFirstOccupiedHeight is the Y level of the highest block, not of the block above it
        int cornerLowest;
        int height = sampleWorldSurfaceHeight(tn, corners[i][0], corners[i][1], minHeight + 1, &cornerLowest) - 1;
        cornerLowest -= 1;
        if (height < y) {
            y = height;
        }
        if (cornerLowest < lowest) {
            lowest = cornerLowest;
        }
    }
    start->pos.y = y;
    start->minY = lowest;
    if (y < minHeight) {
        return 0;
    }
    int id = getBiomeAt(g, 4, x >> 2, y >> 2, z >> 2);
    return id >= 0 && isViableFeatureBiome(mc, Mansion, id);
}

const char *getMansionTemplateName(int tmpl) {
    if (tmpl < 0 || tmpl >= MANSION_ROOM_TEMPLATE_COUNT) {
        return NULL;
    }
    return g_mansion_templates[tmpl].name;
}

int getMansionRooms(MansionRoom *rooms, int n, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY) {
    (void) mc;
    uint64_t rnd = chunkGenerateRnd(seed, chunkX, chunkZ);
    const int rot = nextInt(&rnd, 4);
    const Pos3 origin = {chunkX * 16 + 7, baseY, chunkZ * 16 + 7};

    MansionGrid mg;
    mansionGridInit(&mg, &rnd);

    RoomPlacer placer = {rooms, n, 0, &rnd, 0};
    const int startX = mg.entranceX + 1;
    const int startY = mg.entranceY + 1;

    for (int floor = 0; floor < 3; ++floor) {
        placer.floor = floor;
        Pos3 floorPos = origin;
        floorPos.y += 8 * floor + (floor == 2 ? 3 : 0);
        const SimpleGrid *roomGrid = &mg.floorRooms[floor];
        const SimpleGrid *grid = floor == 2 ? &mg.thirdFloorGrid : &mg.baseGrid;

        for (int y = 0; y < GRID_SIZE; ++y) {
            for (int x = 0; x < GRID_SIZE; ++x) {
                int isStart = floor == 2 && gridGet(grid, x, y) == START_ROOM;
                if (gridGet(grid, x, y) != ROOM && !isStart) {
                    continue;
                }
                int v = gridGet(roomGrid, x, y);
                int type = v & ROOM_TYPE_MASK;
                int roomId = v & ROOM_ID_MASK;

                int doors[4];
                int doorCount = 0;
                if ((v & ROOM_DOOR_FLAG) == ROOM_DOOR_FLAG) {
                    for (int i = 0; i < 4; ++i) {
                        int d = g_horizontal[i];
                        if (gridGet(grid, x + stepX(d), y + stepZ(d)) == CORRIDOR) {
                            doors[doorCount++] = d;
                        }
                    }
                }
                int doorDir = DIR_NONE;
                if (doorCount > 0) {
                    doorDir = doors[nextInt(&rnd, doorCount)];
                } else if ((v & ROOM_ORIGIN_FLAG) == ROOM_ORIGIN_FLAG) {
                    doorDir = DIR_UP;
                }

                Pos3 pos = relative(floorPos, rotateDir(rot, DIR_SOUTH), 8 + (y - startY) * 8);
                pos = relative(pos, rotateDir(rot, DIR_EAST), -1 + (x - startX) * 8);

                if ((v & ROOM_ORIGIN_FLAG) != ROOM_ORIGIN_FLAG) {
                    continue;
                }
                if (type == ROOM_1x1) {
                    addRoom1x1(&placer, pos, rot, doorDir);
                } else if (type == ROOM_1x2 && doorDir != DIR_NONE) {
                    int roomDir = get1x2RoomDirection(&mg, x, y, floor, roomId);
                    int isStairs = (v & ROOM_STAIRS_FLAG) == ROOM_STAIRS_FLAG;
                    addRoom1x2(&placer, pos, rot, roomDir, doorDir, isStairs);
                } else if (type == ROOM_2x2 && doorDir != DIR_NONE && doorDir != DIR_UP) {
                    int sideDir = clockwise(doorDir);
                    if (!isRoomId(&mg, x + stepX(sideDir), y + stepZ(sideDir), floor, roomId)) {
                        sideDir = opposite(sideDir);
                    }
                    addRoom2x2(&placer, pos, rot, sideDir, doorDir);
                } else if (type == ROOM_2x2 && doorDir == DIR_UP) {
                    addRoom2x2Secret(&placer, pos, rot);
                }
            }
        }
    }
    return placer.count < n ? placer.count : n;
}

// StructureTemplate#calculateRelativePosition with the pivot at the origin
static inline Pos3 transformTemplatePos(const MansionRoom *room, MansionTemplatePos local) {
    int x = local.x, z = local.z;
    switch (room->mirror) {
    case MANSION_MIRROR_LEFT_RIGHT: z = -z; break;
    case MANSION_MIRROR_FRONT_BACK: x = -x; break;
    default: break;
    }
    int tx, tz;
    switch (room->rot) {
    case ROT_CCW90: tx = z; tz = -x; break;
    case ROT_CW90: tx = -z; tz = x; break;
    case ROT_CW180: tx = -x; tz = -z; break;
    default: tx = x; tz = z; break;
    }
    return (Pos3) {room->pos.x + tx, room->pos.y + local.y, room->pos.z + tz};
}

typedef struct {
    Pos3 pos;
    int roomIndex;
    int isChest;
} MansionRandomEvent;

int getMansionChests(MansionChest *chests, int n, StructureSaltConfig ssconf, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY) {
    MansionRoom rooms[MANSION_ROOMS_MAX];
    const int roomCount = getMansionRooms(rooms, MANSION_ROOMS_MAX, mc, seed, chunkX, chunkZ, baseY);

    // every draw from the decoration random, in the order in which the pieces are placed
    int eventCount = 0;
    for (int i = 0; i < roomCount; ++i) {
        const MansionTemplate *tmpl = &g_mansion_templates[rooms[i].tmpl];
        eventCount += tmpl->containerCount + tmpl->chestCount;
    }
    if (eventCount == 0) {
        return 0;
    }
    MansionRandomEvent events[eventCount];
    int e = 0;
    for (int i = 0; i < roomCount; ++i) {
        const MansionTemplate *tmpl = &g_mansion_templates[rooms[i].tmpl];
        // StructureTemplate#placeInWorld assigns a (discarded) loot seed to every container of the template
        for (int j = 0; j < tmpl->containerCount; ++j) {
            events[e++] = (MansionRandomEvent) {transformTemplatePos(&rooms[i], tmpl->containers[j]), i, 0};
        }
        // afterwards the data markers are handled
        for (int j = 0; j < tmpl->chestCount; ++j) {
            events[e++] = (MansionRandomEvent) {transformTemplatePos(&rooms[i], tmpl->chests[j]), i, 1};
        }
    }

    const int legacy = mc <= MC_1_17;
    int count = 0;
    for (int i = 0; i < eventCount; ++i) {
        if (!events[i].isChest) {
            continue;
        }
        // Each chunk is decorated with its own random. Replay the draws that happen in the chunk of this chest.
        const int cx = events[i].pos.x >> 4;
        const int cz = events[i].pos.z >> 4;
        uint64_t populationSeed = getPopulationSeed(mc, seed, cx << 4, cz << 4);
        RandomSource rnd = {.type = legacy ? JAVA_RANDOM : XOROSHIRO_J};
        absSetSeed(&rnd, populationSeed + ssconf.decoratorIndex + 10000 * ssconf.generationStep);
        uint64_t lootSeed = 0;
        for (int j = 0; j <= i; ++j) {
            if (events[j].pos.x >> 4 == cx && events[j].pos.z >> 4 == cz) {
                lootSeed = absNextLong(&rnd);
            }
        }
        if (count < n) {
            const MansionRoom *room = &rooms[events[i].roomIndex];
            MansionChest *chest = &chests[count];
            chest->pos = events[i].pos;
            chest->lootSeed = lootSeed;
            chest->lootTable = "chests/woodland_mansion";
            chest->room = g_mansion_templates[room->tmpl].name;
            chest->roomIndex = events[i].roomIndex;
            chest->floor = room->floor;
        }
        count++;
    }
    return count < n ? count : n;
}

int getMansionLoot(Piece *list, int n, StructureSaltConfig ssconf, int mc, uint64_t seed, int chunkX, int chunkZ, int baseY) {
    MansionRoom rooms[MANSION_ROOMS_MAX];
    getMansionRooms(rooms, MANSION_ROOMS_MAX, mc, seed, chunkX, chunkZ, baseY);
    MansionChest chests[MANSION_CHESTS_MAX];
    const int chestCount = getMansionChests(chests, MANSION_CHESTS_MAX, ssconf, mc, seed, chunkX, chunkZ, baseY);

    int count = 0;
    int lastRoom = -1;
    for (int i = 0; i < chestCount; ++i) {
        const MansionChest *chest = &chests[i];
        if (chest->roomIndex != lastRoom) {
            if (count >= n) {
                break;
            }
            lastRoom = chest->roomIndex;
            const MansionRoom *room = &rooms[chest->roomIndex];
            const MansionTemplate *tmpl = &g_mansion_templates[room->tmpl];
            Piece *p = &list[count++];
            memset(p, 0, sizeof(*p));
            p->name = tmpl->name;
            p->pos = room->pos;
            // the bounding box of the template after mirroring and rotating it
            MansionTemplatePos corner = {(int8_t) (tmpl->sx - 1), (int8_t) (tmpl->sy - 1), (int8_t) (tmpl->sz - 1)};
            Pos3 other = transformTemplatePos(room, corner);
            p->bb0 = (Pos3) {room->pos.x < other.x ? room->pos.x : other.x, room->pos.y, room->pos.z < other.z ? room->pos.z : other.z};
            p->bb1 = (Pos3) {room->pos.x > other.x ? room->pos.x : other.x, other.y, room->pos.z > other.z ? room->pos.z : other.z};
            p->rot = room->rot;
            p->depth = (int8_t) room->floor;
            p->type = (int8_t) room->tmpl;
            p->chestCount = 0;
            p->next = NULL;
        }
        Piece *p = &list[count - 1];
        if (p->chestCount < (int) (sizeof(p->chestPoses) / sizeof(*p->chestPoses))) {
            p->chestPoses[p->chestCount] = (Pos) {chest->pos.x, chest->pos.z};
            p->lootTables[p->chestCount] = chest->lootTable;
            p->lootSeeds[p->chestCount] = chest->lootSeed;
            p->chestCount++;
        }
    }
    return count;
}
