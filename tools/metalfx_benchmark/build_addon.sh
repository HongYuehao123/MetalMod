#!/bin/bash
# Compile this optional test add-on against the real client API, never package it in MetalMod.
set -euo pipefail
cd "$(dirname "$0")/../.."
instance="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
jdk="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
classpath="build/classes:$(python3 scripts/build_classpath.py "$instance")"
output="build/metalfx-benchmark"
rm -rf "$output/classes"
mkdir -p "$output/classes"
"$jdk/bin/javac" --release 22 -cp "$classpath" -d "$output/classes" tools/metalfx_benchmark/*.java
cp tools/metalfx_benchmark/*.json "$output/classes/"
"$jdk/bin/jar" cf "$output/spatial-benchmark.jar" -C "$output/classes" .
