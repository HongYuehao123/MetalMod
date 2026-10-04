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
parser.add_argument("--frame-generation", action="store_true")
parser.add_argument("--delivery-check", action="store_true")
args = parser.parse_args()
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
command = [str(jdk / "bin/java"), "-XstartOnFirstThread", "-Xmx4G", "--enable-native-access=ALL-UNNAMED",
           "-Dmetalmod.frameGenerationDeliveryValidation="+str(args.delivery_check).lower(), "-Dmetalmod.metalBackend=true", "-Dmetalmod.frameGenerationForce=true", "-Dmetalmod.dynamicLights=true", "-Dmetalmod.temporalInputValidation="+str(not args.frame_generation).lower(), "-Dmetalmod.frameGenerationValidation="+str(args.frame_generation).lower(), "-Dmetalmod.validationWorld="+args.world, "-Dmetalmod.validationFocus="+str(repo/"build/temporal-validation/focus.dylib"), "-cp", classpath,
           "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "MetalFXTest",
           "--uuid", "00000000000000000000000000000001", "--accessToken", "0", "--version", "26.2",
           "--gameDir", str(output), "--assetsDir", str(instance.parents[1] / "assets"), "--assetIndex", "32",
           "--width", "1280", "--height", "720", "--quickPlaySingleplayer", args.world]
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
