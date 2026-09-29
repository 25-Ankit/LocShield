# LocShield Android Experiments (Runtime Validation phase)

Isolated diagnostic apps + scripts. No enforcement code here — evidence only.

## Modules

| App | Package | GMS? | Used by |
|---|---|---|---|
| `location-client` | `shield.loc.client` | No | E-LMS-01 (LMS side), E-CACHE-01, E-RF-01, E-GNSS-01/02 |
| `gms-client` | `shield.loc.gms` | **REQUIRED** | E-LMS-01 (GMS side) |
| `producer` | `shield.loc.producer` | No | E-PAS-01 (active requester) |
| `passive-consumer` | `shield.loc.passive` | No | E-PAS-01 (passive listener) |
| `geofence-client` | `shield.loc.geofence` | **REQUIRED** (GMS part) | E-GEO-02 |

## Control

Headless via adb (no UI interaction needed):

```sh
adb shell am start -n shield.loc.client/.MainActivity --es action lms-last
adb logcat -s LS_DIAG
```

Each run appends one JSON line per observation to logcat tag `LS_DIAG` and to the
app's private files dir (`<action>.jsonl`). Coordinates are emulator-synthesized
(`adb emu geo fix`) and rounded to 2 decimals in logs.

## Data rules

No uploads, no telemetry, no personal data. Synthetic coordinates only.
Results (dumpsys/logcat excerpts) go under `results/<EXP-ID>/`.
