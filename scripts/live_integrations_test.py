"""Live integration verification for the 0.4.0 feature blocks.

Boots a real dedicated server from the cached positive-smoke dir with a set of
integration mods (resolved from Modrinth incl. one level of required deps), a
user-supplied custom icon, and checks log lines + HTTP endpoints + RCON output.

Usage: python scripts/live_integrations_test.py <case>
  a = 1.21.1 + LuckPerms + Waystones/Balm + GOML ReServed + custom icon
  b = 1.21.11 + FTB Chunks stack
  c = 26.2 + OPAC + Forge Config API Port
"""
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
VER = "0.4.0-dev"

CASES = {
    "a": dict(mc="1.21.1", java="21", jar=f"explorersfriend-fabric-1.21.1-{VER}.jar",
              mods=["luckperms", "balm", "waystones", "goml-reserved"],
              web=8171, rcon=25671, game=25611, loader="0.17.3"),
    "b": dict(mc="1.21.11", java="21", jar=f"explorersfriend-fabric-1.21.11-{VER}.jar",
              mods=[], direct=[
                  "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-fabric/2111.1.1/ftb-chunks-fabric-2111.1.1.jar",
                  "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-teams-fabric/2111.1.1/ftb-teams-fabric-2111.1.1.jar",
                  "https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-fabric/2111.1.1/ftb-library-fabric-2111.1.1.jar",
                  "https://maven.architectury.dev/dev/architectury/architectury-fabric/19.0.1/architectury-fabric-19.0.1.jar"],
              web=8172, rcon=25672, game=25612),
    "c": dict(mc="26.2", java="25", jar=f"explorersfriend-fabric-26.2-{VER}.jar",
              mods=["open-parties-and-claims", "forge-config-api-port"],
              web=8173, rcon=25673, game=25613),
}


