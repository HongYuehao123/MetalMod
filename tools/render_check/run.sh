#!/bin/bash
# Render a real vanilla pipeline through MetalMod's backend, offscreen, and check the pixels.
#
# Everything else here verifies that shaders compile and that reflection agrees with the generated
# MSL. None of it proves a frame comes out right. This drives the actual path - device, command
# encoder, render pass, uniform and vertex binding, draw, readback - with minecraft:pipeline/gui,
# whose fragment shader multiplies by a uniform, so a wrong binding shows up as the wrong colour.
#
#   tools/render_check/run.sh [instance-dir]
#
# Exits 0 when every checked pixel is the expected colour.
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
  "${SCRIPT_DIR}/RenderCheck.java" "${INSTANCE}"
