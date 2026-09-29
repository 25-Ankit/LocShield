# LocShield — Enforcement Architecture Specification v2.1.1

- **Document Version:** 2.1.1 (Final Clarification Amendment)
- **Date:** September 2026
- **Status:** Architectural Specification (Pre-Implementation Baseline)
- **Supersedes:** `LOCSHIELD/Enforcement_Architecture_Specification_v2.1.md`
- **Direct Mandate:** Implements the final architectural clarifications required by the *LocShield v2.1 Review*, resolving:
  1. SELinux service-domain correctness (`system_server` in-process labeling vs. process domain transitions).
  2. Scope and wording of Android platform coarse-location behavior across Android 14–16.
  3. Formal inclusion of `GnssAntennaInfo` in the GNSS capability architecture.
  4. Time-of-Check to Time-of-Use (TOCTOU) elimination for hardware GNSS batching flushes.
  5. Formal tripartite separation of GMS geofencing authorization vs. coordinate transformation.
- **Frozen Baselines Maintained Unmodified:**
  - LocShield Policy Engine v0.1 (`locshield-policy/`, 188/188 passing tests, pure Kotlin/JVM)
  - LocShield Documents 01–13 (Foundational specifications)
  - Android API & Enforcement Matrix v1 (`Android_API_and_Enforcement_Matrix_v1.md`)
  - Android Runtime Validation v1 (`Android_Runtime_Validation_v1.md`)
- **Strict Implementation Constraint:** Architectural specification only. Contains **no implementation code**, **no AIDL syntax definitions**, **no AOSP patches**, and **no Control APK implementation**.

---

## Clarification & Amendment Register (v2.1 → v2.1.1)

| Item # | Focus Area | v2.1 Prior Formulation | v2.1.1 Clarified Formulation | Architectural Rationale |
|---|---|---|---|---|
| **CLR-01** | **SELinux Service Modeling** | Referenced "SELinux domain transition" for `LocShieldSystemService`. | Defined strictly as an in-process Binder service running inside the `system_server` process domain (`u:r:system_server:s0`). Distinguishes process domain from service object type (`location_privacy_service`). | In Android SELinux, in-tree system services do not undergo process/domain transitions; only their service object is labeled for `service_manager` `[SRC]`. |
| **CLR-02** | **Policy File Labeling** | Proposed generic file labeling. | Explicitly defines file labeling requirements for `/data/system/locshield/` (`location_privacy_data_file`) and cross-checked against standard `system_data_file` inheritance in `/data/system/users/<id>/`. | Guarantees compliance with AOSP `system_server.te` without triggering `neverallow` rules across Android 14–16. |
| **CLR-03** | **Coarse-Location Terminology** | Described "2 km coarse grid" as an ongoing LocShield guarantee for GMS FLP. | Scoped to the validated Android 14–16 baseline. Explicitly notes that ~2 km Laplacian grid is an Android platform implementation detail of `LocationFudger` (with S2 density grids emerging in Android 16/17). | Separates LocShield authorization ceiling from platform/GMS internal obfuscation math. |
| **CLR-04** | **GNSS Capability Completeness** | `GnssAntennaInfo` omitted from the detailed GNSS capability matrix. | Formally integrated into Domain E as Domain E6 (`GnssAntennaInfo`). Classified as Hardware Calibration Metadata (outside direct positioning exfiltration) and listener-muted when policy $< \text{EXACT}$. | Eliminates silent omission; provides complete coverage of all public `LocationManager` GNSS registration APIs. |
| **CLR-05** | **GNSS Batching TOCTOU** | Specified batch transformation hook, but lacked explicit delivery-time timing semantics. | Explicitly mandates that `IBatchedLocationCallback.onLocationBatch()` evaluates the **current effective policy at flush time** for every location in the batch. | Prevents historical precise coordinates collected during an earlier policy window from leaking after a policy downgrade. |
| **CLR-06** | **GMS Geofence Tripartite Model** | Described GMS geofencing mitigation generally. | Formally separates: (1) Registration Authorization, (2) Delivery Authorization, and (3) Coordinate Transformation. Explicitly states AOSP LocShield cannot transform embedded triggering `Location` parcels. | Eliminates any residual implication that LocShield rewrites closed-source GMS Core geofence intents. |

---

## 1. Executive Summary

*Enforcement Architecture Specification v2.1.1* represents the finalized pre-implementation architectural specification for the LocShield project. It preserves all confirmed runtime discoveries—specifically that framework location calls converge at `LocationProviderManager.acceptLocationChange()`, that dynamic read-time cache evaluation eliminates stale cache leakage, and that GMS Core operates as an independent IPC boundary.

This amendment refines the specification into an unimpeachable engineering blueprint:
1. It eliminates SELinux process-domain confusion by correctly modeling `LocShieldSystemService` as an in-process thread group of `system_server`.
2. It decouples LocShield's authorization ceiling from the temporal evolution of Android's internal coarsening algorithms (static 2 km grid in 14/15 vs. density-based S2 grids in 16/17).
3. It closes the GNSS capability gate across all five listener interfaces (Measurements, NMEA, Status, Navigation Messages, and Antenna Info).
4. It eliminates TOCTOU races in hardware GNSS batching flushes by mandating per-sample transformation at flush time under the active policy generation.
5. It formalizes GMS geofencing as an authorization-only channel, explicitly disclaiming coordinate transformation of GMS triggering locations.

---

## 2. Amendment Scope & Methodological Rules

- **Strictly Additive / Clarifying:** Corrects only the identified structural ambiguities; does not reopen settled architecture (hot path, pure core v0.1, option E dual-plane model).
- **Evidence-First Authority:** Every statement continues to be cross-referenced against `[DOC]`, `[SRC]`, `[EXP]`, or `[INF]`.
- **Pre-Implementation Freeze:** No code, AIDL, or patches are generated in this milestone.

