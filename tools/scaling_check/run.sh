#!/bin/bash
# Phase 7A end to end, offscreen: render-resolution scaling and the MetalFX upscale.
#
# The render check proves individual mechanisms. This proves the shape the mixins assemble in the
# game - a scaled level target built out of the engine's own MainTarget, written through the engine's
# own frame graph as an imported external resource, upscaled into a native-resolution target, and off
# by default at scale 1.0.
#
#   tools/scaling_check/run.sh [instance-dir]
#
# Exits 0 when every checked pixel and state is what Phase 7A promises.
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
  "${SCRIPT_DIR}/ScalingCheck.java" "${INSTANCE}"
