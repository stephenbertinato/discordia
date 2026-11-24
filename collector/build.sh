#!/usr/bin/env bash
# Builds only the private collector and the shared plain-Java market core.
set -euo pipefail
cd "$(dirname "$0")"

GSON="../ahflip/libs/gson.jar"
if [ ! -f "$GSON" ]; then
  echo "Missing $GSON; run ../ahflip/build.sh once to download dependencies." >&2
  exit 1
fi

mkdir -p build/classes
rm -rf build/classes
mkdir -p build/classes

echo "Compiling collector..."
javac --release 21 -encoding UTF-8 -Xlint:-options \
  -cp "$GSON" \
  -d build/classes \
  $(find "../ahflip/src/main/java/dev/mmm/discordia/core" \
         "src/main/java" -name '*.java')

echo
echo "Built collector/build/classes"
echo "Run: COLLECTOR_TOKEN='change-me' ./run.sh"