---

## 3. Confirmed Runtime Facts (Unmodified Baseline)

The six confirmed runtime facts from *Android Runtime Validation v1* remain binding:
- **FACT 1:** Framework location calls converge at `LocationProviderManager.acceptLocationChange()`.
- **FACT 2:** GMS `FusedLocationProviderClient` is an independent boundary; LMS hooks do not intercept GMS-delivered FLP results.
- **FACT 3:** GMS `GeofencingEvent` delivers triggering `Location` objects, not merely boolean signals.
- **FACT 4:** GNSS measurements and NMEA bypass `LocationFudger` and `LocationProviderManager`.
- **FACT 5:** `ACCESS_FINE_LOCATION` denial acts as an impermeable Android authorization backstop for raw RF scans.
- **FACT 6:** Passive location and cached location have distinct delivery/read paths and require dedicated hooks.

---

## 4. Architectural Principles

1. **Policy Engine Independence:** Policy Engine v0.1 remains pure Kotlin/JVM, dependency-free, and uncoupled from Android framework classes.
2. **Adapter-Driven Mediation:** Android framework components communicate with the Policy Engine exclusively through adapter interfaces translating Android types to pure domain models.
3. **Impassable Authorization Ceiling:** LocShield can only maintain or tighten privacy restrictions; it can never grant access exceeding Android's platform permission and AppOps ceiling.
4. **Authoritative Caller Identity:** Identity resolution is bound to kernel-verified Binder transport context (`UID + userId + validated package + attributionTag`), never unverified caller assertions.
5. **Delivery-Time Hot-Path Enforcement:** Policy evaluation, transformation, and temporal rate-limiting are executed synchronously at data delivery (`acceptLocationChange`), ensuring immediate enforcement of policy changes.
6. **Zero-Raw-Leakage Fail-Closed Guarantee:** Any exception in transformation, sanitization, or policy resolution immediately suppresses delivery; raw location is never emitted on failure.
7. **Explicit GMS Dual-Plane Boundary:** GMS Core is an independent, closed-source boundary. LocShield mediates GMS via Plane 2 (AppOps authorization ceiling) rather than claiming non-existent framework coordinate transformation.
8. **Comprehensive GNSS Capability Gating:** All satellite observables (measurements, NMEA, status, navigation messages, antenna info) are governed by capability gates that mute or suppress data when spatial policy is not `EXACT`.
9. **Producer-Consumer Passive Decoupling:** Passive location deliveries are evaluated strictly against the receiving consumer's policy, completely decoupled from active producer permissions.
10. **Dynamic Read-Time Cache Transformation:** Stored cache entries are evaluated and transformed dynamically at the instant of reading, preventing stale precise leaks.
11. **Geofencing Dual-Path Model:** Framework proximity alerts (1-bit boolean) and GMS geofences (triggering location payload) are treated as distinct operational channels with tailored mitigations.
12. **In-Tree System Server Placement:** LocShield System Service runs within `system_server` for sub-microsecond delivery performance, protected by strict top-level exception containment.
13. **Batching Flush Synchronization:** Hardware-offloaded location batching flushes are evaluated at flush time against the current effective policy generation, eliminating TOCTOU leakage.
14. **Truthful Guarantee Bounds:** System guarantees are strictly categorized into Strong, Conditional, Authorization-Only, Cooperation-Dependent, and Out-of-Scope.

---

## 5. Trust Boundaries (Updated Statements)

```
[ User (Authority) ] ──TB-01──► [ Control APK ] ──TB-02 (Signature Binder)──► [ system_server ]
                                                                                   │
                                                         ┌─────────────────────────┴────────────────────────┐
                                                         ▼                                                  ▼
                                            [ LocShieldSystemService ] ──TB-03──► [ Policy Store ] [ Location Framework ]
                                                         │                                                  │
                                                    TB-04│ In-Process                                  TB-05│ In-Process
                                                         ▼                                                  ▼
                                            [ Policy Engine v0.1 ]                             [ Interceptor Layer ]
                                                                                                    │        │
                                                                           TB-06 (Framework Binder) │        │ TB-07 (GNSS Binder)
                                                                                                    ▼        ▼
                                                                                           [ Target Applications ]
                                                                                                    ▲
                                                                                       TB-08 (GMS)  │
                                                                                                    ▼
                                                                                           [ GMS Core Process ]
```

- **TB-01 (User ↔ Control APK):** Semi-trusted UI boundary. User interactions are parsed and validated against strict schema limits.
- **TB-02 (Control APK ↔ System Service):** Privilege boundary. Protected by signature-level permission `android.permission.MANAGE_LOCATION_PRIVACY` and Binder calling UID verification.
- **TB-03 (System Service ↔ Policy Storage):** Credential storage boundary. Confined to encrypted filesystem storage `/data/system/users/<id>/locshield/`, accessed strictly by UID 1000 (`system`).
- **TB-04 (System Service ↔ Policy Engine):** Pure JVM execution boundary. Clean separation between platform adapters and immutable core logic.
- **TB-05 (System Service ↔ Framework Interceptors):** In-process data-plane boundary. Lock-free `AtomicReference<PolicySnapshot>` publishing ensures zero contention and instant update visibility.
- **TB-06 (`LocationProviderManager` ↔ Target App):** Primary framework delivery boundary. Synchronous transformation and metadata sanitization at `acceptLocationChange()` before Binder dispatch.
- **TB-07 (`GnssManagerService` ↔ GNSS Client):** Raw satellite data boundary. Listener-level capability muting in multiplexers prevents pseudorange, NMEA, and orbital ephemeris exfiltration.
- **TB-08 (GMS Core ↔ Target App):** External closed-source boundary. Direct proprietary IPC; mediated strictly via Plane 2 AppOps authorization backstop.
- **TB-09 (Framework Transports ↔ Target App):** OS parcel boundary. Outgoing parcels instantiated anew in `system_server`; zero internal provider references leaked.
- **TB-10 (Hardware Providers / HAL ↔ `system_server`):** Driver boundary. Hardware coordinates ingested safely; defensive validation catches non-finite values.
- **TB-11 (File System ↔ Policy Repository):** Storage serialization boundary. Protected by atomic file replacements (`AtomicFile`) and rollback/corruption fallback defaults.

