#!/usr/bin/env python3
"""Print the commands that build the floating-island lobby hub (pipe into rcon.py).

Layout (top view, centre 0,64,0, spawn faces the fountain from the south):
  beacon-and-crown fountain plaza, six game stations on a ring at radius 20 joined by lit gated paths,
  themed scenery + a tinted beacon behind each station, waterfalls east/west, floating islands around.
Keeps: walking surface y=64, station centres/NPC spots (pad top y65), the parkour lane (|x|<=14, z<-30).
"""
import math, random

random.seed(26_3)
W = "minecraft:overworld"  # the lobby is the server's main world
CY = 64                    # walking surface
R = 30                     # island radius
LIM = 28.5                 # scenery stays inside this radius (the wall sits at 29.5)

STATIONS = [  # (name, angle in degrees, pad block, accent block, glass colour)
    ("survival", 270, "lime_concrete", "moss_block", "lime"),
    ("skyblock", 330, "light_blue_concrete", "prismarine_bricks", "light_blue"),
    ("oneblock", 30, "yellow_concrete", "honeycomb_block", "yellow"),
    ("bedwars", 90, "red_concrete", "red_nether_bricks", "red"),
    ("hideseek", 150, "purple_concrete", "purpur_block", "purple"),
    ("arena", 210, "orange_concrete", "cut_copper", "orange"),
]

cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")

pend = {}
def put(x, y, z, b, lim=LIM):
    if math.hypot(x, z) <= lim: pend[(x, y, z)] = b
def flush():
    """Supports first, attached blocks (lanterns, beds, rods, carpets...) after them."""
    late = ("lantern", "banner", "bed", "end_rod", "wheat", "campfire", "cluster", "torch")
    for (x, y, z), b in sorted(pend.items(), key=lambda kv: (any(k in kv[1] for k in late), kv[0][1], kv[0][0], kv[0][2])):
        setb(x, y, z, b)
    pend.clear()

def disc(y, r, block, cx=0, cz=0, rin=-1, mode=""):
    """Filled disc (or ring when rin >= 0) as one fill per row."""
    for dz in range(-int(r), int(r) + 1):
        half = math.sqrt(max(r * r - dz * dz, 0))
        if rin < 0 or abs(dz) > rin:
            fill(cx - int(half), y, cz + dz, cx + int(half), y, cz + dz, block, mode)
        else:  # ring: two segments either side of the hole
            hin = math.sqrt(rin * rin - dz * dz)
            fill(cx - int(half), y, cz + dz, cx - int(hin) - 1, y, cz + dz, block, mode)
            fill(cx + int(hin) + 1, y, cz + dz, cx + int(half), y, cz + dz, block, mode)

def polar(r, deg):
    a = math.radians(deg)
    return round(r * math.cos(a)), round(r * math.sin(a))

def arc(r, d1, d2, fn, step=1.0):
    d = d1
    while d <= d2:
        fn(*polar(r, d), d); d += step

def nearest(deg):
    return min(STATIONS, key=lambda s: min(abs(deg - s[1]), 360 - abs(deg - s[1])))

SC = {n: polar(20, deg) for n, deg, *_ in STATIONS}
rock = ["stone", "andesite", "tuff", "stone", "cobblestone", "diorite"]

# ---------- clear ----------
c("forceload add -48 -48 48 48")
for y in reversed(range(30, 91, 3)):  # top-down so attached blocks never pop off as items; slabs stay under the 32768-block fill cap
    fill(-48, y, -30, 48, y + 2, 48, "air")
    fill(-48, y, -48, -15, y + 2, -31, "air")  # the parkour lane (|x|<=14, z<-30) is left alone
    fill(15, y, -48, 48, y + 2, -31, "air")

# ---------- underside: rocky cone, ores, glow ----------
for i, y in enumerate(range(CY - 1, CY - 31, -1)):
    t = i / 30
    r = (R + 1) * (1 - t) ** 0.7 + random.uniform(-1.2, 1.2)
    if r < 1: break
    disc(y, r, "dirt" if i < 3 else random.choice(rock))
