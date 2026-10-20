#!/usr/bin/env python3
"""Build Blackwood Manor in world `nightfall` and write plugins/NajursCore/nightfall.yml for Nightfall.java.

Usage: nightfall.py commands        -> build commands (pipe into rcon.py)
       nightfall.py data <file>     -> writes the station / spawn / generator layout

The manor is a 5x5 grid of 15x15 rooms (walls on every 16th block) joined by a random maze of doorways,
with the Great Hall (meeting bell, altar) in the middle and exit gates in the north and south walls.
There is no light inside at all: players only see by the torches they carry (Nightfall's moving light).
A lit waiting lobby floats above at y=120.
"""
import json, random, sys

W = "minecraft:nightfall"
Y = 64            # floor
TOP = 69          # ceiling
N = 5             # grid size
CELL = 16
X0 = -40          # manor spans -40..40 on x and z
HALL = (2, 2)
GENERATOR_CELLS = [(0, 0), (4, 0), (0, 4), (4, 4), (2, 1)]
FUSE_CELL = (2, 3)

# name, floor, wall, station block, task text, props
THEMES = [
    ("Kitchen", "spruce_planks", "stripped_spruce_log", "cauldron", "Stir the soup", "kitchen"),
    ("Library", "dark_oak_planks", "bookshelf", "lectern", "Read the cursed tome", "library"),
    ("Laboratory", "polished_andesite", "smooth_stone", "brewing_stand", "Mix the antidote", "lab"),
    ("Crypt", "deepslate_tiles", "polished_blackstone_bricks", "soul_campfire[lit=false]", "Light the crypt fire", "crypt"),
    ("Greenhouse", "moss_block", "mossy_cobblestone", "composter", "Feed the plants", "greenhouse"),
    ("Chapel", "polished_deepslate", "stone_bricks", "enchanting_table", "Pray at the altar", "chapel"),
    ("Nursery", "white_wool", "birch_planks", "jukebox", "Wind the music box", "nursery"),
    ("Dining Room", "dark_oak_planks", "stripped_dark_oak_log", "barrel[facing=up]", "Set the table", "dining"),
    ("Study", "spruce_planks", "spruce_planks", "cartography_table", "Sort the old maps", "study"),
    ("Storage", "oak_planks", "stripped_oak_log", "chest[facing=south]", "Search the crates", "storage"),
    ("Bedroom", "red_wool", "dark_oak_planks", "loom", "Mend the curtains", "bedroom"),
    ("Armory", "cobblestone", "stone_bricks", "grindstone[face=floor]", "Sharpen the blades", "armory"),
    ("Bathroom", "calcite", "smooth_quartz", "water_cauldron[level=3]", "Drain the bath", "bathroom"),
    ("Cellar", "packed_mud", "mud_bricks", "barrel[facing=up]", "Tap the old barrel", "cellar"),
]
GEN = ("Generator Room", "smooth_stone", "iron_block", "blast_furnace[facing=south]", "Refuel the generator", "generator")
FUSE = ("Fuse Room", "gray_concrete", "polished_tuff", "crafter", "Fix the wiring", "fuse")

random.seed(1313)
cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")

def cell_origin(i, j): return X0 + i * CELL, X0 + j * CELL
def compass(i, j):
    v = "North" if j == 0 else "South" if j == N - 1 else ""
    h = "West" if i == 0 else "East" if i == N - 1 else ""
    return (v + h) or "Central"

# ---------- maze of doorways ----------
doors = set()  # frozenset of two cells
seen = {HALL}; stack = [HALL]
while stack:
    i, j = stack[-1]
    nb = [(i + di, j + dj) for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)) if 0 <= i + di < N and 0 <= j + dj < N and (i + di, j + dj) not in seen]
    if not nb: stack.pop(); continue
    n = random.choice(nb); doors.add(frozenset([(i, j), n])); seen.add(n); stack.append(n)
for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)): doors.add(frozenset([HALL, (2 + di, 2 + dj)]))   # the hall opens every way
extra = 0
while extra < 9:  # loops so nobody gets cornered in a dead end
    i, j = random.randrange(N), random.randrange(N); di, dj = random.choice(((1, 0), (0, 1)))
    if i + di < N and j + dj < N and frozenset([(i, j), (i + di, j + dj)]) not in doors:
        doors.add(frozenset([(i, j), (i + di, j + dj)])); extra += 1

