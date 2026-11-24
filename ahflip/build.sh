#!/usr/bin/env bash
# Builds Discordia without Fabric Loom.
#
# Minecraft 26.x ships unobfuscated and Mojang publishes no client_mappings for 26.2,
# so Loom cannot provision mappings and refuses to configure. Compiling directly against
# the client jar is both possible and simpler.
set -euo pipefail
cd "$(dirname "$0")"

MC_VERSION="26.2"
CLIENT_URL="https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar"
MC_META_URL="https://piston-meta.mojang.com/v1/packages/71e5b451a6b4d59fdbbbc6dcc4a4e9d32cacfe20/26.2.json"
LOADER_VERSION="0.20.1"
LOADER_URL="https://maven.fabricmc.net/net/fabricmc/fabric-loader/${LOADER_VERSION}/fabric-loader-${LOADER_VERSION}.jar"
MIXIN_URL="https://repo.spongepowered.org/repository/maven-public/org/spongepowered/mixin/0.8.7/mixin-0.8.7.jar"
GSON_URL="https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
MOD_VERSION="1.5.1"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
  *) CP_SEP=":" ;;
esac

mkdir -p .tooling libs build/classes build/jar build/libs

if [ ! -f ".tooling/client-${MC_VERSION}.jar" ]; then
  echo "Downloading Minecraft ${MC_VERSION} client..."
  curl -fL --retry 3 -o ".tooling/client-${MC_VERSION}.jar" "$CLIENT_URL"
fi

if [ ! -f "libs/fabric-loader.jar" ]; then
  echo "Downloading Fabric Loader ${LOADER_VERSION}..."
  curl -fL --retry 3 -o "libs/fabric-loader.jar" "$LOADER_URL"
fi

if [ ! -f "libs/gson.jar" ]; then
  echo "Downloading Gson..."
  curl -fL --retry 3 -o "libs/gson.jar" "$GSON_URL"
fi

if [ ! -f "libs/mixin.jar" ]; then
  echo "Downloading Mixin..."
  curl -fL --retry 3 -o "libs/mixin.jar" "$MIXIN_URL"
fi

# Minecraft's own libraries (LWJGL, brigadier, jspecify, ...) are not inside client.jar.
if [ ! -d libs/mc ] || [ -z "$(ls -A libs/mc 2>/dev/null)" ]; then
  echo "Resolving Minecraft libraries..."
  mkdir -p libs/mc .tooling
  curl -fsSL --retry 3 -o .tooling/mc-meta.json "$MC_META_URL"
  if command -v node >/dev/null 2>&1; then
    node -e 'const j=require("./.tooling/mc-meta.json");for(const l of j.libraries){if(l.downloads&&l.downloads.artifact&&l.downloads.artifact.url)console.log(l.downloads.artifact.url)}' > .tooling/liburls.txt
  elif command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' > .tooling/liburls.txt
import json
with open(".tooling/mc-meta.json") as handle:
    meta = json.load(handle)
for library in meta.get("libraries", []):
    artifact = (library.get("downloads") or {}).get("artifact") or {}
    if artifact.get("url"):
        print(artifact["url"])
PY
  else
    echo "Need node or python3 to read the Minecraft library manifest." >&2
    exit 1
  fi
  while read -r url; do
    file="libs/mc/$(basename "$url")"
    [ -f "$file" ] || curl -fsSL --retry 2 -o "$file" "$url"
  done < .tooling/liburls.txt
fi

CP=".tooling/client-${MC_VERSION}.jar${CP_SEP}libs/mc/*${CP_SEP}libs/fabric-loader.jar${CP_SEP}libs/mixin.jar${CP_SEP}libs/gson.jar"

echo "Compiling..."
rm -rf build/classes && mkdir -p build/classes
javac --release 21 -encoding UTF-8 -Xlint:-options -cp "$CP" -d build/classes \
  $(find src/main/java ../collector/src/main/java -name '*.java')
# Resources must also live on the runtime classpath, not only inside the jar.
cp -r src/main/resources/. build/classes/

echo "Packaging..."
rm -rf build/jar && mkdir -p build/jar
cp -r src/main/resources/. build/jar/
cp -r build/classes/. build/jar/
# Replace the placeholder after copying classes/resources so it is not overwritten.
sed "s/[$][{]version[}]/${MOD_VERSION}/" build/jar/fabric.mod.json > build/jar/fabric.mod.json.tmp
mv build/jar/fabric.mod.json.tmp build/jar/fabric.mod.json
jar --create --file "build/libs/discordia-${MOD_VERSION}.jar" -C build/jar .

echo
echo "Built build/libs/discordia-${MOD_VERSION}.jar"
echo "Install: put it in .minecraft/mods on a Fabric ${MC_VERSION} profile (Fabric Loader ${LOADER_VERSION}+)."
echo "Commands: /discordia [toggle [nonbin]] | list | open | copy | margin | minprofit | maxcost | status | collector | help."
