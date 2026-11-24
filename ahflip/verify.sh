#!/usr/bin/env bash
# Build and run the deterministic global-sales checks. No public network calls are made.
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac
CP="build/classes${SEP}libs/gson.jar"

java -cp "$CP" dev.mmm.discordia.tools.GlobalFailureProbe
java -cp "$CP" dev.mmm.discordia.tools.GlobalGateProbe
java -cp "$CP" dev.mmm.discordia.tools.CoflContractProbe
