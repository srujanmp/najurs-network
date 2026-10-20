#!/usr/bin/env python3
"""NAJURS NETWORK server manager (Python 3.8+, standard library only).

  najurs.py setup      before boot: Java, Paper + plugins (tools/plugins.lock), NajursCore build, configs, owner, EULA
  najurs.py provision  after boot (run.sh starts it): permissions, missing worlds, map builds, one-off setup steps
  najurs.py publish    owner: copy the live server's configs into config/ and refresh the plugin lock (then commit)
  najurs.py lock       rewrite tools/plugins.lock from the jars installed in server/
  najurs.py adopt      mark an existing, already-built server as up to date (nothing gets rebuilt or overwritten)

The repo holds no world or player data. Worlds are regenerated from the seeds in Multiverse's worlds.yml, maps are
rebuilt by tools/build/*.py, and each build re-runs only when its script changes, so `git pull && ./start.sh` updates
an existing server in place.
"""
import hashlib, io, json, os, platform, re, secrets, shutil, subprocess, sys, tarfile, time, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "server")
CONFIG = os.path.join(ROOT, "config", "server")
TOOLS = os.path.join(ROOT, "tools")
STATE_DIR = os.path.join(SERVER, ".najurs")
STATE_FILE = os.path.join(STATE_DIR, "state.json")
DIMS = os.path.join(SERVER, "lobby", "dimensions", "minecraft")
UA = {"User-Agent": "najurs-network-setup"}
JAVA_MAJOR = 25

# ---------- which server files are configuration (tracked) ----------

CONFIG_EXT = (".yml", ".yaml", ".json", ".txt", ".properties", ".conf", ".toml", ".html", ".sh", ".blu")  # .blu: BentoBox island blueprints
ROOT_FILES = {"bukkit.yml", "spigot.yml", "commands.yml", "help.yml", "permissions.yml", "wepif.yml", "start.sh",
              "config/paper-global.yml", "config/paper-world-defaults.yml"}
EXCLUDE_DIRS = ("database", "database_backup", "userdata", "userdata-npc-backup", "players", "playerdata", "cache", "logs",
                "tmp", "libs", "translations", "skins", "SavedSkins", "Skins", "cooldowns", "internal", "users", "locales",
                "languages", "expansions", "tasks")
EXCLUDE_FILES = {
    "plugins/NajursCore/data.yml", "plugins/BedWars/record.yml", "plugins/Multiverse-Inventories/playernames.json",
    "plugins/TAB/playerdata.yml", "plugins/TAB/users.yml", "plugins/TAB/skincache.yml", "plugins/SkinsRestorer/recommendations.json",
    "plugins/Essentials/upgrades-done.yml", "plugins/FancyNpcs/version.yml", "plugins/KenshinsHideAndSeek/worlds.yml",
    "plugins/NajursCore/pending-op-commands.txt",
}
EXCLUDE_PLUGINS = ("bStats", "faststats", "FancyAnalytics", "spark", "CustomEntity")
# Multiverse-Inventories keeps per-world/per-group inventories in these folders
MVINV_DATA = ("plugins/Multiverse-Inventories/groups/", "plugins/Multiverse-Inventories/worlds/",
              "plugins/WorldGuard/worlds/")  # WorldGuard's per-world files are generated defaults (no custom regions)
LOCAL_KEYS = ("rcon.password", "rcon.port", "server-port", "server-ip", "query.port", "management-server-secret")  # never shared
PROPS_DEFAULTS = {"rcon.password": "", "rcon.port": "25575", "server-port": "25565", "server-ip": "", "query.port": "25565",
                 "management-server-secret": ""}

def is_config(rel):
    rel = rel.replace(os.sep, "/")
    if rel in ROOT_FILES: return True
    if not rel.startswith("plugins/") or rel.endswith(".jar") or rel in EXCLUDE_FILES: return False
    parts = rel.split("/")
    if len(parts) < 3 or parts[1] in EXCLUDE_PLUGINS: return False
    if any(rel.startswith(p) for p in MVINV_DATA): return False
    if any(p in EXCLUDE_DIRS or p.startswith(".") for p in parts[2:-1]): return False
    name = parts[-1]
    if re.search(r"\.(bak\d*|orig|log|db|bin|old|part)$", name) or name.endswith(".mv.db"): return False
    return name.endswith(CONFIG_EXT)

