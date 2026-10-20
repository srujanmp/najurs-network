#!/usr/bin/env python3
"""Build the 9 rotating PvP round maps (x = 300..2700 step 300) in world `arena` (the Colosseum at 0,0 is built by arena.py).

Each map is centred at (CX, 64, 0) with a glass spectator platform at y=80 above its centre.
Match.java holds the matching spawn ring / spectator spot / fall height for every map.
"""
import math, random

random.seed(77)
W = "minecraft:arena"
Y = 64
cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")
def disc(cx, y, cz, r, block, rin=-1):
    for dz in range(-int(r), int(r) + 1):
        h = int(math.sqrt(max(r * r - dz * dz, 0)))
        if rin < 0 or abs(dz) > rin: fill(cx - h, y, cz + dz, cx + h, y, cz + dz, block)
        else:
            hi = int(math.sqrt(rin * rin - dz * dz))
            fill(cx - h, y, cz + dz, cx - hi - 1, y, cz + dz, block); fill(cx + hi + 1, y, cz + dz, cx + h, y, cz + dz, block)
def polar(cx, r, deg): a = math.radians(deg); return cx + round(r * math.cos(a)), round(r * math.sin(a))
def clear(cx):
    c(f"forceload add {cx - 32} -32 {cx + 32} 32")
    c(f"minecraft:kill @e[type=text_display,tag=mapholo,x={cx - 32},y=0,z=-32,dx=64,dy=200,dz=64]")  # after forceload, so old labels are loaded
    for y in reversed(range(40, 92)): fill(cx - 30, y, -30, cx + 30, y, 30, "air")  # top-down so attached blocks never pop off
def spectator(cx, label, colour):
    fill(cx - 4, 80, -4, cx + 4, 80, 4, "tinted_glass")
    fill(cx - 4, 81, -4, cx + 4, 82, 4, "barrier", "hollow"); fill(cx - 3, 81, -3, cx + 3, 82, 3, "air"); fill(cx - 4, 83, -4, cx + 4, 83, 4, "air")
    c(f'summon text_display {cx + 0.5} 84.5 0.5 {{Tags:["mapholo"],billboard:"center",background:1073741824,'
      f'transformation:{{scale:[2f,2f,2f],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}},'
      f'text:{{text:"{label}",color:"{colour}",bold:1b}}}}')
def underside(cx, r, rocks, depth=10):
    for i in range(1, depth):
        rr = r * (1 - i / depth) ** 0.8
        if rr >= 1: disc(cx, Y - i, 0, rr, random.choice(rocks))


# ---------- Nether Pit (300) ----------
cx = 300; clear(cx)
disc(cx, Y - 1, 0, 21, "netherrack"); disc(cx, Y, 0, 21, "blackstone"); disc(cx, Y, 0, 16, "polished_blackstone_bricks")
disc(cx, Y, 0, 20, "lava", rin=16.5)                       # lava moat around the fighting floor
disc(cx, Y + 1, 0, 21.5, "nether_brick_wall", rin=20.5)
disc(cx, Y, 0, 3, "magma_block"); setb(cx, Y, 0, "crying_obsidian")
for d in range(0, 360, 60):
    x, z = polar(cx, 10, d + 30); fill(x, Y + 1, z, x, Y + 4, z, "basalt"); setb(x, Y + 5, z, "soul_lantern")
for d in range(0, 360, 90):
    x, z = polar(cx, 6, d); fill(x - 1, Y + 1, z, x + 1, Y + 2, z, "blackstone_wall")
underside(cx, 21, ["netherrack", "blackstone", "basalt"])
spectator(cx, "NETHER PIT", "red")

# ---------- Sky Ruins (600) ----------
cx = 600; clear(cx)
disc(cx, Y, 0, 6, "stone_bricks"); disc(cx, Y, 0, 3, "chiseled_stone_bricks"); underside(cx, 6, ["stone", "andesite", "cobblestone"], 7)
for d in (45, 135, 225, 315):
    x, z = polar(cx, 15, d)
    disc(x, Y, z, 4, "mossy_stone_bricks")
    for i in range(1, 5): disc(x, Y - i, z, 4 - i, random.choice(["stone", "cobblestone", "mossy_cobblestone"]))
    fill(x + 2, Y + 1, z, x + 2, Y + 3, z, "cracked_stone_bricks"); setb(x - 2, Y + 1, z, "mossy_stone_brick_wall")
    for t in range(6, 12):                                   # thin bridge to the centre
        bx, bz = polar(cx, t, d); setb(bx, Y, bz, "stone_brick_slab[type=top]")