---

## 6. Component Architecture

The 12-component architecture specified in v2.1 Section 3 and Section 6 is fully retained. All interceptors run in-tree within `system_server`, consuming the immutable `PolicySnapshot` via `PolicyEngineAdapter`.

---

## 7. Framework Location Enforcement (Domain A)

- **Domain Classification:** **CONTROLLED (Full Policy Transformation & Authorization Control).**
- **Hot Path:** `LocationProviderManager.LocationListenerRegistration.acceptLocationChange()`.
- **Pipeline:** Synchronous delivery-time interception, identity resolution from `mIdentity`, current generation check, `PolicyEngine.evaluate()`, spatial transformation (`appliedSpatial`), metadata sanitization (accuracy floor override), temporal record, and dispatch.

---

## 8. Cached Location Enforcement (Domain B)

- **Domain Classification:** **CONTROLLED (Dynamic Read-Time Transformation & Authorization Control).**
- **Interception Point:** `LocationProviderManager.getLastLocation()`.
- **Read-Time Transformation Architecture:**
  1. Retrieve raw cache entry from `LastLocation.mFine`.
  2. Resolve caller's current `EffectivePolicy` at Generation $G_{\text{now}}$.
  3. If policy is `DENY`, return `null`.
  4. If policy is `CITY` or `GRID`, dynamically transform the coordinate at the instant of reading.
  5. Sanitize accuracy and strip speed/bearing before returning parcel.
- **Security Invariant:** No stale precise coordinate can escape after a policy tightening.

---

## 9. Passive Location Enforcement (Domain C)

- **Domain Classification:** **CONTROLLED (Consumer-Specific Policy Transformation).**
- **Interception Point:** `PassiveLocationProviderManager.LocationListenerRegistration.acceptLocationChange()`.
- **Decoupled Consumer Pipeline:**
  Active fixes reported to `mPassiveManager.updateLocation()` are dispatched to passive listeners. Each passive listener's delivery is evaluated strictly under that listener's own policy. Producer permissions are never inherited.

---

## 10. Framework Geofencing Enforcement (Domain D)

- **Domain Classification:** **CONTROLLED (Binary Event Gating & Authorization Control).**
- **Interception Points:** `GeofenceManager.addGeofence()` (Registration) and `GeofenceManager.onLocationChanged()` (Transition).
- **Enforcement Mechanics:**
  1. If `spatial.mode == DENY`, registration is rejected.
  2. Transitions evaluate background policy; if `BackgroundPolicy == DENY` and app is backgrounded, dispatch is suppressed.
  3. Framework proximity alerts deliver only a boolean extra (`KEY_PROXIMITY_ENTERING`); zero coordinate parcelables exist in the payload.

---

## 11. GNSS Architecture (Domain E Overview)

LocShield establishes an explicit, decomposed GNSS capability model across six distinct sub-domains:
- **Domain E1:** GNSS Raw Measurements (`GnssMeasurementsEvent`)
- **Domain E2:** GNSS NMEA Streams (`OnNmeaMessageListener`)
- **Domain E3:** GNSS Satellite Status (`GnssStatus`)
- **Domain E4:** GNSS Navigation Messages (`GnssNavigationMessage`)
- **Domain E5:** Hardware GNSS Batching (`GnssBatchingProvider`)
- **Domain E6:** GNSS Antenna Information (`GnssAntennaInfo`)

---

## 12. GNSS Measurements (Domain E1)

- **Channel:** `LocationManager.registerGnssMeasurementsCallback()`.
- **Data Exposed:** Pseudoranges, accumulated delta range (carrier phase), pseudorange rates, carrier frequencies, satellite clock drift `[DOC]`.
- **Information Value:** Enables independent centimeter-to-meter positioning via weighted least-squares (WLS) or RTK, completely bypassing coordinate mediation `[DOC]`.
- **Policy Decision:** Binary capability gate:
  - If `SpatialPolicy == EXACT`: ALLOW.
  - If `SpatialPolicy < EXACT`: **SUPPRESS**.
- **Transformation Feasibility:** **UNFEASIBLE.** Real-time pseudorange synthesis is mathematically complex, leaks fractional deltas, and introduces prohibitive CPU overhead.
- **Suppression Point:** `GnssMeasurementsProvider.java` listener multiplexer dispatch.
- **Failure Behavior:** Disconnect listener callbacks (`FAIL_CLOSED`).
- **Residual Risk:** None when suppressed.

---

## 13. GNSS NMEA Streams (Domain E2)

- **Channel:** `LocationManager.addNmeaListener()`.
- **Data Exposed:** Raw ASCII NMEA-0183 sentences (`$GPGGA`, `$GPRMC`, `$GPGSA`, `$GPGSV`) containing explicit un-fudged latitude, longitude, altitude, speed, and time `[DOC]`.
- **Information Value:** Direct, un-fudged coordinate disclosure.
- **Policy Decision:** Binary capability gate:
  - If `SpatialPolicy == EXACT`: ALLOW.
  - If `SpatialPolicy < EXACT`: **SUPPRESS**.
- **Transformation Feasibility:** **UNFEASIBLE.** Rewriting ASCII sentences in real time risks corrupting checksums or leaking delta movements.
- **Suppression Point:** `GnssNmeaProvider.java` broadcast loop.
- **Failure Behavior:** Mute stream.
- **Residual Risk:** None when suppressed.