# ---------- shell ----------
c(f"forceload add {X0 - 16} {X0 - 16} {-X0 + 16} {-X0 + 16}")
for y in reversed(range(Y - 2, TOP + 2)): fill(X0 - 12, y, X0 - 12, -X0 + 12, y, -X0 + 12, "air")
for y in reversed(range(112, 132)): fill(-12, y, -12, 12, y, 12, "air")
c("minecraft:kill @e[type=text_display,tag=nf]")
for g in ("advance_time false", "advance_weather false", "spawn_mobs false", "keep_inventory true", "show_death_messages false",
          "fall_damage false", "mob_griefing false", "block_drops false", "immediate_respawn true", "natural_health_regeneration true"):
    c(f"gamerule {g}")
c("minecraft:time set 18000")

fill(X0, Y - 1, X0, -X0, Y - 1, -X0, "deepslate")
fill(X0, TOP, X0, -X0, TOP, -X0, "dark_oak_planks")
fill(X0, TOP + 1, X0, -X0, TOP + 1, -X0, "black_concrete")   # blocks the (dim) night sky completely

rooms, stations, generators, candles = [], [], [], []
occupied = set()
theme_cycle = list(THEMES); random.shuffle(theme_cycle)
used_names = {}

def free(x, z): return (x, z) not in occupied
def put(x, y, z, b):
    setb(x, y, z, b); occupied.add((x, z))

def props(kind, x0, z0, rnd):
    """Furniture along the walls, kept off the doorway middles (offsets 6..10)."""
    edge = [(x0 + a, z0 + 1) for a in list(range(1, 6)) + list(range(11, 16))] + [(x0 + a, z0 + 15) for a in list(range(1, 6)) + list(range(11, 16))] \
         + [(x0 + 1, z0 + a) for a in list(range(2, 6)) + list(range(11, 15))] + [(x0 + 15, z0 + a) for a in list(range(2, 6)) + list(range(11, 15))]
    rnd.shuffle(edge)
    pick = {
        "kitchen": ["smoker[facing=south]", "barrel[facing=up]", "furnace[facing=south]", "spruce_trapdoor[half=top]", "cauldron"],
        "library": ["bookshelf", "bookshelf", "chiseled_bookshelf[facing=south]", "bookshelf"],
        "lab": ["brewing_stand", "cauldron", "glass", "iron_block", "smithing_table"],
        "crypt": ["skeleton_skull[rotation=4]", "cobweb", "polished_blackstone_brick_slab", "soul_soil", "bone_block"],
        "greenhouse": ["potted_dead_bush", "flowering_azalea_leaves[persistent=true]", "moss_carpet", "big_dripleaf", "potted_red_mushroom"],
        "chapel": ["dark_oak_stairs[facing=north]", "dark_oak_stairs[facing=north]", "candle", "cobweb"],
        "nursery": ["carved_pumpkin[facing=south]", "white_carpet", "birch_stairs[facing=south]", "cobweb"],
        "dining": ["dark_oak_stairs[facing=east]", "dark_oak_stairs[facing=west]", "flower_pot"],
        "study": ["spruce_stairs[facing=south]", "bookshelf", "spruce_trapdoor[half=top]"],
        "storage": ["barrel[facing=up]", "chest[facing=north]", "oak_log", "barrel[facing=north]", "scaffolding"],
        "bedroom": ["red_wool", "dark_oak_trapdoor[half=top]", "chest[facing=south]", "flower_pot"],
        "armory": ["anvil", "chipped_anvil", "armor_stand", "smithing_table", "grindstone[face=floor]"],
        "bathroom": ["water_cauldron[level=3]", "smooth_quartz_slab", "cauldron"],
        "cellar": ["barrel[facing=north]", "barrel[facing=up]", "cobweb", "spruce_slab"],
        "generator": ["iron_bars", "lightning_rod", "chain", "copper_bulb"],
        "fuse": ["observer[facing=up]", "redstone_lamp", "iron_bars", "lever[face=floor]"],
    }[kind]
    for (x, z) in edge[:14]:
        b = rnd.choice(pick)
        if b == "armor_stand": c(f'summon armor_stand {x + 0.5} {Y + 1} {z + 0.5} {{Invulnerable:1b,NoGravity:1b,Tags:["nf"]}}'); occupied.add((x, z)); continue
        if b == "chain": b = "iron_chain"
        put(x, Y + 1, z, b)
        if b in ("bookshelf",) and rnd.random() < 0.6: setb(x, Y + 2, z, "bookshelf")
    # cobwebs in the ceiling corners for everyone
    for (x, z) in ((x0 + 1, z0 + 1), (x0 + 15, z0 + 15), (x0 + 15, z0 + 1), (x0 + 1, z0 + 15)):
        if rnd.random() < 0.6: setb(x, TOP - 1, z, "cobweb")
    if kind == "dining" or kind == "study":  # a table in the middle
        fill(x0 + 5, Y + 1, z0 + 7, x0 + 11, Y + 1, z0 + 9, "dark_oak_fence"); fill(x0 + 5, Y + 2, z0 + 7, x0 + 11, Y + 2, z0 + 9, "dark_oak_pressure_plate")
        for a in range(5, 12): occupied.update({(x0 + a, z0 + 7), (x0 + a, z0 + 8), (x0 + a, z0 + 9)})
    if kind == "crypt":  # coffins
        for a in (4, 12):
            fill(x0 + a, Y + 1, z0 + 6, x0 + a, Y + 1, z0 + 10, "dark_oak_slab"); occupied.update({(x0 + a, z0 + b) for b in range(6, 11)})
    if kind == "greenhouse":
        for a in range(3, 14, 3): put(x0 + a, Y + 1, z0 + 12, "rooted_dirt"); setb(x0 + a, Y + 2, z0 + 12, "dead_bush")
    if kind == "chapel":
        for row in (6, 9, 12):
            fill(x0 + 3, Y + 1, z0 + row, x0 + 6, Y + 1, z0 + row, "dark_oak_stairs[facing=north]"); fill(x0 + 10, Y + 1, z0 + row, x0 + 13, Y + 1, z0 + row, "dark_oak_stairs[facing=north]")
            occupied.update({(x0 + a, z0 + row) for a in list(range(3, 7)) + list(range(10, 14))})

