# LocShield — Per-Application Location Precision & Privacy Control Framework for Android

**LocShield is a research and engineering framework for enforcing application-specific least-privilege access to location information on Android.**

> **Location Least-Privilege Principle:** *An application should receive no more location information than is necessary for its authorized functionality and permitted by the effective policy.*

| Area | Status |
|---|---|
| Research & architecture baseline | Complete |
| Policy Engine v0.1 (pure Kotlin/JVM) | Implemented, frozen — 188/188 tests passing |
| AOSP / framework integration | Designed, **not yet implemented** |
| Device / runtime validation | Pending |

This repository is the working research record: specifications, a tested policy engine, enforcement analysis, diagnostic tooling, and explicit readiness gates. It is **not** a finished product, and it does not claim protections it has not built.

---

## 1. Project Overview

Modern Android location access is governed by permission grants (coarse vs. fine accuracy, foreground vs. background) together with platform behaviors such as app-ops checks, throttling, and fused-provider selection. Those controls decide *whether* and *roughly how accurately* an app may access location, but they do not provide a general per-application policy mediation layer: they express no per-app rules for spatial precision levels, temporal exposure and frequency, application context, source/channel selection, or derived information such as cached fixes, passive delivery, geofence events, and metadata handling. In practice, different applications need very different things from location — navigation needs precise, continuous fixes; a weather app needs only a city; a social feed may need nothing at all. A single grant can therefore still overexpose users: one approval can hand an app high-precision coordinates, at high frequency, in the background, together with revealing metadata (accuracy, speed, bearing, altitude), derived channels (geofences, cached fixes), and side channels (GNSS observables, RF environment).

LocShield investigates a different model: **policy mediation instead of permission gating alone**. A per-application policy constrains *what* location information an app receives — spatial precision, temporal frequency, foreground/background context, source channel, and metadata — while Android's own permission grant remains an inviolable ceiling that policy can only restrict, never exceed.

LocShield is a **system-level research project**: the enforcement point it designs toward is a privileged framework service, not an ordinary app. A regular APK cannot transparently mediate another app's location stream, and this repository never pretends otherwise.

## 2. Research Problem

Location overexposure has several compounding dimensions, each of which the project models explicitly:

- **Spatial precision** — exact coordinates vs. grid, radius, city-level, randomized, or denied representations.
- **Temporal exposure** — update frequency and minimum intervals; repeated coarse fixes can reconstruct trajectories.
- **Foreground/background context** — background collection is a distinct, higher-risk channel.
- **Source/channel considerations** — framework fixes, fused providers, GNSS, and RF-derived signals behave differently.
- **Cache / last-known location** — stale fixes retrieved later must still satisfy the *current* policy.
- **Passive location** — fixes delivered without an active request must be evaluated for the receiving app.
- **Geofencing** — enter/exit/dwell events leak presence even without coordinates.
- **GNSS and related side channels** — raw observables can defeat coordinate-only mediation.
- **Metadata consistency** — a coarse coordinate paired with precise accuracy/speed/bearing metadata leaks the precision it claims to hide.

Conceptually modeled throughout the specification set; experimentally validated so far at the policy-engine level (deterministic and adversarial test suites), with framework/device validation gated as future work (see §9 and §15).

## 3. Core Research Question

Primary research question (Document 03):

> **RQ1 — Can a trusted Android system-level mediation layer enforce application-specific least-privilege access to location and selected location-derived information while preserving acceptable application functionality?**

Secondary questions (Document 03, RQ2–RQ10, condensed):