def under(n, blocks):
    for _ in range(n):
        y = random.randint(CY - 22, CY - 4)
        r = (R + 1) * (1 - (CY - 1 - y) / 30) ** 0.7 - 0.5
        x, z = polar(r, random.uniform(0, 360))
        setb(x, y, z, random.choice(blocks))
under(30, ["coal_ore", "iron_ore", "copper_ore", "gold_ore", "amethyst_block", "diamond_ore"])
under(26, ["shroomlight", "sea_lantern", "glowstone", "budding_amethyst"])  # glows when seen from below at night

# ---------- top surface ----------
disc(CY, R, "grass_block")
disc(CY, 11, "polished_andesite")
disc(CY, 11, "polished_deepslate", rin=9.5)

# plaza mosaic: station-coloured terrazzo ring, quartz rings, star spokes
for d in range(0, 360):
    for r in (5.3, 5.9, 6.5):
        x, z = polar(r, d); put(x, CY, z, nearest(d)[2])
    for r in (8.3, 8.8):
        x, z = polar(r, d); put(x, CY, z, "smooth_quartz")
for d in range(0, 360, 60):
    for r in range(5, 9):
        x, z = polar(r, d); put(x, CY, z, "chiseled_quartz_block")
flush()

# paths: mixed brick with a dark stripe, gate arch and lamp posts
pal = ["stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks"]
for name, deg, *_ in STATIONS:
    a = math.radians(deg)
    def at(r, w): return round(r * math.cos(a) - w * math.sin(a)), round(r * math.sin(a) + w * math.cos(a))
    for r in range(11, 18):
        for w in (-1, 0, 1):
            x, z = at(r, w)
            put(x, CY, z, "polished_blackstone_bricks" if w == 0 and r % 2 else random.choice(pal))
    for w in (-2, 2):
        x, z = at(14, w)
        for y in range(CY + 1, CY + 5): put(x, y, z, "deepslate_bricks")
        put(x, CY + 5, z, "chiseled_deepslate")
    for w in (-1, 0, 1):
        x, z = at(14, w); put(x, CY + 5, z, "polished_deepslate")
    x, z = at(14, 0); put(x, CY + 4, z, "lantern[hanging=true]")
    for r in (12, 16):
        for w in (-2, 2):
            x, z = at(r, w)
            put(x, CY + 1, z, "polished_blackstone_brick_wall"); put(x, CY + 2, z, "polished_blackstone_brick_wall")
            put(x, CY + 3, z, "lantern")

# perimeter wall: crenellated, lantern posts, gaps for parkour (north) and the two waterfalls
for deg in range(0, 360, 2):
    if 255 <= deg <= 285 or deg <= 9 or deg >= 351 or 171 <= deg <= 189: continue
    x, z = polar(R - 0.5, deg)
    put(x, CY + 1, z, "stone_brick_wall" if deg % 4 else "stone_bricks", 99)
for deg in range(0, 360, 12):
    if 250 <= deg <= 290 or deg <= 12 or deg >= 348 or 168 <= deg <= 192: continue
    x, z = polar(R - 0.5, deg)
    put(x, CY + 1, z, "stone_bricks", 99); put(x, CY + 2, z, "stone_brick_wall", 99); put(x, CY + 3, z, "lantern", 99)
# parkour exit gate
for sx in (-1, 1):
    for y in range(CY + 1, CY + 7): put(sx * 9, y, -28, "deepslate_bricks", 99)
    put(sx * 9, CY + 7, -28, "sea_lantern", 99)
for x in range(-9, 10): put(x, CY + 6, -28, "polished_deepslate", 99)
for x in (-6, -3, 0, 3, 6): put(x, CY + 5, -28, "lantern[hanging=true]", 99)
flush()

