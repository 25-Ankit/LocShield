# LocShield — LSPosed Enforcement Prototype Readiness v1

- **Status:** READINESS / SPECIFICATION ONLY — no hooks implemented, no source modified.
- **Phase:** Phase 1 of `LOCShield — LSPosed Enforcement Prototype`
- **Frozen core:** Policy Engine v0.1 (`locshield-policy/`, tag `locshield-v0.1-policy-engine-frozen`) — **unchanged and unchangeable by this prototype.**
- **Claim discipline used below:**
  - **[FACT]** — documented in frozen LocShield specs, AOSP/Google public docs, or directly observed in this repo/environment.
  - **[OBSERVED]** — seen live in this session (commands run, outputs quoted or summarized).
  - **[HYPOTHESIS]** — engineering expectation, explicitly untested. Nothing marked this way may be cited as working.

---

## A. Prototype Objective

Validate the LocShield policy model against real Android location APIs by interposing a **disposable, explicitly non-production** enforcement adapter between applications and the framework location stack:

```
Control / Policy (static test fixtures, v0.1 domain objects)
    ↓
LSPosed interception layer (client-process hooks; NOT system_server)
    ↓
Policy Engine v0.1 (frozen: PolicyEngine.evaluate/resolveAndEvaluate)
    ↓
Transformation (TransformationEngine.transform) + Metadata sanitization (MetadataSanitizer.sanitize)
    ↓
Application (receives ALLOW passthrough / TRANSFORMED fix / THROTTLE suppression / DENY null-or-error)
```

Success is **evidence**, not coverage: for each path, a machine-readable record proving (or refuting) that the hook fires, the engine decided deterministically, the output matches the decision, and metadata is consistent with the transformed precision. The prototype must also **demonstrate bypasses** (paths that escape it), because a bypass demonstrated now is an enforcement requirement documented for the AOSP stage — not a failure to hide.

## B. Exact Target Android Version(s)

- **[FACT]** Existing diagnostic apps target `minSdk = 29`, `targetSdk = 34`, `compileSdk = 34` (verified in `android-experiments/apps/*/build.gradle*`).
- **[FACT]** The standing emulator definition is an **API 34 (`google_apis`, x86_64)** AVD named `ls34` (`/home/kali/.config/.android/avd/ls34.{avd,ini}`) — [OBSERVED] present on disk, device not currently booted/attached.
- **Prototype target: API 34 first.** API 35/36 images are documented as pending disk/time in the results skeleton; they are **not** targets until an API-34 vertical slice passes.
- **[HYPOTHESIS]** Hook points named below exist in materially the same form on API 35/36 for framework `LocationManager` paths; GMS paths must be re-verified per GMS version regardless of API level.

## C. Target Device Model

- **[FACT]** No physical device facts exist in this repo or session. The emulator AVD `ls34` is the only defined target; its profile could not be confirmed from the files inspected (only `ls34.avd/` + `ls34.ini` presence verified).
- **No Galaxy J6 availability is assumed.** [OBSERVED] `adb devices` returns an empty list — no device, emulator, or target of any model is currently attached.
- **Rule:** the real device/emulator identity (model, build fingerprint, security patch, GMS version) will be recorded from `adb` output and `scripts/env-info.sh` at experiment time — never assumed beforehand.

## D. Root / Access State

- **[OBSERVED]** Host shell is unprivileged user `kali`; no root claim made or needed on the host (adb + SDK only).
- **[FACT]** LSPosed module installation and Zygisk injection fundamentally require a **rooted device** (Magisk + Zygisk + LSPosed framework) **or** a userdebug/eng emulator image where the module can be activated.
- **[HYPOTHESIS]** The practical path here is the existing `google_apis` emulator image (userdebug-like, GMS present for later FLP stages) with Magisk/LSPosed installed — untested, listed under §K stop conditions.
- Until root/framework presence is demonstrated on the actual target, **no hook claim advances past HYPOTHESIS.**

## E. LSPosed / Zygisk Availability

- **[FACT]** No LSPosed module, no Zygisk configuration, and no Magisk artifacts exist anywhere in this repository (searched: `*.md`, `*.kts`, `*.kt`, `*.xml`, excluding build outputs).
- **[FACT]** No LSPosed API/version pin exists yet; the framework version must be chosen at implementation time against the API-34 target (LSPosed API surface differs across releases — pin exact version + scope list then).
- **[HYPOTHESIS]** LSPosed modern scope model allows per-package scoping (target app + `system_server`-adjacent scope where needed); exact scoping behavior to be confirmed against the installed LSPosed build, not assumed.

