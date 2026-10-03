#!/usr/bin/env python3
"""Run the five authoritative gates and optionally a packaged copied-world validation."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-source", type=Path, help="Test game directory to copy; source is never modified")
    parser.add_argument("--runtime-world", help="Existing folder directly under the source saves directory")
    args = parser.parse_args()
    if bool(args.runtime_source) != bool(args.runtime_world):
        parser.error("Supply both --runtime-source and --runtime-world")
    repo = Path(__file__).resolve().parents[1]
    report = repo / "build/reports" / datetime.now().strftime("verification-%Y%m%d-%H%M%S-%f")
    report.mkdir(parents=True)
    instance = Path(os.environ.get("METALMOD_MC_INSTANCE", str(Path.home() / "Documents/.minecraft/versions/MetalMod_Test_26.2")))
    jdk = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home"))
    results = []
    environment = dict(os.environ, MTL_DEBUG_LAYER="1")

    def save():
        (report / "result.json").write_text(json.dumps(results, indent=2) + "\n")
        (report / "result.txt").write_text("\n".join(f"{r['status']} {r['name']} ({r['log']})" for r in results) + "\n")

    def gate(name, command, markers=(), overrides=None):
        log = report / f"{len(results)+1:02d}-{name}.log"
        print(f"Running {name} ...", flush=True)
        with log.open("w") as output:
            process = subprocess.run(command, cwd=repo, env=dict(environment, **(overrides or {})), stdout=output, stderr=subprocess.STDOUT)
        text = log.read_text(errors="replace")
        passed = process.returncode == 0 and all(marker in text for marker in markers)
        results.append(dict(name=name, status="PASS" if passed else "FAIL", exit_code=process.returncode, log=str(log)))
        save()
        print(f"{results[-1]['status']} {name}", flush=True)
        if not passed:
            print(text[-4000:], file=sys.stderr)
            raise RuntimeError(f"{name} failed; see {log}")

    print(f"Reports: {report}", flush=True)
    gate("build", ["./scripts/build_mod.sh"], ["SUCCESS"])
    # Verify the artifact actually contains the current native image and no test add-on.
    jar = repo / "build/libs/metalmod-1.0.0.jar"
    with zipfile.ZipFile(jar) as package:
        metadata = json.loads(package.read("fabric.mod.json"))
        if metadata["id"] != "metalmod" or package.read("natives/libmetalmod.dylib") != (repo / "native/build/libmetalmod.dylib").read_bytes():
            raise RuntimeError("Packaged native library or mod metadata does not match the build")
        if any(name.startswith("net/metalmod/validation/") for name in package.namelist()):
            raise RuntimeError("Test-only add-on classes are present in the production JAR")
    (report / "artifact.json").write_text(json.dumps({"jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                                                     "embedded_native_matches": True, "test_addon_absent": True}, indent=2) + "\n")
    gate("native-smoke", ["./native/build/metalmod_smoke"], ["ALL CHECKS PASSED"])
    gate("shader-inventory", ["./tools/shader_inventory/run.sh"],
         ["total=87 ok=87 failed=0", "total=9 ok=9 failed=0"])
    gate("shader-inventory-lighting", ["./tools/shader_inventory/run.sh"],
         ["total=87 ok=87 failed=0", "total=9 ok=9 failed=0"],
         {"JAVA_TOOL_OPTIONS": environment.get("JAVA_TOOL_OPTIONS", "")
          + " -Dmetalmod.dynamicLights=true -Dmetalmod.clusteredLights=true"})
    gate("render-check", ["./tools/render_check/run.sh"], ["RENDER CHECK PASSED"])
    classpath = subprocess.check_output(["python3", "scripts/build_classpath.py", str(instance)], cwd=repo, text=True).strip()
    gate("standalone", [str(jdk / "bin/java"), "--enable-native-access=ALL-UNNAMED", "-cp",
                        f"{repo}/build/classes:{repo}/build/test-classes:{classpath}", "net.metalmod.StandaloneTestRunner"],
         ["ALL TESTS PASSED SUCCESSFULLY!"])
    if args.runtime_source:
        gate("packaged-game", [sys.executable, "tools/temporal_validation/run.py", "--source-game", str(args.runtime_source.resolve()),
                               "--world", args.runtime_world, "--output", str(report / "game-copy")], ["failures=0"])
    print(f"All requested gates passed. Results: {report / 'result.txt'}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, KeyError) as error:
        print(f"Verification failed: {error}", file=sys.stderr)
        sys.exit(1)
