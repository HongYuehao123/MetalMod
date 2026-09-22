#!/bin/bash
# Render real vanilla pipelines through MetalMod's backend, offscreen, and check the pixels.
#
# Everything else here verifies that shaders compile and that reflection agrees with the generated
# MSL. None of it proves a frame comes out right. This drives the actual path - device, command
# encoder, render pass, uniform and vertex binding, draw, readback - with pipelines whose output is
# a known colour, so a wrong binding or a wrong vertex layout shows up as the wrong pixel:
#
#   gui, gui_textured      ColorModulator and texCoord0 orientation
#   solid_terrain          Globals vs Fog slot collision, the 28-byte terrain vertex
#   entity_cutout          the 36-byte entity vertex, per-face lighting, four uniform blocks
#   lines                  the screen-space line expansion behind BUG-002
#   copy/blit/multidraw/   the non-draw paths BUG-001 implicates
#   scissor/atlas/blend
#   short indices          16-bit indices with firstIndex and base-vertex offsets
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
