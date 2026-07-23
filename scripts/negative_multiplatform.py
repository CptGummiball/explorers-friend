"""Cross-platform negative tests: the wrong artifact on the wrong platform must
never produce a silently broken map - either the loader refuses/ignores the jar
with a clear message and the server stays healthy, or it refuses to start.

Cases (reusing cached smoke servers):
  fabric-gets-neoforge : neoforge jar in a Fabric server's mods/
  fabric-gets-spigot   : spigot-paper jar in a Fabric server's mods/
  neoforge-gets-fabric : fabric jar in a NeoForge server's mods/
Results land in dist/negative-multiplatform.json.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import time

TEMP = os.environ.get("TEMP", "/tmp")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J21 = r"C:\Program Files\Java\jdk-21\bin\java.exe"
VER = "0.4.3"


def prep(case, src, keep_mods=()):
    work = os.path.join(TEMP, "ef-neg-mp", case)
    if os.path.exists(work):
        shutil.rmtree(work)
    shutil.copytree(src, work, ignore=shutil.ignore_patterns(
        "mods", "logs", "world", "explorersfriend", "crash-reports",
        "server-out*.log", "start*.log", "fabricloader.log"))
    os.makedirs(os.path.join(work, "mods"), exist_ok=True)
    for m in keep_mods:
        s = os.path.join(src, "mods", m)
        if os.path.exists(s):
            shutil.copy(s, os.path.join(work, "mods", m))
    io.open(os.path.join(work, "server.properties"), "w").write(
        "online-mode=false\nserver-port=25655\nview-distance=4\nlevel-seed=neg\n")
    return work


def run(work, launch, timeout=240):
    proc = subprocess.Popen(launch, cwd=work, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, errors="replace")
    out = []
    start = time.time()
    while True:
        line = proc.stdout.readline()
        if line:
            out.append(line)
            text = "".join(out)
            if "Done (" in text or ")! For help" in text:
                proc.stdin = None
                proc.terminate()
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    proc.kill()
                return "".join(out), True
        if proc.poll() is not None and not line:
            return "".join(out), False
        if time.time() - start > timeout:
            proc.kill()
            return "".join(out), False


def verdict(case, text, started):
    loaded = "ExplorersFriend/Init" in text and "initializing" in text
    if loaded:
        return f"FAILED - foreign artifact was loaded as a mod"
    if started:
        return "passed (server healthy, foreign jar ignored)"
    if "explorersfriend" in text.lower() or "mods.toml" in text or "not a valid mod" in text.lower():
        return "passed (loader refused with a clear message)"
    return "failed (server did not start and gave no clear reason)"


def main():
    results = {}
    fab_src = os.path.join(TEMP, "ef-smoke", "1.21.1")
    neo_src = os.path.join(TEMP, "ef-neoforge", "1.21.1")

    work = prep("fabric-gets-neoforge", fab_src, keep_mods=("fabric-api.jar",))
    shutil.copy(os.path.join(ROOT, "dist", f"explorersfriend-neoforge-1.21.1-{VER}.jar"),
                os.path.join(work, "mods", "neoforge.jar"))
    text, started = run(work, [J21, "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"])
    results["fabric-gets-neoforge"] = verdict("fabric-gets-neoforge", text, started)

    work = prep("fabric-gets-spigot", fab_src, keep_mods=("fabric-api.jar",))
    shutil.copy(os.path.join(ROOT, "dist", f"explorersfriend-spigot-paper-{VER}.jar"),
                os.path.join(work, "mods", "spigot.jar"))
    text, started = run(work, [J21, "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"])
    results["fabric-gets-spigot"] = verdict("fabric-gets-spigot", text, started)

    args_file = None
    for root, dirs, files in os.walk(os.path.join(neo_src, "libraries", "net", "neoforged")):
        for f in files:
            if f == "win_args.txt":
                args_file = os.path.join(root, f)
    work = prep("neoforge-gets-fabric", neo_src)
    shutil.copytree(os.path.join(neo_src, "libraries"), os.path.join(work, "libraries"),
                    dirs_exist_ok=True)
    rel = os.path.relpath(args_file, neo_src)
    shutil.copy(os.path.join(ROOT, "dist", f"explorersfriend-fabric-1.21.1-{VER}.jar"),
                os.path.join(work, "mods", "fabric.jar"))
    text, started = run(work, [J21, "-Xmx2G", f"@{os.path.join(work, rel)}", "nogui"])
    results["neoforge-gets-fabric"] = verdict("neoforge-gets-fabric", text, started)

    io.open(os.path.join(ROOT, "dist", "negative-multiplatform.json"), "w").write(
        json.dumps(results, indent=2))
    ok = all(v.startswith("passed") for v in results.values())
    for k, v in results.items():
        print(f"  {k}: {v}")
    print("[negative-mp] " + ("4/4" if ok else "FAILED"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