for i in range(N):
    for j in range(N):
        x0, z0 = cell_origin(i, j)
        rnd = random.Random(i * 31 + j)
        if (i, j) == HALL: theme = ("Great Hall", "polished_blackstone_bricks", "deepslate_bricks", None, None, "hall")
        elif (i, j) in GENERATOR_CELLS: theme = GEN
        elif (i, j) == FUSE_CELL: theme = FUSE
        else: theme = theme_cycle.pop() if theme_cycle else rnd.choice(THEMES)
        base, floor, wall, station, task, kind = theme
        name = f"{compass(i, j)} {base}" if base in used_names or kind == "generator" else base
        used_names[base] = True
        fill(x0, Y, z0, x0 + CELL, Y, z0 + CELL, floor)
        for y in range(Y + 1, TOP):
            fill(x0, y, z0, x0 + CELL, y, z0, wall); fill(x0, y, z0 + CELL, x0 + CELL, y, z0 + CELL, wall)
            fill(x0, y, z0, x0, y, z0 + CELL, wall); fill(x0 + CELL, y, z0, x0 + CELL, y, z0 + CELL, wall)
        if kind == "greenhouse": fill(x0 + 1, TOP, z0 + 1, x0 + CELL - 1, TOP, z0 + CELL - 1, "tinted_glass")
        rooms.append(dict(name=name, kind=kind, x1=x0 + 1, z1=z0 + 1, x2=x0 + CELL - 1, z2=z0 + CELL - 1))
        if kind == "hall":
            # round table with the meeting bell, seats around it
            for dx in range(-2, 3):
                for dz in range(-2, 3):
                    if dx * dx + dz * dz <= 5: setb(dx, Y + 1, dz, "stripped_dark_oak_wood"); occupied.add((dx, dz))
            setb(0, Y + 2, 0, "bell[attachment=floor,facing=north]")
            for dx, dz in ((-1, -1), (1, 1), (1, -1), (-1, 1)): setb(dx, Y + 2, dz, "candle[candles=3]")
            fill(-7, Y, -7, 7, Y, 7, "red_carpet", "keep")
            continue
        sx, sz = x0 + 8, z0 + 4
        if kind == "generator":
            put(sx, Y + 1, sz, station); setb(sx, Y + 2, sz, "lightning_rod")
            fill(x0 + 4, TOP, z0 + 4, x0 + 12, TOP, z0 + 12, "redstone_lamp", "replace dark_oak_planks")  # lit when the generator runs
            generators.append(dict(x=sx, y=Y + 1, z=sz, room=name, lamps=[x0 + 4, z0 + 4, x0 + 12, z0 + 12]))
        else:
            put(sx, Y + 1, sz, station)
        stations.append(dict(id=f"s{len(stations)}", x=sx, y=Y + 1, z=sz, room=name, task=task, kind=kind))
        props(kind, x0, z0, rnd)
        free_spots = [(x, z) for x in range(x0 + 2, x0 + 15) for z in range(z0 + 2, z0 + 15) if free(x, z) and not (x0 + 6 <= x <= x0 + 10 and z0 + 6 <= z <= z0 + 10)]
        for (x, z) in rnd.sample(free_spots, 3): candles.append([x + 0.5, Y + 1.2, z + 0.5])

