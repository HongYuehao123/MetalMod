#!/bin/bash
# Optional separate adapter; compile against the actual tested release, never bundle Sodium.
set -euo pipefail
cd "$(dirname "$0")/../.."
sodium_jar="$1"
output="build/sodium-adapter/classes"
python3 - "$sodium_jar" <<'PY'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    metadata = json.loads(jar.read('fabric.mod.json'))
if (metadata.get('id'), metadata.get('version')) != ('sodium', '0.9.2+mc26.2'):
    raise SystemExit('Build requires the actual Fabric Sodium 0.9.2+mc26.2 release JAR')
PY
jdk="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
instance="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
classpath="build/classes:$sodium_jar:$(python3 scripts/build_classpath.py "$instance")"
rm -rf "$output"
mkdir -p "$output" build/sodium-adapter
find compat/sodium/src/main/java -name '*.java' > build/sodium-adapter/sources.txt
"$jdk/bin/javac" --release 22 -proc:none -cp "$classpath" -d "$output" @build/sodium-adapter/sources.txt
cp compat/sodium/src/main/resources/*.json "$output/"
"$jdk/bin/jar" cf build/libs/metalmod-sodium-0.1.1.jar -C "$output" .
echo "Optional Sodium adapter -> build/libs/metalmod-sodium-0.1.1.jar"