def modrinth(url):
    req = urllib.request.Request(url, headers={"User-Agent": "explorersfriend-livetest"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def resolve_mod(slug, mc, seen):
    if slug in seen:
        return []
    seen.add(slug)
    versions = modrinth(f"https://api.modrinth.com/v2/project/{slug}/version"
                        f"?game_versions=%5B%22{mc}%22%5D&loaders=%5B%22fabric%22%5D")
    if not versions:
        print(f"[live] WARN: no fabric build of {slug} for {mc}")
        return []
    v = versions[0]
    out = [(slug, v["files"][0]["url"], v["files"][0]["filename"])]
    for dep in v.get("dependencies", []):
        if dep.get("dependency_type") == "required" and dep.get("project_id"):
            p = modrinth(f"https://api.modrinth.com/v2/project/{dep['project_id']}")
            if p["slug"] not in ("fabric-api",):
                out += resolve_mod(p["slug"], mc, seen)
    return out


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
        rid, ptype = struct.unpack("<ii", data[:8])
        return rid, data[8:-2].decode(errors="replace")

    send(1, 3, password)
    recv()
    send(2, 2, command)
    _, payload = recv()
    s.close()
    return payload


def http_get(port, path):
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}",
                                     headers={"User-Agent": "livetest"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception:
        return 0, b""


def main():
    case = sys.argv[1]
    cfg = CASES[case]
    src = os.path.join(TEMP, "ef-smoke", cfg["mc"])
    work = os.path.join(TEMP, "ef-live", case)
    if os.path.exists(work):
        shutil.rmtree(work)
    shutil.copytree(src, work, ignore=shutil.ignore_patterns(
        "mods", "logs", "world", "config", "crash-reports", "start*.log",
        "fabricloader.log", "explorersfriend"))
    if cfg.get("loader"):
        url = (f"https://meta.fabricmc.net/v2/versions/loader/{cfg['mc']}/"
               f"{cfg['loader']}/1.1.2/server/jar")
        print(f"[live] fresh launcher: loader {cfg['loader']}")
        urllib.request.urlretrieve(url, os.path.join(work, "fabric-server-launch.jar"))
        for stale in (".fabric", "libraries", "versions"):
            shutil.rmtree(os.path.join(work, stale), ignore_errors=True)
    mods = os.path.join(work, "mods")
    os.makedirs(mods)
    shutil.copy(os.path.join(src, "mods", "fabric-api.jar"), os.path.join(mods, "fabric-api.jar"))
    shutil.copy(os.path.join(ROOT, "dist", cfg["jar"]), os.path.join(mods, cfg["jar"]))
    for url in cfg.get("direct", []):
        filename = url.rsplit("/", 1)[-1]
        print(f"[live] mod: {filename}")
        req = urllib.request.Request(url, headers={"User-Agent": "explorersfriend-livetest"})
        with urllib.request.urlopen(req) as r:
            io.open(os.path.join(mods, filename), "wb").write(r.read())
    seen = set()
    for slug in cfg["mods"]:
        for name, url, filename in resolve_mod(slug, cfg["mc"], seen):
            print(f"[live] mod: {filename}")
            urllib.request.urlretrieve(url, os.path.join(mods, filename))

    os.makedirs(os.path.join(work, "config", "explorersfriend", "icons"))
    if case == "a":
        import struct, zlib

        def make_png(width=16, height=16):
            def chunk(typ, data):
                c = struct.pack(">I", len(data)) + typ + data
                return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
            ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
            filt = bytes([0])
            row = filt + bytes([255, 200, 50, 255] * width)
            raw_px = row * height
            sig = bytes([137]) + b"PNG" + bytes([13, 10, 26, 10])
            return (sig + chunk(b"IHDR", ihdr)
                    + chunk(b"IDAT", zlib.compress(raw_px)) + chunk(b"IEND", b""))

        io.open(os.path.join(work, "config", "explorersfriend", "icons", "star.png"),
                "wb").write(make_png())
    io.open(os.path.join(work, "config", "explorersfriend", "config.jsonc"), "w").write(
        '{ "web": { "port": %d } }\n' % cfg["web"])
    io.open(os.path.join(work, "server.properties"), "w").write(
        f"online-mode=false\nserver-port={cfg['game']}\nenable-rcon=true\n"
        f"rcon.port={cfg['rcon']}\nrcon.password=eflive\nview-distance=4\n"
        f"simulation-distance=4\nlevel-seed=eflive\nsync-chunk-writes=false\n")
    io.open(os.path.join(work, "eula.txt"), "w").write("eula=true\n")

    exe = os.path.join(JAVA[cfg["java"]], "bin", "java.exe")
    log_path = os.path.join(work, "server-out.log")
    log = io.open(log_path, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen([exe, "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
                            cwd=work, stdout=log, stderr=subprocess.STDOUT)
    checks = {}
    try:
        deadline = time.time() + 420
        ready = False
        while time.time() < deadline:
            time.sleep(3)
            text = io.open(log_path, encoding="utf-8", errors="replace").read()
            if "[ExplorersFriend/Init] Ready in" in text:
                ready = True
                break
            if proc.poll() is not None:
                break
        checks["ready"] = ready
        text = io.open(log_path, encoding="utf-8", errors="replace").read()
        if case == "a":
            checks["customIconLoaded"] = "custom icon(s) loaded" in text
            checks["waystonesDetected"] = "Waystones detected" in text
            checks["cpaDetected"] = "Common Protection API: detected" in text
            checks["luckPermsLoaded"] = "luckperms" in text.lower()
            status, body = http_get(cfg["web"], "/icons/c/star.png")
            checks["customIconServed"] = status == 200 and body[:4] == b"\x89PNG"
            status, body = http_get(cfg["web"], "/api/v1/icons")
            checks["iconListHasCustom"] = status == 200 and b'"star"' in body
            status, body = http_get(cfg["web"], "/api/v1/waystones?world=minecraft_overworld")
            checks["waystonesEndpoint"] = status == 200 and b"revision" in body
            status, body = http_get(cfg["web"], "/api/v1/overlays")
            checks["overlaysListWaystones"] = status == 200 and b'"waystones"' in body
            out = rcon(cfg["rcon"], "eflive", "efmap marker icons")
            checks["iconsCommandListsCustom"] = "custom:star" in out
            out = rcon(cfg["rcon"], "eflive", "efmap status")
            checks["statusCommandWorks"] = "ExplorersFriend" in out or "tiles" in out or len(out) > 10
        if case == "b":
            checks["ftbDetected"] = "FTB Chunks: detected" in text
            status, body = http_get(cfg["web"], "/api/v1/claims?world=minecraft_overworld")
            checks["claimsEndpoint"] = status == 200
        if case == "c":
            checks["opacDetected"] = "Open Parties and Claims: detected" in text
            status, body = http_get(cfg["web"], "/api/v1/claims?world=minecraft_overworld")
            checks["claimsEndpoint"] = status == 200
        try:
            rcon(cfg["rcon"], "eflive", "stop")
        except Exception:
            proc.kill()
        try:
            proc.wait(timeout=90)
            checks["cleanShutdown"] = True
        except subprocess.TimeoutExpired:
            proc.kill()
            checks["cleanShutdown"] = False
    finally:
        log.close()
        if proc.poll() is None:
            proc.kill()

    passed = all(checks.values())
    print(f"[live] case {case}: " + ("PASSED" if passed else "FAILED"))
    for k, v in checks.items():
        print(f"  {k}: {'ok' if v else 'FAIL'}")
    out_file = os.path.join(ROOT, "dist", f"live-test-{case}.json")
    io.open(out_file, "w").write(json.dumps(checks, indent=2))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