# doorways: carve after the walls, 3 wide and 3 high, with a dark frame
for d in doors:
    (a, b) = sorted(d)
    x0, z0 = cell_origin(*b)
    if a[0] != b[0]:   # neighbours on x: door in b's west wall
        fill(x0, Y + 1, z0 + 7, x0, Y + 3, z0 + 9, "air"); fill(x0, Y + 4, z0 + 7, x0, Y + 4, z0 + 9, "dark_oak_planks")
    else:              # neighbours on z: door in b's north wall
        fill(x0 + 7, Y + 1, z0, x0 + 9, Y + 3, z0, "air"); fill(x0 + 7, Y + 4, z0, x0 + 9, Y + 4, z0, "dark_oak_planks")

# exit gates (iron bars, removed when the generators are done) and lit courtyards beyond
GATES = [dict(x1=-1, y1=Y + 1, z1=X0, x2=1, y2=Y + 3, z2=X0, zone=[-6, X0 - 9, 6, X0 - 1]),
         dict(x1=-1, y1=Y + 1, z1=-X0, x2=1, y2=Y + 3, z2=-X0, zone=[-6, -X0 + 1, 6, -X0 + 9])]
for g in GATES:
    fill(g["x1"], g["y1"], g["z1"], g["x2"], g["y2"], g["z2"], "iron_bars")
    zx1, zz1, zx2, zz2 = g["zone"]
    fill(zx1, Y, zz1, zx2, Y, zz2, "gravel"); fill(zx1, Y - 1, zz1, zx2, Y - 1, zz2, "stone")
    far = zz1 - 1 if zz1 < 0 else zz2 + 1          # the side away from the manor wall
    fill(zx1 - 1, Y + 1, zz1 - 1, zx1 - 1, Y + 4, zz2 + 1, "barrier"); fill(zx2 + 1, Y + 1, zz1 - 1, zx2 + 1, Y + 4, zz2 + 1, "barrier")
    fill(zx1 - 1, Y + 1, far, zx2 + 1, Y + 4, far, "barrier"); fill(zx1, Y + 4, zz1, zx2, Y + 4, zz2, "barrier")
    for x in (zx1, zx2): setb(x, Y + 1, (zz1 + zz2) // 2, "soul_lantern")
    c(f'summon text_display 0.5 {Y + 3.5} {(zz1 + zz2) / 2 + 0.5} {{Tags:["nf"],billboard:"center",text:{{text:"EXIT",color:"green",bold:1b}},transformation:{{scale:[2f,2f,2f],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}}}')

# ---------- waiting lobby above the manor ----------
L = 120
fill(-8, L - 1, -8, 8, L - 1, 8, "polished_blackstone_bricks")
fill(-8, L, -8, 8, L + 6, 8, "deepslate_bricks", "hollow"); fill(-7, L, -7, 7, L + 5, 7, "air")
fill(-7, L - 1, -7, 7, L - 1, 7, "polished_blackstone"); fill(-3, L - 1, -3, 3, L - 1, 3, "crying_obsidian")
for x, z in ((-7, -7), (7, -7), (-7, 7), (7, 7), (0, -7), (0, 7), (-7, 0), (7, 0)): setb(x, L, z, "soul_lantern")
for x in (-6, 6):
    for z in (-4, 4): setb(x, L + 5, z, "shroomlight")
fill(-8, L + 2, -3, -8, L + 3, 3, "tinted_glass"); fill(8, L + 2, -3, 8, L + 3, 3, "tinted_glass")
c(f'summon text_display 0.5 {L + 3.6} -6.4 {{Tags:["nf"],billboard:"fixed",text:{{text:"NIGHTFALL",color:"dark_red",bold:1b}},'
  f'transformation:{{scale:[3f,3f,3f],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}}}')
c(f'summon text_display 0.5 {L + 1.4} -6.4 {{Tags:["nf","nf_board"],billboard:"fixed",background:1610612736,line_width:260,text:{{text:"Waiting for players...",color:"gray"}}}}')
c(f"setworldspawn 0 {L} 4")

c(f"forceload remove {X0 - 16} {X0 - 16} {-X0 + 16} {-X0 + 16}")

def data(path):
    out = dict(lobby=[0.5, L, 4.5], hall=[0.5, Y + 1, 0.5],
               spawns=[[round(4.5 * __import__("math").cos(a / 8 * 6.283), 2) + 0.5, Y + 1, round(4.5 * __import__("math").sin(a / 8 * 6.283), 2) + 0.5] for a in range(8)],
               bell=[0, Y + 2, 0], rooms=rooms, stations=stations, generators=generators, candles=candles,
               gates=GATES, bounds=[X0, X0, -X0, -X0])
    open(path, "w").write(json.dumps(out, indent=1))   # JSON is valid YAML

if __name__ == "__main__":
    if sys.argv[1] == "commands": print("\n".join(cmds))
    else: data(sys.argv[2])
