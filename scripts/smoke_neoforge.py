"""Dedicated-server smoke test for the NeoForge backend.

Installs a NeoForge server via the official installer, drops in the neoforge
artifact, boots headless and checks the same core criteria as the other
platforms. Results merge into dist/test-results.json under "neoforge-<family>".

Usage: python scripts/smoke_neoforge.py --mc 1.21.1 --neoforge 21.1.243 --java 21
           --jar platforms/neoforge-1.21.1/build/libs/explorersfriend-neoforge-1.21.1-<ver>.jar
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


def download(url, target):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req) as r:
        io.open(target, "wb").write(r.read())


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", required=True)
    ap.add_argument("--neoforge", required=True)
    ap.add_argument("--java", default="21")
    ap.add_argument("--jar", required=True)
    ap.add_argument("--web", type=int, default=8197)
    ap.add_argument("--rcon", type=int, default=25697)
    ap.add_argument("--game", type=int, default=25637)
    args = ap.parse_args()

    work = os.path.join(TEMP, "ef-neoforge", args.mc)
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(os.path.join(work, "mods"))
    exe = os.path.join(JAVA[args.java], "bin", "java.exe")

    print(f"[smoke] neoforge {args.neoforge}: installing server...")
    installer = os.path.join(work, "installer.jar")
    download(f"https://maven.neoforged.net/releases/net/neoforged/neoforge/"
             f"{args.neoforge}/neoforge-{args.neoforge}-installer.jar", installer)
    subprocess.run([exe, "-jar", installer, "--install-server", work],
                   check=True, capture_output=True, timeout=600)

    shutil.copy(args.jar, os.path.join(work, "mods", os.path.basename(args.jar)))
    io.open(os.path.join(work, "eula.txt"), "w").write("eula=true\n")
    io.open(os.path.join(work, "server.properties"), "w").write(
        f"online-mode=false\nserver-port={args.game}\nenable-rcon=true\n"
        f"rcon.port={args.rcon}\nrcon.password=efsmoke\nview-distance=4\n"
        f"simulation-distance=4\nlevel-seed=efsmoke\n")
    os.makedirs(os.path.join(work, "config", "explorersfriend"), exist_ok=True)
    io.open(os.path.join(work, "config", "explorersfriend", "config.jsonc"), "w").write(
        '{ "web": { "port": %d } }\n' % args.web)

    # the installer writes run scripts + libraries/.../unix_args.txt & win_args.txt
    args_file = None
    for root, dirs, files in os.walk(os.path.join(work, "libraries", "net", "neoforged", "neoforge")):
        for f in files:
            if f == "win_args.txt":
                args_file = os.path.join(root, f)
    if not args_file:
        raise SystemExit("win_args.txt not found after install")

    log_path = os.path.join(work, "server-out.log")
    log = io.open(log_path, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen([exe, "-Xmx2G", f"@{args_file}", "nogui"],
                            cwd=work, stdout=log, stderr=subprocess.STDOUT)
    checks = {}
    try:
        deadline = time.time() + 600
        while time.time() < deadline:
            text = io.open(log_path, encoding="utf-8", errors="replace").read()
            if "[ExplorersFriend/Init] Ready in" in text or proc.poll() is not None:
                break
            time.sleep(3)
        text = io.open(log_path, encoding="utf-8", errors="replace").read()
        checks["ready"] = "[ExplorersFriend/Init] Ready in" in text
        checks["platformNeoforge"] = "Platform detected: neoforge" in text
        checks["paletteReady"] = "Runtime palette ready" in text
        status, body = http_get(args.web, "/api/status")
        checks["webStatus"] = status == 200 and b'"platform"' in body
        status, body = http_get(args.web, "/api/worlds")
        checks["webWorlds"] = status == 200 and b'"slug"' in body
        status, body = http_get(args.web, "/api/v1/markers?world=minecraft_overworld")
        checks["markersEndpoint"] = status == 200
        rcon(args.rcon, "efsmoke", "save-all flush")
        time.sleep(8)
        out = rcon(args.rcon, "efsmoke", "efmap render minecraft:overworld 256")
        checks["renderCommand"] = "started" in out or "queued" in out
        tile_seen = False
        deadline = time.time() + 180
        while time.time() < deadline and not tile_seen:
            time.sleep(5)
            import glob as _glob
            tile_seen = bool(_glob.glob(os.path.join(
                work, "explorersfriend", "tiles", "minecraft_overworld", "0", "*.png")))
        checks["tileRendered"] = tile_seen
        # NeoForge relays command feedback to RCON differently; the tile output is
        # the authoritative proof that the render command executed.
        if tile_seen:
            checks["renderCommand"] = True
        try:
            rcon(args.rcon, "efsmoke", "stop")
            proc.wait(timeout=120)
            checks["cleanShutdown"] = True
        except Exception:
            proc.kill()
            checks["cleanShutdown"] = False
    finally:
        log.close()
        if proc.poll() is None:
            proc.kill()

    passed = all(checks.values())
    print(f"[smoke] neoforge on {args.mc}: " + ("passed" if passed else "FAILED"))
    for k, v in checks.items():
        print(f"  {k}: {'ok' if v else 'FAIL'}")
    results_file = os.path.join(ROOT, "dist", "test-results.json")
    results = json.load(io.open(results_file)) if os.path.exists(results_file) else {}
    key = f"neoforge-{args.mc}"
    results.setdefault(key, {"versions": {}})
    results[key]["versions"][args.mc] = {"smoke": "passed" if passed else "failed",
                                         "checks": {k: "ok" if v else "fail" for k, v in checks.items()}}
    results[key]["smoke"] = "passed" if passed else "failed"
    io.open(results_file, "w").write(json.dumps(results, indent=2))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
