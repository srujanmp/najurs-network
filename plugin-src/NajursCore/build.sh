#!/bin/bash
# Compile NajursCore against the server's own Paper libraries and install it into plugins/.
set -euo pipefail
cd "$(dirname "$0")"
JDK=${JDK:-../../tools/jdk/bin}  # tools/najurs.py setup sets this to the JDK it found or installed
CP=$(find ../../server/libraries -name '*.jar' | tr '\n' ':'):$(ls ../../server/plugins/placeholderapi--*.jar):$(ls ../../server/plugins/screamingbedwars--*.jar):$(ls ../../server/plugins/viaversion--*.jar)
rm -rf out && mkdir -p out
$JDK/javac -nowarn -proc:none --release 25 -cp "$CP" -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/. out/
$JDK/jar cf ../../server/plugins/NajursCore.jar -C out .
echo "built ../../server/plugins/NajursCore.jar"
