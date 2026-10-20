#!/usr/bin/env python3
"""Print commands that (re)create the hub's floating text (vanilla text_display entities, tag 'hubholo')."""
import json
HOLOS = [  # x, y, z, scale, lines [(text, colour, bold)]
    (0.5, 74.5, 0.5, 2.2, [("NAJURS NETWORK", "#7CFC00", True)]),
    (0.5, 73.6, 0.5, 1.0, [("Survival • Skyblock • BedWars • Hide & Seek • PvP • Horror • Creative", "gray", False)]),
    (0.5, 68.9, -19.5, 1.2, [("SURVIVAL", "green", True), ("\nVanilla, Random Drops, Mob Morph & more.", "gray", False), ("\n▶ Click to pick a world", "yellow", False)]),
    (17.5, 68.9, -9.5, 1.2, [("SKYBLOCK", "aqua", True), ("\nStart on a tiny island in the void.", "gray", False), ("\n▶ Click to play", "yellow", False)]),
    (17.5, 68.9, 10.5, 1.2, [("ONEBLOCK", "yellow", True), ("\nOne block. Infinite phases.", "gray", False), ("\n▶ Click to play", "yellow", False)]),
    (0.5, 69.1, 20.5, 1.2, [("BEDWARS", "red", True), ("\n4 maps • Protect your bed!", "gray", False), ("\n▶ Click to pick a map", "yellow", False)]),
    (-16.5, 69.4, 10.5, 1.2, [("HIDE & SEEK", "light_purple", True), ("\nVillage • Haunted Mansion • Deep Dark", "gray", False), ("\n▶ Click to pick a map", "yellow", False)]),
    (-16.5, 69.1, -9.5, 1.2, [("PVP", "gold", True), ("\nColosseum • Sumo • Spleef • KB FFA • Duels", "gray", False), ("\n▶ Click to pick a mode", "yellow", False)]),
    (0.5, 66.8, 26.0, 0.9, [("/menu", "aqua", True), (" all games & horror  ", "gray", False), ("/spawn", "aqua", True), (" back here", "gray", False)]),
]
print("execute in minecraft:overworld run minecraft:kill @e[type=text_display,tag=hubholo]")
for x, y, z, s, lines in HOLOS:
    text = json.dumps([{"text": t, "color": c, "bold": b} for t, c, b in lines], ensure_ascii=False)
    nbt = (f'{{Tags:["hubholo"],billboard:"center",alignment:"center",shadow:1b,background:1073741824,'
           f'line_width:400,transformation:{{scale:[{s}f,{s}f,{s}f],translation:[0f,0f,0f],'
           f'left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}},text:{text}}}')
    print(f"execute in minecraft:overworld run summon text_display {x} {y} {z} {nbt}")