## F. Development Requirements

| Requirement | State |
|---|---|
| Frozen Policy Engine v0.1 source (`locshield-policy/src/main`, 20 files) | [FACT] present, unmodified; belegt reuse target |
| Engine adapter surface (verified signatures) | [FACT] `PolicyEngine.resolveAndEvaluate()` / `evaluate()`; `TransformationEngine.transform()`; `MetadataSanitizer.sanitize()`; `TemporalController.evaluate()` / `recordDelivery()` |
| Android SDK + build of diagnostic apps | [FACT] prior `apks/` outputs exist; sources under `android-experiments/apps/` (5 apps) |
| LSPosed module skeleton + API dep | missing — Phase-2 design only, no code yet |
| Rooted API-34 target with LSPosed active | missing — must be demonstrated (§K) |
| Test fixture policies (CITY / GRID) | to be authored as v0.1 `AppPolicy` objects at implementation |
| Machine-readable evidence schema | to be defined (see §Phase-6 note below) |

## G. Process / Package Boundaries Under Investigation

- **[FACT]** An LSPosed module executes **inside the target application's process** (client-side interposition), i.e. on the opposite side of the Binder boundary from the future `system_server` enforcement point. It therefore sees exactly what the app sees — and nothing the system sees.
- Boundaries to probe, in order:
  1. **Target app process** (`shield.loc.*` diagnostic apps first): hook `android.location.LocationManager` methods *as called by the app*. Identity here is the app's own context — the policy subject is known by construction (test fixture), which is what makes the first slice valid without a system identity resolver.
  2. **System-server boundary (observe-only):** `dumpsys location`, binder transaction traces — used to confirm what the framework *would* have delivered, contrasted with what the hook returned.
  3. **`com.google.android.gms` process (later stages only):** FLP/geofence paths live here; hooking GMS is a separate, higher-risk stage requiring its own readiness check. Not in the first slice.
- **[HYPOTHESIS]** Client-side hooks suffice to validate *policy semantics* (decision → transform → sanitize → deliver) even though they prove nothing about system-wide enforcement — which remains the AOSP stage's job.

## H. Candidate Hook Points

Ordered per Phase-5 expansion order. Each is **[HYPOTHESIS]** until a passing vertical slice promotes it.

| # | API method (declaring class) | Process | Intended mediation |
|---|---|---|---|
| 1 | `getLastKnownLocation(String)` (`android.location.LocationManager`) | target app | Hook → engine (`isCached=true` context) → TRANSFORM/DENY → return controlled `Location` or `null` |
| 2 | `requestLocationUpdates(...)` (`LocationManager`, all overloads incl. `PendingIntent`) | target app (+ system delivery) | Hook registration + callback/`ILocationListener` delivery; per-delivery engine evaluation |
| 3 | Passive provider path (`PASSIVE_PROVIDER`) | target app | Same as (2), flagged `isPassive=true`; verifies recipient-side evaluation |
| 4 | `getCurrentLocation(...)` (`LocationManager`) | target app | Single-shot variant of (2) |
| 5 | `addProximityAlert()` / geofence callbacks | target app (+ `system_server` evaluation) | Registration + transition-event gating |
| 6 | `FusedLocationProviderClient` (`com.google.android.gms.location`) | GMS process + app callbacks | **Highest risk.** Requires GMS-process hooking or client-callback interposition; full bypass expected until proven otherwise |
| 7 | Cache/policy-change behavior | app + framework cache | Re-query after policy swap; stale-fix handling |
| 8 | Bypass analysis | all of the above | Document every path that escapes hooks 1–6 |

## I. Security and Isolation Assumptions

