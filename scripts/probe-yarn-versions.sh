#!/bin/bash
# Family discovery: compile the 1.21.1 platform source against each yarn-era target.
cd "$(dirname "$0")/.."
OUT="${PROBE_OUT:-probe-results.txt}"
: > "$OUT"
while read -r MC YARN API; do
  cat > platforms/probe/gradle.properties <<PROPS
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
    echo "$MC FAIL $(grep -c 'Fehler\|error:' "probe-$MC.log")" >> "$OUT"
  fi
done <<'VERSIONS'
1.21.2 1.21.2+build.1 0.106.1+1.21.2
1.21.3 1.21.3+build.2 0.114.1+1.21.3
1.21.4 1.21.4+build.8 0.119.4+1.21.4
1.21.5 1.21.5+build.1 0.128.2+1.21.5
1.21.6 1.21.6+build.1 0.128.2+1.21.6
1.21.7 1.21.7+build.8 0.129.0+1.21.7
1.21.8 1.21.8+build.1 0.136.1+1.21.8
1.21.9 1.21.9+build.1 0.134.1+1.21.9
1.21.10 1.21.10+build.3 0.138.4+1.21.10
1.21.11 1.21.11+build.6 0.141.5+1.21.11
VERSIONS
echo DONE >> "$OUT"
