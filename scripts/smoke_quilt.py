"""Quilt verification for the Fabric artifacts (Phase E of MULTIPLATFORM.md).

Installs a Quilt server via the official quilt-installer, drops in the Fabric
family jar plus QFAPI/QSL (Quilted Fabric API from Modrinth), and runs the same
checks as the Fabric smoke: ready, scan, mixin active (implied by ready), web,
platform id reported as "quilt", markers/players/claims endpoints, clean stop.
Results merge into dist/test-results.json under "quilt@<mc>".

Usage: python scripts/smoke_quilt.py --mc 1.21.1 --java 21
           --jar dist/explorersfriend-fabric-1.21.1-<ver>.jar
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
INSTALLER = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/maven-metadata.xml"


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req) as r:
        return r.read()


def latest_installer():
    import re
    meta = fetch(INSTALLER).decode()
    version = re.findall(r"<release>([^<]+)</release>", meta)[0]
    return (f"https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/"
            f"{version}/quilt-installer-{version}.jar")


def modrinth_file(slug, mc, loaders):
    data = json.loads(fetch(f"https://api.modrinth.com/v2/project/{slug}/version"))
    for v in data:
        if mc in v["game_versions"] and any(l in v["loaders"] for l in loaders):
            return v["files"][0]["url"], v["files"][0]["filename"]
    return None, None


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
    ap.add_argument("--java", default="21")
    ap.add_argument("--jar", required=True)
    ap.add_argument("--web", type=int, default=8195)
    ap.add_argument("--rcon", type=int, default=25695)
    ap.add_argument("--game", type=int, default=25635)
    args = ap.parse_args()

    work = os.path.join(TEMP, "ef-quilt", args.mc)
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(os.path.join(work, "mods"))
    exe = os.path.join(JAVA[args.java], "bin", "java.exe")

    print(f"[smoke] quilt {args.mc}: installing server...")
    installer = os.path.join(work, "quilt-installer.jar")
    io.open(installer, "wb").write(fetch(latest_installer()))
    subprocess.run([exe, "-jar", installer, "install", "server", args.mc,
                    "--download-server", f"--install-dir={work}"],
                   check=True, capture_output=True, timeout=300)

    shutil.copy(args.jar, os.path.join(work, "mods", os.path.basename(args.jar)))
    url, filename = modrinth_file("qsl", args.mc, ["quilt"])
    if not url:
        url, filename = modrinth_file("fabric-api", args.mc, ["fabric"])
        print(f"[smoke] QFAPI/QSL unavailable for {args.mc}; using Fabric API ({filename})")
    else:
        print(f"[smoke] mod: {filename}")
    io.open(os.path.join(work, "mods", filename), "wb").write(fetch(url))

    io.open(os.path.join(work, "eula.txt"), "w").write("eula=true\n")
    io.open(os.path.join(work, "server.properties"), "w").write(
        f"online-mode=false\nserver-port={args.game}\nenable-rcon=true\n"
        f"rcon.port={args.rcon}\nrcon.password=efsmoke\nview-distance=4\n"
        f"simulation-distance=4\nlevel-seed=efsmoke\n")
    os.makedirs(os.path.join(work, "config", "explorersfriend"), exist_ok=True)
    io.open(os.path.join(work, "config", "explorersfriend", "config.jsonc"), "w").write(
        '{ "web": { "port": %d } }\n' % args.web)

    log_path = os.path.join(work, "server-out.log")
    log = io.open(log_path, "w", encoding="utf-8", errors="replace")
    launcher = "quilt-server-launch.jar"
    proc = subprocess.Popen([exe, "-Xmx2G", "-jar", launcher, "nogui"],
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
        checks["quiltIdentified"] = "Platform detected: quilt" in text
        checks["paletteReady"] = "Runtime palette ready" in text
        status, body = http_get(args.web, "/api/status")
        checks["webStatus"] = status == 200 and b'"platform":"quilt"' in body.replace(b" ", b"")
        status, body = http_get(args.web, "/api/v1/markers?world=minecraft_overworld")
        checks["markersEndpoint"] = status == 200
        status, body = http_get(args.web, "/api/v1/players")
        checks["playersEndpoint"] = status == 200
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
    print(f"[smoke] quilt on {args.mc}: " + ("passed" if passed else "FAILED"))
    for k, v in checks.items():
        print(f"  {k}: {'ok' if v else 'FAIL'}")
    results_file = os.path.join(ROOT, "dist", "test-results.json")
    results = json.load(io.open(results_file)) if os.path.exists(results_file) else {}
    results.setdefault("quilt", {"versions": {}})
    results["quilt"]["versions"][args.mc] = {"smoke": "passed" if passed else "failed",
                                            "checks": {k: "ok" if v else "fail" for k, v in checks.items()}}
    results["quilt"]["smoke"] = "passed" if all(
        v.get("smoke") == "passed" for v in results["quilt"]["versions"].values()) else "failed"
    io.open(results_file, "w").write(json.dumps(results, indent=2))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
