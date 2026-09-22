#!/bin/bash
# Build and run the native Metal smoke test.
#
# This exercises the Metal substrate directly - no Minecraft, no mod jar, no game launch. It proves
# the device / texture / clear / readback / surface path works on this machine, which is the native
# half of the MetalMod renderer backend (ROADMAP Phase 1).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/native/build"

echo "==> Configuring CMake..."
cmake -B "${BUILD_DIR}" -S "${ROOT_DIR}/native" -DCMAKE_BUILD_TYPE=Release

echo
echo "==> Building native library + smoke test..."
cmake --build "${BUILD_DIR}" --config Release --target metalmod_smoke

echo
echo "==> Running smoke test..."
echo
exec "${BUILD_DIR}/metalmod_smoke"