setb(cx, Y + 1, 0, "lodestone")
spectator(cx, "SKY RUINS", "aqua")

# ---------- Frozen Lake (900) ----------
cx = 900; clear(cx)
disc(cx, Y - 1, 0, 20, "packed_ice"); disc(cx, Y, 0, 20, "ice"); disc(cx, Y, 0, 4, "blue_ice")
disc(cx, Y + 1, 0, 20.5, "snow_block", rin=19)
disc(cx, Y + 2, 0, 20.5, "snow_block", rin=19.5)
for d in range(0, 360, 45):
    x, z = polar(cx, 12, d)
    fill(x, Y + 1, z, x, Y + 3 + (d // 45) % 2, z, "packed_ice")
for d in range(22, 360, 90):
    x, z = polar(cx, 7, d); fill(x - 1, Y + 1, z - 1, x + 1, Y + 1, z + 1, "snow_block")
underside(cx, 20, ["packed_ice", "snow_block", "ice"])
spectator(cx, "FROZEN LAKE", "aqua")

# ---------- Jungle Temple (1200) ----------
cx = 1200; clear(cx)
disc(cx, Y - 1, 0, 19, "dirt"); disc(cx, Y, 0, 19, "moss_block"); disc(cx, Y, 0, 12, "mossy_cobblestone")
fill(cx - 5, Y + 1, -5, cx + 5, Y + 3, 5, "mossy_stone_bricks"); fill(cx - 4, Y + 4, -4, cx + 4, Y + 4, 4, "chiseled_stone_bricks")
for i in range(4):  # a 3-wide staircase up each side of the temple
    y = Y + 1 + i
    fill(cx - 1, y, -9 + i, cx + 1, y, -9 + i, "mossy_stone_brick_stairs[facing=south]")
    fill(cx - 1, y, 9 - i, cx + 1, y, 9 - i, "mossy_stone_brick_stairs[facing=north]")
    fill(cx - 9 + i, y, -1, cx - 9 + i, y, 1, "mossy_stone_brick_stairs[facing=east]")
    fill(cx + 9 - i, y, -1, cx + 9 - i, y, 1, "mossy_stone_brick_stairs[facing=west]")
for d in range(0, 360, 40):
    x, z = polar(cx, 15, d)
    fill(x, Y + 1, z, x, Y + 2 + (d // 40) % 3, z, "jungle_log")
    setb(x, Y + 3 + (d // 40) % 3, z, "jungle_leaves[persistent=true]")
for d in range(20, 360, 120):
    x, z = polar(cx, 9, d); fill(x - 1, Y + 1, z - 1, x + 1, Y + 2, z + 1, "oak_leaves[persistent=true]")
setb(cx, Y + 5, 0, "lantern")
underside(cx, 19, ["dirt", "stone", "mossy_cobblestone"])
spectator(cx, "JUNGLE TEMPLE", "green")

# ---------- Cherry Grove (1500) ----------
cx = 1500; clear(cx)
disc(cx, Y - 1, 0, 19, "dirt"); disc(cx, Y, 0, 19, "grass_block"); disc(cx, Y, 0, 4, "cherry_planks")
disc(cx, Y + 1, 0, 19.5, "cherry_fence", rin=18.5)
for d in range(0, 360, 60):
    x, z = polar(cx, 11, d + 30); h = 3 + (d // 60) % 2
    fill(x, Y + 1, z, x, Y + h, z, "cherry_log")
    for dy, r in ((h, 2.5), (h + 1, 1.5)): disc(x, Y + dy, z, r, "cherry_leaves[persistent=true]")
    setb(x, Y + h, z, "cherry_log")
for d in range(0, 360, 90):
    x, z = polar(cx, 6, d + 45); fill(x - 1, Y + 1, z, x + 1, Y + 1, z, "cherry_slab")
for _ in range(60):
    x, z = polar(cx, random.uniform(5, 18), random.uniform(0, 360)); setb(x, Y + 1, z, "pink_petals[flower_amount=4]")
disc(cx, Y, 0, 2, "water"); setb(cx, Y, 0, "glowstone")
underside(cx, 19, ["dirt", "stone", "andesite"])
spectator(cx, "CHERRY GROVE", "light_purple")

# ---------- End Citadel (1800): a ring of end stone round a centre island, fall = out ----------
cx = 1800; clear(cx)
disc(cx, Y, 0, 18, "end_stone_bricks", rin=10); disc(cx, Y, 0, 18, "purpur_block", rin=16.5)
disc(cx, Y, 0, 5, "end_stone"); disc(cx, Y, 0, 2, "purpur_pillar"); setb(cx, Y + 1, 0, "end_rod")
for d in (0, 90, 180, 270):
    for t in range(6, 11):
        bx, bz = polar(cx, t, d); setb(bx, Y, bz, "purpur_slab[type=top]")
for d in range(0, 360, 45):
    x, z = polar(cx, 16, d + 22); fill(x, Y + 1, z, x, Y + 3, z, "purpur_pillar"); setb(x, Y + 4, z, "end_rod")
underside(cx, 18, ["end_stone", "end_stone", "obsidian"], 8)
spectator(cx, "END CITADEL", "light_purple")

# ---------- Sculk Depths (2100) ----------
cx = 2100; clear(cx)
disc(cx, Y - 1, 0, 20, "deepslate"); disc(cx, Y, 0, 20, "deepslate_tiles"); disc(cx, Y, 0, 6, "sculk")
for _ in range(90):
    x, z = polar(cx, random.uniform(0, 19), random.uniform(0, 360)); setb(x, Y, z, random.choice(["sculk", "sculk", "cobbled_deepslate", "reinforced_deepslate"]))
disc(cx, Y + 1, 0, 20.5, "deepslate_brick_wall", rin=19.5); disc(cx, Y + 2, 0, 20.5, "deepslate_brick_wall", rin=19.5)
for d in range(0, 360, 72):
    x, z = polar(cx, 12, d); fill(x - 1, Y + 1, z - 1, x + 1, Y + 4, z + 1, "polished_deepslate"); setb(x, Y + 5, z, "soul_lantern")
for d in range(36, 360, 72):
    x, z = polar(cx, 7, d); setb(x, Y + 1, z, "sculk_sensor"); setb(x, Y, z, "sculk")
setb(cx, Y + 1, 0, "sculk_catalyst")
underside(cx, 20, ["deepslate", "tuff", "sculk"])
spectator(cx, "SCULK DEPTHS", "dark_aqua")

# ---------- Ocean Ruins (2400) ----------
cx = 2400; clear(cx)
disc(cx, Y - 2, 0, 20, "dark_prismarine"); disc(cx, Y - 1, 0, 20, "prismarine"); disc(cx, Y, 0, 20, "prismarine_bricks")
for d in range(0, 360, 60):
    x, z = polar(cx, 9, d); disc(x, Y, z, 2, "water")            # knee-deep pools: slow you down mid-fight
disc(cx, Y + 1, 0, 20.5, "prismarine_wall", rin=19.5)
for d in range(30, 360, 60):
    x, z = polar(cx, 15, d); fill(x, Y + 1, z, x, Y + 3 + (d // 60) % 2, z, "prismarine_bricks"); setb(x, Y + 4 + (d // 60) % 2, z, "sea_lantern")
fill(cx - 2, Y + 1, -2, cx + 2, Y + 1, 2, "dark_prismarine_slab"); setb(cx, Y + 2, 0, "conduit")
for _ in range(30):
    x, z = polar(cx, random.uniform(4, 19), random.uniform(0, 360))
    if (x - cx) ** 2 + z * z > 25: setb(x, Y, z, random.choice(["sea_lantern", "dark_prismarine", "wet_sponge"]))
underside(cx, 20, ["prismarine", "dark_prismarine", "sand"])
spectator(cx, "OCEAN RUINS", "aqua")

# ---------- Mushroom Isle (2700) ----------
cx = 2700; clear(cx)
disc(cx, Y - 1, 0, 19, "dirt"); disc(cx, Y, 0, 19, "mycelium"); disc(cx, Y, 0, 3, "brown_mushroom_block")
for i, d in enumerate(range(0, 360, 72)):
    x, z = polar(cx, 12, d + 10); h = 4 + i % 2; cap = "red_mushroom_block" if i % 2 else "brown_mushroom_block"
    fill(x, Y + 1, z, x, Y + h, z, "mushroom_stem"); disc(x, Y + h + 1, z, 2.5, cap)
for _ in range(25):
    x, z = polar(cx, random.uniform(4, 18), random.uniform(0, 360)); setb(x, Y + 1, z, random.choice(["red_mushroom", "brown_mushroom"]))
disc(cx, Y + 1, 0, 19.5, "spruce_fence", rin=18.5)
setb(cx, Y + 1, 0, "shroomlight")
underside(cx, 19, ["dirt", "stone", "mycelium"])
spectator(cx, "MUSHROOM ISLE", "red")

c("gamerule block_drops false")
for cx in range(300, 3000, 300): c(f"forceload remove {cx - 32} -32 {cx + 32} 32")
print("\n".join(cmds))
