#!/usr/bin/env python3
"""Generate the DeluxeMenus GUIs (plugins/DeluxeMenus/gui_menus/*.yml). Run, then `/dm reload` in game.

Each item: (slot, material, name, lore lines, commands run as the player; "@" prefix = console command).
"""
import json, os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/minecraft/server/plugins/DeluxeMenus/gui_menus")

def back(menu="games"):
    return (49, "arrow", "&7« Back", [], [f"[openguimenu] {menu}"])

def tp(world, x, y, z, yaw=0):
    return f"mv tp e:{world}:{x},{y},{z}:0:{yaw}"

MENUS = {
    "games": dict(title="&8» &2&lNAJURS &a&lNETWORK &8«", commands=["games"], size=54, border="black", items=[
        (4, "nether_star", "&a&lChoose what to play", ["&7Click a category or game.", "&7&o/spawn &7takes you back to the hub."], []),
        (19, "grass_block", "&a&lSurvival Worlds", ["&7Vanilla SMPs, each with its own", "&7nether, end & inventory.", "&7Includes &6Random Drops&7, &2Mob Morph", "&7and &cGoose Chase&7.", "", "&e▶ Click to pick a world"], ["[close]", "worlds"]),
        (20, "oak_sapling", "&b&lSkyblock", ["&7Tiny island in the void.", "&7Challenges, levels, warps.", "", "&e▶ Click to play"], ["[close]", "island"]),
        (21, "dirt", "&e&lOneBlock", ["&7One magic block, endless phases.", "", "&e▶ Click to play"], ["[close]", "ob"]),
        (22, "red_bed", "&c&lBedWars", ["&74 maps: Skylands, Frostbite,", "&7Inferno (2 teams), Overgrown.", "&7Starts at 2 players.", "", "&e▶ Click to pick a map"], ["[openguimenu] bedwars"]),
        (23, "spyglass", "&d&lHide & Seek", ["&7Village, Haunted Mansion", "&7and Deep Dark maps.", "", "&e▶ Click to choose a map"], ["[openguimenu] hideseek"]),
        (24, "diamond_sword", "&6&lPvP", ["&7Colosseum FFA, Sumo, Spleef,", "&7Knockback FFA & 1v1 Duels.", "", "&e▶ Click to choose a mode"], ["[openguimenu] pvp"]),
        (25, "wither_skeleton_skull", "&4&lHorror", ["&7Nightfall party games, Haunted", "&7Mansion, Deep Dark & Infection.", "&8Turn your volume up...", "", "&e▶ Click to choose"], ["[openguimenu] horror"]),
        (29, "chest", "&6&lRandom Drops SMP", ["&7Every block drops a random item.", "", "&e▶ Click to play"], ["[close]", "worlds join sw_random_drops"]),
        (30, "creeper_head", "&2&lMob Morph SMP", ["&7You turn into a random mob", "&7every minute. No mods needed!", "", "&e▶ Click to play"], ["[close]", "worlds join sw_mob_morph"]),
        (31, "brick", "&e&lCreative", ["&7Flat free-build world.", "&7Creative mode, own inventory.", "", "&e▶ Click to build"], ["[close]", "go creative"]),
        (32, "feather", "&f&lHub Parkour", ["&7Sky course with checkpoints.", "", "&e▶ Click to start"], ["[close]", "go parkour"]),
        (33, "tnt", "&6&lGoose Chase", ["&7Survival where something crazy", "&7happens every minute: nukes,", "&7THE GOOSE, TNT rain, sky drops...", "", "&e▶ Click to play"], ["[close]", "go sw_goose_chase"]),
        (45, "compass", "&b&lBack to Hub", [], ["[close]", "spawn"]),
        (48, "book", "&e&lCommands", ["&b/spawn &7hub", "&b/tpa <name> &7go to a friend", "&b/go <world> &7jump to a world", "&b/sethome &7& &b/home", "&b/msg <name> &7private message", "&b/bw leave &7| &b/hs leave &7| &b/duel <name>"], []),
        (49, "barrier", "&cClose", [], ["[close]"]),
        (53, "command_block", "&4&lAdmin Panel", ["&7Owner only."], ["[openguimenu] admin"]),
    ]),
    "bedwars": dict(title="&8» &c&lBedWars Maps", commands=["bwmenu"], size=54, border="red", items=[
        (19, "grass_block", "&a&lSkylands", ["&74 teams • up to 4 each", "&7Classic floating islands.", "", "&e▶ Click to join"], ["[close]", "bw join skylands"]),
        (21, "blue_ice", "&b&lFrostbite", ["&74 teams • up to 4 each", "&7Frozen islands of ice and snow.", "", "&e▶ Click to join"], ["[close]", "bw join frostbite"]),
        (23, "crimson_nylium", "&c&lInferno", ["&72 teams • up to 5 each", "&7Red vs Blue over a nether void.", "&7Best for small groups!", "", "&e▶ Click to join"], ["[close]", "bw join inferno"]),
        (25, "moss_block", "&2&lOvergrown", ["&74 teams • up to 4 each", "&7Mossy jungle ruins.", "", "&e▶ Click to join"], ["[close]", "bw join overgrown"]),
        (31, "nether_star", "&e&lQuick join", ["&7Joins whichever map is filling up."], ["[close]", "bw join"]),
        (40, "oak_door", "&cLeave game", ["&7/bw leave"], ["[close]", "bw leave"]),
        back(),
    ]),
    "hideseek": dict(title="&8» &d&lHide & Seek", commands=["hsmenu"], size=54, border="purple", items=[
        (20, "hay_block", "&a&lVillage", ["&7Classic plains village.", "&7Daylight, lots of houses.", "", "&e▶ Click to join"], ["[close]", "hs join village"]),
        (22, "dark_oak_log", "&4&lHaunted Mansion", ["&7A real woodland mansion at", "&7midnight. 3 floors of rooms.", "", "&e▶ Click to join"], ["[close]", "hs join mansion"]),
        (24, "sculk", "&3&lDeep Dark", ["&7Ancient city far underground.", "&cDon't wake the Warden...", "", "&e▶ Click to join"], ["[close]", "hs join deepdark"]),
        (40, "oak_door", "&cLeave game", ["&7/hs leave"], ["[close]", "hs leave"]),
        back(),
    ]),
    "pvp": dict(title="&8» &6&lPvP Modes", commands=["pvpmenu"], size=54, border="orange", items=[
        (19, "diamond_sword", "&6&lArena Rounds", ["&7Last one standing wins.", "&7Random map every round (10 maps)", "&7and a random kit theme for all:", "&7Pirates, Mace & Wind, Cowboys...", "&7Rematch vote after each round!", "", "&e▶ Click to fight"], ["[close]", "go arena"]),
        (28, "red_banner", "&c&lTeam Battle", ["&7Red vs Blue, teams auto-balance.", "&7Random map & kit, friendly fire off.", "", "&e▶ Click to fight"], ["[close]", "go teams"]),
        (20, "slime_ball", "&f&lSumo", ["&7Rounds, fists only. Knock", "&7everyone off the platform!", "", "&e▶ Click to play"], ["[close]", "go sumo"]),
        (21, "diamond_shovel", "&b&lSpleef", ["&7Rounds: dig the snow under", "&7your foes, last one up wins.", "", "&e▶ Click to play"], ["[close]", "go spleef"]),
        (22, "stick", "&e&lKnockback FFA", ["&7Free-for-all, Knockback III sticks.", "&7Kills & streaks on the leaderboard.", "", "&e▶ Click to play"], ["[close]", "go kbffa"]),
        (23, "iron_sword", "&c&l1v1 Duels", ["&7Challenge a friend:", "&b/duel <name>", "&7They accept with &b/duel accept"], ["[close]", "duel"]),
        (24, "bow", "&a&lBedWars", ["&7Team PvP: protect your bed."], ["[openguimenu] bedwars"]),
        (25, "rotten_flesh", "&2&lInfection", ["&7Survivors vs the infected.", "&7Join: &b/infection join mansion"], ["[close]", "infection join mansion"]),
        back(),
    ]),
    "horror": dict(title="&8» &4&lHorror", commands=["horror"], size=54, border="black", items=[
        (13, "wither_skeleton_skull", "&4&lNIGHTFALL", ["&7Horror party games in a pitch-black", "&7manor: The Impostor, Blackout Escape,", "&7Soul Candles, Silent Relics.", "&7A random game every round!", "", "&e▶ Click to enter"], ["[close]", "go nightfall"]),
        (21, "dark_oak_log", "&5&lHaunted Mansion", ["&7Hide & Seek in a mansion", "&7at midnight.", "", "&e▶ Click to join"], ["[close]", "hs join mansion"]),
        (23, "sculk_shrieker", "&3&lDeep Dark", ["&7Hide & Seek in an ancient city.", "&7The Warden is listening.", "", "&e▶ Click to join"], ["[close]", "hs join deepdark"]),
        (24, "zombie_head", "&2&lInfection", ["&7One alpha zombie hunts the rest.", "&7Every kill makes a new zombie.", "", "&e▶ Click to join"], ["[close]", "infection join mansion"]),
        back(),
    ]),
    "admin": dict(title="&8» &4&lADMIN PANEL", commands=["admin", "panel"], size=54, border="red", perm="najurs.admin", items=[
        (9, "iron_sword", "&aSurvival mode", [], ["gamemode survival"]),
        (17, "grass_block", "&bCreative mode", [], ["gamemode creative"]),
        (18, "map", "&eAdventure mode", [], ["gamemode adventure"]),
        (44, "ender_eye", "&7Spectator mode", [], ["gamemode spectator"]),
        (26, "elytra", "&fToggle fly", [], ["fly"]),
        (27, "golden_apple", "&cHeal & feed", [], ["heal", "feed"]),
        (36, "glass", "&7Toggle vanish", [], ["vanish"]),
        (10, "grass_block", "&a&lSurvival World Manager", ["&7Create, delete, rename worlds,", "&7toggle Random Drops / Mob Morph / Horror.", "&8Shift-click a world to manage it."], ["[close]", "worlds"]),
        (11, "sunflower", "&eDay here", [], ["minecraft:time set day"]),
        (12, "clock", "&9Night here", [], ["minecraft:time set midnight"]),
        (13, "blue_ice", "&bClear weather", [], ["minecraft:weather clear"]),
        (14, "player_head", "&fWho is online", [], ["[close]", "list"]),
        (15, "writable_book", "&aSave all worlds", [], ["@save-all", "[message] &aWorlds saved."]),
        (16, "repeater", "&eReload menus", [], ["[close]", "dm reload"]),
        # Bring everyone into a game
        (19, "red_bed", "&c&lSend ALL → BedWars: Inferno", ["&72 teams, best for small groups."], ["[close]", "send all bw join inferno"]),
        (20, "hay_block", "&d&lSend ALL → Hide & Seek: Village", [], ["[close]", "send all hs join village"]),
        (21, "dark_oak_log", "&5&lSend ALL → Haunted Mansion", [], ["[close]", "send all hs join mansion"]),
        (22, "sculk", "&3&lSend ALL → Deep Dark", [], ["[close]", "send all hs join deepdark"]),
        (23, "diamond_sword", "&6&lSend ALL → Arena Rounds", [], ["[close]", "send all go arena"]),
        (24, "slime_ball", "&f&lSend ALL → Sumo", [], ["[close]", "send all go sumo"]),
        (25, "diamond_shovel", "&b&lSend ALL → Spleef", [], ["[close]", "send all go spleef"]),
        (28, "stick", "&e&lSend ALL → Knockback FFA", [], ["[close]", "send all go kbffa"]),
        (29, "zombie_head", "&2&lSend ALL → Infection", [], ["[close]", "send all infection join mansion"]),
        (30, "beacon", "&b&lSend ALL → Hub", [], ["[close]", "send all spawn"]),
        (31, "chest", "&6&lSend ALL → Random Drops", [], ["[close]", "send all worlds join sw_random_drops"]),
        (32, "creeper_head", "&2&lSend ALL → Mob Morph", [], ["[close]", "send all worlds join sw_mob_morph"]),
        (35, "tnt", "&6&lSend ALL → Goose Chase", [], ["[close]", "send all go sw_goose_chase"]),
        (34, "feather", "&f&lSend ALL → Parkour", [], ["[close]", "send all go parkour"]),
        # Manage mode worlds (reset with same/new seed, pull everyone, delete)
        (1, "grass_block", "&a&lAll survival worlds", ["&7Create, reset, new seed, delete"], ["[close]", "worlds"]),
        (2, "chest", "&6&lManage: Random Drops", ["&7Reset, new seed, pull everyone, delete"], ["[close]", "worlds manage sw_random_drops"]),
        (3, "creeper_head", "&2&lManage: Mob Morph", ["&7Reset, new seed, pull everyone, delete"], ["[close]", "worlds manage sw_mob_morph"]),
        (6, "tnt", "&c&lManage: Goose Chase", ["&7Reset, new seed, pull everyone, delete"], ["[close]", "worlds manage sw_goose_chase"]),
        # Start / stop games
        (37, "tnt", "&cForce-start BedWars", ["&7Run while in the BedWars lobby."], ["[close]", "bw start"]),
        (38, "target", "&dForce-start Hide & Seek", [], ["[close]", "hs start"]),
        (39, "red_concrete", "&cStop Hide & Seek", [], ["[close]", "hs stop"]),
        (41, "filled_map", "&eSave H&S map: Village", ["&7Needed once after editing the map."], ["hs map save village"]),
        (42, "filled_map", "&5Save H&S map: Mansion", ["&7Needed once after editing the map."], ["hs map save mansion"]),
        (43, "filled_map", "&3Save H&S map: Deep Dark", ["&7Needed once after editing the map."], ["hs map save deepdark"]),
        # Teleports for editing maps
        (46, "red_wool", "&7TP: BedWars map", [], ["[close]", "mv tp bedwars"]),
        (47, "sandstone", "&7TP: Arena world", [], ["[close]", "go arena"]),
        (48, "deepslate_tiles", "&7TP: Duel arenas", ["&7Set up Duels here (see guide)."], ["[close]", tp("arena", 0.5, 64, -100.5)]),
        (50, "dark_oak_log", "&7TP: Horror maps world", [], ["[close]", tp("hs_horror", -6767.5, 111, -4671.5)]),
        (51, "hay_block", "&7TP: Village map", [], ["[close]", tp("hideseek", 27.5, 11, 215.5)]),
        (52, "arrow", "&7« Games menu", [], ["[openguimenu] games"]),
    ]),
}