# ---------- centrepiece: fountain, beacon spire, crown ----------
disc(CY, 4, "water")
disc(CY - 1, 4, "sea_lantern")
disc(CY + 1, 4.7, "smooth_quartz_slab", rin=3.7)
fill(0, CY, 0, 0, CY + 5, 0, "quartz_pillar")
fill(-2, CY + 4, -2, 2, CY + 4, 2, "smooth_quartz")            # cascade tier
fill(-1, CY + 5, -1, 1, CY + 5, 1, "chiseled_quartz_block")
fill(-1, CY + 6, -1, 1, CY + 6, 1, "gold_block")               # beacon pyramid
setb(0, CY + 7, 0, "beacon")
setb(0, CY + 8, 0, "white_stained_glass")
for dx, dz in ((2, 0), (-2, 0), (0, 2), (0, -2), (2, 2), (-2, 2), (2, -2), (-2, -2)):
    setb(dx, CY + 5, dz, "water")                                # cascade pours off the tier into the basin
for dx, dz in ((3, 0), (-3, 0), (0, 3), (0, -3)):
    setb(dx, CY + 1, dz, "sea_lantern")
# six crown pillars between the paths, linked by a ring of station-coloured glass
for d in range(0, 360):
    x, z = polar(7, d); put(x, CY + 8, z, f"{nearest(d)[4]}_stained_glass")
for d in range(0, 360, 60):
    x, z = polar(7, d)
    for y in range(CY + 1, CY + 8): put(x, y, z, "quartz_pillar")
    put(x, CY + 8, z, "chiseled_quartz_block"); put(x, CY + 9, z, "end_rod")
flush()

# ---------- game stations ----------
for name, deg, pad, accent, col in STATIONS:
    x, z = SC[name]
    fill(x - 3, CY, z - 3, x + 3, CY, z + 3, accent)
    fill(x - 2, CY + 1, z - 2, x + 2, CY + 1, z + 2, pad)
    for ox, oz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
        fill(x + ox, CY + 1, z + oz, x + ox, CY + 3, z + oz, "polished_blackstone_wall")
        setb(x + ox, CY + 4, z + oz, "lantern")
    setb(x, CY, z, "glowstone")
    # beacon behind the station, beam tinted in the game colour
    bx, bz = (0, -25) if name == "survival" else polar(26, deg)
    fill(bx - 1, CY, bz - 1, bx + 1, CY, bz + 1, "iron_block")
    setb(bx, CY + 1, bz, "beacon"); setb(bx, CY + 2, bz, f"{col}_stained_glass")
    for ox, oz in ((-1, -1), (1, -1), (-1, 1), (1, 1)): setb(bx + ox, CY + 1, bz + oz, "sea_lantern")

# --- survival: trees, fenced farm patch, camp
for tx, tz, tree in ((-8, -22, "oak"), (9, -22, "birch"), (-12, -23, "spruce"), (13, -21, "fancy_oak")):
    c(f"place feature minecraft:{tree} {tx} {CY + 1} {tz}")
for fx in range(-10, -5):
    for fz in (-16, -15, -14):
        if (fx, fz) == (-8, -15): put(fx, CY, fz, "water")
        else: put(fx, CY, fz, "farmland"); put(fx, CY + 1, fz, "wheat[age=7]")
for fx in range(-11, -4):
    for fz in (-17, -13): put(fx, CY + 1, fz, "oak_fence")
put(6, CY + 1, -16, "campfire[lit=true]")
for hx, hz in ((8, -16), (8, -15), (6, -18)): put(hx, CY + 1, hz, "hay_block")
put(4, CY + 1, -17, "barrel[facing=up]"); put(4, CY + 1, -15, "crafting_table"); put(4, CY + 1, -16, "furnace[facing=east]")
put(9, CY + 1, -17, "pumpkin"); put(9, CY + 1, -14, "melon")
flush()

# --- skyblock: three floating mini-islands (built with the others below)
# --- oneblock: a giant hovering grass block with a glow underneath
fill(23, CY + 2, 5, 27, CY + 6, 9, "grass_block")
setb(25, CY + 1, 7, "sea_lantern")
for dx, dz in ((22, 4), (22, 10), (27, 11), (27, 3)):
    put(dx, CY + 1, dz, "dandelion")

# --- hide & seek: two broken hedge arcs with openings, barrels and lanterns to hide in
def hedge(x, z, d):
    for y in (CY + 1, CY + 2):
        put(x, y, z, "azalea_leaves[persistent=true]" if (x + z) % 5 else "flowering_azalea_leaves[persistent=true]")
