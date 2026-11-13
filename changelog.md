## Changelog
- Added woodland mansion loot. `/sm:locate loot` now also searches the chests of woodland mansions, and reports the exact position of every matching mansion chest. This makes it possible to locate the vex armor trim (`vex_armor_trim_smithing_template`). Supported for versions 26.1 and later.
- Added `/sm:locate mansionloot <amount> [<radius>] [<item>]`, which searches the woodland mansions around the player for an item (the vex armor trim by default), nearest mansion first.
- Items in `/sm:locate loot` can now also be written with the `minecraft:` namespace.
- Woodland mansions are now located exactly for versions 26.1 and later, on the seed map and with `/sm:locate feature mansion`. Previously the terrain and biome requirements were approximated.
- Fixed a small inaccuracy in the terrain noise for versions 26.1 and later, which could move the surface by one block.
- Added a sinkhole finder (`/sm:find sinkhole`). Sinkholes are formations that occur at very low depth values, where additionally the weirdness climate is extremely high in magnitude. The resulting terrain forms a very large open pit with steep sides. Up until 1.19.2 they would generate with lava pits in the center. After 1.19.2 the lava no longer generates but the large water lakes remain.
- Fixed a bug with displaying ore veins on the seed map.
- Fixed a bug where the seed map would use incorrect biome parameters.

## Mod compatibility
|      | Mod JAR | Biomes | Structures | Loot | Ores | Slime chunks |
|:----:|:-------:|:------:|:----------:|:----:|:----:|:------------:|
| 26.3 |   ✔️    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 26.2 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 26.1 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.21 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.20 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.19 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.18 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.17 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.16 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.15 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.14 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.13 |   ❌    |   ✔️   |     ✔️     |  ✔️  |  ✔️  |      ✔️      |
| 1.12 |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.11 |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.10 |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.9  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.8  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.7  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.6  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.5  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.4  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.3  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.2  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.1  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
| 1.0  |   ❌    |   ✔️   |     ✔️     |  ❌  |  ❌  |      ✔️      |