BORDER_SLOTS = {54: "[0-8, 9, 17, 18, 26, 27, 35, 36, 44, 45-53]"}

def q(s): return json.dumps(s, ensure_ascii=False)

def render(name, m):
    out = [f"# Generated by tools/build/menus.py - edit that file, not this one.", f"menu_title: {q(m['title'])}"]
    if m["commands"]: out.append(f"open_command: [{', '.join(m['commands'])}]")
    out.append(f"size: {m['size']}")
    if m.get("perm"):
        out += ["open_requirement:", "  requirements:", "    perm:", "      type: has permission", f"      permission: {m['perm']}",
                "  deny_commands: ['[message] &cOwner only.']"]
    out += ["items:", "  border:", f"    material: {m['border']}_stained_glass_pane", f"    slots: {BORDER_SLOTS[m['size']]}", "    display_name: ' '", "    priority: 99"]
    for i, (slot, mat, title, lore, cmds) in enumerate(m["items"]):
        out += [f"  i{i}:", f"    material: {mat}", f"    slot: {slot}", "    priority: 1", f"    display_name: {q(title)}"]
        if lore: out.append(f"    lore: [{', '.join(q(l) for l in lore)}]")
        out.append("    item_flags: [HIDE_ATTRIBUTES]")
        if cmds:
            actions = [c if c.startswith("[") else f"[console] {c[1:]}" if c.startswith("@") else f"[player] {c}" for c in cmds]
            out.append(f"    left_click_commands: [{', '.join(q(a) for a in actions)}]")
            out.append(f"    right_click_commands: [{', '.join(q(a) for a in actions)}]")
    return "\n".join(out) + "\n"

for name, m in MENUS.items():
    open(os.path.join(OUT, f"{name}.yml"), "w").write(render(name, m))
cfg = os.path.join(OUT, "..", "config.yml")
lines = ["check_updates: true", "debug: LOW", "use_admin_commands_in_menus_list: false", "menus_list_page_size: 10",
         "metas_list_page_size: 15", "gui_menus:"] + [f"  {n}:\n    file: {n}.yml" for n in MENUS]
open(cfg, "w").write("\n".join(lines) + "\n")
print("wrote", ", ".join(MENUS))
