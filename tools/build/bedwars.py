#!/usr/bin/env python3
"""Build BedWars maps in world `bedwars` and write their ScreamingBedWars arena files.

Usage: bedwars.py commands <map|all>     -> prints build commands (pipe into rcon.py)
       bedwars.py arena <map|all> <dir>  -> writes <dir>/<map>.yml (then `bw reload`)

Each map sits at its own x offset in the void world so arenas never overlap.
Bed locations are the bed HEAD block: ScreamingBedWars normalises a broken bed to its head before matching.
"""
import math, random, sys

W = "minecraft:bedwars"
Y = 64
ALL_TEAMS = [("Red", "RED", "red"), ("Blue", "BLUE", "blue"), ("Green", "LIME", "lime"), ("Yellow", "YELLOW", "yellow")]

MAPS = {
    "skylands": dict(cx=0, teams=4, team_r=52, mid_r=34, per_team=4,
                     top="grass_block", rock=["stone", "andesite", "cobblestone", "tuff"], sub="dirt",
                     floor="smooth_stone", tower="chiseled_stone_bricks", slab="stone_brick_slab", lamp="lantern",
                     mid_top="grass_block", side_top="grass_block"),
    "frostbite": dict(cx=400, teams=4, team_r=50, mid_r=32, per_team=4,
                      top="snow_block", rock=["packed_ice", "blue_ice", "ice", "calcite"], sub="snow_block",
                      floor="packed_ice", tower="blue_ice", slab="smooth_quartz_slab", lamp="sea_lantern",
                      mid_top="blue_ice", side_top="snow_block"),
    "inferno": dict(cx=800, teams=2, team_r=46, mid_r=24, per_team=5,
                    top="crimson_nylium", rock=["netherrack", "blackstone", "basalt", "magma_block"], sub="netherrack",
                    floor="polished_blackstone_bricks", tower="nether_bricks", slab="nether_brick_slab", lamp="shroomlight",
                    mid_top="warped_nylium", side_top="soul_soil"),
    "overgrown": dict(cx=1200, teams=4, team_r=54, mid_r=34, per_team=4,
                      top="moss_block", rock=["mud", "packed_mud", "mud_bricks", "rooted_dirt"], sub="rooted_dirt",
                      floor="mossy_stone_bricks", tower="mangrove_planks", slab="mangrove_slab", lamp="ochre_froglight",
                      mid_top="moss_block", side_top="grass_block"),
}

def loc(x, y, z, yaw=0.0, pitch=0.0): return f"{x};{y};{z};{yaw};{pitch}"
def yaw_towards(dx, dz): return math.degrees(math.atan2(-dx, dz))

def plan(name):
    m = MAPS[name]
    dirs = [(0, -1), (1, 0), (0, 1), (-1, 0)] if m["teams"] == 4 else [(0, -1), (0, 1)]
    teams = []
    for (tname, enum, col), (dx, dz) in zip(ALL_TEAMS, dirs):
        cx, cz = dx * m["team_r"], dz * m["team_r"]
        inward = (-dx, -dz)
        rx, rz = -inward[1], inward[0]
        p = lambda f, r: (cx + inward[0] * f + rx * r, cz + inward[1] * f + rz * r)
        teams.append(dict(name=tname, enum=enum, col=col, centre=(cx, cz), inward=inward,
                          spawn=p(0, 0), bed_foot=p(5, 0), bed_head=p(6, 0),
                          bronze=p(-5, 0), iron=p(-5, 2), shop=p(0, -5),
                          yaw=yaw_towards(*inward), shop_yaw=yaw_towards(rx, rz)))
    if m["teams"] == 4:
        sides = [(round(m["mid_r"] * math.cos(math.radians(a))), round(m["mid_r"] * math.sin(math.radians(a)))) for a in (45, 135, 225, 315)]
    else:  # duel map: iron islands either side of the lane
        sides = [(m["mid_r"], 0), (-m["mid_r"], 0)]
    return teams, sides