| ID | Dimension | Question |
|---|---|---|
| RQ2 | Policy expressiveness | Can spatial, temporal, contextual, and source-level requirements be represented as one coherent per-application policy model? |
| RQ3 | Enforcement coverage | Which Android location paths can be consistently mediated by the proposed system-level architecture? |
| RQ4 | Privacy reduction | How much can the framework reduce unnecessary spatial precision, update frequency, and location-derived exposure? |
| RQ5 | Utility | How do different privacy policies affect the functional behavior of representative applications? |
| RQ6 | Security | Which bypasses remain possible through cached, passive, geofence, GNSS, Wi-Fi, Bluetooth, cellular, GMS, or metadata paths? |
| RQ7 | Metadata | What location metadata must be transformed or suppressed so a coarse location cannot leak higher precision? |
| RQ8 | Performance | What CPU, memory, latency, storage, and battery overhead does system-level mediation introduce? |
| RQ9 | Compatibility | How does enforcement behavior differ across target Android versions and across AOSP vs. Google Play Services paths? |
| RQ10 | Deployment model | Can an LSPosed-based prototype validate the policy model and attack surface before an AOSP/system-service implementation? |

## 4. Key Contribution and Research Direction

The prior-art review (Document 02) is explicit: per-app location hiding, spoofing, and framework modification already exist, so the project does **not** claim novelty for per-app fake GPS, generic spoofing, or basic obfuscation. Instead, LocShield focuses on, proposes, and seeks to evaluate a **unified least-privilege mediation model** that combines, in one enforceable policy:

- spatial precision control,
- temporal precision and frequency control,
- application (foreground/background) context,
- location source and channel awareness,
- delivery-time (not just request-time) enforcement,
- metadata consistency handling,
- cache, passive-location, and geofence coverage,
- systematic security and bypass analysis (including GMS as a separate boundary).

The contribution under investigation is the model, its enforcement architecture, and its measured privacy/utility trade-offs — not any single transformation trick.

## 5. High-Level Architecture

```
USER
  ↓  (policy intent)
CONTROL APK                      ← control plane: configuration UI only.
  ↓  Binder / IPC                    NOT an enforcement mechanism.
LocationPrivacyManagerService    ← privileged system service (planned):
  ↓                                   identity, policy lifecycle, IPC.
POLICY ENGINE                    ← pure decision core (implemented, frozen).
  ↓
Policy Store / Temporal Controller / Transformation Engine
  ↓
Android location enforcement paths (planned: framework hooks)
```

- **Control plane** (planned): the Control APK edits policy and inspects status; it never sees raw location and never sits in the delivery path.
- **Privileged service** (planned): hosts identity resolution, policy storage, and the Binder interface inside `system_server`.
- **Policy engine** (implemented): pure, deterministic, Android-independent decision logic.
- **Enforcement hot path** (planned): per-delivery interception in the framework location pipeline.
- **Transformation layer** (engine implemented; framework wiring planned): converts a raw fix into the policy-compliant representation plus sanitized metadata.

## 6. Request-Time and Delivery-Time Flow

### Request flow

```
Application
  ↓
Location API
  ↓
Android Location Framework
  ↓
Identity Resolution  (trusted UID/package context, never a self-asserted string)
  ↓
Policy Evaluation  (Android ceiling ∩ LocShield policy)
  ↓
Provider Request  (admitted, transformed-later, or denied)
```

### Delivery flow

```
Provider
  ↓
Raw Location
  ↓
Policy Evaluation  (re-checked against the CURRENT policy generation)
  ↓
Temporal Control  (minimum interval / rate limit state per app)
  ↓
Transformation  (spatial degradation per applied policy)
  ↓
Metadata Sanitization  (accuracy floor, stripped motion fields)
  ↓
Application
```

Delivery-time enforcement matters because request-time checks alone leak: policies change while streams are active, cached fixes outlive the policy that created them, passive delivery bypasses the original request entirely, and temporal limits only make sense at the moment of delivery. Every one of these cases is covered by engine tests.

## 7. Policy Model

A policy is an immutable per-application value object. Actual fields from the implemented model (`locshield-policy/.../model/Policy.kt`):

