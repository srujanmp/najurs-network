#!/usr/bin/env python3
"""Build the themed Hub Parkour north of the lobby.

Usage: parkour.py commands       -> build commands (pipe into rcon.py)
       parkour.py course <file>  -> writes the course layout NajursCore reads (start, checkpoints, pads, finish)

Course runs north (-Z) out of the gap in the hub wall through six themed zones, each ending in a checkpoint.
Timer, checkpoints, launch pads, respawn and leaderboard are handled by NajursCore (Parkour.java).
"""
import random, sys

random.seed(2603)
W = "minecraft:overworld"
X_LIMIT = 9                  # stay inside the lane (|x| <= 13) where double jump is disabled
LANE = (-14, -470, 14, -31)  # x1, z1, x2, z2

cmds, pads_out, checkpoints, launch = [], [], [], []
def c(s): cmds.append(f"execute in {W} run {s}")
def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {b} {mode}".strip())
def setb(x, y, z, b): c(f"setblock {x} {y} {z} {b}")
def label(x, y, z, text, colour, scale=1.0, tag="pkholo"):
    c(f'summon text_display {x} {y} {z} {{Tags:["{tag}"],billboard:"center",background:1073741824,'
      f'transformation:{{scale:[{scale}f,{scale}f,{scale}f],translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}},'
      f'text:{{text:"{text}",color:"{colour}",bold:1b}}}}')

pos = [0, 65, -35]  # top block of the current pad

def step(dz, dy=0, dx=None, block="stone", deco=None):
    """Next pad dz blocks north (dz>0), dy up, dx sideways (random small if None)."""
    if dx is None:
        dx = random.choice([-1, 0, 0, 1]) if dz < 4 else 0
    pos[0] = max(-X_LIMIT, min(X_LIMIT, pos[0] + dx)); pos[1] += dy; pos[2] -= dz
    setb(*pos, block)
    if deco: setb(pos[0], pos[1] + 1, pos[2], deco)

def platform(block, edge, r=1):
    x, y, z = pos
    fill(x - r - 1, y, z - r - 1, x + r + 1, y, z + r + 1, edge)
    fill(x - r, y, z - r, x + r, y, z + r, block)

def checkpoint(name, colour, block, edge):
    pos[2] -= 4
    platform(block, edge)
    x, y, z = pos
    setb(x, y + 1, z, "light_weighted_pressure_plate")
    checkpoints.append(dict(name=name, x=x + 0.5, y=y + 1, z=z + 0.5))
    label(x + 0.5, y + 2.6, z + 0.5, f"✔ {name}", colour, 1.2)

def zone_title(text, colour):
    x, y, z = pos
    label(x + 0.5, y + 3.4, z - 3.5, text, colour, 1.6)

# ---------- clear + start ----------
c("forceload add -16 -480 16 -16")
x1, z1, x2, z2 = LANE
for y in reversed(range(55, 130)):  # top-down so attached blocks (plates, ladders) never pop off as items
    for zs in range(z1, z2, 120):   # chunks of the lane, each fill stays under the block cap
        fill(x1, y, zs, x2, y, min(zs + 119, z2 - 1), "air")
c("minecraft:kill @e[type=text_display,tag=pkholo]")
c("minecraft:kill @e[type=text_display,tag=pkboard]")

fill(-2, 65, -35, 2, 65, -31, "polished_deepslate")
fill(-1, 65, -34, 1, 65, -32, "lime_concrete")
setb(0, 66, -33, "heavy_weighted_pressure_plate")
start = dict(x=0.5, y=66, z=-32.5)
label(0.5, 68.4, -32.5, "HUB PARKOUR", "aqua", 1.6)
label(0.5, 67.7, -32.5, "Step on the plate to start", "gray", 0.8)
board = dict(x=3.5, y=69.5, z=-33.5)  # leaderboard display spawned by NajursCore

# ---------- 1. Sky Garden ----------
zone_title("☘ SKY GARDEN", "green")
garden = ["moss_block", "flowering_azalea_leaves[persistent=true]", "azalea_leaves[persistent=true]", "grass_block"]
for i in range(12):
    dy = 1 if i in (3, 7, 10) else 0
    step(2 if dy else random.choice([3, 3, 4]), dy, block=garden[i % 4], deco="pink_petals" if garden[i % 4] == "grass_block" else None)
    if i % 4 == 2: setb(pos[0] + 2, pos[1], pos[2], "oak_fence"); setb(pos[0] + 2, pos[1] + 1, pos[2], "lantern")
checkpoint("Sky Garden", "green", "moss_block", "oak_planks")

# ---------- 2. Frozen Caverns ----------
zone_title("❄ FROZEN CAVERNS", "aqua")
for i in range(5):
    step(3, 0, block="packed_ice")
step(3, 0, dx=0, block="ice")
x, y, z = pos
fill(x, y, z - 1, x, y, z - 6, "ice")                      # 1-wide ice bridge
pos[2] -= 6
step(3, 1, dx=0, block="snow_block")
x, y, z = pos
fill(x, y, z - 1, x, y, z - 8, "blue_ice")                 # speed strip into a long jump
pos[2] -= 8
step(4, 0, dx=0, block="packed_ice")
for i in range(4):
    step(3, 1 if i % 2 else 0, block=random.choice(["packed_ice", "snow_block", "blue_ice"]))
checkpoint("Frozen Caverns", "aqua", "snow_block", "packed_ice")

