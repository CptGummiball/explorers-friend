"""Dedicated-server smoke test for the Spigot/Paper backend.

Downloads Paper for the given MC version (fill.papermc.io v3), installs the
spigot-paper plugin jar, boots headless, then checks: plugin enable, platform
log lines, color scan, web endpoints, /efmap render via RCON, tile output,
clean shutdown, second-start cache hits. Results merge into dist/test-results.json
under the key "spigot-paper@<mc>".

Usage: python scripts/smoke_paper.py --mc 1.21.1 --java 21
           --jar dist/explorersfriend-spigot-paper-<ver>.jar
           [--web 8191] [--rcon 25691] [--game 25631]
"""
import argparse
import io
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request

TEMP = os.environ.get("TEMP", "/tmp")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = {
    "21": r"C:\Program Files\Java\jdk-21",
    "25": r"C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot",
}
UA = {"User-Agent": "explorersfriend-smoke"}


def fetch_json(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def download(url, target):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req) as r:
        io.open(target, "wb").write(r.read())


def paper_jar_url(mc):
    builds = fetch_json(f"https://fill.papermc.io/v3/projects/paper/versions/{mc}/builds")
    for build in builds:   # newest first
        downloads = build.get("downloads", {})
        entry = downloads.get("server:default") or downloads.get("application")
        if entry and entry.get("url"):
            return entry["url"]
    raise SystemExit(f"no paper build with a download for {mc}")


def rcon(port, password, command):
    s = socket.create_connection(("127.0.0.1", port), timeout=10)

    def send(rid, ptype, payload):
        data = struct.pack("<ii", rid, ptype) + payload.encode() + b"\x00\x00"
        s.sendall(struct.pack("<i", len(data)) + data)

    def recv():
        length = struct.unpack("<i", s.recv(4))[0]
        data = b""
        while len(data) < length:
            data += s.recv(length - len(data))
        return data[8:-2].decode(errors="replace")

    send(1, 3, password)
    recv()
    send(2, 2, command)
    out = recv()
    s.close()
    return out


def http_get(port, path):
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=UA)
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception:
        return 0, b""


def read_log(work):
    p = os.path.join(work, "logs", "latest.log")
    return io.open(p, encoding="utf-8", errors="replace").read() if os.path.exists(p) else ""