def server_config_files():
    out = []
    for d, dirs, files in os.walk(SERVER):
        rd = os.path.relpath(d, SERVER).replace(os.sep, "/")
        if rd.split("/")[0] in ("lobby", "libraries", "versions", "cache", "logs", ".najurs", "crash-reports"): dirs[:] = []; continue
        for f in files:
            rel = f if rd == "." else f"{rd}/{f}"
            if is_config(rel): out.append(rel)
    return sorted(out)

# ---------- helpers ----------

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def tree_hash(*paths):
    h = hashlib.sha256()
    for p in paths:
        p = os.path.join(ROOT, p)
        files = [p] if os.path.isfile(p) else sorted(os.path.join(d, f) for d, _, fs in os.walk(p) for f in fs if "/out/" not in os.path.join(d, f) + "/" and "__pycache__" not in d)
        for f in files:
            h.update(os.path.relpath(f, ROOT).encode()); h.update(open(f, "rb").read())
    return h.hexdigest()

def load_state():
    try: return json.load(open(STATE_FILE))
    except (OSError, ValueError): return {}

def save_state(st):
    os.makedirs(STATE_DIR, exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    json.dump(st, open(tmp, "w"), indent=1, sort_keys=True)
    os.replace(tmp, STATE_FILE)

def say(msg): print(f"[najurs] {msg}", flush=True)

def download(url, dest, want_sha=None):
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    tmp = dest + ".part"
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f)
            break
        except OSError as e:
            if attempt == 2: raise SystemExit(f"download failed: {url}: {e}")
            time.sleep(3)
    if want_sha and sha256(tmp) != want_sha:
        os.remove(tmp); raise SystemExit(f"checksum mismatch for {url} - refusing to install it")
    os.replace(tmp, dest)

def offline_uuid(name):
    """The UUID an offline-mode server gives a player name (same as Bukkit's OfflinePlayer)."""
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30; h[8] = (h[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))

def read_props(path):
    props, order = {}, []
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.rstrip("\n")
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1); props[k] = v; order.append(k)
    return props, order

def write_props(path, props, order):
    with open(path, "w", encoding="utf-8") as f:
        f.write("# Generated by tools/najurs.py from config/server/server.properties.example + your local keys.\n")
        for k in order: f.write(f"{k}={props[k]}\n")

# ---------- Java ----------

def java_major(java):
    try:
        out = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30).stderr
        m = re.search(r'version "(\d+)', out)
        return int(m.group(1)) if m else 0
    except OSError:
        return 0

def find_jdk():
    """A JDK (java + javac) of version >= 25: tools/jdk, $JAVA_HOME, or the one on PATH."""
    cands = [os.path.join(TOOLS, "jdk")]
    if os.environ.get("JAVA_HOME"): cands.append(os.environ["JAVA_HOME"])
    j = shutil.which("java")
    if j: cands.append(os.path.dirname(os.path.dirname(os.path.realpath(j))))
    for home in cands:
        b = os.path.join(home, "bin")
        if os.path.exists(os.path.join(b, "javac")) and java_major(os.path.join(b, "java")) >= JAVA_MAJOR: return b
    return None