```json
{
  "schemaVersion": 1,
  "packageName": "com.example.weather",
  "userId": 0,
  "enabled": true,
  "spatial": { "mode": "CITY" },
  "temporal": { "mode": "MIN_INTERVAL", "minimumIntervalMs": 900000 },
  "background": { "mode": "DENY" },
  "metadata": { "accuracyMode": "COARSE", "speedMode": "DENY", "bearingMode": "DENY" },
  "randomization": { "enabled": false }
}
```

Dimensions:

- **Spatial modes:** `EXACT`, `RADIUS` (+`radiusMeters`), `GRID` (+`gridMeters`), `CITY` (+optional `cityId`), `RANDOMIZED`, `DENY`, `ANDROID_CEILING` (no extra degradation; platform rules govern).
- **Temporal modes:** `REALTIME`, `MIN_INTERVAL`, `PERIODIC`, `RATE_LIMIT`, `ONE_SHOT`.
- **Background modes:** `INHERIT_ANDROID`, `ALLOW_POLICY`, `RESTRICT`, `DENY`.
- **Metadata:** per-field handling (`RETAIN` / `COARSE` / `DENY`) for accuracy, timestamp, elapsed realtime, altitude, speed, bearing, provider, and extras.
- **Randomization:** bounded perturbation with explicit seed strategies (`PER_DELIVERY`, `PERIOD_STABLE`, `SESSION_STABLE`, `SYSTEM_MANAGED`).
- **Lifecycle:** versioned schema, creation/update/expiry timestamps, enabled flag; updates replace the whole object and bump a snapshot generation.

Source, geofence, and passive/cache distinctions enter evaluation through request and source classification rather than separate policy fields. The governing rule is restrictive intersection:

**Effective policy = Android authorization ceiling ∩ LocShield policy** — policy can only narrow what the platform grant allows, never widen it. An absent policy resolves to an explicit versioned default, never to silent unlimited access; expired policies fall back to a deny-preserving policy.

## 8. Policy Engine v0.1

The engine (`locshield-policy/`, Kotlin/JVM, `group = "shield.loc"`, version `0.1.0`) is deliberately free of any Android dependency — no SDK, no framework classes, no Binder, no Play Services. It operates on supplied domain objects so the policy model and decision logic could be proven correct *before* any platform wiring:

- **Representation:** immutable value types (`AppPolicy`, `EffectivePolicy`, `PolicyDecision`, …).
- **Validation:** closed-vocabulary parsing plus structural checks (required parameters per mode, contradictory fields, bounds, expiry); invalid policies are rejected, never silently accepted.
- **Evaluation:** deterministic resolution (explicit → user default → versioned system default), ceiling intersection, background handling, temporal gating, then decision selection among `ALLOW`, `TRANSFORM`, `THROTTLE`, `DENY`, `FAIL_CLOSED`.
- **Spatial logic:** metric projection grids, bounded-radius snapping, offline city table with deterministic fallback, seeded randomization whose seed never derives from coordinates.
- **Temporal logic:** per-identity state machines on monotonic elapsed time (wall-clock is used only for expiry); generation changes reset state so new intervals apply immediately.
- **Metadata:** accuracy floors derived from the spatial mode override `RETAIN`; motion fields default to denied; extras allowlist is empty in v0.1.
- **Storage/snapshot model:** transactional in-memory store with copy-on-write immutable snapshots and a monotonic generation counter; rejected commits leave state untouched.
- **Security properties:** fail-closed on every failure class, cross-identity state isolation, no live-location preview oracle, coordinate-free audit values.

**Verification: 188/188 tests pass** (`./gradlew :locshield-policy:test`, JUnit 5; re-verified on this machine). The suite spans validation, resolution, decisions, spatial/temporal/metadata behavior, composition conflicts, fail-closed paths, security invariants, seeded property sweeps, structured fuzzing (malformed policies, hostile coordinates, temporal sequences), concurrency, and performance bounds. Performance tests assert engineering bounds (e.g. sub-millisecond policy lookup+evaluation averages, bounded transform/sanitize cost); these are suite assertions on the JVM test host, not universal device guarantees, and no specific timing figures are claimed here.

## 9. Policy Engine Components

