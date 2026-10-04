#!/bin/bash
# Additional real-display gate. Build with scripts/build_mod.sh first; requires an unlocked Mac.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_DIR="${TASK_ROOT}/build/reports/display-link"
TEST_APP="${REPORT_DIR}/MetalModDisplayLinkCheck.app"
if [ ! -x "${TASK_ROOT}/native/build/metalmod_display_link_check" ]; then
  echo "Run ./scripts/build_mod.sh first." >&2
  exit 1
fi
mkdir -p "${TEST_APP}/Contents/MacOS"
cp "${TASK_ROOT}/native/build/metalmod_display_link_check" "${TEST_APP}/Contents/MacOS/check"
cp "${TASK_ROOT}/native/build/libmetalmod.dylib" "${TEST_APP}/Contents/MacOS/"
cat > "${TEST_APP}/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>net.metalmod.displaylinkcheck</string>
<key>CFBundleName</key><string>MetalMod Display Link Check</string>
<key>CFBundleExecutable</key><string>check</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSEnvironment</key><dict><key>MTL_DEBUG_LAYER</key><string>1</string></dict>
</dict></plist>
PLIST
: > "${REPORT_DIR}/display.log"
open -W -n "${TEST_APP}" --stdout "${REPORT_DIR}/display.log" --stderr "${REPORT_DIR}/display.log"
cat "${REPORT_DIR}/display.log"
# open's exit code does not report the child app's exit code; require the explicit terminal result.
if rg -q '^DISPLAY LINK CHECK PASSED$' "${REPORT_DIR}/display.log"; then
  exit 0
elif rg -q '^DISPLAY LINK CHECK BLOCKED:' "${REPORT_DIR}/display.log"; then
  exit 77
else
  exit 1
fi
