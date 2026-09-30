#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTANCE="${1:-${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}}"
JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
CLASSPATH="${ROOT_DIR}/build/classes:$(python3 "${ROOT_DIR}/scripts/build_classpath.py" "$INSTANCE")"
exec "$JAVA_HOME_DIR/bin/java" --source 22 -cp "$CLASSPATH" "$ROOT_DIR/tools/jitter_check/JitterCheck.java"