- **[FACT]** The prototype runs **with the privileges of its host process only**: inside a target app it can only affect that app; it confers no system authority and enforces nothing system-wide.
- **[FACT]** Engine invariants carry over unchanged: no raw location on transform failure, invalid policy never becomes permissive, metadata must not contradict spatial precision (these are engine-tested properties; the prototype's job is to preserve them across the Android adapter, not re-prove them).
- **[HYPOTHESIS]** Package-target scoping confines hook effects to intended test packages; cross-package leakage checks are part of Phase-4 verification (criterion 2), not an assumption.
- Logs must carry redacted/test representations only (established convention: emulator-synthesized coordinates, rounded to 2 decimals, `LS_DIAG` tag).

## J. Known Limitations

1. **Not system-wide.** A hook in app X says nothing about app Y, background services, or GMS — by construction.
2. **GMS closed boundary.** Per frozen review findings, intercepting GMS-delivered parcels from app-process hooks is unproven; GMS coverage claims are forbidden until demonstrated (cf. E-LMS-01/E-GEO-02 program).
3. **Hook fragility.** Client-side hooks can be bypassed by native code paths, PendingIntent delivery quirks, and OEM/Version differences — each such case is *evidence*, to be recorded, not patched over.
4. **No persistence or IPC.** Test policies are in-process fixtures; no Binder service, no AIDL, no `system_server` changes, no Control APK in this phase.
5. **Performance numbers from this stage are adapter overhead only** (hook + engine + transform in-app), not system enforcement cost.

## K. Stop Conditions

Do not proceed to implementation until **all** hold; if any fails, stop and record:
1. A bootable API-34 target (emulator `ls34` or real device) is observable via `adb devices` with recorded fingerprint/patch/GMS version.
2. Root (or equivalent module-loading capability) **and** an active, version-pinned LSPosed framework are demonstrated on that target.
3. The 5 diagnostic apps install and their baseline (unhooked) actions produce interpretable `LS_DIAG` output.
4. Frozen engine artifacts (`PolicyEngine`, `TransformationEngine`, `MetadataSanitizer`, `TemporalController` signatures above) are confirmed unchanged at implementation start.
5. Any GMS-stage work additionally requires its own hook-feasibility check — never bundled into the first slice.

---

## Phase-2 Prototype Architecture (design, not code)

```
Application (target pkg, e.g. shield.loc.client)
  ↓  android.location.* calls
LSPosed Hook layer (per-package scope; methods §H)
  ↓  raw Location / registration / callback + calling context
Android/API Adapter layer (NEW, prototype-owned)
  │  • android.location.Location  → locshield.model.LocationSample
  │  • calling pkg + foreground state → RequestContext{identity, ...}
  │  • Android permission state → ceiling input (never exceeded)
  ↓
Policy Engine Adapter (THIN; calls frozen engine, adds nothing semantic)
  │  • PolicyEngine.resolveAndEvaluate() → PolicyDecision
  │     (ALLOW / TRANSFORM / THROTTLE / DENY / FAIL_CLOSED)
  ↓
Transformation Adapter → TransformationEngine.transform(appliedSpatial)
Metadata Sanitization Adapter → MetadataSanitizer.sanitize(...)
Temporal recording → TemporalController.recordDelivery(...) (on delivered only)
  ↓
Return path: original Location (ALLOW) / transformed copy (TRANSFORM) /
             suppression or null (THROTTLE/DENY) / null + diagnostic (FAIL_CLOSED)
  ↓
Original Android API behavior resumes (app unaffected structurally)
```

Components: (1) LSPosed module + scope config; (2) hook registration layer (one wrapper per §H entry, stages 1–6); (3) Android/API adapter (all `android.*` contact lives here); (4) engine adapter (pass-through to frozen `PolicyEngine`); (5) transformation adapter; (6) metadata adapter; (7) diagnostic logging (`LS_DIAG`, redacted, per existing data rules); (8) experiment harness (existing `scripts/run-action.sh`, `perms.sh`, `results/<EXP-ID>/` extended with decision records). **No Android logic enters `locshield-policy/`.**

## Phase-3 First Vertical Slice (getLastKnownLocation)

- **Baseline:** unhooked app receives the normal framework fix (record provider, accuracy, timestamp).
- **Policy fixture:** `CITY` (primary) or `GRID` (alternate) `AppPolicy` for the test package.
- **Interposed run:** hook → engine evaluation with `isCached=true` → TRANSFORM → return controlled `Location`.
- **Measure and record:** original fix summary; transformed fix; hook-fired proof (log marker); package identity observed; full `PolicyDecision` (decision + reason + generation); post-transform metadata (accuracy floor vs. spatial mode, motion fields stripped); app stability (no crash, follow-up calls behave).
- Redaction rule from existing harness applies (synthetic coords, 2-decimal logs).

## Phase-4 Verification Gate (before any streaming work)

1. Module loads and activates on the target (LSPosed manager shows scope).
2. Hook fires **only** for the intended package (negative control: unscoped app unaffected).
3. Engine receives correct identity/context (decision record matches fixture).
4. Same inputs → same decision (determinism spot-check).
5. Output coordinate differs per policy (TRANSFORM actually applied, not passthrough).
6. Metadata consistent (accuracy ≥ spatial floor; speed/bearing/altitude stripped per fixture).
7. ALLOW policy returns the original fix untouched (no behavior change when permitted).
8. DENY is explicit and safe (`null`/documented error, no crash, no raw leak).
9. No crashes or ANRs introduced across repeated runs.
10. `git status` shows zero modifications under `locshield-policy/` (engine untouched — verify, don't assume).

## Phase-5 Expansion Order (evidence-gated)

`getLastKnownLocation()` → `requestLocationUpdates()` → passive → current-location → geofence/proximity → FLP → cache/policy-change → bypass analysis. Each stage opens **only** on a passing Phase-4-style gate for the previous one; hookability is never assumed.

## Phase-6 Evidence Record (schema sketch)

Per hook/path, one JSON record: `{api, declaringClass, process, package, mechanism, inputSummary, policyId, decision, reasonCode, generation, outputSummary, metadataTreatment, limitations, bypassNotes, evidenceRefs}` stored under `results/<EXP-ID>/`. Schema to be frozen at implementation start; this document only reserves the shape.

---

## Final Output — LocShield LSPosed Prototype Readiness Report

### Repository state
- Branch `main`, HEAD `60407cf`, clean tree (`git status --short` empty). History: `cb85c66` (frozen engine tag) → `f9576c8` → `672242d` → `60407cf`. No LSPosed module exists; no duplicate structure to avoid — nothing created in this stage.
- `locshield-policy/`: 20 main-source files (`engine`, `metadata`, `model`, `policy`, `spatial`, `temporal`); frozen, verified present and unmodified (no source touched in this stage).
- `android-experiments/`: 5 diagnostic apps (`minSdk 29 / targetSdk 34`), adb scripts, `results/` skeleton with per-experiment dirs; prior APK outputs present under `apks/` (rebuildable, git-ignored).
- This document is the **only** new file: `docs/LSPOSED_PROTOTYPE_READINESS_v1.md`.

### Existing architecture
- Frozen decision core (`PolicyEngine.evaluate`/`resolveAndEvaluate` → `ALLOW/TRANSFORM/THROTTLE/DENY/FAIL_CLOSED`), transformation + sanitization + temporal recording as separate engine-owned steps; adapters must consume `appliedSpatial/appliedTemporal` from the decision, never re-derive policy.
- Prior findings incorporated: GMS as separate unproven boundary (E-LMS-01/E-GEO-02 program), LSPosed explicitly classified as fragile/non-production for the AOSP baseline, RQ10 framing prototype-before-AOSP.

### Device readiness
- **Not ready.** Target narrows to API-34 (`ls34` AVD present on disk) but: no device attached ([OBSERVED] empty `adb devices`), no root/LSPosed demonstrated, no device facts recorded. Stop conditions §K.1–K.3 unsatisfied.

### Candidate hook surface
- §H table, stages 1–8; all HYPOTHESIS until slice evidence promotes them. GMS (stage 6) explicitly highest-risk with bypass expected by default.

### Prototype architecture
- 8 components as diagrammed above; all Android contact isolated in prototype-owned adapters; engine untouched.

### First vertical slice
- Hooked `getLastKnownLocation()` under CITY/GRID fixture; six measured outputs; redacted logging; app-stability requirement.

### Verification criteria
- Phase-4 ten-point gate, including negative package control, determinism, metadata consistency, explicit DENY, and engine-untouched proof.

### Risks
- Target-device gap (no attached/booted target); root/LSPosed unproven here; GMS bypass likely; OEM/version drift; hook fragility misread as enforcement.

### Unknowns
- Real device identity/fingerprint/patch/GMS version; LSPosed version pin + scoping behavior; per-package isolation in practice; hook coverage for PendingIntent/geofence/FLP paths; adapter overhead numbers.

### Exact next implementation step
1. Satisfy §K.1–K.3 (boot `ls34`, demonstrate root + version-pinned LSPosed, run unhooked baselines), recording all device facts.
2. Then scaffold the LSPosed module + hook #1 (`getLastKnownLocation`) with CITY fixture and the §Phase-6 record shape — and nothing else until the Phase-4 gate passes.

---

*End of readiness stage. No hooks implemented; no source modified; Policy Engine unchanged. Awaiting device readiness before any implementation.*
