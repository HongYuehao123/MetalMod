#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAVA_HOME_DIR="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
JAVAC="${JAVA_HOME_DIR}/bin/javac"
JAR="${JAVA_HOME_DIR}/bin/jar"

# Minecraft instance to compile against. Point this at the instance you test with - we compile
# against the REAL client jar, not API stubs, so javac verifies every Minecraft API call.
#   export METALMOD_MC_INSTANCE="$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2"
INSTANCE="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"

echo "=================================================="
echo "Building MetalMod (macOS / Metal)"
echo "  instance : ${INSTANCE}"
echo "=================================================="

if [ ! -d "${INSTANCE}" ]; then
  echo "ERROR: Minecraft instance not found: ${INSTANCE}" >&2
  echo "Set METALMOD_MC_INSTANCE to a directory under <minecraft>/versions/<name>/" >&2
  echo "containing <name>.jar and <name>.json." >&2
  exit 1
fi

# Step 1: Compile the native library
echo "==> Step 1: Compiling native Metal library..."
cd "${ROOT_DIR}/native"
cmake -B build -S . -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build --config Release
cd "${ROOT_DIR}"

# Step 2: Resolve the real classpath from the launcher's version JSON
echo "==> Step 2: Resolving Minecraft classpath..."
CLASSPATH="$(python3 "${SCRIPT_DIR}/build_classpath.py" "${INSTANCE}")"
echo "    $(echo "${CLASSPATH}" | tr ':' '\n' | wc -l | tr -d ' ') entries"

# Step 3: Prepare output directories
MOD_BIN="${ROOT_DIR}/build/classes"
TEST_BIN="${ROOT_DIR}/build/test-classes"
DIST_DIR="${ROOT_DIR}/build/libs"
rm -rf "${MOD_BIN}" "${TEST_BIN}" "${DIST_DIR}"
mkdir -p "${MOD_BIN}" "${TEST_BIN}" "${DIST_DIR}"

# Step 4: Compile the mod against the real Minecraft jar.
#   --release 22 must match compatibilityLevel in metalmod.mixins.json.
echo "==> Step 3: Compiling mod sources against the real client jar..."
find "${ROOT_DIR}/src/main/java" -name "*.java" > "${ROOT_DIR}/build/mod_sources.txt"
"${JAVAC}" --release 22 -nowarn -cp "${CLASSPATH}" -d "${MOD_BIN}" \
  @"${ROOT_DIR}/build/mod_sources.txt"

# Step 5: Package resources and the native library
echo "==> Step 4: Packaging resources and native binaries..."
cp "${ROOT_DIR}/src/main/resources/fabric.mod.json" "${MOD_BIN}/"
cp "${ROOT_DIR}/src/main/resources/metalmod.mixins.json" "${MOD_BIN}/"
mkdir -p "${MOD_BIN}/natives"
cp "${ROOT_DIR}/native/build/libmetalmod.dylib" "${MOD_BIN}/natives/"

# Step 6: Create the mod jar
MOD_JAR="${DIST_DIR}/metalmod-1.0.0.jar"
echo "==> Step 5: Creating mod jar: ${MOD_JAR}..."
"${JAR}" cf "${MOD_JAR}" -C "${MOD_BIN}" .

# Step 7: Compile the standalone verification suite
echo "==> Step 6: Compiling standalone verification suite..."
"${JAVAC}" --release 22 -nowarn -cp "${MOD_BIN}:${CLASSPATH}" -d "${TEST_BIN}" \
  "${ROOT_DIR}/src/test/java/net/metalmod/StandaloneTestRunner.java" \
  "${ROOT_DIR}/src/test/java/net/metalmod/UnifiedMemoryTest.java" \
  "${ROOT_DIR}/src/test/java/net/metalmod/backend/MetalRenderPassBackendTest.java"

echo "=================================================="
echo "SUCCESS -> ${MOD_JAR}"
echo
echo "Install:"
echo "  cp ${MOD_JAR} \"${INSTANCE}/mods/\""
echo
echo "Verify:"
echo "  ${JAVA_HOME_DIR}/bin/java --enable-native-access=ALL-UNNAMED \\"
echo "      -cp ${MOD_BIN}:${TEST_BIN}:${CLASSPATH} net.metalmod.StandaloneTestRunner"
echo "=================================================="
