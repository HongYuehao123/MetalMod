#!/usr/bin/env python3
"""Launch packaged validation in a new disposable copy, never in the source game directory."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source-game", type=Path, required=True)
parser.add_argument("--world", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--extra-mod", type=Path, action="append", default=[], help="Additional mod JARs for this disposable copy only")
parser.add_argument("--backend-property", action="append", default=[], metavar="KEY=true|false",
                    help="Explicit Stage 1 backend switch, recorded in launch.json")
parser.add_argument("--frame-generation", action="store_true")
parser.add_argument("--delivery-check", action="store_true")
parser.add_argument("--static-check", action="store_true", help="Only native/resize/static temporal image regression, without the full lifecycle suite")
parser.add_argument("--lighting-check", action="store_true", help="Only native and lighting suppression/re-enable transitions")
parser.add_argument("--dump-temporal-inputs", action="store_true", help="Save static ROI motion/reactivity inputs for projection diagnostics")
parser.add_argument("--skip-timing", action="store_true", help="Omit benchmark-only stages while retaining lifecycle and image checks")
parser.add_argument("--static-strength", type=int, choices=[25,33,50], default=50, help="Strength for --static-check image sequences")
args = parser.parse_args()
backend_properties = {}
allowed_backend_properties = {"metalmod.fenceTickets", "metalmod.deferredClears", "metalmod.encoderContinuation",
                              "metalmod.texelCache", "metalmod.nativeTexelBuffers", "metalmod.compileRegionDrop",
                              "metalmod.skipBlackVignette", "metalmod.dumpVignetteProof"}
for item in args.backend_property:
    key, separator, value = item.partition("=")
    if not separator or key not in allowed_backend_properties or value not in ("true", "false"):
        parser.error("backend property must be a supported Stage 1 key with true or false")
    if key in backend_properties:
        parser.error("duplicate backend property: " + key)
    backend_properties[key] = value
if sum([args.static_check, args.lighting_check, args.frame_generation]) > 1:
    parser.error("--static-check, --lighting-check and --frame-generation are mutually exclusive")
if args.static_strength != 50 and not args.static_check:
    parser.error("--static-strength requires --static-check")
for extra in args.extra_mod:
    if not extra.is_file() or extra.suffix != ".jar":
        parser.error("--extra-mod must be an existing JAR")
repo = Path(__file__).resolve().parents[2]
source = args.source_game.resolve()
output = args.output.resolve()
world = source / "saves" / args.world
if not world.is_dir() or world.parent != source / "saves":
    parser.error("--world must name an existing world directly under the source saves directory")
if output.exists():
    parser.error("--output must be a new directory")
instance = Path(os.environ.get("METALMOD_MC_INSTANCE", str(Path.home() / "Documents/.minecraft/versions/MetalMod_Test_26.2")))
jdk = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home"))
subprocess.run(["bash", "tools/temporal_validation/build.sh"], cwd=repo, check=True)
classpath = subprocess.check_output(["python3", "scripts/build_classpath.py", str(instance)], cwd=repo, text=True).strip()
classpath = ":".join(path for path in classpath.split(":") if "/mods/" not in path)
output.mkdir(parents=True)
(output / ".metalmod-benchmark-copy").touch()
shutil.copytree(world, output / "saves" / args.world)
if (source / "config").is_dir():
    shutil.copytree(source / "config", output / "config")
if args.frame_generation:
    import re
    config = output / "config/metalmod.properties"
    config.parent.mkdir(exist_ok=True)
    content = config.read_text() if config.exists() else ""
    content = re.sub(r"(?m)^enableFrameGeneration=.*\n?", "", content)
    config.write_text(content + "\nenableFrameGeneration=true\n")
options = (source / "options.txt").read_text() if (source / "options.txt").exists() else ""
settings = {"renderDistance": "8", "simulationDistance": "5", "guiScale": "2", "fullscreen": "false",
            "maxFps": "60", "enableVsync": "true" if args.frame_generation else "false", "soundCategory_master": "0.0", "overrideWidth": "1280", "overrideHeight": "720"}
lines = [line for line in options.splitlines() if line.split(":", 1)[0] not in settings]
(output / "options.txt").write_text("\n".join(lines + [f"{key}:{value}" for key, value in settings.items()]) + "\n")
(output / "mods").mkdir()
for jar in (source / "mods").glob("*.jar"):
    if "metalmod" not in jar.name.lower() and "temporal-input-validation" not in jar.name.lower():
        shutil.copy2(jar, output / "mods" / jar.name)
shutil.copy2(repo / "build/libs/metalmod-1.0.0.jar", output / "mods/metalmod-1.0.0.jar")
shutil.copy2(repo / "build/temporal-validation/validation.jar", output / "mods/temporal-input-validation.jar")
for extra in args.extra_mod:
    destination = output / "mods" / extra.name
    if destination.exists():
        parser.error("extra mod name conflicts with copied mods: " + extra.name)
    shutil.copy2(extra, destination)
command = [os.environ.get("METALMOD_BENCHMARK_JAVA", str(jdk / "bin/java")), "-XstartOnFirstThread", "-Xmx4G", "--enable-native-access=ALL-UNNAMED",
           "-Dmetalmod.validationStaticOnly="+str(args.static_check).lower(),
           "-Dmetalmod.validationLightingOnly="+str(args.lighting_check).lower(),
           "-Dmetalmod.validationSkipTiming="+str(args.skip_timing).lower(),
           "-Dmetalmod.validationStaticStrength="+str(args.static_strength),
           "-Dmetalmod.validationDumpTemporalInputs="+str(args.dump_temporal_inputs).lower(),
           "-Dmetalmod.frameGenerationDeliveryValidation="+str(args.delivery_check).lower(), "-Dmetalmod.metalBackend=true", "-Dmetalmod.frameGenerationForce=true", "-Dmetalmod.dynamicLights=true", "-Dmetalmod.temporalInputValidation="+str(not args.frame_generation).lower(), "-Dmetalmod.frameGenerationValidation="+str(args.frame_generation).lower(), "-Dmetalmod.validationWorld="+args.world, "-Dmetalmod.validationFocus="+str(repo/"build/temporal-validation/focus.dylib"), "-cp", classpath,
           "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "MetalFXTest",
           "--uuid", "00000000000000000000000000000001", "--accessToken", "0", "--version", "26.2",
           "--gameDir", str(output), "--assetsDir", str(instance.parents[1] / "assets"), "--assetIndex", "32",
           "--width", "1280", "--height", "720", "--quickPlaySingleplayer", args.world]
for key, value in backend_properties.items():
    command.insert(command.index("-cp"), "-D" + key + "=" + value)
(output / "launch.json").write_text(json.dumps(command, indent=2))
environment = dict(os.environ, MTL_DEBUG_LAYER="1")
with (output / "console.log").open("w") as log:
    process = subprocess.Popen(command, cwd=output, env=environment, stdout=log, stderr=subprocess.STDOUT)
    subprocess.Popen(["/usr/bin/caffeinate", "-d", "-i", "-u", "-t", "600", "-w", str(process.pid)])
    (output / "pid").write_text(str(process.pid))
    print(f"Validation running in {output}; pid={process.pid}", flush=True)
    try:
        code = process.wait(timeout=510)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=20)
        raise SystemExit("Validation timed out; inspect console.log")
result = output / "validation/result.txt"
print(result.read_text() if result.exists() else f"No result; client exit={code}")
if not (code == 0 and result.exists() and result.read_text().startswith("PASS\n")):
    raise SystemExit(1)

if not args.frame_generation and not args.lighting_check:
    # Contract/lifecycle PASS is insufficient: BUG-051 passed every runtime assertion while
    # retaining coherent stationary jitter. Keep both results and fail the launcher on either.
    image_gate = subprocess.run([sys.executable, str(repo / "tools/temporal_validation/analyze.py"),
                                 str(output / "validation")])
    (output / "validation/launcher-result.json").write_text(json.dumps(
        {"runtime_pass": True, "image_gate_exit_code": image_gate.returncode,
         "pass": image_gate.returncode == 0}, indent=2) + "\n")
    if image_gate.returncode != 0:
        raise SystemExit("Temporal image verification failed; runtime PASS is retained separately. "
                         "Inspect validation/image-analysis.json and console output.")

if args.delivery_check:
    subprocess.run([sys.executable,str(repo / "tools/temporal_validation/analyze_delivery.py"),str(output / "validation")],check=True)

if args.frame_generation and not args.delivery_check:
    restart = command.copy()
    restart.insert(restart.index("-cp"), "-Dmetalmod.frameGenerationRestartValidation=true")
    with (output / "restart-console.log").open("w") as log:
        process = subprocess.Popen(restart, cwd=output, env=environment, stdout=log, stderr=subprocess.STDOUT)
        subprocess.Popen(["/usr/bin/caffeinate", "-d", "-i", "-u", "-t", "600", "-w", str(process.pid)])
        (output / "restart-pid").write_text(str(process.pid))
        try:
            code = process.wait(timeout=100)
        except subprocess.TimeoutExpired:
            process.terminate(); process.wait(timeout=20)
            raise SystemExit("Saved-On restart timed out")
    result = output / "validation-restart/result.txt"
    print("Saved-On fresh JVM restart:", result.read_text() if result.exists() else f"No result; exit={code}")
    raise SystemExit(0 if code == 0 and result.exists() and result.read_text().startswith("PASS\n") else 1)
