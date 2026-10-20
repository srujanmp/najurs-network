#!/usr/bin/env python3
"""Small builds that live inside generated worlds: Hide & Seek lobbies and seeker boxes, the duel arenas.

hideseek and hs_horror are vanilla worlds grown from fixed seeds (see Multiverse-Core/worlds.yml), so the village,
the woodland mansion and the ancient city land at the same coordinates every time; this adds our platforms on top.
"""

cmds = []
def c(world, s): cmds.append(f"execute in minecraft:{world} run {s}")

# --- Hide & Seek: Village (flat plains world with villages) ---
H = "hideseek"
c(H, "forceload add 0 192 80 272")
for s in ["fill 20 10 208 34 10 222 tinted_glass", "fill 21 10 209 33 10 221 glass", "fill 20 11 208 34 13 222 barrier hollow",
          "fill 20 14 208 34 14 222 air", "fill 21 11 209 33 13 221 air",
          "setblock 20 10 208 sea_lantern", "setblock 34 10 222 sea_lantern", "setblock 20 10 222 sea_lantern", "setblock 34 10 208 sea_lantern",
          "fill 60 0 250 66 5 256 polished_blackstone_bricks hollow", "fill 61 1 251 65 1 255 black_carpet"]:
    c(H, s)
c(H, "forceload remove 0 192 80 272")

# --- Haunted Mansion and Deep Dark (hs_horror): glass lobby + seeker box above each structure ---
def lobby(x, z, y):
    W = "hs_horror"
    c(W, f"forceload add {x - 8} {z - 8} {x + 28} {z + 8}")
    c(W, f"fill {x-7} {y} {z-7} {x+7} {y} {z+7} black_stained_glass")
    c(W, f"fill {x-7} {y+1} {z-7} {x+7} {y+3} {z+7} barrier hollow")
    c(W, f"fill {x-6} {y+1} {z-6} {x+6} {y+3} {z+6} air")
    c(W, f"fill {x-7} {y+4} {z-7} {x+7} {y+4} {z+7} air")
    for dx, dz in ((-7, -7), (7, -7), (-7, 7), (7, 7)): c(W, f"setblock {x+dx} {y} {z+dz} jack_o_lantern")
    c(W, f"fill {x+20} {y-10} {z} {x+26} {y-5} {z+6} polished_blackstone_bricks hollow")
    c(W, f"forceload remove {x - 8} {z - 8} {x + 28} {z + 8}")
lobby(-6768, -4672, 110)   # woodland mansion
lobby(-1808, -192, 60)     # ancient city
c("hs_horror", "forceload add -6830 -4740 -6700 -4610")
c("hs_horror", "minecraft:kill @e[type=#minecraft:illager,x=-6830,y=-64,z=-4740,dx=130,dy=200,dz=130]")
c("hs_horror", "minecraft:kill @e[type=vex]")
c("hs_horror", "minecraft:kill @e[type=allay]")
c("hs_horror", "forceload remove -6830 -4740 -6700 -4610")

# --- Duel arenas in the arena world ---
A = "arena"
c(A, "forceload add -16 -116 56 -84")
for cx in (0, 40):
    z = -100
    for s in [f"fill {cx-12} 63 {z-12} {cx+12} 69 {z+12} air", f"fill {cx-12} 63 {z-12} {cx+12} 63 {z+12} deepslate_tiles",
              f"fill {cx-11} 63 {z-11} {cx+11} 63 {z+11} polished_deepslate", f"fill {cx-12} 64 {z-12} {cx+12} 68 {z+12} tinted_glass hollow",
              f"fill {cx-11} 64 {z-11} {cx+11} 68 {z+11} air", f"fill {cx-12} 69 {z-12} {cx+12} 69 {z+12} air",
              f"setblock {cx} 63 {z-9} gold_block", f"setblock {cx} 63 {z+9} emerald_block"]:
        c(A, s)
    for dx, dz in ((-11, -11), (11, -11), (-11, 11), (11, 11)): c(A, f"setblock {cx+dx} 63 {z+dz} sea_lantern")
c(A, "minecraft:kill @e[type=text_display,tag=duelholo]")
for x, n in ((0.5, 1), (40.5, 2)):
    c(A, f'summon text_display {x} 66 -99.5 {{Tags:["modeholo","duelholo"],billboard:"center",background:1073741824,text:{{text:"DUEL ARENA {n}",color:"red",bold:1b}}}}')
c(A, "forceload remove -16 -116 56 -84")

print("\n".join(cmds))