Actual layout (`locshield-policy/src/main/kotlin/locshield/`):

```
locshield-policy/
├── model/      # frozen vocabularies, policies, identity, locations,
│               # authorization, effective policy, decisions, audit values
├── policy/     # validation, snapshot store, resolver, ceiling intersection,
│               # restriction ordering
├── engine/     # deterministic evaluator (resolve → gate → decide)
├── spatial/    # grid / radius / city / randomized transformers + engine facade
├── temporal/   # monotonic clock abstraction + per-identity delivery gate
└── metadata/   # post-transform field sanitizer with spatial accuracy floors
```

- `model/` defines what policies *are*; `policy/` decides what is *active and allowed*; `engine/` produces the *decision*; `spatial/`, `temporal/`, and `metadata/` enforce the *how, when, and with-what-fields*. Test mirrors live under `src/test/kotlin/locshield/` (15 suites plus shared fixtures).

## 10. Security Model

Threats analyzed (per Document 04 as traced in the enforcement matrix): direct precise-location requests; request-vs-delivery mismatch; cached/last-known retrieval; passive delivery; geofence inference; GNSS side channels; Wi-Fi/Bluetooth/cellular-derived inference; temporal and trajectory inference; metadata leakage and precision contradiction; GMS/fused path bypass; policy tampering; identity confusion; TOCTOU and generation consistency; stale/replay behavior; audit-channel leakage.

Documented invariants include: no self-escalation or cross-app policy writes; Android authorization is never exceeded; every protected delivery is evaluated against the current effective policy; cached and passive paths cannot bypass stricter policy; temporal limits bind delivery, not admission; transformed output carries no contradicting metadata; identity comes from trusted platform context; policy updates are atomic and versioned; failures close (never fail open to raw location); audit records carry no coordinates.

## 11. Trust Boundary

- **Control APK** — partially trusted configuration client; must authenticate over Binder and can never directly mediate enforcement.
- **system_server / LocShield service** — trusted; owns policy lifecycle, identity, and (planned) framework hooks.
- **Policy Engine** — trusted deterministic core; stateless, side-effect free, no I/O.
- **Android framework** — trusted platform boundary the design builds on (permission and AppOps state as ceiling inputs).
- **GMS boundary** — conditional/external: Play Services location paths are analyzed as a *separate* boundary that framework hooks cannot be assumed to cover.
- **Provider / vendor boundary** — hardware, HAL, and OEM behavior vary and are not assumed uniform.
- **Kernel / baseband boundary** — out of scope: a compromised kernel, baseband, or `system_server` voids the model by definition.

Documented non-goals include: kernel/baseband/carrier-level protection, remote server-side inference control, universal sensor-inference prevention, IP/network geolocation control, formal differential-privacy guarantees in v1, and any claim of zero location leakage.

## 12. Android Enforcement Model

The matrix documents Android 14, 15, and 16 across: `LocationManagerService` and provider/listener management; last-known/cached retrieval; passive provider fan-out; framework geofencing; GNSS managers, measurements, status, navigation messages, and NMEA; GMS fused location and geofencing **as a separate boundary requiring independent verification**; and RF-derived side channels (Wi-Fi scans, BLE observations, cell information). It separates **AOSP source findings** (verified in release-branch trees: service/entry points, identity construction, sanitization and delivery gates, fudge/cache paths) from **engineering inferences** (notably: GMS-core internals are closed, so any GMS coverage claim stays explicitly unverified until per-build dynamic tracing proves traffic crosses a hook). The matrix never claims all GMS paths interceptable.

## 13. AOSP Implementation Plan

The intended integration (planned, designed, **not implemented**) proceeds in reviewable commits: privileged permission declaration; system-service skeleton with `SystemServer` registration and service publication; SELinux service labeling; Policy Engine packaging and linkage (with the D1/D2 questions below resolved in a real workspace); then the delivery, cache, passive, geofence, and GNSS hooks.

