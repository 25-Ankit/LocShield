# LocShield Android Runtime Validation v1

- **Status:** DRAFT — live phase; results appended per experiment.
- **Frozen inputs:** Policy Engine v0.1 (188/188), Enforcement Matrix v1.
- **Constraints:** No service/APK/AIDL/AOSP implementation; evidence only. No uploads,
  no telemetry, synthetic emulator coordinates only.

## 1. Objective

Provide runtime evidence ([EXP]) for the highest-risk Matrix v1 claims across
E-LMS-01, E-GEO-02, E-GNSS-02, E-PAS-01, E-CACHE-01, E-RF-01, E-GNSS-01 plus a
side-channel baseline, on Android 14/15/16 where practical.

## 2. Test Environment

Host: Kali Linux 7.1.5 (x86_64, i5-8500, 6 cores, 7.6 GiB RAM).
**Constraint:** no `/dev/kvm`, no virtualization flags, no root → emulator runs
with `-accel off` (full TCG translation). Boot times are long; this is recorded
per AVD, not treated as an Android behavior.

SDK: cmdline-tools 12.0, platform-tools 37.0.1, emulator 37.1.11.0.
Diagnostic apps: `android-experiments/apks/` (5 modules, headless `--es action`).

| AVD | API | Image | ABI | GMS | Status |
|-----|-----|-------|-----|-----|--------|
| ls34 | 34 | google_apis x86_64 | x86_64 | yes (bundled) | booting (TCG) |

Per-AVD fingerprints, patch levels, and GMS versions are captured in
`results/<EXP>/env.txt` via `scripts/env-info.sh` at experiment time.

## 3. Emulator Images

- `system-images;android-34;google_apis;x86_64` (rev 14). GMS REQUIRED exps run here.
- android-35 / android-36 images: pending disk/time; see §13 for coverage matrix.

## 4. Experiment Methodology

Each app logs JSON lines to logcat tag `LS_DIAG` (plus app-private `<action>.jsonl`).
Procedures use `scripts/run-action.sh` (start + `dumpsys location` + filtered logcat
capture), `scripts/perms.sh` (grant/revoke FINE/COARSE), `adb emu geo fix` for
synthetic positions. Coordinates in logs rounded to 2 decimals. Evidence classes:
[DOC] documentation · [SRC] AOSP source · [EXP] this phase · [INF] inference.
Verdicts: CONFIRMED / REFUTED / PARTIALLY CONFIRMED / UNVERIFIED.
Version cells: PASS / FAIL / NOT AVAILABLE / NOT TESTED / INCONCLUSIVE
(PASS = interpretable evidence produced, not "app compiled").
