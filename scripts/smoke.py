#!/usr/bin/env python3
"""Dedicated-server smoke test for one artifact on one Minecraft version.

Usage:
  python scripts/smoke.py --module fabric-1.21.1 --mc 1.21.1 --java 21 \
      --loader 0.16.14 --jar dist/explorersfriend-fabric-1.21.1-0.3.0.jar

Performs: fabric server setup (launcher + Fabric API), first start (scan + web up),
HTTP checks, clean stop, second start (cache hits), stop. Appends the result to
dist/test-results.json (consumed by packageAllVersions for the release manifest).
"""
import argparse
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request

JAVA_HOMES = {
    "21": r"C:\Program Files\Java\jdk-21",
    "25": r"C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot",
}
INSTALLER = "1.1.2"


def http_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def download(url, dest):
    if os.path.exists(dest):
        return
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)


def rcon(port, password, *commands):
    with socket.create_connection(("127.0.0.1", port), timeout=15) as s:
        def send(rid, ptype, body):
            payload = struct.pack("<ii", rid, ptype) + body.encode() + b"\x00\x00"
            s.sendall(struct.pack("<i", len(payload)) + payload)

        def read():
            (length,) = struct.unpack("<i", s.recv(4))
            data = b""
            while len(data) < length:
                data += s.recv(length - len(data))
            return data[8:-2].decode(errors="replace")

        send(1, 3, password)
        read()
        out = []
        for i, c in enumerate(commands):
            send(10 + i, 2, c)
            out.append(read())
        return out


def wait_for(path, needle, timeout, fail_needles=()):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(path):
            text = open(path, encoding="utf-8", errors="replace").read()
            if needle in text:
                return text
            for bad in fail_needles:
                if bad in text:
                    raise RuntimeError(f"failure marker '{bad}' in log")
        time.sleep(2)
    raise RuntimeError(f"timeout waiting for '{needle}'")


def start_server(work, java_home, log_name):
    log = os.path.join(work, log_name)
    with open(log, "wb") as out:
        proc = subprocess.Popen(
            [os.path.join(java_home, "bin", "java.exe"), "-Xmx2G",
             "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=work, stdout=out, stderr=subprocess.STDOUT)
    return proc, log


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--module", required=True)
    ap.add_argument("--mc", required=True)
    ap.add_argument("--java", required=True, choices=["21", "25"])
    ap.add_argument("--loader", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--port", type=int, default=8199)
    ap.add_argument("--rcon-port", type=int, default=25698)
    ap.add_argument("--workdir", default=None)
    ap.add_argument("--game-port", type=int, default=25599)
    args = ap.parse_args()

    java_home = JAVA_HOMES[args.java]
    work = args.workdir or os.path.join(os.environ.get("TEMP", "/tmp"),
                                        "ef-smoke", args.mc)
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(os.path.join(work, "mods"))
    os.makedirs(os.path.join(work, "config", "explorersfriend"))

    print(f"[smoke] {args.module} on MC {args.mc} (Java {args.java})")
    download(f"https://meta.fabricmc.net/v2/versions/loader/{args.mc}/{args.loader}/{INSTALLER}/server/jar",
             os.path.join(work, "fabric-server-launch.jar"))
    api = http_json(f"https://api.modrinth.com/v2/project/fabric-api/version"
                    f"?game_versions=%5B%22{args.mc}%22%5D&loaders=%5B%22fabric%22%5D")[0]
    download(api["files"][0]["url"], os.path.join(work, "mods", "fabric-api.jar"))
    shutil.copy(args.jar, os.path.join(work, "mods", os.path.basename(args.jar)))

    open(os.path.join(work, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(work, "server.properties"), "w").write(
        f"online-mode=false\nserver-port={args.game_port}\nenable-rcon=true\n"
        f"rcon.port={args.rcon_port}\nrcon.password=efsmoke\nview-distance=4\n"
        f"simulation-distance=4\nlevel-seed=efsmoke\nsync-chunk-writes=false\n")
    open(os.path.join(work, "config", "explorersfriend", "config.jsonc"), "w").write(
        '{ "web": { "port": %d } }\n' % args.port)

    result = {"module": args.module, "mc": args.mc, "smoke": "failed", "checks": {}}
    try:
        proc, log = start_server(work, java_home, "start1.log")
        text = wait_for(log, "[ExplorersFriend/Init] Ready in", 420,
                        ("Incompatible mods", "Mixin apply failed", "Exception in server tick"))
        result["checks"]["start"] = "ok"
        result["checks"]["scan"] = "ok" if "Mod inventory completed" in text else "missing"
        result["checks"]["noClientClasses"] = \
            "ok" if "client" not in text.lower() or "ClassNotFoundException" not in text else "suspect"

        status = http_json(f"http://127.0.0.1:{args.port}/api/v1/status")
        assert status.get("ready") is True
        worlds = http_json(f"http://127.0.0.1:{args.port}/api/v1/worlds")
        assert worlds.get("worlds"), "no worlds"
        result["checks"]["web"] = "ok"
        result["checks"]["markersLoaded"] = "ok" if "persistent marker" in text else "missing"
        result["checks"]["playersLayer"] = "ok" if "Live player layer" in text else "missing"
        result["checks"]["claimsDetection"] = "ok" if "Active providers" in text else "missing"

        rcon(args.rcon_port, "efsmoke", "stop")
        proc.wait(timeout=90)
        stop_text = open(log, encoding="utf-8", errors="replace").read()
        result["checks"]["cleanShutdown"] = "ok" if "Shutdown complete" in stop_text else "missing"

        proc, log2 = start_server(work, java_home, "start2.log")
        text2 = wait_for(log2, "[ExplorersFriend/Init] Ready in", 300)
        result["checks"]["secondStartCacheHits"] = \
            "ok" if "Cache hits:" in text2 and "New: 0" in text2 else "missing"
        rcon(args.rcon_port, "efsmoke", "stop")
        proc.wait(timeout=90)

        failed = [k for k, v in result["checks"].items() if v not in ("ok",)]
        result["smoke"] = "passed" if not failed else f"partial:{','.join(failed)}"
    except Exception as e:
        result["error"] = str(e)
        try:
            proc.kill()
        except Exception:
            pass
    finally:
        os.makedirs("dist", exist_ok=True)
        results_path = os.path.join("dist", "test-results.json")
        all_results = {}
        if os.path.exists(results_path):
            all_results = json.load(open(results_path, encoding="utf-8"))
        module = all_results.setdefault(args.module, {"versions": {}})
        module["versions"][args.mc] = result
        statuses = [v.get("smoke") for v in module["versions"].values()]
        module["smoke"] = "passed" if all(s == "passed" for s in statuses) else "failed"
        json.dump(all_results, open(results_path, "w", encoding="utf-8"), indent=2)
        print(f"[smoke] result: {result['smoke']}"
              + (f" ({result.get('error')})" if result.get("error") else ""))
    sys.exit(0 if result["smoke"] == "passed" else 1)


if __name__ == "__main__":
    main()