**AOSP Commit 1 has not yet been implemented.** Explicitly pending build verification:

- **D1** — Soong `java_import` / prebuilt-JAR integration for `services.core` (no in-tree precedent found in the inspected `frameworks/base` revision).
- **D2** — Kotlin runtime linkage for the engine artifact (runtime `kotlin/*` references proven; relocation-vs-linkage decision pending a compiling tree).
- **D3** — minimal exact SELinux rule set (macro coverage analyzed; compile unproven).
- **D4** — privileged permission declaration and grant behavior on a booting image.

## 14. Current Implementation Status

| Area | Status | Evidence / Notes |
|---|---|---|
| Research documentation (Docs 01–13) | Complete | `1-13 documentation/` (13 files) |
| Prior-art analysis | Complete | Document 02; novelty scoped to unified mediation |
| Threat model & boundaries | Complete | Document 04; traced in matrix §20 |
| Technical requirements / design | Complete | Documents 05–06 |
| Architecture & enforcement specs | Complete | Documents 07–13 + v2/v2.1/v2.1.1 amendments |
| Android 14/15/16 enforcement analysis | Documented | Matrix v1 (source findings vs. inferences separated) |
| Policy Engine v0.1 | Frozen | `locshield-policy/`; pure Kotlin/JVM, zero Android deps |
| Policy Engine tests | Complete | 15 suites, **188/188 passing** (re-verified locally) |
| Policy Engine artifact | Frozen | Reproducible Gradle `jar`; hash in §19 |
| Diagnostic apps (5) | Complete | `android-experiments/apps/` (source only; APKs git-ignored) |
| AOSP workspace | Blocked | No tree; ~5 GiB free vs ~250–400 GB needed |
| AOSP Commit 1 | Blocked | Planned; gated on provisioned build host |
| SELinux build | Blocked | Tooling + tree absent |
| Runtime / device validation | Pending | Planned experiment program (§15) |
| GMS validation | Pending | Requires per-build dynamic tracing |
| GNSS side-channel experiments | Pending | Diagnostic actions exist; devices pending |
| Control APK | Planned | Specified; not implemented |
| Production deployment | Planned | Out of scope for current stage |

## 15. Experiment / Validation Plan

The planned program (diagnostic apps in `android-experiments/`, procedures in the matrix §22, report skeleton in `results/`) includes:

| ID | Question |
|---|---|
| E-LMS-01 | Does GMS location traffic cross the framework delivery path? (path attribution) |
| E-GEO-02 | What does a GMS geofence event actually deliver? (event vs. fix content) |
| E-GNSS-02 | Which GNSS APIs are reachable under each permission configuration? |
| E-PAS-01 | Does a passive subscriber receive another app's fixes, and under whose policy? |
| E-CACHE-01 | Do last-known reads re-evaluate current policy? |
| E-RF-01 | Does denying fine location close RF-derived paths? (ceiling backstop) |
| E-GNSS-01 | Are fixes and measurement callbacks governed by equivalent gates? |

Evidence tiers are kept distinct: **source-level** (AOSP tree reads — partially done), **build** (compilation — pending), **runtime** (emulator/device traces — pending), **empirical experiment** (the above program — pending). No experiment is claimed complete.

## 16. Repository Structure

```
LOCSHIELD/
├── 1-13 documentation/      # authoritative research & specs (Docs 01–13, .docx)
├── locshield-policy/        # Policy Engine v0.1 (Kotlin/JVM) + tests + notes
├── android-experiments/     # 5 diagnostic apps, adb scripts, results skeleton
├── *.md                     # matrix, runtime validation, architecture specs,
│                            #   AIDL/API contracts, plans, readiness reports
├── build.gradle.kts / settings.gradle.kts / gradlew*  # root Gradle wiring
├── gradle.properties        # local JDK pin for the build
└── README.md                # this file
```

Generated outputs (`build/`, `.gradle/`, APKs, `local.properties`) are intentionally git-ignored and absent from history.

