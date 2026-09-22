#!/bin/bash
# Reproduce one vanilla shader pair's Metal pipeline offline.
#
# Extracts the shaders from the real client jar, runs them through GLSL -> SPIR-V -> MSL, and tries
# to create the MTLRenderPipelineState outside the game. Use it when the game only says
# "native pipeline creation failed": this prints the SPIR-V interface locations, both MSL sources,
# and Metal's own error.
#
#   tools/shader_repro/run.sh <vertex-shader-path-in-jar> <fragment-shader-path-in-jar> [colorFormat] [depthFormat]
#
# Example (the pair that exposed the cross-stage location mismatch):
#   tools/shader_repro/run.sh \
#       assets/minecraft/shaders/core/animate_sprite.vsh \
#       assets/minecraft/shaders/core/animate_sprite_interpolate.fsh
#
# Exits 0 when Metal accepted the pipeline, 1 when it did not.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
JAVA="${JAVA_HOME_DIR}/bin/java"
INSTANCE="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <vertex-shader-path-in-jar> <fragment-shader-path-in-jar> [colorFormat] [depthFormat]" >&2
  exit 2
fi

VERTEX_SHADER="$1"
FRAGMENT_SHADER="$2"
COLOR_FORMAT="${3:-70}"
DEPTH_FORMAT="${4:-0}"

VERTEX_JAR="${INSTANCE}/$(basename "${INSTANCE}").jar"
if [ ! -f "${VERTEX_JAR}" ]; then
  echo "ERROR: client jar not found: ${VERTEX_JAR}" >&2
  echo "Set METALMOD_MC_INSTANCE to a directory under <minecraft>/versions/<name>/." >&2
  exit 1
fi
if [ ! -d "${ROOT_DIR}/build/classes" ]; then
  echo "ERROR: ${ROOT_DIR}/build/classes missing. Run ./scripts/build_mod.sh first." >&2
  exit 1
fi

WORK="${ROOT_DIR}/build/shader_repro"
rm -rf "${WORK}"
mkdir -p "${WORK}"

# The shaders plus every include (they are referenced as '#moj_import <minecraft:...>').
unzip -o -q "${VERTEX_JAR}" "${VERTEX_SHADER}" "${FRAGMENT_SHADER}" 'assets/minecraft/shaders/include/*' -d "${WORK}"

# Includes live under a per-namespace directory, because namespaces collide: Sodium and vanilla both
# ship a globals.glsl and a fog.glsl with different contents, so a flat lookup picks the wrong one.
INCLUDE_DIR="${WORK}/include"
mkdir -p "${INCLUDE_DIR}/minecraft"
cp "${WORK}"/assets/minecraft/shaders/include/*.glsl "${INCLUDE_DIR}/minecraft/" 2>/dev/null || true
CLASSPATH="${ROOT_DIR}/build/classes:$(python3 "${ROOT_DIR}/scripts/build_classpath.py" "${INSTANCE}" 2>/dev/null)"

cd "${ROOT_DIR}"
exec "${JAVA}" --enable-native-access=ALL-UNNAMED --source 22 -cp "${CLASSPATH}" \
  "${SCRIPT_DIR}/ShaderRepro.java" \
  "${INCLUDE_DIR}" "${WORK}/${VERTEX_SHADER}" "${WORK}/${FRAGMENT_SHADER}" \
  "${COLOR_FORMAT}" "${DEPTH_FORMAT}"
