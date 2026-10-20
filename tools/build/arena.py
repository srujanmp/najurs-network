#!/usr/bin/env python3
"""Print the commands that build the Colosseum free-for-all PvP arena in world `arena`."""
import math, random

random.seed(7)
W = "minecraft:arena"
Y = 64
PIT = 22   # fighting floor radius
cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")

def ring(y, r_out, r_in, block):
    for dz in range(-int(r_out), int(r_out) + 1):
        ho = int(math.sqrt(max(r_out * r_out - dz * dz, 0)))
        if abs(dz) > r_in:
            fill(-ho, y, dz, ho, y, dz, block)
        else:
            hi = int(math.sqrt(r_in * r_in - dz * dz))
            fill(-ho, y, dz, -hi - 1, y, dz, block); fill(hi + 1, y, dz, ho, y, dz, block)

c("forceload add -48 -48 48 48")
for y in reversed(range(50, 90)):  # top-down so attached blocks (plates, ladders) never pop off as items
    fill(-45, y, -45, 45, y, 45, "air")
c("gamerule advance_time false"); c("gamerule advance_weather false"); c("gamerule spawn_mobs false")
c("gamerule keep_inventory true"); c("gamerule show_death_messages true")

# Foundation and sand floor with a patterned centre
ring(Y - 1, 40, -1, "sandstone")
ring(Y, PIT, -1, "smooth_sandstone")
for _ in range(120):
    a = random.uniform(0, 2 * math.pi); r = random.uniform(0, PIT - 1)
    setb(round(r * math.cos(a)), Y, round(r * math.sin(a)), random.choice(["sand", "suspicious_sand", "smooth_sandstone", "chiseled_sandstone"]))
ring(Y, 4, 2.5, "chiseled_red_sandstone"); ring(Y, 2, -1, "gold_block")

# Arena wall
for y in range(Y + 1, Y + 6):
    ring(y, PIT + 2, PIT, "cut_sandstone" if y % 2 else "sandstone")
ring(Y + 6, PIT + 2, PIT, "chiseled_sandstone")
# Tiered spectator stands rising outward
for i, r in enumerate(range(PIT + 3, PIT + 12, 2)):
    ring(Y + 6 + i, r + 1, r - 1, "smooth_red_sandstone")
    ring(Y + 7 + i, r + 1.2, r + 0.4, "red_sandstone_slab")
# Outer colonnade
for deg in range(0, 360, 15):
    a = math.radians(deg); x = round((PIT + 13) * math.cos(a)); z = round((PIT + 13) * math.sin(a))
    fill(x, Y, z, x, Y + 14, z, "sandstone_wall")
    setb(x, Y + 15, z, "lantern")
# Cover: four pillars and four low walls in the pit
for deg in (45, 135, 225, 315):
    a = math.radians(deg); x = round(12 * math.cos(a)); z = round(12 * math.sin(a))
    fill(x - 1, Y + 1, z - 1, x + 1, Y + 4, z + 1, "cut_sandstone")
    fill(x - 1, Y + 5, z - 1, x + 1, Y + 5, z + 1, "chiseled_sandstone")
for deg in (0, 90, 180, 270):
    a = math.radians(deg); x = round(15 * math.cos(a)); z = round(15 * math.sin(a))
    dx, dz = (0, 2) if deg in (0, 180) else (2, 0)
    fill(x - dx, Y + 1, z - dz, x + dx, Y + 2, z + dz, "sandstone_wall")
# Entry gate on the south side, with a viewing balcony above it
fill(-2, Y + 1, PIT, 2, Y + 4, PIT + 2, "air")
fill(-3, Y + 5, PIT, 3, Y + 5, PIT + 2, "red_sandstone")
for x in (-3, 3):
    fill(x, Y + 1, PIT, x, Y + 4, PIT, "chiseled_red_sandstone")
# Banners of light around the pit floor
for deg in range(0, 360, 30):
    a = math.radians(deg); x = round((PIT - 0.5) * math.cos(a)); z = round((PIT - 0.5) * math.sin(a))
    setb(x, Y, z, "shroomlight")

c(f"setworldspawn 0 {Y + 1} {PIT - 3} 180 0")
print("\n".join(cmds))
