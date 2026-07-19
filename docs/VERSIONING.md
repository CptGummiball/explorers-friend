# Versioning (X.Y.Z build-attempt scheme)

Adopted 2026-07-19. Implemented by [`scripts/efver.py`](../scripts/efver.py) with
state under [`versioning/`](../versioning/).

## Scope and starting point

- The scheme applies **only to builds made after adoption**. Everything older is
  frozen: releases 0.1.0, 0.2.0, 0.2.1 and 0.3.0, their git tags, artifacts and
  every earlier build attempt keep their numbers forever. Nothing is renumbered,
  recalculated or pulled into the new logic.
- Starting point: the last **published** version, **0.3.0**. The feature work that
  was in progress under the internal label "0.4.0-dev" becomes subversion **Y=4**
  at **X=0** under the new scheme.
- **The first build attempt after adoption is `0.4.1`.** If it fails, the next is
  `0.4.2`, and so on; the first successful attempt freezes its number and that
  exact number is what gets published.

## Semantics

| Part | Meaning | When it changes |
| --- | --- | --- |
| **X** | Release version | Manually (`efver bump-release`). Y resets to 0, next build is `X+1.0.1`. |
| **Y** | Subversion | For fixes/changes within a release (`efver bump-sub`). Next build is `X.Y+1.1`. |
| **Z** | Build attempt | Starts at 1 per subversion. A failed attempt burns its number; the next attempt gets Z+1. A successful attempt freezes the version — it is published as-is and never rebuilt. |

Invariants the tooling enforces:

- **No duplicate numbers**: Z is strictly monotonic per subversion; a claim is
  refused if a git tag `vX.Y.Z` already exists.
- **Burned numbers stay burned**: every claim and every result is appended to
  `versioning/history.jsonl` (append-only audit log); failed attempts are never
  reused.
- **Released numbers are never rebuilt**: after a success, `claim` refuses until
  `bump-sub`/`bump-release` opens a new subversion. Existing release artifacts can
  therefore never be overwritten.
- **A crashed build burns its number**: `claim` marks the attempt `pending`
  *before* the build starts. If the build dies without reporting, the state stays
  `pending`; `efver report failure` closes it and the number is gone — it is never
  handed out again.

## 1. Version file structure

`versioning/version.json` — single source of truth, committed to git:

```json
{
  "schemaVersion": 1,
  "x": 0,
  "y": 4,
  "z": 0,
  "lastResult": "none",
  "updatedAt": "2026-07-19T16:10:00+00:00",
  "note": "Adopted with docs/VERSIONING.md. Legacy versions ... are frozen ..."
}
```

- `z` is the **last claimed** attempt (0 = none yet in this subversion).
- `lastResult`: `none` (fresh subversion) → `pending` (attempt running) →
  `failed` | `success`.
- `versioning/history.jsonl` records every `init`/`claim`/`failure`/`success`/
  `bump-*` event with timestamp — append-only, never rewritten.
- `versioning/version.lock` exists only while a command mutates state.

## 2. Initialization (already executed)

One-time, refuses to run twice:

```bash
python scripts/efver.py init --start 0.4
```

This wrote `versioning/version.json` with `0.4`, `z=0`, `lastResult=none` and a
history entry. `gradle.properties` keeps `mod_version=0.4.0-dev` until the first
claim replaces it with the claimed number. A manual jump to a new main release
instead would simply be `efver bump-release` before the first claim.

## 3. Build flow (local and CI)

Every **publishable** build goes through efver. Plain development builds
(`./gradlew buildAllVersions` during coding) do not claim numbers — they are not
attempts in the sense of this scheme and never get published.

Local, fully automatic (claim → build → auto-report from exit code):

```bash
python scripts/efver.py build -- ./gradlew clean buildAllVersions testAllVersions packageAllVersions verifyAllArtifacts
```

Or step by step: `efver claim` → run the build → `efver report success|failure`.

CI: `.github/workflows/release-build.yml` (manual `workflow_dispatch`, optional
input to bump Y or X first):

1. `concurrency: release-build` — GitHub serializes runs; a second dispatch waits.
2. Checkout with write permissions; optional `efver bump-sub`/`bump-release`.
3. `efver claim` and **commit + push the claimed state before building**
   (`[skip ci]`). The push is the reservation: git's atomic ref update makes it
   impossible for two builds to hold the same number (see §5).
4. Full build (`buildAllVersions testAllVersions packageAllVersions
   verifyAllArtifacts`).
5. `efver report success|failure`, commit + push the result, upload `dist/` as
   workflow artifacts on success. Releases themselves stay manual.

## 4. Safe increment and persistence

`efver claim` under the hood:

1. Acquire `versioning/version.lock` via exclusive create (`O_CREAT|O_EXCL`,
   retry up to 30 s, stale locks older than 10 min are broken). Works on Windows
   and Linux without extra dependencies.
2. Guards: refuse when `lastResult` is `success` (bump first) or `pending`
   (another build running / crashed) or when tag `vX.Y.Z+1` exists.
3. Set `z+1`, `lastResult=pending`; write via **temp file + fsync +
   `os.replace`** (atomic on NTFS and POSIX — the state file is never observable
   half-written).
4. Append the claim to `history.jsonl` (fsync'd), sync `mod_version` in
   `gradle.properties`, print the version.

`report` uses the same lock + atomic write and only transitions `pending →
failed|success`.

## 5. Parallel builds

- **Same machine**: the lock file serializes every state mutation; a second
  `claim` while one is `pending` is refused with a clear message — by design, one
  publishable build at a time (a burned number would otherwise be ambiguous).
- **CI**: the `concurrency` group serializes workflow runs. Defense in depth: the
  claim commit must be *pushed* before building. If two runners raced anyway, the
  second push is rejected as non-fast-forward; that runner rebases, sees
  `pending`/the burned number, and claims the **next** Z. Two builds can never
  publish the same number because a number only becomes final via the pushed
  `success` state, and `claim` refuses anything already claimed or released.
- **Mixed local + CI**: both mutate the same committed state file; whoever pushes
  first wins, the other rebases and re-claims. When in doubt, `efver status`
  shows exactly what the next claim will be or why claiming is blocked.

## Cheat sheet

```bash
python scripts/efver.py status          # where am I?
python scripts/efver.py build -- <cmd>  # claim + build + auto-report
python scripts/efver.py bump-sub       # fix line:   0.4.3 -> next 0.5.1
python scripts/efver.py bump-release   # main line:  0.5.2 -> next 1.0.1
```