for rr, spans in ((24.5, [(122, 140), (144, 147), (153, 156), (160, 178)]), (27.0, [(124, 144), (156, 176)])):
    for a, b in spans: arc(rr, a, b, hedge)
for d in (121, 141, 157, 179):
    x, z = polar(25.8, d)
    for y in range(CY + 1, CY + 4): put(x, y, z, "dark_oak_fence")
    put(x, CY + 4, z, "lantern")
for d in (131, 168):
    x, z = polar(22.5, d); put(x, CY + 1, z, "barrel[facing=up]")
x, z = polar(23.2, 140.0); put(x, CY + 1, z, "hay_block")
flush()

# --- bedwars: four team bases (bed under a glass-roofed obsidian bunker) + generators flanking the spawn
for (bx, bz), colr in zip(((-9, 22), (9, 22), (-12, 16), (12, 16)), ("red", "blue", "lime", "yellow")):
    for dx in range(-2, 3):
        for dz in range(-2, 3): put(bx + dx, CY, bz + dz, f"{colr}_wool")
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1, 2):
            put(bx + dx, CY + 1, bz + dz, "obsidian"); put(bx + dx, CY + 2, bz + dz, f"{colr}_stained_glass")
    put(bx, CY + 1, bz, f"{colr}_bed[facing=north,part=head]")      # head = foot + facing
    put(bx, CY + 1, bz + 1, f"{colr}_bed[facing=north,part=foot]")
    for ox, oz in ((-2, -2), (2, -2), (-2, 2), (2, 2)): put(bx + ox, CY + 1, bz + oz, "sea_lantern")
for k in (-1, 1):
    put(k * 5, CY, 26, "polished_blackstone"); put(k * 5, CY + 1, 26, "emerald_block" if k > 0 else "diamond_block")
    put(k * 5, CY + 2, 26, "glass"); put(k * 5, CY + 3, 26, "end_rod")
flush()

# --- arena: crenellated colosseum arcs with banners, forge corner, practice targets
def colos(x, z, d):
    put(x, CY + 1, z, "cut_copper"); put(x, CY + 2, z, "stone_bricks")
    put(x, CY + 3, z, "stone_brick_wall" if int(d) % 3 else "stone_bricks")
    if int(d) % 6 == 0: put(x, CY + 4, z, "orange_banner[rotation=%d]" % random.choice((4, 12)))
for a, b in ((172, 196), (224, 248)): arc(27.5, a, b, colos)
for d in (172, 196, 224, 248):
    x, z = polar(27.5, d)
    for y in range(CY + 1, CY + 6): put(x, y, z, "chiseled_stone_bricks")
    put(x, CY + 6, z, "lantern")
for x, z, b in ((-24, -6, "smithing_table"), (-25, -6, "grindstone[face=floor,facing=east]"), (-23, -4, "anvil[facing=north]"),
                (-24, -4, "blast_furnace[facing=south]"), (-25, -4, "fletching_table")):
    put(x, CY + 1, z, b)
for tx, tz in ((-16, -17), (-19, -19), (-22, -16)):
    put(tx, CY + 1, tz, "oak_fence"); put(tx, CY + 2, tz, "target")
flush()

# ---------- floating islands (grass top, rocky taper, chained lantern below, tiny scenery) ----------
def island(cx, cy, cz, r, depth, tree=None, extra=None):
    for i in range(depth):
        rr = r * (1 - i / (depth + 1)) ** 0.8 + random.uniform(-0.4, 0.4)
        if rr < 0.6: break
        disc(cy - i, rr, "grass_block" if i == 0 else ("dirt" if i < 2 else random.choice(rock)), cx, cz)
    setb(cx, cy - depth + 1, cz, random.choice(["amethyst_block", "sea_lantern", "glowstone"]))
    for k in range(3): put(cx, cy - depth - k, cz, "iron_chain[axis=y]", 99)
    put(cx, cy - depth - 3, cz, "lantern[hanging=true]", 99)
    if tree: c(f"place feature minecraft:{tree} {cx + 1} {cy + 1} {cz}")
    for _ in range(int(r * 2)):
        px, pz = cx + random.randint(-int(r) + 1, int(r) - 1), cz + random.randint(-int(r) + 1, int(r) - 1)
        if math.hypot(px - cx, pz - cz) < r - 0.5:
            c(f"execute if block {px} {cy} {pz} grass_block run setblock {px} {cy + 1} {pz} {random.choice(['short_grass', 'poppy', 'cornflower', 'azure_bluet', 'allium'])} keep")
    if extra: extra(cx, cy, cz)
    flush()