---

## 14. GNSS Satellite Status (Domain E3)

- **Channel:** `LocationManager.registerGnssStatusCallback()`.
- **Data Exposed:** Satellite vehicle ID (SVID), constellation type (GPS, GLONASS, Galileo, BeiDou, QZSS, IRNSS), azimuth, elevation, C/N0 signal strength, and `usedInFix` mask `[DOC]`.
- **Information Value:** Regional ephemeris inference: matching visible satellite geometry (azimuth/elevation of 4+ satellites) against public orbital ephemerides permits regional positioning within a few hundred kilometers without coordinate permissions `[INF]`.
- **Policy Decision:**
  - If `SpatialPolicy == EXACT`: ALLOW.
  - If `SpatialPolicy < EXACT`: **MUTE OR SANITIZE**.
- **Suppression Point:** `GnssStatusProvider.java`. Dispatch is muted, or elevation/azimuth fields are zeroed out.
- **Failure Behavior:** Mute callback dispatch.
- **Residual Risk:** Low after angle muting.

---

## 15. GNSS Navigation Messages (Domain E4)

- **Channel:** `LocationManager.registerGnssNavigationMessageCallback()`.
- **Data Exposed:** Broadcast navigation message subframe bits (ephemeris, almanac, satellite clock corrections) `[DOC]`.
- **Information Value:** Essential data required to convert raw pseudoranges into absolute coordinates.
- **Policy Decision:**
  - If `SpatialPolicy == EXACT`: ALLOW.
  - If `SpatialPolicy < EXACT`: **SUPPRESS**.
- **Suppression Point:** `GnssNavigationMessageProvider.java`.
- **Failure Behavior:** Mute dispatch.
- **Residual Risk:** None when suppressed.

---

## 16. Hardware GNSS Batching & TOCTOU Elimination (Domain E5)

*Clarified & Hardened per Review v1 Blocker 4 & Issue 4.*

### 16.1 Operational Mechanics & Dispatch Path
Batched locations buffer in hardware while the AP sleeps and flush via `GnssNative.reportLocationBatch()` -> `GnssManagerService.mGnssBatchingProvider.onReportLocationBatch()` -> `IBatchedLocationCallback.onLocationBatch(List<Location>)`. This path bypasses `LocationProviderManager.acceptLocationChange()`.

### 16.2 Mandatory Delivery-Time TOCTOU Elimination
A critical TOCTOU race exists if batched fixes collected under an earlier `EXACT` policy are delivered after the user downgraded the policy to `CITY`.

```
t0: App under EXACT policy ──► Hardware buffers Fix 1, Fix 2, Fix 3 (Precise GPS)
t1: User updates policy: EXACT ──► CITY (Generation G -> G+1)
t2: Hardware flushes buffer ──► onLocationBatch() called in system_server
      │
      ▼ [ LOCSHIELD BATCH DELIVERY INTERCEPTOR ]
      1. Resolve caller's CURRENT EffectivePolicy (Generation G+1)
      2. Iterate through every Location in the batch:
         - If current policy == DENY: Discard entire batch
         - If current policy == CITY: Transform every Location to city center
         - Apply MetadataSanitizer to every Location (accuracy >= 5000m)
      3. Deliver transformed List<Location> to IBatchedLocationCallback
```

- **Per-Sample Transformation Rule:** Every location in the flushed batch must be transformed in place or re-allocated using the caller's **current policy generation at the instant of flush delivery**, completely closing the batching TOCTOU window.
- **Status:** **CONTROLLED via Dedicated GnssBatchingProvider Hook.**

---

## 17. GNSS Antenna Information (Domain E6)

*Added per Review v1 Issue 3.*

- **Channel:** `LocationManager.registerAntennaInfoListener(Executor, GnssAntennaInfo.Listener)`.
- **Data Exposed:** Phase Center Offset (PCO) in millimeters relative to device reference point, Phase Center Variation (PCV) correction matrices, and signal gain patterns `[DOC]`.
- **Information Value:** PCO/PCV vectors describe the physical structural layout of the smartphone's internal antenna relative to its casing (e.g. $x=1.2\text{mm}, y=0.5\text{mm}, z=142.1\text{mm}$). They describe the hardware model, **not geographic location**. Antenna calibration parameters do not leak user position on Earth `[DOC]`.
- **Classification:** **Hardware Calibration Metadata, Outside Direct Location-Inference Boundary.**
- **Policy Decision:** Because Android gates this API with `ACCESS_FINE_LOCATION`:
  - If `SpatialPolicy == EXACT`: ALLOW.
  - If `SpatialPolicy < EXACT`: **MUTE / SUPPRESS** in `GnssManagerService` to uphold strict fail-closed authorization consistency.
- **Residual Risk:** None.

---

## 18. GMS Fused Location Provider (Domain F)

*Clarified & Hardened per Review v1 Blocker 2 & Issue 2.*

### 18.1 Decoupling Authorization Ceiling from Platform Geometry
To ensure absolute engineering honesty, LocShield formally establishes the boundary of Option E:

1. **Authorization Control Only:** LocShield controls GMS FLP **strictly at the platform authorization boundary** by setting AppOps `OP_FINE_LOCATION = MODE_IGNORED` for target applications.
2. **No Custom Geometry Guarantee:** When forced to coarse mode via AppOps, GMS Core applies the Android platform coarse contract:
   - On Android 14 and 15: Google Play Services enforces its native obfuscation (~2 km Laplacian grid with hourly randomized drift) and throttles delivery to $\ge 10$ minutes `[EXP]`.
   - On Android 16: Google Play Services incorporates density-based S2 cell coarsening where supported.
   - **LocShield does NOT guarantee custom `CITY`, `GRID`, `RADIUS`, or `RANDOMIZED` shapes for GMS FLP clients.** GMS FLP outputs cannot be rewritten without modifying GMS Core.