def install_jdk():
    osname = {"Linux": "linux", "Darwin": "mac"}.get(platform.system())
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "aarch64", "arm64": "aarch64"}.get(platform.machine().lower())
    if not osname or not arch: raise SystemExit("Please install a Java 25 JDK yourself (no automatic download for this OS).")
    url = f"https://api.adoptium.net/v3/binary/latest/{JAVA_MAJOR}/ga/{osname}/{arch}/jdk/hotspot/normal/eclipse"
    say(f"downloading Java {JAVA_MAJOR} (Eclipse Temurin) ...")
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=600).read()
    dest = os.path.join(TOOLS, "jdk")
    shutil.rmtree(dest, ignore_errors=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        top = t.getmembers()[0].name.split("/")[0]
        t.extractall(os.path.join(TOOLS, ".jdk-tmp"))
    src = os.path.join(TOOLS, ".jdk-tmp", top)
    if osname == "mac": src = os.path.join(src, "Contents", "Home")
    shutil.move(src, dest); shutil.rmtree(os.path.join(TOOLS, ".jdk-tmp"), ignore_errors=True)
    return os.path.join(dest, "bin")

# ---------- setup steps ----------

def install_jars(st):
    lock = [l.split() for l in open(os.path.join(TOOLS, "plugins.lock")) if l.strip() and not l.startswith("#")]
    wanted = set()
    for sha, rel, url in lock:
        dest = os.path.join(SERVER, rel)
        wanted.add(rel)
        if os.path.exists(dest) and sha256(dest) == sha: continue
        say(f"downloading {rel}")
        download(url, dest, sha)
    for rel in st.get("jars", []):  # a plugin whose pinned version changed: drop the old jar
        if rel not in wanted and os.path.exists(os.path.join(SERVER, rel)):
            say(f"removing old {rel}"); os.remove(os.path.join(SERVER, rel))
    st["jars"] = sorted(wanted)

def build_plugin(st, jdk):
    jar = os.path.join(SERVER, "plugins", "NajursCore.jar")
    h = tree_hash("plugin-src/NajursCore/src", "plugin-src/NajursCore/build.sh", "tools/plugins.lock")
    if st.get("plugin") == h and os.path.exists(jar): return
    if not os.path.isdir(os.path.join(SERVER, "libraries")):
        say("unpacking Paper's libraries ...")
        subprocess.run([os.path.join(jdk, "java"), "-Dpaperclip.patchonly=true", "-jar", "paper.jar"], cwd=SERVER, check=True,
                       stdout=subprocess.DEVNULL)
    say("building NajursCore ...")
    subprocess.run(["bash", "build.sh"], cwd=os.path.join(ROOT, "plugin-src", "NajursCore"), check=True,
                   env=dict(os.environ, JDK=jdk), stdout=subprocess.DEVNULL)
    st["plugin"] = h

def sync_configs(st):
    """Copy config/server/** into server/, but only files that changed upstream since the last sync,
    so local edits survive until the same file is changed in the repo."""
    synced = st.setdefault("sync", {})
    seen = set()
    for d, _, files in os.walk(CONFIG):
        for f in files:
            src = os.path.join(d, f)
            rel = os.path.relpath(src, CONFIG).replace(os.sep, "/")
            if rel == "server.properties.example": continue
            seen.add(rel)
            dest = os.path.join(SERVER, rel)
            h = sha256(src)
            if synced.get(rel) == h and os.path.exists(dest): continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(src, dest)
            synced[rel] = h
    for rel in [r for r in synced if r not in seen]:  # removed upstream
        p = os.path.join(SERVER, rel)
        if os.path.exists(p): os.remove(p)
        del synced[rel]

def server_properties(st):
    tpl, order = read_props(os.path.join(CONFIG, "server.properties.example"))
    path = os.path.join(SERVER, "server.properties")
    local, _ = read_props(path)
    h = sha256(os.path.join(CONFIG, "server.properties.example"))
    if st.get("props") == h and local.get("rcon.password"): return
    props = dict(tpl)
    for k in LOCAL_KEYS:
        if local.get(k): props[k] = local[k]
    if os.environ.get("NAJURS_PORT"): props["server-port"] = os.environ["NAJURS_PORT"]
    if os.environ.get("NAJURS_RCON_PORT"): props["rcon.port"] = os.environ["NAJURS_RCON_PORT"]
    if not props.get("rcon.password"): props["rcon.password"] = secrets.token_urlsafe(18)
    for k in LOCAL_KEYS:
        if k not in order: order.append(k)
    write_props(path, props, order)
    os.chmod(path, 0o600)
    st["props"] = h

def owner(st, interactive):
    ops = os.path.join(SERVER, "ops.json")
    if st.get("owner") and os.path.exists(ops): return
    name = os.environ.get("NAJURS_OWNER", "").strip()
    if not name and interactive:
        name = input("Your Minecraft username (you become the server owner/op): ").strip()
    if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", name or ""):
        say("no owner set - run again with NAJURS_OWNER=<your name> to become op"); return
    json.dump([{"uuid": offline_uuid(name), "name": name, "level": 4, "bypassesPlayerLimit": True}], open(ops, "w"), indent=2)
    st["owner"] = name
    say(f"owner: {name}")

def eula(interactive):
    path = os.path.join(SERVER, "eula.txt")
    if os.path.exists(path) and "eula=true" in open(path).read(): return
    ok = os.environ.get("NAJURS_ACCEPT_EULA") == "1"
    if not ok and interactive:
        ok = input("Do you accept the Minecraft EULA (https://aka.ms/MinecraftEULA)? [y/N] ").strip().lower() in ("y", "yes")
    if not ok: raise SystemExit("You must accept the Minecraft EULA to run the server (or set NAJURS_ACCEPT_EULA=1).")
    open(path, "w").write("# Accepted via tools/najurs.py\neula=true\n")

# ---------- Multiverse worlds ----------

MAIN_DIMS = {"overworld", "the_nether", "the_end"}       # the server makes these itself
SELF_MADE = ("bskyblock_world", "oneblock_world")         # BentoBox makes these (and their _nether/_the_end)

def mv_blocks(text):
    """Split Multiverse-Core/worlds.yml into {key: block text} (top-level keys like 'minecraft:arena')."""
    blocks, key, buf = {}, None, []
    for line in text.splitlines(keepends=True):
        if line and not line[0].isspace() and line.rstrip().endswith(":") and not line.startswith("#"):
            if key: blocks[key] = "".join(buf)
            key, buf = line.rstrip()[:-1], [line]
        elif key: buf.append(line)
    if key: blocks[key] = "".join(buf)
    return blocks

def stash_missing_worlds(st):
    """Multiverse must not see worlds whose folder doesn't exist yet: park their entries until provision creates them."""
    path = os.path.join(SERVER, "plugins", "Multiverse-Core", "worlds.yml")
    if not os.path.exists(path): return
    blocks = mv_blocks(open(path).read())
    pending = st.setdefault("pending_worlds", {})
    for key, block in list(blocks.items()):
        name = key.split(":", 1)[-1]
        if name in MAIN_DIMS: continue
        if not os.path.isdir(os.path.join(DIMS, name)):
            pending[key] = block; del blocks[key]
        elif key in pending:          # created last boot: restore the tracked settings over Multiverse's defaults
            blocks[key] = pending.pop(key)
    for key in [k for k in pending if os.path.isdir(os.path.join(DIMS, k.split(":", 1)[-1]))]:
        blocks[key] = pending.pop(key)
    open(path, "w").write("".join(blocks.values()))

def setup(interactive):
    st = load_state()
    os.makedirs(SERVER, exist_ok=True)
    jdk = find_jdk() or install_jdk()
    st["jdk"] = jdk
    install_jars(st)
    sync_configs(st)
    server_properties(st)
    eula(interactive)
    owner(st, interactive)
    build_plugin(st, jdk)
    stash_missing_worlds(st)
    save_state(st)
    say("setup done")

# ---------- provision (server running) ----------

BUILDS = [  # name, files whose change triggers a rebuild, command producing RCON commands, worlds it needs
    ("hub", ["tools/build/hub.py"], ["hub.py"], ["overworld"]),
    ("arena", ["tools/build/arena.py"], ["arena.py"], ["arena"]),
    ("arena_modes", ["tools/build/arena_modes.py"], ["arena_modes.py"], ["arena"]),
    ("pvp_maps", ["tools/build/pvp_maps.py"], ["pvp_maps.py"], ["arena"]),
    ("bedwars", ["tools/build/bedwars.py"], ["bedwars.py", "commands", "all"], ["bedwars"]),
    ("parkour", ["tools/build/parkour.py"], ["parkour.py", "commands"], ["overworld"]),
    ("nightfall", ["tools/build/nightfall.py"], ["nightfall.py", "commands"], ["nightfall"]),
    ("holograms", ["tools/build/holograms.py"], ["holograms.py"], ["overworld"]),
    ("extras", ["tools/build/extras.py"], ["extras.py"], ["hideseek", "hs_horror", "arena"]),
]
HS_MAPS = {"village": "hs2_village", "mansion": "hs2_mansion", "deepdark": "hs2_deepdark"}

def wait_rcon(timeout=900):
    sys.path.insert(0, TOOLS)
    from rcon import Rcon
    end = time.time() + timeout
    while time.time() < end:
        try:
            r = Rcon()
            if "maximum" in r.run("list"): return r
        except Exception:  # not listening yet, or still starting up
            pass
        time.sleep(5)
    raise SystemExit("server did not come up for provisioning")

def world_loaded(r, name):
    out = re.sub("§.", "", r.run(f"execute in minecraft:{name} run minecraft:time query gametime"))  # Essentials owns plain `time`
    return "Unknown dimension" not in out and "time is" in out

def restart(r, why):
    say(f"restarting the server: {why}")
    open(os.path.join(STATE_DIR, "restart"), "w").write(why)
    r.run("save-all")
    r.run("stop")

def provision():
    st = load_state()
    r = wait_rcon()
    say("server is up - provisioning")
    # 1. permission groups and the owner
    lp = os.path.join(TOOLS, "provision", "luckperms-groups.json.gz")
    if os.path.exists(lp) and st.get("lp") != sha256(lp):
        shutil.copy(lp, os.path.join(SERVER, "plugins", "LuckPerms", "najurs-groups.json.gz"))
        r.run("lp import najurs-groups.json.gz"); time.sleep(8)
        st["lp"] = sha256(lp)
    if st.get("owner") and not st.get("owner_lp"):
        r.run(f"lp user {offline_uuid(st['owner'])} parent add owner"); st["owner_lp"] = True
    save_state(st)
    # 2. worlds that exist in the config but not on disk yet
    created = []
    types = najurs_world_types()
    for key, block in list(st.get("pending_worlds", {}).items()):
        name = key.split(":", 1)[-1]
        if name.startswith("hs2_") or name.startswith(SELF_MADE) or world_loaded(r, name): continue
        env = re.search(r"environment: (\w+)", block).group(1)
        seed = re.search(r"seed: (-?\d+)", block)
        gen = re.search(r"generator-settings: '(.+)'", block)
        args = f"mv create {name} {env}" + (f" --seed {seed.group(1)}" if seed else "")
        base = re.sub(r"_(nether|the_end)$", "", name)
        if gen and gen.group(1).strip("{} "):
            args += f" --world-type flat --generator-settings {gen.group(1)}"
        elif env == "normal" and types.get(base) in ("amplified", "large_biomes", "flat"):
            args += f" --world-type {types[base]}"
        say(f"creating world {name}")
        r.run(args); created.append(name)
    for name in created:
        for _ in range(60):
            if world_loaded(r, name): break
            time.sleep(2)
    if created:
        save_state(st)
        restart(r, f"created {len(created)} worlds; reloading so every plugin sees them")
        return
    # 3. map builds (each only when its script changed)
    builds = st.setdefault("builds", {})
    for name, deps, cmd, worlds in BUILDS:
        h = tree_hash(*deps)
        if builds.get(name) == h: continue
        if not all(w == "overworld" or world_loaded(r, w) for w in worlds):
            say(f"skipping {name} build: its world isn't there yet"); continue
        say(f"building {name} ...")
        out = subprocess.run([sys.executable] + [os.path.join(TOOLS, "build", cmd[0])] + cmd[1:], capture_output=True, text=True, check=True,
                             cwd=os.path.join(TOOLS, "build")).stdout
        for line in out.splitlines():
            if line.strip() and not line.startswith("#"): r.run(line)
        builds[name] = h
        save_state(st)
    # 4. co-op Skyblock / OneBlock worlds regrown by Multiverse still need their starting island
    voids = st.setdefault("voids", [])
    for wid, t in najurs_world_types().items():
        if t in ("skyblock", "oneblock") and wid not in voids and world_loaded(r, wid):
            say(f"building the starting island of {wid}")
            r.run(f"worlds setupvoid {wid}"); voids.append(wid)
    save_state(st)
    # 5. Hide & Seek map copies need an in-game op (the first op to join runs them)
    missing = [m for m, w in HS_MAPS.items() if not os.path.isdir(os.path.join(DIMS, w))]
    pending = os.path.join(SERVER, "plugins", "NajursCore", "pending-op-commands.txt")
    if missing and not os.path.exists(pending):
        open(pending, "w").write("# run by the first op who joins (Hide & Seek map copies)\n" + "".join(f"hs map save {m}\n" for m in missing))
        say("Hide & Seek maps get prepared when the first op joins the game")
    save_state(st)
    say("provisioning done")

def najurs_world_types():
    """id -> type from NajursCore/config.yml (worlds: <id>: type: ...)."""
    out, cur = {}, None
    try:
        for line in open(os.path.join(SERVER, "plugins", "NajursCore", "config.yml")):
            m = re.match(r"^  (\w+):\s*$", line)
            if m: cur = m.group(1)
            t = re.match(r"^    type: (\w+)", line)
            if t and cur: out[cur] = t.group(1)
    except OSError:
        pass
    return out

# ---------- owner tools ----------

def publish():
    """Copy every config file from the running server into config/server/ (and drop ones that disappeared)."""
    st = load_state()
    files = server_config_files()
    synced = st.setdefault("sync", {})
    for rel in files:
        src, dest = os.path.join(SERVER, rel), os.path.join(CONFIG, rel)
        if not os.path.exists(dest) or sha256(src) != sha256(dest):
            os.makedirs(os.path.dirname(dest), exist_ok=True); shutil.copy2(src, dest); say(f"updated {rel}")
        synced[rel] = sha256(dest)
    keep = set(files)
    for d, _, fs in os.walk(CONFIG):
        for f in fs:
            rel = os.path.relpath(os.path.join(d, f), CONFIG).replace(os.sep, "/")
            if rel != "server.properties.example" and rel not in keep:
                os.remove(os.path.join(d, f)); synced.pop(rel, None); say(f"removed {rel}")
    props, order = read_props(os.path.join(SERVER, "server.properties"))
    for k in LOCAL_KEYS:
        if k in props: props[k] = PROPS_DEFAULTS[k]
    write_props(os.path.join(CONFIG, "server.properties.example"), props, order)
    st["props"] = sha256(os.path.join(CONFIG, "server.properties.example"))
    lock()
    save_state(st)

def lock():
    """Pin every installed jar by sha256, keeping the URLs of jars that didn't change."""
    path = os.path.join(TOOLS, "plugins.lock")
    old = {l.split()[0]: l.split()[2] for l in open(path) if l.strip() and not l.startswith("#")} if os.path.exists(path) else {}
    jars = ["paper.jar"] + sorted(os.path.relpath(os.path.join(d, f), SERVER) for d, _, fs in os.walk(os.path.join(SERVER, "plugins"))
                                  for f in fs if f.endswith(".jar") and f != "NajursCore.jar" and "/libs" not in d and "LibsDisguises/" not in d)
    fetched = {}  # path -> url, recorded by tools/update-plugins.sh
    if os.path.exists(os.path.join(STATE_DIR, "urls.tsv")):
        for line in open(os.path.join(STATE_DIR, "urls.tsv")):
            if " " in line.strip(): k, v = line.strip().split(" ", 1); fetched[k] = v
    rows, unknown = [], []
    for rel in jars:
        h = sha256(os.path.join(SERVER, rel))
        url = old.get(h) or fetched.get(rel) or modrinth_url(os.path.join(SERVER, rel))
        if not url: unknown.append(rel); continue
        rows.append(f"{h}  {rel}  {url}")
    if unknown: raise SystemExit("no download URL known for: " + ", ".join(unknown) + " (add them to tools/plugins.lock by hand)")
    open(path, "w").write("# sha256  path-under-server/  url   (najurs.py lock)\n" + "\n".join(sorted(rows, key=lambda r: r.split()[1])) + "\n")
    say(f"plugins.lock: {len(rows)} jars")

def modrinth_url(path):
    sha1 = hashlib.sha1(open(path, "rb").read()).hexdigest()
    try:
        v = json.load(urllib.request.urlopen(urllib.request.Request(f"https://api.modrinth.com/v2/version_file/{sha1}?algorithm=sha1", headers=UA), timeout=30))
        return next(f["url"] for f in v["files"] if f["hashes"]["sha1"] == sha1)
    except (OSError, StopIteration, ValueError):
        return None

def adopt():
    """For a server that is already fully built: record everything as current."""
    st = load_state()
    st["sync"] = {rel: sha256(os.path.join(CONFIG, rel)) for rel in (os.path.relpath(os.path.join(d, f), CONFIG).replace(os.sep, "/")
                  for d, _, fs in os.walk(CONFIG) for f in fs) if rel != "server.properties.example"}
    st["props"] = sha256(os.path.join(CONFIG, "server.properties.example"))
    st["jars"] = [l.split()[1] for l in open(os.path.join(TOOLS, "plugins.lock")) if l.strip() and not l.startswith("#")]
    st["builds"] = {name: tree_hash(*deps) for name, deps, _, _ in BUILDS}
    st["plugin"] = tree_hash("plugin-src/NajursCore/src", "plugin-src/NajursCore/build.sh", "tools/plugins.lock")
    st["lp"] = sha256(os.path.join(TOOLS, "provision", "luckperms-groups.json.gz"))
    st["owner_lp"] = True
    st["voids"] = [w for w, t in najurs_world_types().items() if t in ("skyblock", "oneblock")]
    st.setdefault("pending_worlds", {})
    ops = json.load(open(os.path.join(SERVER, "ops.json"))) if os.path.exists(os.path.join(SERVER, "ops.json")) else []
    if ops: st["owner"] = ops[0]["name"]
    st["jdk"] = find_jdk()
    save_state(st)
    say("this server is now marked as up to date")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "setup"
    interactive = sys.stdin.isatty() and "--yes" not in sys.argv
    {"setup": lambda: setup(interactive), "provision": provision, "publish": publish, "lock": lock, "adopt": adopt}.get(
        cmd, lambda: sys.exit(__doc__))()