def commands(name):
    m = MAPS[name]
    random.seed(name)
    CX = m["cx"]
    cmds = []
    def c(s): cmds.append(f"execute in {W} run {s}")
    def fill(x1, y1, z1, x2, y2, z2, b, mode=""): c(f"fill {x1 + CX} {y1} {z1} {x2 + CX} {y2} {z2} {b} {mode}".strip())
    def setb(x, y, z, b): c(f"setblock {x + CX} {y} {z} {b}")
    def disc(cx, y, cz, r, block):
        for dz in range(-int(r), int(r) + 1):
            h = int(math.sqrt(max(r * r - dz * dz, 0)))
            fill(cx - h, y, cz + dz, cx + h, y, cz + dz, block)
    def island(cx, cz, r, top, depth=10):
        disc(cx, Y, cz, r, top)
        for i in range(1, depth):
            rr = r * (1 - i / depth) ** 0.8 + random.uniform(-0.8, 0.8)
            if rr >= 1:
                disc(cx, Y - i, cz, rr, m["sub"] if i < 3 else random.choice(m["rock"]))

    teams, sides = plan(name)
    c(f"forceload add {CX - 80} -80 {CX + 80} 80")
    for y in reversed(range(40, 106)):  # top-down so attached blocks (plates, ladders) never pop off as items  # one layer per fill keeps each under the 32768-block cap
        fill(-75, y, -75, 75, y, 75, "air")
    c("gamerule advance_time false"); c("gamerule advance_weather false"); c("gamerule spawn_mobs false")

    # waiting platform the arena lobby spawn points at
    fill(-6, 100, -6, 6, 100, 6, "white_stained_glass")
    fill(-6, 101, -6, 6, 103, 6, "barrier", "hollow")
    fill(-5, 101, -5, 5, 103, 5, "air")
    fill(-6, 104, -6, 6, 104, 6, "air")

    # centre: gold island with a tower
    island(0, 0, 10, m["mid_top"], 14)
    disc(0, Y, 0, 5, m["floor"])
    fill(-1, Y + 1, -1, 1, Y + 6, 1, m["tower"], "outline")
    fill(-2, Y + 7, -2, 2, Y + 7, 2, m["slab"])
    setb(0, Y + 8, 0, "gold_block")
    for x, z in ((4, 0), (-4, 0)):
        setb(x, Y, z, "gold_block")

    for x, z in sides:
        island(x, z, 5, m["side_top"], 8)
        setb(x, Y, z, "iron_block")
        setb(x + 2, Y + 1, z + 2, m["lamp"]); setb(x - 2, Y + 1, z - 2, m["lamp"])

    for t in teams:
        cx, cz = t["centre"]; col = t["col"]
        island(cx, cz, 9, m["top"], 12)
        disc(cx, Y, cz, 4, f"{col}_concrete")
        disc(cx, Y, cz, 2, m["floor"])
        facing = {(0, 1): "south", (0, -1): "north", (1, 0): "east", (-1, 0): "west"}[t["inward"]]
        (bx, bz), (hx, hz) = t["bed_foot"], t["bed_head"]
        setb(bx, Y + 1, bz, f"{col}_bed[facing={facing},part=foot]")
        setb(hx, Y + 1, hz, f"{col}_bed[facing={facing},part=head]")
        setb(t["bronze"][0], Y, t["bronze"][1], "copper_block")
        setb(t["iron"][0], Y, t["iron"][1], "iron_block")
        setb(t["shop"][0], Y, t["shop"][1], "emerald_block")
        for ox, oz in ((3, 3), (-3, 3), (3, -3), (-3, -3)):
            fill(cx + ox, Y + 1, cz + oz, cx + ox, Y + 2, cz + oz, f"{col}_stained_glass")
            setb(cx + ox, Y + 3, cz + oz, m["lamp"])
    return cmds

def arena(name, path):
    m = MAPS[name]
    CX = m["cx"]
    teams, sides = plan(name)
    out = [f"name: {name}", "world: bedwars",
           f"pos1: '{loc(CX - 75, 0, -75)}'", f"pos2: '{loc(CX + 75, 140, 75)}'",
           f"specSpawn: '{loc(CX + 0.5, Y + 20, 0.5, 0, 60)}'",
           f"lobbySpawn: '{loc(CX + 0.5, 101, 0.5)}'",  # glass waiting platform high above the centre
           "lobbySpawnWorld: bedwars", "minPlayers: 2", "pauseCountdown: 30", "gameTime: 3600",
           "postGameWaiting: 3", "customPrefix: ''", "arenaTime: DAY", "arenaWeather: CLEAR", "teams:"]
    for t in teams:
        sx, sz = t["spawn"]; hx, hz = t["bed_head"]
        out += [f"  {t['name']}:", f"    color: {t['enum']}", "    isNewColor: true", f"    maxPlayers: {m['per_team']}",
                f"    bed: '{loc(hx + CX, Y + 1, hz)}'", f"    spawn: '{loc(sx + CX + 0.5, Y + 1, sz + 0.5, t['yaw'])}'"]
    out.append("spawners:")
    def spawner(x, z, typ, team=None):
        out.extend([f"- location: '{loc(x + CX + 0.5, Y + 1, z + 0.5)}'", f"  type: {typ}", "  customName: null",
                    "  startLevel: 1.0", "  hologramEnabled: true", f"  team: {team if team else 'null'}",
                    "  maxSpawnedResources: -1"])
    for t in teams:
        spawner(*t["bronze"], "bronze", t["name"]); spawner(*t["iron"], "iron", t["name"])
    for x, z in sides:
        spawner(x, z, "iron")
    spawner(4, 0, "gold"); spawner(-4, 0, "gold")
    out.append("stores:")
    for t in teams:
        x, z = t["shop"]
        out += [f"- loc: '{loc(x + CX + 0.5, Y + 1, z + 0.5, t['shop_yaw'])}'", "  shop: null", "  parent: true",
                "  type: VILLAGER", "  name: '&a&lSHOP'", "  isBaby: false", "  skin: null"]
    out += ["constant: {}", "lobbyBossBarColor: default", "gameBossBarColor: default"]
    open(path, "w").write("\n".join(out) + "\n")

if __name__ == "__main__":
    names = list(MAPS) if sys.argv[2] == "all" else [sys.argv[2]]
    for n in names:
        if sys.argv[1] == "commands": print("\n".join(commands(n)))
        else: arena(n, f"{sys.argv[3]}/{n}.yml")