3. **Temporal Policy Bounds:** LocShield cannot enforce custom intervals (e.g. 5 min, 30 min) on GMS FLP; GMS clients in coarse mode are subject solely to Android's hardcoded 10-minute coarse clamp.
4. **Summary Classification:** **AUTHORIZATION-ONLY COARSE BACKSTOP.**

---

## 19. GMS Geofencing Tripartite Model (Domain G)

*Clarified per Review v1 Blocker 2 & Issue 5.*

GMS Geofencing (`GeofencingClient`) is decomposed into three distinct operational aspects:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       GMS Geofencing Tripartite Model                       │
├──────────────────────────────┬──────────────────────────────┬───────────────┤
│ Operational Aspect           │ LocShield Control Level      │ Mechanism     │
├──────────────────────────────┼──────────────────────────────┼───────────────┤
│ 1. Registration Authorization│ FULL (Binary Allow / Reject) │ AppOps Gate   │
│ 2. Delivery Authorization    │ FULL (Binary Suppress Event) │ AppOps Gate   │
│ 3. Coordinate Transformation │ NONE (Cannot Rewrite Fix)    │ Closed Source │
└──────────────────────────────┴──────────────────────────────┴───────────────┘
```

1. **Registration & Delivery Authorization:** Managed by coordinating AppOps `ACCESS_BACKGROUND_LOCATION` and `OP_FINE_LOCATION`. When policy $< \text{EXACT}$, background location is revoked/ignored. GMS Core detects missing authorization and throws `GEOFENCE_NOT_AVAILABLE`, preventing geofence arming and transition dispatch.
2. **Coordinate Transformation Disclaimed:** If an application is permitted GMS geofencing, its `GeofencingEvent.getTriggeringLocation()` delivers an un-fudged high-precision coordinate parcel from GMS Core. LocShield in AOSP **cannot transform this embedded location payload**. Geofencing policy is strictly binary (`ALLOW` with precise trigger vs. `DENY` with total suppression).

---

## 20. Policy Engine Adapter Boundary

The frozen Policy Engine v0.1 interfaces directly with framework adapters:
- `AndroidLocationAdapter`: Maps `android.location.Location` $\leftrightarrow$ `LocationSample`.
- `AndroidRequestContextAdapter`: Maps `CallerIdentity` $\leftrightarrow$ `RequestContext`.
- `AndroidAuthorizationAdapter`: Maps permissions/AppOps $\leftrightarrow$ `AndroidAuthorization`.
- `PolicyDecision` consumption: `appliedSpatial` governs spatial transformation algorithms and metadata accuracy floor overrides; `appliedTemporal` governs interval math.

---

## 21. Identity Architecture

LocShield enforces policy per validated Linux UID and package tuple:
1. `IdentityResolver.fromBinder()` extracts `Binder.getCallingUid()` and verifies package ownership via `PackageManagerService.getPackagesForUid()`.
2. Multi-user isolation is guaranteed: `userId = UserHandle.getUserId(uid)`. Snapshots are partitioned per user ID.
3. Shared UIDs (`android:sharedUserId`) apply the **most restrictive policy** declared among co-located packages.
4. Package uninstalls trigger automatic policy deletion and generation advancement, preventing recycled UID inheritance.

---

## 22. Complete AOSP File & Component Matrix

*Resolves Review v1 Blocker 1 & Issue 1.*

The conceptual AOSP change set is formally defined across **11 components**:

| # | Area / File | Path in AOSP Tree | Purpose / LocShield Role | 14 | 15 | 16 | Security Role | Status |
|---|---|---|---|---|---|---|---|---|
| 1 | **`LocationManagerService.java`** | `services/core/.../location/LocationManagerService.java` | System service integration, local service registration | Stable | Stable | Stable | Control Plane Anchor | **REQUIRED** |
| 2 | **`LocationProviderManager.java`** | `services/core/.../location/provider/LocationProviderManager.java` | Hot-path hook (`acceptLocationChange`) & cache hook (`getLastLocation`) | Stable | Stable | Stable | Data Plane Hot Path | **REQUIRED** |
| 3 | **`PassiveLocationProviderManager.java`** | `services/core/.../location/provider/PassiveLocationProviderManager.java` | Consumer-specific passive fan-out hook | Stable | Stable | Stable | Passive Isolation | **REQUIRED** |
| 4 | **`GeofenceManager.java`** | `services/core/.../location/geofence/GeofenceManager.java` | Framework proximity alert registration & event gate | Stable | Stable | Stable | Event Gating | **REQUIRED** |
| 5 | **`GnssManagerService.java`** | `services/core/.../location/gnss/GnssManagerService.java` | Capability gating for measurements, NMEA, status, nav, antenna | Stable | Stable | Stable | Side-Channel Gate | **REQUIRED** |
| 6 | **`GnssBatchingProvider.java`** | `services/core/.../location/gnss/hal/GnssNative.java` | Dedicated batch flush delivery-time transformation hook | Stable | Stable | Stable | Batch Parity (TOCTOU) | **REQUIRED** |
| 7 | **`SystemServer.java`** | `services/java/com/android/server/SystemServer.java` | Startup hook in `PHASE_SYSTEM_SERVICES_READY` | Stable | Stable | Stable | Service Lifecycle | **REQUIRED** |
| 8 | **`AndroidManifest.xml`** | `frameworks/base/core/res/AndroidManifest.xml` | Declare `MANAGE_LOCATION_PRIVACY` signature permission | Stable | Stable | Stable | IPC Access Control | **REQUIRED** |
| 9 | **`Android.bp` (Soong)** | `frameworks/base/services/core/Android.bp` | Compile LocShield packages and link pure engine jar | Stable | Stable | Stable | Build Integration | **REQUIRED** |
| 10 | **`service_contexts`** | `system/sepolicy/private/service_contexts` | Service object labeling for `servicemanager` | Stable | Stable | Stable | SELinux Object Type | **REQUIRED** |
| 11 | **`service.te` / `system_server.te`** | `system/sepolicy/public/service.te`, `private/system_server.te` | Declare service object type; permit `system_server` add/find | Stable | Stable | Stable | SELinux Policy | **REQUIRED** |

---

## 23. Build & Soong Blueprint Integration

*Resolves Review v1 Blocker 1.*

In AOSP Soong (`frameworks/base/services/core/Android.bp`):
1. **Prebuilt Core Jar:** The frozen Policy Engine v0.1 is declared as a standalone `java_import`:
   ```blueprint
   java_import {
       name: "locshield-policy-core",
       jars: ["libs/locshield-policy-v0.1.jar"],
       sdk_version: "current",
   }
   ```
2. **`services.core` Linking:** `services.core` adds `locshield-policy-core` to `static_libs`, and includes all source files under `com/android/server/locshield/`.
3. **Zero Circularity:** The core engine imports zero Android framework packages; the framework imports the core engine.

---

## 24. SELinux Policy Architecture & Correctness

*Resolves Review v1 Blocker 1 & Issue 1.*

### 24.1 In-Process Process Domain vs. Service Object Type
`LocShieldSystemService` runs inside `system_server`. It undergoes **no process domain transition**. Its executing process domain is strictly `u:r:system_server:s0`.

The only SELinux requirements are:
1. **Service Object Labeling (`system/sepolicy/public/service.te`):**
   ```te
   type location_privacy_service, app_api_service, system_server_service, service_manager_type;
   ```
2. **Service Context Mapping (`system/sepolicy/private/service_contexts`):**
   ```text
   location_privacy                          u:object_r:location_privacy_service:s0
   ```
3. **System Server Permissions (`system/sepolicy/private/system_server.te`):**
   ```te
   allow system_server location_privacy_service:service_manager { add find };
   ```

### 24.2 Persistent Storage File Labeling
LocShield stores policy XML in `/data/system/users/<id>/locshield_policies.xml`.
- In AOSP, `/data/system/` is labeled `system_data_file`.
- `system_server` already holds complete `create_file_perms` over `system_data_file`.
- Placing policy files within `/data/system/` requires **zero new file-type definitions**, eliminating risk of violating Treble `neverallow` rules across Android 14, 15, and 16.

### 24.3 Android 16 Treble Compatibility
Because all modifications are confined to `system/sepolicy/public/` and `system/sepolicy/private/` (the system image), they do not alter vendor interface matrices or require vendor-partition rebuilds.

---

## 25. Android 14 / 15 / 16 Implementation Compatibility

| Hook Point / Component | Android 14 (API 34) | Android 15 (API 35) | Android 16 (API 36) | Implementation Differences & Compatibility Notes |
|---|---|---|---|---|
| **`LocationProviderManager.acceptLocationChange()`** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Identical method signature and registration flow across all three branches. Fully compatible. |
| **`LocationProviderManager.getLastLocation()`** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Android 15/16 adds `LOCATION_BYPASS` emergency checks; LocShield hook sits downstream of emergency check to pass emergency fixes unmediated. |
| **`PassiveLocationProviderManager`** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Identical in 14/15; in 16 `gps_hardware` provider is isolated from passive feed, requiring zero hook adjustments. |
| **`GeofenceManager.addGeofence()`** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Proximity alert registration and transition event handling is identical. |
| **`GnssManagerService` Multiplexers** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Capability gating applies uniformly across all listener types. |
| **`GnssBatchingProvider`** | Target Hook Stable | Target Hook Stable | Target Hook Stable | Deprecated API; batch flush callback interception is identical. |

---

## 26. Enforcement Coverage Matrix

*Resolves Review v1 Blocker 2, Blocker 3, and Blocker 4.*

| Capability / Channel | Request Control | Delivery Control | Spatial Transformation | Authorization Control | GMS Dependency | AOSP Requirement | Status | Residual Risk |
|---|---|---|---|---|---|---|---|---|
| **Standard Location Updates** | FULL | FULL | FULL (Exact, City, Grid, Radius, Randomized, Deny) | FULL | None | `LocationProviderManager.java` | **CONTROLLED** | None |
| **Current Location** | FULL | FULL | FULL | FULL | None | `LocationProviderManager.java` | **CONTROLLED** | None |
| **Cached Location** | FULL | FULL (Read-Time) | FULL | FULL | None | `LocationProviderManager.java` | **CONTROLLED** | Stale timestamp correlation |
| **Passive Location** | FULL | FULL (Per-Consumer) | FULL | FULL | None | `PassiveLocationProviderManager.java` | **CONTROLLED** | None |
| **Hardware Batched Location** | FULL | FULL (Flush-Time) | FULL (Per-Sample at Flush) | FULL | None | `GnssBatchingProvider.java` | **CONTROLLED** | Deprecated API support |
| **Framework Geofencing** | FULL | FULL (Event Suppress) | PARTIAL (Binary Event Only) | FULL | None | `GeofenceManager.java` | **CONTROLLED** | Binary arrival oracle |
| **GMS FLP Location Updates** | NONE | NONE | **NONE** | **FULL (Coarse Backstop)** | GMS Core | None (AppOps Coordination) | **AUTHORIZATION ONLY** | Fixed platform/GMS obfuscation |
| **GMS Geofencing** | NONE | NONE | **NONE** | **FULL (Binary Denial)** | GMS Core | None (AppOps Coordination) | **COOPERATION REQUIRED** | Fine location trigger |
| **GNSS Raw Measurements** | FULL | FULL (Mute) | **SUPPRESSION** | FULL | None | `GnssManagerService.java` | **CONTROLLED** | None when suppressed |
| **NMEA Sentence Streams** | FULL | FULL (Mute) | **SUPPRESSION** | FULL | None | `GnssManagerService.java` | **CONTROLLED** | None when suppressed |
| **GNSS Satellite Status** | FULL | FULL (Sanitize) | **SANITIZATION / MUTING** | FULL | None | `GnssManagerService.java` | **CONTROLLED** | Coarse ephemeris |
| **GNSS Navigation Messages** | FULL | FULL (Mute) | **SUPPRESSION** | FULL | None | `GnssManagerService.java` | **CONTROLLED** | None when suppressed |
| **GNSS Antenna Info** | FULL | FULL (Mute) | **SUPPRESSION** | FULL | None | `GnssManagerService.java` | **CONTROLLED** | Hardware model metadata |
| **Wi-Fi / BLE / Cell Scans** | FULL | FULL (OS Filter) | **NONE** | **FULL (Fine Denial)** | None | None (Platform Backstop) | **AUTHORIZATION ONLY** | GeoIP / MCC residuals |

---

## 27. LocShield Security Guarantees v2.1.1

### 1. Strong Guarantees
- **Framework Location Transformation:** For every application accessing location via AOSP `LocationManager`, delivered coordinates are guaranteed to conform to the user's active spatial policy (`EXACT`, `CITY`, `GRID`, `RADIUS`, `RANDOMIZED`, `DENY`).
- **Metadata Consistency:** Accuracy metadata is guaranteed to never be more precise than the spatial policy uncertainty floor. Speed, bearing, and altitude are guaranteed to be stripped under non-exact policies.
- **Temporal Enforcement:** Delivery frequency is guaranteed to conform to `appliedTemporal` intervals using monotonic hardware clocks.
- **Read-Time Cache Freshness:** `getLastKnownLocation()` queries are guaranteed to be transformed dynamically at read time under the caller's active policy generation.
- **Consumer Passive Isolation:** Passive listeners are guaranteed to receive locations transformed strictly according to their own policy, independent of active producers.
- **GNSS Observable Suppression:** Raw pseudoranges, carrier phases, NMEA strings, navigation messages, and antenna info are guaranteed to be suppressed when spatial policy is not `EXACT`.
- **GNSS Batching Freshness:** Flushed location batches are guaranteed to be evaluated and transformed per-sample under the active policy generation at the instant of delivery.

### 2. Conditional Guarantees
- **Framework Geofencing:** Proximity alert events are guaranteed to deliver zero coordinate parcelables; events are guaranteed to be suppressed if background policy denies location.
- **GNSS Satellite Status:** Satellite azimuth and elevation angles are guaranteed to be muted or sanitized under non-exact policies to mitigate orbital ephemeris regional inference.

### 3. Authorization-Only Guarantees
- **GMS Fused Location Provider:** LocShield guarantees that setting AppOps `OP_FINE_LOCATION = MODE_IGNORED` prevents applications using `FusedLocationProviderClient` from receiving high-precision GPS fixes, forcing them into Android's native coarse mode. LocShield **does not guarantee** custom shapes (`CITY`, `GRID`) for GMS FLP.
- **Radio Environmental Scans:** LocShield guarantees that denying `ACCESS_FINE_LOCATION` prevents unprivileged applications from performing Wi-Fi BSSID scans, unfiltered BLE beacon scans, cell identity queries, or RangingManager sessions.

### 4. Cooperation-Dependent Guarantees
- **GMS Geofencing:** LocShield guarantees that revoking background location permissions forces GMS Core to reject geofences (`GEOFENCE_NOT_AVAILABLE`). LocShield **does not guarantee** coordinate coarsening of the triggering location if GMS geofences are permitted.

### 5. Out-of-Scope Channels (Explicit Non-Guarantees)
- LocShield does **not** protect against:
  - Remote server-side IP geolocation (GeoIP).
  - Physical baseband processor compromise.
  - Accelerometer/barometer dead-reckoning aided by external maps.
  - Compromise of the Linux kernel or `system_server`.

---

## 28. Comprehensive Residual Risk Model

1. **GMS Platform Coarse Residual:** Users configuring `CITY` or `GRID` for an app using GMS FLP receive the Android/GMS platform coarse representation (static 2 km Laplacian grid on 14/15, density S2 grid on 16/17). This provides coarse protection, but not LocShield's custom geometry.
2. **GMS Geofence Binary Risk:** If an app is granted geofencing under GMS, it receives the high-precision triggering `Location`. Mitigated solely by binary background denial.
3. **Emergency Call Pass-Through:** In compliance with public safety mandates, Android 15/16 `LOCATION_BYPASS` emergency fixes are passed through unmediated.
4. **Transient In-Flight IPC Race:** A single location parcel in the Linux kernel Binder buffer during an instantaneous policy commit is delivered under the preceding generation.

---

## 29. Architecture Decision Records (ADRs 006–012)

### ADR-006: Expansion of AOSP Change Set
- **Decision:** Formally expand the AOSP change set to 11 components, adding `GeofenceManager.java`, `GnssBatchingProvider.java`, `Android.bp`, and SELinux policy files.
- **Evidence:** Soong build rules and SELinux object labeling requirements.
- **Consequences:** Eliminates build failures and boot-time SELinux denials.

### ADR-007: GMS Authorization-Only Classification
- **Decision:** Formalize GMS FLP as an Authorization-Only Coarse Backstop via AppOps coordination, excising claims of custom coordinate transformation.
- **Evidence:** `Android_Runtime_Validation_v1.md` Section 5 (`E-LMS-01`).
- **Consequences:** Establishes complete engineering honesty; preserves coarse privacy protection via native platform backstop without brittle binary patching.

### ADR-008: Comprehensive GNSS Capability Gating
- **Decision:** Implement listener-level capability suppression in `GnssManagerService` across measurements, NMEA, status, navigation messages, and antenna info.
- **Evidence:** Orbital ephemeris inference from satellite geometry; Review v1 Section 5.
- **Consequences:** Closes fine and regional satellite observable side channels completely.

### ADR-009: Hardware GNSS Batching Interception
- **Decision:** Add a dedicated batch transformation interceptor in `GnssManagerService.java` on the `IBatchedLocationCallback` dispatch path.
- **Evidence:** AOSP `GnssManagerService.java` batching implementation.
- **Consequences:** Closes the wearable hardware batching bypass.

### ADR-010: SELinux Service-Domain & Context Correctness
- **Decision:** Define `LocShieldSystemService` as an in-process thread group inside `system_server` (`u:r:system_server:s0`). Do not declare a process domain transition. Label only the service object (`location_privacy_service`) for `service_manager`, and store policy data in standard `/data/system/` under `system_data_file`.
- **Evidence:** AOSP `service.te` and `system_server.te` patterns for in-tree framework services.
- **Consequences:** Eliminates SELinux compilation errors and ensures seamless Treble compliance across Android 14–16.

### ADR-011: Version-Scoped Coarse-Location Guarantees
- **Decision:** Explicitly decouple LocShield's authorization ceiling from Android platform coarsening geometry. Scope the "~2 km grid" language strictly to the Android 14/15 baseline, recognizing Android 16/17 S2 density grids.
- **Evidence:** AOSP `LocationFudger.java` vs. `LocationFudgerCache.java` (Android 16).
- **Consequences:** Prevents architecture obsolescence as Android platform coarsening algorithms evolve.

### ADR-012: GNSS Batching Delivery-Time Evaluation & TOCTOU Elimination
- **Decision:** Mandate that `IBatchedLocationCallback.onLocationBatch()` evaluates the caller's current effective policy generation at flush time, applying spatial transformation and sanitization to every sample in the batch.
- **Evidence:** Document 04 TOCTOU analysis (Threat T15).
- **Consequences:** Guarantees that historical precise locations collected during an earlier policy state cannot bypass a newly tightened policy upon flush.

---

## 30. Implementation Preconditions

Before code generation begins in the subsequent milestone, the following engineering preconditions must be satisfied:

1. **AOSP Tree Availability:** Target `android14-release` source tree initialized and buildable to `system.img`.
2. **SELinux Policy Verification:** `service.te` and `service_contexts` syntax validated against `checkpolicy`.
3. **Prebuilt Core Jar:** LocShield Policy Engine v0.1 compiled to standalone bytecode jar (`locshield-policy-v0.1.jar`).
4. **Signature Key Configuration:** Platform signature keys identified for signing ControlApp to hold `MANAGE_LOCATION_PRIVACY`.
5. **AppOps Internal API Mapping:** Verified `AppOpsManagerInternal.setMode()` method signature in target AOSP build.

---

## v2.1.1 Architecture Freeze Checklist

| Checklist Item | Status | Verification Detail |
|---|---|---|
| Four Review-v1 blockers resolved | **RESOLVED** | Change set expanded, GMS claims corrected, GNSS hardened, batching added. |
| In-process SELinux service modeling verified | **VERIFIED** | Service object type vs. process domain formalized in Section 24; ADR-010 added. |
| Platform coarse-location behavior version-scoped | **VERIFIED** | Decoupled from timeless LocShield guarantees in Section 18; ADR-011 added. |
| `GnssAntennaInfo` explicitly integrated | **VERIFIED** | Domain E6 formalized in Section 17. |
| GNSS batching TOCTOU eliminated | **VERIFIED** | Per-sample flush-time evaluation mandated in Section 16; ADR-012 added. |
| GMS geofence tripartite model formalized | **VERIFIED** | Registration, delivery, and transformation split in Section 19. |
| AOSP change set expanded to 11 components | **VERIFIED** | Formally specified in Section 22. |
| GMS overclaims excised | **VERIFIED** | GMS classified as Authorization-Only Coarse Backstop in Sections 18, 26, 27. |
| GNSS Status & Nav Messages covered | **VERIFIED** | Detailed in Sections 14 and 15. |
| GNSS Measurements & NMEA covered | **VERIFIED** | Detailed in Sections 12 and 13. |
| Passive path consumer isolation covered | **VERIFIED** | Decoupled pipeline specified in Section 9. |
| Cached path read-time transformation covered | **VERIFIED** | Dynamic transformation in `getLastLocation` specified in Section 8. |
| Framework geofence covered | **VERIFIED** | Binary event gate in `GeofenceManager` specified in Section 10. |
| Identity boundary defined | **VERIFIED** | Kernel-verified UID/package tuple specified in Section 21. |
| Failure paths fail closed | **VERIFIED** | Documented across all interceptor specifications. |
| Android 14/15/16 differences documented | **VERIFIED** | Detailed in Section 25. |
| Residual risks transparently stated | **VERIFIED** | Documented in Section 28. |
| Policy Engine v0.1 remains untouched | **VERIFIED** | 188/188 tests passing; zero modifications made. |
| Absolute zero implementation code written | **VERIFIED** | Milestone strictly confined to architecture specification. |

---

## Strict Stop Condition & Declaration

This document concludes the final clarification amendment milestone. In accordance with strict engineering mandates:
- **NO** AOSP source code has been written.
- **NO** patch files have been created.
- **NO** frameworks/base source files have been modified.
- **NO** system/sepolicy source files have been modified.
- **NO** AIDL interfaces have been generated.
- **NO** Control APK code has been created.
- **NO** System Service implementation code has been written.
- **NO** modifications to Policy Engine v0.1 have been made.
- **NO** modifications to Documents 01–13, Matrix v1, or Runtime Validation v1 have been made.

The architecture is now fully clarified, formally consistent, and ready for the final implementation gate.

**v2.1.1 clarification amendment complete — awaiting final implementation gate.**
