#!/bin/bash
# Compiles a family's source against every listed sibling version.
# usage: verify-family.sh <familyDir> "<mc yarn api>" lines on stdin
cd "$(dirname "$0")/.."
FAM="$1"; OUT="family-verify-$FAM.txt"; : > "$OUT"
while read -r MC YARN API; do
  [ -z "$MC" ] && continue
  cat > platforms/probe/gradle.properties <<PROPS
probeSrc=$FAM
mc=$MC
yarn=$YARN
fabricApi=$API
loaderVersion=0.16.14
javaVersion=21
artifactMc=$MC
PROPS
  if JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot" ./gradlew :platforms:probe:compileJava --console=plain -q > "probe-$MC.log" 2>&1; then
    echo "$MC PASS" >> "$OUT"
  else
    echo "$MC FAIL" >> "$OUT"
  fi
done
echo DONE >> "$OUT"
cat "$OUT"
