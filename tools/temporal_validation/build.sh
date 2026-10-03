#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
instance="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
jdk="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home}"
classpath="build/classes:$(python3 scripts/build_classpath.py "$instance")"
output="build/temporal-validation"
mkdir -p "$output/classes"
"$jdk/bin/javac" --release 22 -cp "$classpath" -d "$output/classes" tools/temporal_validation/*.java
cp tools/temporal_validation/*.json "$output/classes/"
"$jdk/bin/jar" cf "$output/validation.jar" -C "$output/classes" .

xcrun clang++ -fobjc-arc -dynamiclib tools/temporal_validation/focus.mm -framework AppKit -o "$output/focus.dylib"