# ---------- 3. Launch Pads ----------
zone_title("⇧ LAUNCH PADS", "light_purple")
for i in range(4):
    step(3, 0, dx=0, block="slime_block")
    launch.append(dict(x=pos[0], y=pos[1], z=pos[2]))      # NajursCore flings players from here
    pos[2] -= 10; pos[1] += 4                               # landing island 10 ahead, 4 up
    platform("purpur_block", "end_stone_bricks", r=1)
checkpoint("Launch Pads", "light_purple", "purpur_block", "end_stone_bricks")

# ---------- 4. Nether Run ----------
zone_title("🔥 NETHER RUN", "red")
nether = ["blackstone", "netherrack", "magma_block", "polished_blackstone_bricks", "crimson_nylium", "soul_soil"]
for i in range(12):
    dy = -1 if i in (2, 8) else 1 if i in (5, 10) else 0
    step(2 if dy == 1 else 3, dy, block=nether[i % len(nether)])
    if i % 3 == 0:
        x, y, z = pos
        fill(x + 3, y + 1, z, x + 3, y + 5, z, "iron_chain")
        setb(x + 3, y, z, "lantern[hanging=true]")
step(3, 0, dx=0, block="soul_sand")                         # slow pad: plan the next jump
step(2, 1, dx=0, block="crying_obsidian")
checkpoint("Nether Run", "red", "polished_blackstone", "nether_bricks")

# ---------- 5. The Tower ----------
zone_title("⌂ THE TOWER", "gold")
pos[2] -= 4
tx, ty, tz = pos
fill(tx - 2, ty, tz - 2, tx + 2, ty + 16, tz + 2, "polished_blackstone_bricks")
for y in range(ty + 1, ty + 17):                            # ladder up the south face
    setb(tx, y, tz + 3, "ladder[facing=south]")
setb(tx, ty, tz + 3, "polished_blackstone_bricks")
fill(tx - 1, ty, tz + 3, tx + 1, ty, tz + 5, "polished_blackstone_bricks")
pos[:] = [tx, ty + 16, tz]
spiral = [(4, -3), (4, 3), (0, 5), (-4, 3), (-4, -3), (0, -6)]
for dx, dz in spiral:                                       # jumps circling the top of the tower
    pos[0], pos[2] = tx + dx, tz + dz
    pos[1] += 1
    setb(*pos, "gilded_blackstone")
    setb(pos[0], pos[1] + 3, pos[2], "iron_chain")                # head-hitter decoration above each pad
pos[0] = tx
checkpoint("The Tower", "gold", "gold_block", "polished_blackstone_bricks")

# ---------- 6. End Skies ----------
zone_title("✦ END SKIES", "yellow")
for i in range(12):
    dy = 1 if i % 4 == 1 else 0
    step(2 if dy else random.choice([3, 4]), dy, block="end_stone_bricks" if i % 2 else "purpur_pillar")
    if i % 3 == 2: setb(pos[0] - 2, pos[1] + 1, pos[2], "end_rod")
pos[2] -= 5
fx, fy, fz = pos
fill(fx - 3, fy, fz - 3, fx + 3, fy, fz + 3, "end_stone_bricks")
fill(fx - 2, fy, fz - 2, fx + 2, fy, fz + 2, "purpur_block")
fill(fx - 1, fy - 1, fz - 1, fx + 1, fy - 1, fz + 1, "iron_block")
setb(fx, fy, fz, "beacon")
for ox, oz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
    setb(fx + ox, fy + 1, fz + oz, "end_rod")
setb(fx + 2, fy + 1, fz - 2, "dragon_egg")
finish = dict(x=fx + 0.5, y=fy + 1, z=fz + 0.5)
label(fx + 0.5, fy + 3.2, fz + 0.5, "★ FINISH ★", "green", 1.8)

# The general void catcher (fallers outside the parkour lane go back to hub spawn) lives under the hub.
lane = f"x={x1},y=-128,z={z1},dx={x2 - x1},dy=158,dz={z2 - z1}"
fill(-10, 20, -10, 10, 20, 10, "air")
catcher = f"execute as @a[x=-200,y=-128,z=-200,dx=400,dy=158,dz=400] unless entity @s[{lane}] run minecraft:tp @s 0.5 65 8.5 180 0"
setb(-10, 20, 2, 'repeating_command_block{auto:1b,Command:"' + catcher.replace('"', '\\"') + '"}')
c("gamerule command_block_output false")
c("forceload remove -16 -480 16 -48")
c("forceload add -16 -48 16 16")

def course_yaml():
    def p(d): return "{x: %s, y: %s, z: %s}" % (d["x"], d["y"], d["z"])
    out = ["# Generated by tools/build/parkour.py", "world: lobby",
           f"lane: {{x1: {x1}, z1: {z1}, x2: {x2}, z2: {z2}}}",
           f"start: {p(start)}", f"finish: {p(finish)}", f"board: {p(board)}", "checkpoints:"]
    out += [f"  - {{name: \"{cp['name']}\", x: {cp['x']}, y: {cp['y']}, z: {cp['z']}}}" for cp in checkpoints]
    out.append("launch:")
    out += [f"  - {p(l)}" for l in launch]
    return "\n".join(out) + "\n"

if __name__ == "__main__":
    if sys.argv[1] == "commands": print("\n".join(cmds))
    else: open(sys.argv[2], "w").write(course_yaml())
