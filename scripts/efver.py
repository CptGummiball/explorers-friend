"""efver - build-attempt versioning for The Explorer's Friend (X.Y.Z scheme).

X = release (manual), Y = subversion (fixes/changes), Z = build attempt within
the subversion. Z starts at 1, increments after every failed attempt, and the
first successful attempt freezes the version. Failed numbers are never reused;
released numbers are never rebuilt. Full concept: docs/VERSIONING.md.

State lives in versioning/version.json (single source of truth), every claim and
result is appended to versioning/history.jsonl (append-only audit log). A lock
file plus atomic replace makes concurrent local invocations safe; CI serializes
through a concurrency group and git's atomic push (see the workflow).

Commands:
  init --start X.Y      one-time adoption (refuses if state exists)
  status                show current state
  claim                 reserve the next build number (Z+1), sync gradle.properties
  report success|failure  record the outcome of the claimed build
  bump-sub              Y+1, Z resets (next claim -> X.Y+1.1)
  bump-release          X+1, Y=0, Z resets (next claim -> X+1.0.1)
  build -- CMD...       claim, run CMD, auto-report from its exit code
"""
import argparse
import io
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class Paths:
    def __init__(self, base):
        self.dir = base
        self.state = os.path.join(base, "version.json")
        self.history = os.path.join(base, "history.jsonl")
        self.lock = os.path.join(base, "version.lock")


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Lock:
    """Exclusive-create lock file; stale after 10 minutes. Windows-safe."""

    def __init__(self, path, timeout=30):
        self.path = path
        self.timeout = timeout
        self.fd = None

    def __enter__(self):
        deadline = time.time() + self.timeout
        while True:
            try:
                self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(self.fd, f"{os.getpid()} {now()}".encode())
                return self
            except FileExistsError:
                try:
                    if time.time() - os.path.getmtime(self.path) > 600:
                        os.remove(self.path)   # stale lock from a killed process
                        continue
                except OSError:
                    pass
                if time.time() > deadline:
                    sys.exit(f"efver: lock {self.path} is held by another build "
                             f"(delete it only if you are sure no build is running)")
                time.sleep(0.5)

    def __exit__(self, *exc):
        if self.fd is not None:
            os.close(self.fd)
        try:
            os.remove(self.path)
        except OSError:
            pass


def read_state(p):
    if not os.path.isfile(p.state):
        sys.exit("efver: no version state - run: python scripts/efver.py init --start X.Y")
    return json.load(io.open(p.state, encoding="utf-8"))


def write_state(p, state):
    """Atomic: temp file + fsync + os.replace."""
    tmp = p.state + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p.state)