## 17. Documentation Index

| Document | Purpose | Status |
|---|---|---|
| Doc 01 — Idea Specification | Concept, principle, scope | Complete |
| Doc 02 — Prior-Art & Novelty Review | Landscape, contribution boundary | Complete |
| Doc 03 — Research Problem & Questions | RQ1–RQ10, hypotheses, variables | Complete |
| Doc 04 — Threat Model & Security Boundaries | Assets, adversaries, invariants | Complete |
| Doc 05 — Technical Requirements (TRD) | Testable requirements baseline | Complete |
| Doc 06 — Technical Design & Rationale (TDR) | Why the architecture is shaped so | Complete |
| Doc 07 — Formal Architecture Specification | Components, interfaces, flows | Complete |
| Doc 08 — System Boundary & Enforcement Spec | Enforcement classes, pipeline | Complete |
| Doc 09 — API & Enforcement Matrix v1 | Android 14/15/16 API→path mapping | Complete |
| Doc 10 — Source-Level Enforcement Path Trace v1 | Framework call-chain evidence | Complete |
| Doc 11 — AIDL & Internal API Spec v1 / v1.1 / v1.1.1 | IPC and internal contracts | Complete |
| Doc 12 — Policy Engine Specification v1 | Decision model, transforms, reference design | Complete |
| Doc 13 — Policy Engine Test Specification v1 | Vectors, properties, fuzzing, gates | Complete |
| Android API & Enforcement Matrix v1 (repo md) | Consolidated research matrix | Complete |
| Android Runtime Validation v1 (repo md) | Validation baseline | Complete |
| Enforcement Architecture v2 / v2.1 / v2.1.1 | Architecture + amendments | Complete |
| Enforcement Architecture Review v1 | Formal review, gaps, verdict | Complete |
| AOSP Pre-Implementation Plan v1 / v1.0.1 | Staged implementation blueprint | Complete |
| AOSP Commit-1 Gate Report | Honest no-build gate record | Complete |
| AOSP Commit-1 Readiness Report v1 | Environment verdict: BLOCKED | Complete |
| HOST_ENVIRONMENT.md | Observed host facts | Complete |
| Policy Engine IMPLEMENTATION_NOTES.md | Terminology mapping, prototype choices | Complete |

## 18. Build / Run

Policy Engine (verified environment): **JDK 21 + Gradle 8.10.2.**

```sh
# Run the full Policy Engine verification suite:
./gradlew :locshield-policy:test

# Test results (JUnit XML) land in:
#   locshield-policy/build/test-results/test/
```

Verified just now on this machine: **188 tests, 0 failures, 0 errors, 0 skipped.**

### AOSP build environment (separate, currently blocked)

AOSP Android 14 integration is a different toolchain: it requires **JDK 17 plus the AOSP build toolchain** (Soong/ninja, SELinux tooling) in a separately provisioned workspace — not the JDK 21 + Gradle setup above, which applies to the Policy Engine only. Framework integration additionally requires a `repo` sync (~250–400 GB), and KVM for emulator validation. On the current host this gate is **BLOCKED** (~5 GiB free, no JDK 17, no `repo`/ninja/`checkpolicy`, no KVM) — see `AOSP_Commit1_Readiness_Report_v1.md`. Do not attempt an AOSP build inside this repository; it contains research and the JVM engine only.

## 19. Verification

| Claim | Standing |
|---|---|
| Policy Engine 188/188 tests | **Verified** — suite re-run locally during this review |
| Zero Android dependencies in core | **Verified** — dependency graph (`jvm`, JUnit-only test deps) plus clean-room package layout |
| AOSP source findings (services, gates, fudge paths) | **Documented** — release-branch reads cited per claim in the matrix |
| GMS interception coverage | **Unverified** — explicitly pending per-build dynamic tracing |
| AOSP compilation / SELinux build | **Blocked** — no workspace on current host |
| Runtime / device behavior | **Blocked/Pending** — experiment program defined, devices pending |

