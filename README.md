# NAJURS NETWORK

A ready-to-run Minecraft **26.3** (Paper) network for a group of friends: a sky-island hub, survival worlds with
crazy modes (Random Drops, Mob Morph, Goose Chase), co-op Skyblock and OneBlock, BedWars (4 maps), Hide & Seek,
round-based and team PvP on 10 maps, the **Nightfall** horror party games, parkour and creative. Vanilla clients,
no mods. Older and newer Minecraft versions can join through ViaVersion.

This repo contains **no world or player data**. The first start downloads the pinned server and plugins, regrows
every world from its seed and rebuilds every map from the scripts in `tools/build/`.

## Run it

Needs Linux or macOS (Windows: use WSL), **Python 3.8+**, ~6 GB free RAM, ~3 GB disk and an internet connection.
Java 25 is downloaded automatically if you don't have it.

```bash
git clone https://github.com/srujanmp/najurs-network.git
cd najurs-network
./start.sh
```

The first run asks for **your Minecraft username** (you become owner/op) and for the
[Minecraft EULA](https://aka.ms/MinecraftEULA). It then builds everything and restarts itself once (that takes a few
minutes). When the console says `provisioning done`, connect to `localhost` (friends use your public IP; forward
TCP port 25565 on your router).

**The first time you join**, register with `/register <password> <password>`. As the owner, stay online for about a
minute: the server finishes copying the Hide & Seek maps with your account (that step needs an in-game op).

Non-interactive (servers, panels):

```bash
NAJURS_OWNER=YourName NAJURS_ACCEPT_EULA=1 ./start.sh --yes
```

Optional settings: `MC_MEMORY=8G` (heap, default: half your RAM, 2-6 GB), `NAJURS_PORT` / `NAJURS_RCON_PORT` (first
run only), or put `MC_MEMORY=8G` in `server/local.env`.

## Update

```bash
git pull
./start.sh
```

Only what changed is redone: new or updated plugins are downloaded (checksums verified), the custom plugin is
rebuilt, changed configs are copied in, and a map whose build script changed is rebuilt. Your worlds, players,
islands and stats stay as they are. A config file you edited locally is kept until that same file changes upstream.

## How it works

| Path | What it is |
|---|---|
| `start.sh` | Install / update / run, in one command |
| `tools/najurs.py` | `setup` (before boot), `provision` (after boot), `publish`, `lock`, `adopt` |
| `tools/plugins.lock` | Exact Paper and plugin builds (sha256 + download URL) |
| `config/server/` | Every server and plugin config (no data, no secrets) |
| `tools/build/*.py` | Map builders: hub, PvP maps, BedWars, parkour, Nightfall manor, Hide & Seek lobbies... |
| `tools/provision/` | Permission groups (LuckPerms export, no users) |
| `plugin-src/NajursCore/` | The custom plugin: menus, game modes, PvP rounds, Nightfall, dashboard |
| `server/` | Created on first run: the actual server (git-ignored) |

Secrets are generated locally and never committed: the RCON password lives only in `server/server.properties`.
Player logins (AuthMe) are stored in `server/`.

## For the owner: publishing changes

Change things on your server (configs, worlds list, plugin code, build scripts), then:

```bash
tools/najurs.py publish     # copies the live configs into config/ and re-pins plugin versions
git add -A && git commit -m "..." && git push
```

Updating plugins: stop the server, `tools/update-plugins.sh`, start and test, then `publish`.

## Guide

Open `GUIDE.html` in a browser for every game, its rules and the owner tools. In game, the compass opens the menu.