def append_history(p, entry):
    with io.open(p.history, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")
        f.flush()
        os.fsync(f.fileno())


def version_str(state, z=None):
    return f"{state['x']}.{state['y']}.{state['z'] if z is None else z}"


def sync_gradle(p, version):
    if os.path.normpath(p.dir) != os.path.normpath(os.path.join(ROOT, "versioning")):
        return   # test state directories never touch the real gradle.properties
    path = os.path.join(ROOT, "gradle.properties")
    if not os.path.isfile(path):
        return
    lines = io.open(path, encoding="utf-8").read().splitlines(keepends=True)
    out = []
    for line in lines:
        if line.startswith("mod_version="):
            out.append(f"mod_version={version}\n")
        else:
            out.append(line)
    io.open(path, "w", encoding="utf-8").writelines(out)
    print(f"efver: gradle.properties -> mod_version={version}")


def tag_exists(version):
    try:
        r = subprocess.run(["git", "tag", "-l", f"v{version}"], cwd=ROOT,
                           capture_output=True, text=True, timeout=30)
        return bool(r.stdout.strip())
    except Exception:
        return False   # git unavailable: skip the extra guard


def cmd_init(p, args):
    if os.path.isfile(p.state):
        sys.exit(f"efver: {p.state} already exists - init is one-time only")
    try:
        x, y = (int(v) for v in args.start.split("."))
    except ValueError:
        sys.exit("efver: --start must be X.Y (e.g. 0.4)")
    os.makedirs(p.dir, exist_ok=True)
    state = {
        "schemaVersion": 1,
        "x": x, "y": y, "z": 0,
        "lastResult": "none",
        "updatedAt": now(),
        "note": ("Adopted with docs/VERSIONING.md. Legacy versions "
                 "(0.1.0/0.2.0/0.2.1/0.3.0 and every build before adoption) are "
                 "frozen and outside this scheme."),
    }
    write_state(p, state)
    append_history(p, {"at": now(), "event": "init", "start": f"{x}.{y}",
                       "firstBuildWillBe": f"{x}.{y}.1"})
    print(f"efver: initialized at X.Y = {x}.{y} - first build attempt will be {x}.{y}.1")


def cmd_status(p, args):
    state = read_state(p)
    v = version_str(state)
    print(f"version:    {v} ({'released' if state['lastResult'] == 'success' else state['lastResult']})")
    print(f"next claim: " + (
        "blocked - bump-sub or bump-release first" if state["lastResult"] == "success"
        else "blocked - report the pending build first" if state["lastResult"] == "pending"
        else version_str(state, state["z"] + 1)))


def cmd_claim(p, args):
    with Lock(p.lock):
        state = read_state(p)
        if state["lastResult"] == "success":
            sys.exit(f"efver: {version_str(state)} is already released - "
                     f"run bump-sub or bump-release before building again "
                     f"(released numbers are never rebuilt)")
        if state["lastResult"] == "pending":
            sys.exit(f"efver: attempt {version_str(state)} is still pending - another "
                     f"build is running, or a crashed build never reported. If crashed: "
                     f"python scripts/efver.py report failure   (the number stays burned)")
        z = state["z"] + 1
        version = version_str(state, z)
        if tag_exists(version):
            sys.exit(f"efver: git tag v{version} already exists - refusing to reuse a "
                     f"published number. Fix versioning/version.json deliberately.")
        state.update(z=z, lastResult="pending", updatedAt=now())
        write_state(p, state)
        append_history(p, {"at": now(), "event": "claim", "version": version})
        if not args.no_gradle:
            sync_gradle(p, version)
        print(version)


def cmd_report(p, args):
    with Lock(p.lock):
        state = read_state(p)
        if state["lastResult"] != "pending":
            sys.exit(f"efver: nothing pending to report (state: {state['lastResult']})")
        state.update(lastResult="success" if args.result == "success" else "failed",
                     updatedAt=now())
        write_state(p, state)
        append_history(p, {"at": now(), "event": args.result,
                           "version": version_str(state)})
        if args.result == "success":
            print(f"efver: {version_str(state)} is final - publish this build; the next "
                  f"change needs bump-sub or bump-release")
        else:
            print(f"efver: {version_str(state)} burned - next attempt will be "
                  f"{version_str(state, state['z'] + 1)}")


def _bump(p, release):
    with Lock(p.lock):
        state = read_state(p)
        if state["lastResult"] == "pending":
            sys.exit("efver: a build attempt is pending - report it before bumping")
        if release:
            state.update(x=state["x"] + 1, y=0, z=0)
        else:
            state.update(y=state["y"] + 1, z=0)
        state.update(lastResult="none", updatedAt=now())
        write_state(p, state)
        append_history(p, {"at": now(),
                           "event": "bump-release" if release else "bump-sub",
                           "nextBuildWillBe": version_str(state, 1)})
        print(f"efver: next build attempt will be {version_str(state, 1)}")


def cmd_build(p, args):
    if not args.cmd:
        sys.exit("efver: build needs a command after -- (e.g. build -- ./gradlew buildAllVersions)")
    claim_args = argparse.Namespace(no_gradle=False)
    cmd_claim(p, claim_args)
    code = subprocess.call(args.cmd, cwd=ROOT, shell=False)
    report_args = argparse.Namespace(result="success" if code == 0 else "failure")
    cmd_report(p, report_args)
    sys.exit(code)


def main():
    ap = argparse.ArgumentParser(prog="efver")
    ap.add_argument("--dir", default=os.path.join(ROOT, "versioning"),
                    help="state directory (tests only)")
    sub = ap.add_subparsers(dest="command", required=True)
    s = sub.add_parser("init"); s.add_argument("--start", required=True)
    sub.add_parser("status")
    s = sub.add_parser("claim"); s.add_argument("--no-gradle", action="store_true")
    s = sub.add_parser("report"); s.add_argument("result", choices=["success", "failure"])
    sub.add_parser("bump-sub")
    sub.add_parser("bump-release")
    s = sub.add_parser("build"); s.add_argument("cmd", nargs=argparse.REMAINDER)
    args = ap.parse_args()
    p = Paths(args.dir)
    if args.command == "build" and args.cmd and args.cmd[0] == "--":
        args.cmd = args.cmd[1:]
    {"init": cmd_init, "status": cmd_status, "claim": cmd_claim,
     "report": cmd_report, "build": cmd_build,
     "bump-sub": lambda p, a: _bump(p, False),
     "bump-release": lambda p, a: _bump(p, True)}[args.command](p, args)


if __name__ == "__main__":
    main()
