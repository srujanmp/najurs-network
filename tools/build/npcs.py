#!/usr/bin/env python3
"""Print FancyNpcs commands that (re)create the six hub station NPCs. Pipe into rcon.py."""
NPCS = [  # name, type, x, z, display, glow, click actions ("~" = wait 1s), extra commands
    ("hub_survival", "VILLAGER", 0, -20, "<green><bold>SURVIVAL</bold></green>", "green", ["worlds"], []),
    ("hub_skyblock", "WANDERING_TRADER", 17, -10, "<aqua><bold>SKYBLOCK</bold></aqua>", "aqua", ["island"], []),
    ("hub_oneblock", "IRON_GOLEM", 17, 10, "<yellow><bold>ONEBLOCK</bold></yellow>", "yellow", ["ob"], ["scale {n} 0.6"]),
    ("hub_bedwars", "PLAYER", 0, 20, "<red><bold>BEDWARS</bold></red>", "red", ["bwmenu"],
     ["skin {n} @mirror", "equipment {n} set mainhand red_bed"]),
    ("hub_hideseek", "FOX", -17, 10, "<light_purple><bold>HIDE & SEEK</bold></light_purple>", "light_purple", ["hsmenu"], ["scale {n} 1.8"]),
    ("hub_arena", "PLAYER", -17, -10, "<gold><bold>PVP ARENA</bold></gold>", "gold", ["pvp"],
     ["skin {n} @mirror", "equipment {n} set mainhand diamond_sword", "equipment {n} set chest iron_chestplate"]),
]
for n, t, x, z, dn, glow, actions, extra in NPCS:
    print(f"npc remove {n}")
    print(f"npc create {n} --type {t} --location {x} 66 {z} --world lobby")
    print(f"npc center {n}")
    for s in (f"displayname {n} {dn}", f"glowing {n} {glow}", f"turn_to_player {n} true",
              f"collidable {n} false", f"interaction_cooldown {n} 2s"):
        print("npc " + s)
    for a in actions:
        print(f"npc action {n} any_click add " + ("wait 1" if a == "~" else f"player_command {a}"))
    for e in extra:
        print("npc " + e.format(n=n))
