#!/usr/bin/env bash
# Runs the collector. Set COLLECTOR_TOKEN before invoking this.
set -euo pipefail
cd "$(dirname "$0")"

GSON="../ahflip/libs/gson.jar"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
  *) CP_SEP=":" ;;
esac

exec java -cp "build/classes${CP_SEP}${GSON}" dev.mmm.discordia.collector.CollectorMain
