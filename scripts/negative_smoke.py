"""Negative/behavioral smoke tests for the multi-version artifacts.

Cases 1, 3, 4 must FAIL fast with the expected loader error (server never ready).
Case 2 ("two variants at once") is a behavioral test: all release artifacts carry
disjoint Minecraft ranges, so Fabric Loader deterministically selects the single
compatible candidate for the running version and drops the other - the server must
come up with exactly ONE ExplorersFriend instance.

Reuses the cached positive-smoke server dirs (%TEMP%/ef-smoke/<mc>) so nothing big
is re-downloaded. Results land in dist/negative-tests.json.
"""
import io
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMP = os.environ.get("TEMP", "/tmp")
JAVA = {
    "21": r"C:\Program Files\Java\jdk-21",
    "25": r"C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot",
}
READY = "[ExplorersFriend/Init] Ready in"
INIT = "The Explorer's Friend v0.3.0 initializing"


def dist(name):
    return os.path.join(ROOT, "dist", name)


def fabric_api(mc):
    return os.path.join(TEMP, "ef-smoke", mc, "mods", "fabric-api.jar")


def prepare(case, base_mc, jars):
    src = os.path.join(TEMP, "ef-smoke", base_mc)
    work = os.path.join(TEMP, "ef-smoke-neg", case)
    if os.path.exists(work):
        shutil.rmtree(work)
    shutil.copytree(src, work, ignore=shutil.ignore_patterns(
        "mods", "logs", "world", "config", "crash-reports",
        "start*.log", "fabricloader.log"))
    os.makedirs(os.path.join(work, "mods"))
    for j in jars:
        shutil.copy(j, os.path.join(work, "mods", os.path.basename(j)))
    return work


def collect_output(work, stdout_text):
    parts = [stdout_text]
    for rel in (os.path.join("logs", "latest.log"), "fabricloader.log"):
        p = os.path.join(work, rel)
        if os.path.exists(p):
            parts.append(io.open(p, encoding="utf-8", errors="replace").read())
    return "\n".join(parts)


def launch(case, base_mc, java, jars, timeout):
    work = prepare(case, base_mc, jars)
    exe = os.path.join(JAVA[java], "bin", "java.exe")
    proc = subprocess.Popen(
        [exe, "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui"],
        cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        errors="replace")
    timed_out = False
    try:
        out, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        out, _ = proc.communicate()
    return collect_output(work, out or ""), timed_out


def expect_refusal(case, base_mc, java, jars, markers, timeout=420):
    text, timed_out = launch(case, base_mc, java, jars, timeout)
    hit = next((m for m in markers if m in text), None)
    if READY in text:
        verdict = "failed (server started although it must not)"
    elif hit:
        verdict = f"passed (marker: {hit})"
    else:
        verdict = ("failed (timed out without expected marker)" if timed_out
                   else "failed (expected marker missing)")
    print(f"[negative] {case}: {verdict}")
    return {"case": case, "result": verdict, "expectedMarkers": markers}


def expect_single_instance(case, base_mc, java, jars, timeout=420):
    text, _ = launch(case, base_mc, java, jars, timeout)
    inits = text.count(INIT)
    if READY in text and inits == 1:
        verdict = "passed (loader selected exactly one variant, server ready)"
    elif "Duplicate" in text or "duplicate" in text:
        verdict = "passed (loader refused the duplicate)"
    else:
        verdict = f"failed (ready={READY in text}, instances={inits})"
    print(f"[negative] {case}: {verdict}")
    return {"case": case, "result": verdict,
            "expected": "exactly one variant active (ranges are disjoint) or refusal"}


def main():
    ver = "0.3.0"
    results = [
        expect_refusal(
            "wrong-jar-on-wrong-version", "1.21.4", "21",
            [dist(f"explorersfriend-fabric-26.2-{ver}.jar"), fabric_api("1.21.4")],
            ["Incompatible mods", "requires version", "Wrong version"]),
        expect_single_instance(
            "two-variants-at-once", "1.21.4", "21",
            [dist(f"explorersfriend-fabric-1.21.2-1.21.4-{ver}.jar"),
             dist(f"explorersfriend-fabric-1.21.1-{ver}.jar"),
             fabric_api("1.21.4")], timeout=240),
        expect_refusal(
            "missing-fabric-api", "1.21.4", "21",
            [dist(f"explorersfriend-fabric-1.21.2-1.21.4-{ver}.jar")],
            ["Incompatible mods", "requires", "fabric-api"]),
        expect_refusal(
            "wrong-java-for-26x", "26.2", "21",
            [dist(f"explorersfriend-fabric-26.2-{ver}.jar"), fabric_api("26.2")],
            ["UnsupportedClassVersionError", "class file version",
             "BundlerProcessor", "requires Java", "Incompatible mods"]),
    ]
    out = os.path.join(ROOT, "dist", "negative-tests.json")
    io.open(out, "w", encoding="utf-8").write(json.dumps(results, indent=2))
    failed = [r for r in results if not r["result"].startswith("passed")]
    print(f"[negative] {len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
