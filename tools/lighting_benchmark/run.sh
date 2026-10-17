#!/bin/bash
# Compile only this harness against the existing real mod/client classpath.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
INSTANCE="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
if [ ! -f "${ROOT_DIR}/build/classes/net/metalmod/lighting/LightClusterGrid.class" ]; then
  echo "ERROR: build/classes missing. Run ./scripts/build_mod.sh first." >&2
  exit 1
fi
BENCH_CLASSPATH="${ROOT_DIR}/build/classes:$(python3 "${ROOT_DIR}/scripts/build_classpath.py" "${INSTANCE}")"
mkdir -p "${SCRIPT_DIR}/build"
"${JAVA_HOME_DIR}/bin/javac" --release 22 -cp "${BENCH_CLASSPATH}" \
  -d "${SCRIPT_DIR}/build" "${SCRIPT_DIR}/LightingBenchmark.java"
if [ "${1:-}" = "--compile-only" ]; then
  echo "Lighting benchmark compiled; no timed work executed."
  exit 0
fi
exec "${JAVA_HOME_DIR}/bin/java" -Xms256m -Xmx512m -XX:MaxDirectMemorySize=256m \
  -cp "${SCRIPT_DIR}/build:${BENCH_CLASSPATH}" LightingBenchmark "$@"