def start_server(work, exe):
    return subprocess.Popen([exe, "-Xmx2G", "-jar", "paper.jar", "nogui"],
                            cwd=work, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def wait_for(work, marker, timeout, proc):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if marker in read_log(work):
            return True
        if proc.poll() is not None:
            return marker in read_log(work)
        time.sleep(3)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", required=True)
    ap.add_argument("--java", default="21")
    ap.add_argument("--jar", required=True)
    ap.add_argument("--web", type=int, default=8191)
    ap.add_argument("--rcon", type=int, default=25691)
    ap.add_argument("--game", type=int, default=25631)
    args = ap.parse_args()

    work = os.path.join(TEMP, "ef-paper", args.mc)
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(os.path.join(work, "plugins", "ExplorersFriend"))
    print(f"[smoke] paper {args.mc}: downloading server...")
    download(paper_jar_url(args.mc), os.path.join(work, "paper.jar"))
    shutil.copy(args.jar, os.path.join(work, "plugins", os.path.basename(args.jar)))
    try:
        gp = fetch_json("https://api.modrinth.com/v2/project/griefprevention/version")
        gp_url = next(v["files"][0]["url"] for v in gp if args.mc in v["game_versions"])
        download(gp_url, os.path.join(work, "plugins", "GriefPrevention.jar"))
        print("[smoke] plugin: GriefPrevention")
    except Exception as e:
        print(f"[smoke] WARN: no GriefPrevention for {args.mc}: {e}")

    io.open(os.path.join(work, "eula.txt"), "w").write("eula=true\n")
    io.open(os.path.join(work, "server.properties"), "w").write(
        f"online-mode=false\nserver-port={args.game}\nenable-rcon=true\n"
        f"rcon.port={args.rcon}\nrcon.password=efsmoke\nview-distance=4\n"
        f"simulation-distance=4\nlevel-seed=efsmoke\n")
    io.open(os.path.join(work, "plugins", "ExplorersFriend", "config.jsonc"), "w").write(
        '{ "web": { "port": %d } }\n' % args.web)

    exe = os.path.join(JAVA[args.java], "bin", "java.exe")
    checks = {}
    proc = start_server(work, exe)
    try:
        checks["ready"] = wait_for(work, "[ExplorersFriend/Init] Ready in", 600, proc)
        text = read_log(work)
        checks["platformDetected"] = "[ExplorersFriend/Platform] Platform detected:" in text
        checks["paletteReady"] = "Runtime palette ready" in text
        checks["biomeTints"] = "Computed biome tints" in text
        checks["gpLogHonest"] = "GriefPrevention" in text

        status, body = http_get(args.web, "/api/status")
        checks["webStatus"] = status == 200 and b'"platform"' in body
        status, body = http_get(args.web, "/api/worlds")
        checks["webWorlds"] = status == 200 and b'"slug"' in body
        checks["gpAdapterActive"] = "GriefPrevention: adapter active" in text
        status, body = http_get(args.web, "/api/v1/claims?world=minecraft_overworld")
        checks["claimsEndpoint"] = status == 200 and b"revision" in body
        status, body = http_get(args.web, "/api/v1/players")
        checks["playersEndpoint"] = status == 200
        status, body = http_get(args.web, "/api/v1/markers?world=minecraft_overworld")
        checks["markersEndpoint"] = status == 200 and b"revision" in body
        status, body = http_get(args.web, "/api/v1/overlays")
        checks["overlaysEndpoint"] = status == 200 and b"layers" in body

        rcon(args.rcon, "efsmoke", "save-all flush")
        time.sleep(8)
        out = rcon(args.rcon, "efsmoke", "efmap render minecraft_overworld 256")
        checks["renderCommand"] = "started" in out
        tile_seen = False
        deadline = time.time() + 180
        while time.time() < deadline and not tile_seen:
            time.sleep(5)
            status, body = http_get(args.web, "/tiles/minecraft_overworld/0/0_0.png")
            tile_seen = status == 200 and body[:4] == b"\x89PNG"
            if not tile_seen:
                status, body = http_get(args.web, "/tiles/minecraft_overworld/0/-1_-1.png")
                tile_seen = status == 200 and body[:4] == b"\x89PNG"
            if not tile_seen:
                import glob as _glob
                tile_seen = bool(_glob.glob(os.path.join(
                    work, "explorersfriend", "tiles", "minecraft_overworld", "0", "*.png")))
        checks["tileRendered"] = tile_seen

        rcon(args.rcon, "efsmoke", "stop")
        try:
            proc.wait(timeout=120)
            checks["cleanShutdown"] = True
        except subprocess.TimeoutExpired:
            proc.kill()
            checks["cleanShutdown"] = False

        stale = os.path.join(work, "logs", "latest.log")
        if os.path.exists(stale):
            os.remove(stale)   # avoid matching the first run's log before rotation
        proc2 = start_server(work, exe)
        checks["secondStartReady"] = wait_for(work, "[ExplorersFriend/Init] Ready in", 300, proc2)
        checks["secondStartCacheHit"] = "Block colors loaded from cache" in read_log(work)
        try:
            rcon(args.rcon, "efsmoke", "stop")
            proc2.wait(timeout=120)
        except Exception:
            proc2.kill()
    finally:
        if proc.poll() is None:
            proc.kill()

    passed = all(checks.values())
    print(f"[smoke] spigot-paper on {args.mc}: " + ("passed" if passed else "FAILED"))
    for k, v in checks.items():
        print(f"  {k}: {'ok' if v else 'FAIL'}")

    results_file = os.path.join(ROOT, "dist", "test-results.json")
    results = {}
    if os.path.exists(results_file):
        results = json.load(io.open(results_file, encoding="utf-8"))
    results.setdefault("spigot-paper", {"versions": {}})
    results["spigot-paper"]["versions"][args.mc] = {
        "smoke": "passed" if passed else "failed", "checks": {k: "ok" if v else "fail" for k, v in checks.items()}}
    results["spigot-paper"]["smoke"] = "passed" if all(
        v.get("smoke") == "passed" for v in results["spigot-paper"]["versions"].values()) else "failed"
    io.open(results_file, "w", encoding="utf-8").write(json.dumps(results, indent=2))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
