#!/usr/bin/env python3
"""Print the commands that build the floating-island lobby hub (pipe into rcon.py).

Layout (top view, centre 0,64,0, spawn faces the fountain from the south):
  six game stations sit on a ring at radius 20, joined to the plaza by paths.
"""
import math, random

random.seed(26_3)
W = "minecraft:overworld"  # the lobby is the server's main world
CY = 64                    # walking surface
R = 30                     # island radius

STATIONS = [  # (name, angle in degrees, pad block, accent block)
    ("survival", 270, "lime_concrete", "moss_block"),
    ("skyblock", 330, "light_blue_concrete", "prismarine_bricks"),
    ("oneblock", 30, "yellow_concrete", "honeycomb_block"),
    ("bedwars", 90, "red_concrete", "red_nether_bricks"),
    ("hideseek", 150, "purple_concrete", "purpur_block"),
    ("arena", 210, "orange_concrete", "cut_copper"),
]

cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")

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

c("forceload add -48 -48 48 48")
for y in reversed(range(30, 91, 3)):  # top-down so attached blocks (plates, ladders) never pop off as items  # clear in slabs (fill caps at 32768 blocks) so rebuilds are clean
    fill(-48, y, -48, 48, y + 2, 48, "air")

# Underside: a tapering rocky cone so the hub reads as a floating island.
rock = ["stone", "andesite", "tuff", "stone", "cobblestone", "diorite"]
for i, y in enumerate(range(CY - 1, CY - 31, -1)):
    t = i / 30
    r = (R + 1) * (1 - t) ** 0.7 + random.uniform(-1.2, 1.2)
    if r < 1: break
    disc(y, r, "dirt" if i < 3 else random.choice(rock))
for _ in range(25):  # scattered ores on the underside for detail
    y = random.randint(CY - 20, CY - 4)
    r = (R + 1) * (1 - (CY - 1 - y) / 30) ** 0.7 - 0.5
    x, z = polar(r, random.uniform(0, 360))
    setb(x, y, z, random.choice(["coal_ore", "iron_ore", "copper_ore", "gold_ore", "amethyst_block"]))

# Top surface
disc(CY, R, "grass_block")
disc(CY, 11, "polished_andesite")
disc(CY, 11, "polished_deepslate", rin=9.5)
for name, deg, pad, accent in STATIONS:
    for r in range(11, 18):  # 3-wide path from plaza to station
        for w in (-1, 0, 1):
            a = math.radians(deg)
            x = round(r * math.cos(a) - w * math.sin(a)); z = round(r * math.sin(a) + w * math.cos(a))
            setb(x, CY, z, "stone_bricks" if w else "chiseled_stone_bricks" if r % 3 == 0 else "stone_bricks")
# Perimeter wall with lanterns, gap at north for the parkour exit
for deg in range(0, 360, 3):
    if 255 <= deg <= 285:
        continue
    x, z = polar(R - 0.5, deg)
    setb(x, CY + 1, z, "stone_brick_wall")
for deg in range(0, 360, 24):
    if 250 <= deg <= 290:
        continue
    x, z = polar(R - 0.5, deg)
    setb(x, CY + 1, z, "stone_bricks"); setb(x, CY + 2, z, "lantern")

# Fountain: basin, quartz spire, water cascading off the top
disc(CY, 4, "water")
disc(CY + 1, 4.6, "smooth_quartz_slab", rin=3.6)
disc(CY - 1, 4, "sea_lantern")
fill(0, CY, 0, 0, CY + 5, 0, "quartz_pillar")
setb(0, CY + 6, 0, "chiseled_quartz_block")
setb(0, CY + 7, 0, "water")
for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
    setb(dx, CY + 6, dz, "smooth_quartz_stairs[facing=" + {(1,0):"west",(-1,0):"east",(0,1):"north",(0,-1):"south"}[(dx,dz)] + "]")

# Game stations: raised 7x7 pad, accent border, lamp posts either side
for name, deg, pad, accent in STATIONS:
    x, z = polar(20, deg)
    fill(x - 3, CY, z - 3, x + 3, CY, z + 3, accent)
    fill(x - 2, CY + 1, z - 2, x + 2, CY + 1, z + 2, pad)
    for ox, oz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
        fill(x + ox, CY + 1, z + oz, x + ox, CY + 3, z + oz, "polished_blackstone_wall")
        setb(x + ox, CY + 4, z + oz, "lantern")
    setb(x, CY, z, "glowstone")

# Cherry trees between stations, flowers sprinkled on the grass
for deg in range(0, 360, 60):
    x, z = polar(25, deg + 0)
    c(f"place feature minecraft:cherry {x} {CY + 1} {z}")
for _ in range(90):
    r = random.uniform(12, R - 2); x, z = polar(r, random.uniform(0, 360))
    flower = random.choice(['short_grass', 'short_grass', 'poppy', 'dandelion', 'cornflower', 'allium'])
    c(f"execute if block {x} {CY} {z} grass_block run setblock {x} {CY + 1} {z} {flower} keep")

# Spawn point on the plaza, south of the fountain, facing it
c(f"setworldspawn 0 {CY + 1} 8 180 0")

print("\n".join(cmds))
