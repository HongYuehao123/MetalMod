#!/bin/bash
# Check every mixin's injection points against the real client jar, without launching the game.
#
# A mixin that names a method the client does not have fails at class-load time - and with
# `defaultRequire: 0` plus `required: false`, some of those failures are quiet: the hook never runs
# and the feature it drives silently does nothing. That is the shape of several defects this project
# has already paid for, so it is worth catching offline.
#
#   tools/mixin_check/run.sh [instance-dir]
#
# Exits non-zero when a mixin names something the client jar does not have.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
JAVA="${JAVA_HOME_DIR}/bin/java"
INSTANCE="${1:-${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}}"

# The client jar is read through ASM (from the launcher's own library set), so nothing is loaded and
# no other Minecraft dependency is needed.
#
#   tools/mixin_check/run.sh [instance-dir]
#
# Exits non-zero when a mixin names something the client jar does not have.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
JAVA="${JAVA_HOME_DIR}/bin/java"
INSTANCE="${1:-${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}}"

# ASM only. The full client classpath is deliberately not used: this check has to work even when the
# launcher's libraries are incomplete, which is the state build_classpath.py already warns about.
# One version, and the newest of them: version-sorted globs would put 6.1.1 first, and that ASM
# predates the class-file version this client jar is built with.
pick_newest() {
  local artifact="$1"
  local newest
  newest="$(ls -1 "$HOME/Documents/.minecraft/libraries/org/ow2/asm/${artifact}"/*/"${artifact}"-*.jar \
      2>/dev/null | sort -V | tail -1)"
  [ -n "${newest}" ] && printf '%s' "${newest}"
}
ASM_JAR="$(pick_newest asm)"
ASM_TREE_JAR="$(pick_newest asm-tree)"
if [ -z "${ASM_JAR}" ]; then
  echo "ERROR: ASM not found under ~/Documents/.minecraft/libraries; cannot read the client jar." >&2
  exit 2
fi
ASM="${ASM_JAR}${ASM_TREE_JAR:+:${ASM_TREE_JAR}}"
echo "  asm    : $(basename "${ASM_JAR}")"

cd "${ROOT_DIR}"
exec "${JAVA}" --source 22 -cp "${ASM}" "${SCRIPT_DIR}/MixinCheck.java" "${INSTANCE}"