def lamp(cx, cy, cz):
    for y in range(cy + 1, cy + 4): put(cx - 1, y, cz - 1, "polished_blackstone_brick_wall", 99)
    put(cx - 1, cy + 4, cz - 1, "lantern", 99)
def ruin(cx, cy, cz):
    for dx, dz, h in ((-1, -1, 3), (1, -1, 2), (-1, 1, 1), (1, 1, 3)):
        for y in range(1, h + 1): put(cx + dx, cy + y, cz + dz, "mossy_stone_bricks", 99)
    put(cx, cy + 1, cz, "enchanting_table", 99)
def crystal(cx, cy, cz):
    for y in range(1, 4): put(cx, cy + y, cz, "amethyst_block", 99)
    put(cx, cy + 4, cz, "amethyst_cluster[facing=up]", 99)

island(26, 71, -5, 2.6, 5, "oak", lamp)       # skyblock trio beside its station
island(20, 77, -21, 2.3, 4, None, crystal)
island(12, 80, -15, 1.8, 4)
RING = [(18, 42, 66, 4.5, 8, "cherry", lamp), (62, 41, 73, 4.0, 7, "oak", ruin), (108, 43, 69, 4.5, 9, "cherry", crystal),
        (140, 40, 78, 3.6, 7, "birch", lamp), (165, 44, 64, 4.2, 8, None, ruin), (198, 42, 74, 4.5, 8, "oak", crystal),
        (228, 41, 68, 3.8, 7, "cherry", lamp), (315, 44, 76, 4.0, 8, "spruce", crystal), (345, 41, 63, 3.6, 7, "birch", ruin)]
for deg, rad, y, r, depth, tree, extra in RING:
    x, z = polar(rad, deg)
    island(x, y, z, r, depth, tree, extra)

# ---------- waterfalls off the east and west rim ----------
for sgn in (1, -1):
    for x in range(26, 32):
        for z in (-1, 0, 1): put(sgn * x, CY, z, "water", 99)
    for x in range(25, 30):
        for z in (-2, 2): put(sgn * x, CY, z, "stone_brick_slab[type=top]", 99)
    for z in (-2, 2): put(sgn * 24, CY, z, "stone_bricks", 99); put(sgn * 24, CY + 1, z, "lantern", 99)
flush()

# ---------- greenery ----------
for deg in (60, 120, 240, 300):  # cherry trees between stations (0/180 hold the waterfalls)
    x, z = polar(25, deg)
    c(f"place feature minecraft:cherry {x} {CY + 1} {z}")
for _ in range(150):
    r = random.uniform(12, R - 2); x, z = polar(r, random.uniform(0, 360))
    if random.random() < 0.15:
        t = random.choice(["rose_bush", "lilac", "peony"])
        c(f"execute if block {x} {CY} {z} grass_block if block {x} {CY + 1} {z} air if block {x} {CY + 2} {z} air run setblock {x} {CY + 2} {z} {t}[half=upper]")
        c(f"execute if block {x} {CY + 2} {z} {t} run setblock {x} {CY + 1} {z} {t}[half=lower]")
        continue
    flower = random.choice(['short_grass', 'short_grass', 'fern', 'poppy', 'dandelion', 'cornflower', 'allium', 'azure_bluet',
                            'lily_of_the_valley', 'oxeye_daisy', 'pink_tulip', 'sweet_berry_bush[age=3]'])
    c(f"execute if block {x} {CY} {z} grass_block run setblock {x} {CY + 1} {z} {flower} keep")

# Spawn point on the plaza, south of the fountain, facing it
c(f"setworldspawn 0 {CY + 1} 8 180 0")

print("\n".join(cmds))
