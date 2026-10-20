#!/usr/bin/env python3
"""Print commands for the extra vanilla PvP modes in world `arena`: Sumo, Spleef and Knockback FFA.

Each mode is a zone; command blocks under the colosseum (y=30) give/clear items inside the zone and
return players who fall to that mode's spawn.
"""
import math

W = "minecraft:arena"
cmds = []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")
def disc(cx, y, cz, r, block):
    for dz in range(-int(r), int(r) + 1):
        h = int(math.sqrt(max(r * r - dz * dz, 0)))
        fill(cx - h, y, cz + dz, cx + h, y, cz + dz, block)
def label(x, y, z, text, colour, scale=1.4):
    c(f'summon text_display {x} {y} {z} {{Tags:["modeholo"],billboard:"center",background:1073741824,'
      f'transformation:{{scale:[{scale}f,{scale}f,{scale}f],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}},'
      f'text:{{text:"{text}",color:"{colour}",bold:1b}}}}')
def zone(x, z, r=20):
    return f"x={x - r},y=-64,z={z - r},dx={2 * r},dz={2 * r},dy=200"

c("forceload add 76 -24 124 24"); c("forceload add -24 76 24 124"); c("forceload add -124 -24 -76 24")
c("minecraft:kill @e[type=text_display,tag=modeholo]")

# --- Sumo: small platform over the void, push them off ---
SX, SZ = 100, 0
for y in reversed(range(55, 75)): fill(SX - 12, y, SZ - 12, SX + 12, y, SZ + 12, "air")  # top-down so attached blocks never pop off
disc(SX, 63, SZ, 7, "stone")
disc(SX, 64, SZ, 6, "white_concrete")
disc(SX, 64, SZ, 1.5, "red_concrete")
for d in range(0, 360, 45):
    a = math.radians(d); setb(SX + round(6 * math.cos(a)), 64, SZ + round(6 * math.sin(a)), "sea_lantern")
label(SX + 0.5, 69, SZ + 0.5, "SUMO", "white")

# --- Spleef: snow floor above water, reset button on the ledge ---
PX, PZ = 0, 100
for y in reversed(range(58, 76)): fill(PX - 14, y, PZ - 14, PX + 14, y, PZ + 18, "air")  # top-down so attached blocks never pop off
fill(PX - 13, 58, PZ - 13, PX + 13, 58, PZ + 13, "stone_bricks")
fill(PX - 12, 59, PZ - 12, PX + 12, 59, PZ + 12, "water")
disc(PX, 70, PZ, 10, "snow_block")
fill(PX - 4, 70, PZ + 13, PX + 4, 70, PZ + 16, "polished_andesite")          # waiting ledge
setb(PX, 71, PZ + 15, "oak_button[face=floor]")  # sits on the command block below, so pressing it fires it
setb(PX, 70, PZ + 15, f'command_block{{Command:"fill {PX - 10} 70 {PZ - 10} {PX + 10} 70 {PZ + 10} snow_block replace air"}}')
label(PX + 0.5, 75, PZ + 0.5, "SPLEEF", "aqua")
label(PX + 0.5, 72.6, PZ + 15.5, "Button = reset floor", "gray", 0.8)

# --- Knockback FFA: grassy island, knockback sticks ---
KX, KZ = -100, 0
for y in reversed(range(50, 80)): fill(KX - 16, y, KZ - 16, KX + 16, y, KZ + 16, "air")  # top-down so attached blocks never pop off
for i, r in enumerate((12, 11, 9, 7, 5, 3)):
    disc(KX, 64 - i, KZ, r, "grass_block" if i == 0 else "dirt" if i < 2 else "stone")
for ox, oz in ((5, 3), (-6, -2), (2, -7), (-3, 6)):
    fill(KX + ox, 65, KZ + oz, KX + ox + 1, 66, KZ + oz + 1, "oak_leaves[persistent=true]")
label(KX + 0.5, 70, KZ + 0.5, "KNOCKBACK FFA", "gold")

# --- Logic (repeating command blocks on a row at y=30 under the colosseum) ---
SUMO_SPAWN = f"{SX + 0.5} 65 {SZ + 4.5}"
SPLEEF_SPAWN = f"{PX + 0.5} 71 {PZ + 14.5}"
KB_SPAWN = f"{KX + 0.5} 65 {KZ + 0.5}"
logic = [
    f'minecraft:give @a[{zone(KX, KZ)},nbt=!{{Inventory:[{{id:"minecraft:stick"}}]}}] stick[enchantments={{knockback:3}},item_name="Knockback Stick"]',
    f"minecraft:clear @a[{zone(KX, KZ)}] diamond_sword",
    f"minecraft:effect give @a[{zone(SX, SZ)}] regeneration 2 3 true",
    f"minecraft:effect give @a[{zone(KX, KZ)}] regeneration 2 3 true",
]
fill(-10, 30, -10, 10, 30, -8, "air")
for i, cmd in enumerate(logic):
    esc = cmd.replace("\\", "\\\\").replace('"', '\\"')
    setb(-10 + i, 30, -9, f'repeating_command_block{{auto:1b,Command:"{esc}"}}')
c("gamerule command_block_output false"); c("gamerule block_drops false")
print("\n".join(cmds))