Frozen artifact (reproducible via `./gradlew :locshield-policy:jar`):

- `locshield-policy-0.1.0.jar` (141,124 bytes) — SHA-256 `da30d9c59f6fba5cacca33e637a6d95a02fa4b5d534a30d6db43c97feff27032`

## 20. Security / Responsible Disclosure

LocShield is a research project. Its security properties hold **within the documented boundaries and for the implemented engine**; they are not proof of universal Android privacy protection. GMS internals, vendor/HAL behavior, kernel/baseband integrity, and remote-server inference sit outside the current claim boundary (see §11). There is no formal disclosure process in this repository; please open a GitHub issue for security-relevant observations rather than assuming any guaranteed response capacity.

## 21. Limitations

- AOSP integration (service, hooks, SELinux, permission grant) is designed but **not implemented**.
- GMS Core is closed source; framework-hook coverage of GMS paths is **unproven by design** until traced per build.
- Vendor, HAL, and OEM ROM behavior is assumed non-uniform and largely untested.
- Runtime and device validation (emulator and physical) is pending; current evidence is source-level plus engine-level.
- Kernel, baseband, carrier, and remote-server inference are outside the boundary.
- Public-API behavior is version-specific; findings are scoped to the analyzed Android 14–16 revisions.
- The project is mid-stage: core engine frozen, platform integration gated on a provisioned build host.

## 22. Roadmap

| Phase | Scope | Status |
|---|---|---|
| Phase 0 — Research baseline | Docs 01–13, threat model, requirements | ✅ Complete |
| Phase 1 — Policy Engine v0.1 | Model, evaluation, transforms, 188-test suite | ✅ Complete / frozen |
| Phase 2 — AOSP build readiness | Host provisioning, workspace, baseline builds | ✅ Assessed / ⛔ currently blocked |
| Phase 3 — AOSP Commit 1 | Permission, service skeleton, SELinux, linkage | ⏳ Pending provisioned host |
| Phase 4 — System service + engine integration | Policy lifecycle in `system_server` | ⏳ Planned |
| Phase 5 — Delivery-time enforcement | Framework interception paths | ⏳ Planned |
| Phase 6 — GNSS / cache / passive / geofence protections | Side-channel and derived-path gates | ⏳ Planned |
| Phase 7 — GMS boundary experiments | Per-build tracing, backstop validation | ⏳ Planned |
| Phase 8 — Device / emulator validation | Full experiment program on hardware | ⏳ Planned |
| Phase 9 — Privacy / utility / performance evaluation | Measured trade-offs on real builds | ⏳ Planned |

## 23. Research Status Statement

LocShield is currently in the research-and-core-engineering stage. The policy engine and architectural foundation are implemented and documented; Android framework integration remains a gated next phase requiring a provisioned AOSP build environment.

## 24. Contributing

There is no formal governance process. Contributions that fit the project should:

- preserve traceability (every claim links to evidence: source, document, or test);
- never mix speculative claims with verified results — label inferences as inferences;
- document experiments fully (environment, commands, raw outputs);
- keep everything reproducible from a clean checkout;
- keep the Policy Engine test suite green (`./gradlew :locshield-policy:test`).

Please open an issue first for anything touching frozen specifications or the v0.1 engine contract.

## 25. License

No license file is present in this repository yet; licensing has not been specified. All rights are reserved by default until a license is added.

## 26. Author / Project

LocShield is developed and maintained by [25-Ankit](https://github.com/25-Ankit). Institutional affiliation, if any, is not stated in the repository.

## 27. References

- This repository's specification set (§17), in particular Documents 01–13 and the Android API & Enforcement Matrix v1, which cite their own primary sources.
- Android location documentation (developer.android.com) and the Android Open Source Project (source.android.com / android.googlesource.com), as referenced throughout the matrix and path-trace documents for service architecture, permission behavior, and provider design.
- Gradle / Kotlin / JUnit 5 tooling documentations for the build and verification setup actually used here.
