#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAVAC="/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/javac"
JAR="/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/jar"

echo "=================================================="
echo "Building MetalMod Fabric Mod (macOS 26+ / Metal 4)"
echo "=================================================="

# Step 1: Compile Native Engine
echo "==> Step 1: Compiling native Metal 4 library..."
cd "${ROOT_DIR}/native"
cmake -B build -S . -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
cd "${ROOT_DIR}"

# Step 2: Clean build directories
STUBS_DIR="${ROOT_DIR}/build/stubs"
STUBS_BIN="${ROOT_DIR}/build/stubs-bin"
MOD_BIN="${ROOT_DIR}/build/mod-bin"
DIST_DIR="${ROOT_DIR}/build/libs"

rm -rf "${STUBS_DIR}" "${STUBS_BIN}" "${MOD_BIN}" "${DIST_DIR}"
mkdir -p "${STUBS_DIR}" "${STUBS_BIN}" "${MOD_BIN}" "${DIST_DIR}"

# Step 3: Generate compile-time API stubs
echo "==> Step 2: Generating compile-time API stubs..."
python3 "${ROOT_DIR}/scripts/generate_stubs.py" "${STUBS_DIR}"

echo "==> Step 3: Compiling API stubs..."
find "${STUBS_DIR}" -name "*.java" > "${ROOT_DIR}/build/stub_sources.txt"
"${JAVAC}" -d "${STUBS_BIN}" @"${ROOT_DIR}/build/stub_sources.txt"

# Step 4: Compile MetalMod Java sources
echo "==> Step 4: Compiling MetalMod Java sources..."
find "${ROOT_DIR}/src/main/java" -name "*.java" > "${ROOT_DIR}/build/mod_sources.txt"
"${JAVAC}" -cp "${STUBS_BIN}" -d "${MOD_BIN}" @"${ROOT_DIR}/build/mod_sources.txt"

# Step 5: Copy metadata and native libraries
echo "==> Step 5: Packaging resources and native binaries..."
cp "${ROOT_DIR}/src/main/resources/fabric.mod.json" "${MOD_BIN}/"
cp "${ROOT_DIR}/src/main/resources/metalmod.mixins.json" "${MOD_BIN}/"
mkdir -p "${MOD_BIN}/natives"
cp "${ROOT_DIR}/native/build/libmetalmod.dylib" "${MOD_BIN}/natives/"

# Step 6: Create Fabric Mod JAR
MOD_JAR="${DIST_DIR}/metalmod-1.0.0.jar"
echo "==> Step 6: Creating Fabric mod jar: ${MOD_JAR}..."
"${JAR}" cvf "${MOD_JAR}" -C "${MOD_BIN}" .

echo "=================================================="
echo "SUCCESS! Fabric mod compiled to:"
echo "${MOD_JAR}"
echo "=================================================="
