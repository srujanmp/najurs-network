#!/bin/bash
# Re-download Paper and every plugin at their newest builds for a Minecraft version. Run with the server stopped.
# Usage: update-plugins.sh [minecraft-version]   e.g. update-plugins.sh 26.4   (default: the version below)
# ViaVersion/ViaBackwards/ViaRewind let newer and older clients (down to 1.7) join, so players don't have to match the server exactly.
# Afterwards: test, then `tools/najurs.py publish` pins the new versions in tools/plugins.lock for everyone.
set -euo pipefail
MC=${1:-26.3}
cd "$(dirname "$0")/../server"
mkdir -p plugins
MODRINTH=(luckperms vaultunlocked multiverse-core multiverse-portals multiverse-inventories multiverse-netherportals worldedit worldguard
  bentobox placeholderapi deluxemenus screamingbedwars kenshins-hide-and-seek tab-was-taken chunky fancynpcs packetevents
  duels-optimised infection-minigame
  authmereloaded skinsrestorer viaversion viabackwards viarewind)
ESSX=(EssentialsX EssentialsXSpawn EssentialsXChat)
BENTO=(BSkyBlock AOneBlock Level Challenges Warps)

mkdir -p .najurs
# remember where each jar came from so `najurs.py lock` can pin it afterwards
fetch() { curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2" && printf '%s %s\n' "$2" "$1" >> .najurs/urls.tsv; }

url=$(curl -fsSL https://fill.papermc.io/v3/projects/paper/versions/$MC/builds/latest | python3 -c "import json,sys;print(json.load(sys.stdin)['downloads']['server:default']['url'])")
fetch "$url" paper.jar && echo "paper  $(basename "$url")"

for slug in "${MODRINTH[@]}"; do
  read -r url name < <(curl -fsSL -G "https://api.modrinth.com/v2/project/$slug/version" \
      --data-urlencode 'loaders=["paper"]' --data-urlencode "game_versions=[\"$MC\"]" |
    python3 -c "import json,sys;d=json.load(sys.stdin);v=([x for x in d if x['version_type']=='release'] or d)[0];f=[x for x in v['files'] if x['primary']] or v['files'];print(f[0]['url'],f[0]['filename'].replace(' ','_'))")
  rm -f plugins/"$slug"--*.jar; fetch "$url" "plugins/$slug--$name" && echo "$slug  $name"
done

tag=$(gh api repos/EssentialsX/Essentials/releases/latest --jq .tag_name)
for n in "${ESSX[@]}"; do rm -f plugins/$n-*.jar; fetch "https://github.com/EssentialsX/Essentials/releases/download/$tag/$n-$tag.jar" "plugins/$n-$tag.jar" && echo "$n $tag"; done

url=$(gh api repos/libraryaddict/LibsDisguises/releases/latest --jq '.assets[0].browser_download_url')
rm -f plugins/LibsDisguises-*.jar; fetch "$url" "plugins/$(basename "$url")" && echo "LibsDisguises $(basename "$url")"

mkdir -p plugins/BentoBox/addons
for r in "${BENTO[@]}"; do
  url=$(gh api repos/BentoBoxWorld/$r/releases/latest --jq '.assets[0].browser_download_url')
  rm -f plugins/BentoBox/addons/$r-*.jar; fetch "$url" "plugins/BentoBox/addons/$(basename "$url")" && echo "$r $(basename "$url")"
done
