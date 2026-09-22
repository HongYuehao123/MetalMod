#!/bin/bash
# Compile every vanilla render pipeline through MetalMod's real shader path, outside the game.
#
# Phase 4's exit criterion is "unmodified vanilla shaders compile and run". The game only proves the
# pipelines the engine announces at startup (~28 of 87); the rest are compiled lazily, so an
# unexercised pipeline is silently unverified. This walks all of them.
#
#   tools/shader_inventory/run.sh [instance-dir]
#
# Exits with the number of pipelines that failed to compile (0 = Phase 4's shader criterion met).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
JAVA="${JAVA_HOME_DIR}/bin/java"
INSTANCE="${1:-${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}}"

if [ ! -d "${ROOT_DIR}/build/classes" ]; then
  echo "ERROR: ${ROOT_DIR}/build/classes missing. Run ./scripts/build_mod.sh first." >&2
  exit 1
fi

CLASSPATH="${ROOT_DIR}/build/classes:$(python3 "${ROOT_DIR}/scripts/build_classpath.py" "${INSTANCE}" 2>/dev/null)"

cd "${ROOT_DIR}"
exec "${JAVA}" --enable-native-access=ALL-UNNAMED --source 22 -cp "${CLASSPATH}" \
  "${SCRIPT_DIR}/ShaderInventory.java" "${INSTANCE}"
