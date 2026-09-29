# LocShield — Android API & Enforcement Matrix v1

- **Status:** Research baseline (no dynamic verification yet — see §22, §24).
- **Targets:** Android 14 (API 34), Android 15 (API 35), Android 16 (API 36).
- **Relation to prior docs:** Extends Documents 09–10 with version-separated, evidence-backed analysis. Does not modify Documents 01–13.
- **Claim labels used throughout:** `[DF]` = DOCUMENTED FACT (official doc URL in §25) · `[SRC]` = AOSP SOURCE FINDING (repo + branch + file + symbol in §25) · `[INF]` = ENGINEERING INFERENCE (explicitly marked, lower authority) · confidence `H/M/L` per claim.

**Classification scale for this document (task-defined):**

| Code | Meaning |
|------|---------|
| E0 | Fully controllable by ordinary APK |
| E1 | Controllable with privileged/system component (signature/privileged app, device-owner capability, or system service without AOSP rebuild) |
| E2 | Requires AOSP/framework modification |
| E3 | Requires provider/vendor/Google component cooperation (closed or out-of-tree code) |
| E4 | Not reliably enforceable by LocShield |
| E5 | Requires separate mitigation rather than interception (policy/UX/permission denial, not a hook) |

Mapping to Document 08 classes: Doc08-E0 (core controlled) ≈ here E1/E2 depending on channel; Doc08-E1 (conditional) ≈ here E2/E3; Doc08-E2 (observed/research-only) ≈ here E4/E5; Doc08-E3 (outside boundary) ≈ here E4/E5. The Doc08 scale described *claim strength*; this scale describes *mechanism required*. Both are retained; where they differ the mechanism scale governs implementation planning.

---

## 1. Executive Summary

1. The strongest framework enforcement target is confirmed: `LocationManagerService` (LMS) in `system_server`, reached via `ILocationManager` Binder from `LocationManager`. Per-receiver policy state exists (`LocationProviderManager.Registration`: identity, permission level FINE/COARSE/NONE, permitted/foreground flags), and **delivery-time** re-checks exist (per-caller fudge via `LocationFudger`, `AppOps.noteOpNoThrow` drop, permitted/foreground gating). `[SRC-H]`
2. Framework mediation alone is **insufficient proof** for Google Play services paths. GMS `FusedLocationProviderClient`/`GeofencingClient` are served from the GMS-core process with a GMS-side cache and proprietary fusion; GMS occupies the AOSP NLP/FLP provider slots on GMS devices but its app-facing outputs do not necessarily transit LMS delivery. LMS and GMS-core must be treated as **two separate enforcement boundaries** until per-build binder/dumpsys tracing proves otherwise. `[DF-H]` + `[INF-M]`
3. Raw GNSS (measurements, navigation messages, NMEA, status) is gated on `ACCESS_FINE_LOCATION` but enables **independent least-squares positioning** — it is a true side channel around any coordinate-only mediation and must be gated together with `Location`. `[DF-H]`
4. Passive and cached reads do **not** bypass LMS: passive delivery re-filters per passive registration, and last-location is fudged **at read time per caller**. Both still require delivery-time policy evaluation (policy may have changed since the fix was generated). `[SRC-H]`
5. Framework geofencing (`GeofenceManager`, proximity alerts) is evaluated in `system_server`, requires FINE to arm, and delivers only a boolean/event — controllable in-framework. GMS geofencing is computed in GMS-core and delivers the triggering `Location` — it bypasses LMS. `[SRC-H]`/`[DF-H]`
6. Wi-Fi scans/AP-RTT, unfiltered BLE scans, cell info, and Android 16 ranging all require FINE (or are filtered without it) on 14/15/16 — **denying FINE closes the fine-grained RF side channels**; no public API yields fine lat/lon with coarse/none. Residuals without location permission are coarse-only (MCC country, GeoIP city, NSD venue hints, motion/baro). `[DF-H]`
7. An ordinary APK **cannot** enforce per-app precision on another APK. Minimum for true system-level per-app transformation: a privileged enforcement point at `LocationProviderManager` delivery + last-location read + framework geofence/GNSS gates (AOSP modification, E2), plus GMS cooperation or FINE-denial for GMS paths (E3). `[INF-H]` (from `[DF-H]`+`[SRC-H]` premises)
8. The frozen Policy Engine v0.1 is reusable unchanged: its ceiling/intersection model maps directly onto FINE/COARSE/NONE + AppOps foreground/background state; only the *adapter* (identity, ceiling, clocks) is Android-specific. No engine contradiction was found.

---

## 2. Research Scope

In scope: every public location API surface on Android 14/15/16 (framework + GMS), the AOSP framework path `LocationManager → ILocationManager → LocationManagerService → LocationProviderManager → providers → transports`, permission/AppOps enforcement, GNSS/RF side channels with credible inference paths, Control-APK privilege analysis, system-service placement, Binder boundary definition, bypass matrix, prototype options, emulator experiment plan.

Out of scope: implementation (no service/APK/AIDL/AOSP/emulator work in this milestone), non-location privacy topics, Android 17+ behavior (noted only as forward-looking where it clarifies 16 flags), OEM-specific skins beyond documented variance, baseband/carrier internals.

---

## 3. Android 14/15/16 Version Matrix

| Area | Android 14 (API 34) | Android 15 (API 35) | Android 16 (API 36) |
|------|---------------------|---------------------|---------------------|
| API level / release | 34, Oct 2023 `[DF-H]` | 35, Sep 2024 `[DF-H]` | 36, Jun 2025 `[DF-H]` |
| Location permission model | FINE/COARSE + BACKGROUND (unchanged core); precise≈50 m, approx≈3 km² `[DF-H]` | Unchanged `[DF-H]` | Unchanged; forward note: density-based coarse grid exists behind flags (S2 path, `Flags.populationDensityProvider/densityBasedCoarseLocations`), default path still static grid `[SRC-H]`/`[DF-M]` |
| Foreground services | **Mandatory FGS types + per-type permissions** (`FOREGROUND_SERVICE_LOCATION` + COARSE/FINE grant check at `startForeground`) `[DF-H]` | dataSync 6 h/24 h timeout + `onTimeout`; new `mediaProcessing` type; **BOOT_COMPLETED FGS launch bans** (camera/dataSync/mediaProjection…); SAW exemption narrowed to visible overlay `[DF-H]` | No location FGS change `[DF-M]` |
| Background location | While-in-use + background request staging (unchanged); FGS `location` type required since 10 `[DF-H]` | No location-specific change; private space stops locked-profile apps (incl. location clients) `[DF-H]` | No location-specific change `[DF-M]` |
| New location-relevant APIs | — | — | **`android.ranging.RangingManager`** (UWB + BLE channel sounding + Wi-Fi NAN RTT + BLE RSSI; needs `RANGING` + FINE) `[DF-H]`; `NsdManager` local-network opt-in; companion pairing timeout no longer delivered to app (privacy) `[DF-H]` |
| RF/scan rules | FINE + location-on for Wi-Fi scans (targetSdk≥29); BT `BLUETOOTH_SCAN` + `neverForLocation` filtering; FINE for cell info `[DF-H]` | Same `[DF-H]` | Same, plus RangingManager gates above; local-network access opt-in via `NEARBY_WIFI_DEVICES` proxy (mandatory `ACCESS_LOCAL_NETWORK` only on 17+) `[DF-H]` |
| Framework location internals | LMS path `services/core/.../location/`; `GeocoderProxy`; static-grid `LocationFudger` only `[SRC-H]` | `ProxyGeocodeProvider` (geocode AIDL migrated); `LOCATION_BYPASS` emergency path; `@EnforcePermission` annotations added `[SRC-H]` | `LocationFudgerCache` (S2, flags-gated); `MAX_GPS_INTERVAL_MS=5 s` + `limitFusedGps`; stationary-throttling flags; GNSS overlay config; provider-request-listener deprecation flagged `[SRC-H]` |
| One-time / auto-revoke / downgrade-restart | Yes (one-time since 11/30; auto-revoke; process restart on precise→approximate downgrade) `[DF-H]` | Same `[DF-H]` | Same `[DF-H]` |

Android 17 (preview, **not** v1 scope): density-based coarse by default (replacing static 2 km grid), one-time precise "location button", persistent location indicator. Recorded here only because 16 trees already contain the behind-flag S2 machinery. `[DF-M]`

---

## 4. Android Location Architecture

### 4.1 Framework path (all three versions; paths identical unless noted)

```
App process
  LocationManager (location/java/android/location/LocationManager.java)
  │  builds CallerIdentity inputs (pkg, attributionTag, listenerId)
  ▼  Binder  (ILocationManager.aidl)
system_server
  LocationManagerService extends ILocationManager.Stub
  │  CallerIdentity.fromBinder(): uid/pid from Binder, pkg VALIDATED vs UID [SRC-H]
  │  LocationPermissions.getPermissionLevel(): FINE / COARSE / NONE [SRC-H]
  │  validateLocationRequest(): WorkSource/flags sanitization [SRC-H]
  │  AppOps watch (OP_FINE/COARSE_LOCATION), foreground tracking [SRC-H]
  ▼
  LocationProviderManager (per provider: gps/network/fused/passive + mock)
  │  Registration{identity, permissionLevel, permitted, foreground, request} [SRC-H]
  │  coarse clamp at registration: QUALITY_LOW_POWER, interval ≥ 10 min [SRC-H]
  ▼
  Providers: GnssLocationProvider (via GnssManagerService) /
             ProxyLocationProvider (network + fused overlays incl. GMS NLP/FLP slots) /
             PassiveLocationProvider / mock providers
  │  onReportLocation → setLastLocation → deliverToListeners → passive forward [SRC-H]
  ▼
  Delivery per registration (DELIVERY-TIME gates):
    getPermittedLocationResult → LocationFudger.createCoarse for COARSE [SRC-H]
    fastest-interval / displacement filter [SRC-H]
    AppOps noteOpNoThrow → drop on denial [SRC-H]
    LocationTransport → ILocationListener / ILocationCallback / PendingIntent [SRC-H]
```

File roots (`platform/frameworks/base`, branches `android14-release`, `android15-release`, `android16-release`, cross-checked against `main`): `location/java/android/location/` (API + `ILocationManager.aidl` + `util/identity/CallerIdentity.java`), `services/core/java/com/android/server/location/` (`LocationManagerService.java`, `LocationPermissions.java`, `provider/LocationProviderManager.java`, `geofence/GeofenceManager.java`, `fudger/LocationFudger.java` + 16-only `LocationFudgerCache.java`, `gnss/GnssManagerService.java`), `packages/FusedLocation/` (AOSP fallback fused). `[SRC-H]`

### 4.2 GMS path (separate boundary)

```
App process
  FusedLocationProviderClient / GeofencingClient (GMS client library)
  │  GoogleApi IPC (NOT ILocationManager)
  ▼
GMS-core process (com.google.android.gms, privileged, closed source)
  │  proprietary fusion (GNSS/sensors/Wi-Fi/BT/cloud DB) [DF-H for inputs class]
  │  GMS-side cache (getLastLocation; cleared on location-off / GMS restart) [DF-H]
  ▼  callback / PendingIntent to app
```

Framework interaction points (documented): GMS occupies AOSP NLP (`com.android.location.service.v2/v3.NetworkLocationProvider`) and fused (`com.android.location.service.FusedLocationProvider`) slots via `ServiceWatcher` on GMS devices `[SRC-H]`; GMS mock docs describe FLP sitting above network/gps providers `[DF-M]`; geofence/FLP availability tracks framework location-mode/accuracy state `[DF-H]`. Whether GMS-core additionally reads HAL/sensors directly is **undisclosed** — hence the two-boundary rule (§15). `[INF-M]`

### 4.3 Key architectural facts

- `CallerIdentity.fromBinder` validates the package against the calling UID (`SecurityException` otherwise); `fromBinderUnsafe` exists only for mock/test paths. Identity spoofing via a forged package string is rejected at this boundary. `[SRC-H]`
- Coarse callers are clamped at **registration** (quality + 10-min interval) and fudged at **delivery**; AppOps denial drops delivery (placeholder behavior: callbacks do not happen). `[SRC-H]` + `[DF-H]` (AppOps placeholder semantics)
- Last-location store is per-user `{fine, coarse-10min-throttled, bypass variants}`; **fudge is applied on read per caller**, not pre-fudged in store. `[SRC-H]` (+ `[INF-M]` on the exact stored-coarse semantics)
- Every non-passive provider report is forwarded to `PassiveLocationProviderManager`, which re-filters per passive registration. `[SRC-H]`
- 15/16 add a `LOCATION_BYPASS` (signature) emergency path promoting NONE→FINE with `OP_EMERGENCY_LOCATION`; absent on 14. `[SRC-H]`
- AOSP `packages/FusedLocation` fuses **only** gps+network (recency-then-accuracy, 11 s window) — a simple fallback, not GMS FLP. `[SRC-H]` + `[DF-H]`

---

## 5. LocationManager Analysis

| Method family | Binder call | Permission / gate | Delivery | Enforcement point | Bypass |
|---|---|---|---|---|---|
| `requestLocationUpdates` (listener/executor, ~12 overloads) | `registerLocationListener` | COARSE min; GPS/passive need FINE; coarse clamped | `LocationListenerTransport` | LPM registration + delivery gates (§4.1) | None within framework; GMS analogue separate (§6) |
| `requestLocationUpdates` (PendingIntent) | `registerLocationPendingIntent` | Same; PI system-API abuse rejected | `PendingIntent` send w/ allowlist | Same as above | Same |
| `requestSingleUpdate` | Same two calls (one-shot wrapper) | Same | Same | Same | Same |
| `getCurrentLocation` | `getCurrentLocation` (+`ICancellationSignal`) | Same; 30 s timeout clamp; `deliverNull` if never active | `ILocationCallback` | Same as above | Same |
| `getLastKnownLocation` | `getLastLocation` | COARSE min; `noteOp`; read-time fudge | Direct return | Read-time gate in `LocationProviderManager.getLastLocation` | Stale-data semantics only (still policy-evaluated); no bypass |
| `addProximityAlert` | `requestGeofence` (circle geofence) | **FINE required to arm** | PI boolean enter/exit | `GeofenceManager` (system_server) | None in framework; GMS geofence separate (§11) |
| GNSS callbacks/NMEA/measurements | `registerGnss*` family | **FINE only** (COARSE rejected) | Per-UID listeners via `GnssManagerService` | Gnss providers (must be gated with Location) | Raw positioning if left ungated (§7) |
| Geocoder (15/16) | `reverseGeocode`/`forwardGeocode` | Uid==caller check; no location perm listed | Callback | System `ProxyGeocodeProvider` | Forward = intent channel, not device leak (§12) |

`[SRC-H]` for Binder mappings and gates (AOSP item A1–A5 in prior trace); `[DF-H]` for FINE-only GNSS and proximity-alert gates (API reference).

---

## 6. Fused Location Provider Analysis

- **API:** `requestLocationUpdates` (Callback/Listener/PendingIntent), `getCurrentLocation`, `getLastLocation(+LastLocationRequest)`, `flushLocations`, `getLocationAvailability`, `setMockMode/setMockLocation`; needs COARSE or FINE (coarse-only → obfuscated + throttled); background needs `ACCESS_BACKGROUND_LOCATION` or location FGS. `[DF-H]`
- **Granularity:** per-request `GRANULARITY_COARSE/FINE/PERMISSION_LEVEL`; FINE-granularity + coarse grant → **no location** (not degraded). `[DF-H]`
- **Cache:** GMS-side; null when location off (clears cache), never recorded, or GMS restarted without active client; mock mode clears cache. Independent of LMS caches. `[DF-H]`
- **Computation site:** GMS-core (closed). Documented inputs "GPS and Wi-Fi" + QoS fusion; occupies AOSP NLP/fused slots on GMS devices; consumes framework location-mode/accuracy state. Direct HAL/sensor reads undisclosed. `[DF-H]` + `[INF-M]`
- **Interception:** a third-party system service **cannot** intercept GMS-core IPC (different service, signature/privileged surface). AOSP modification covers GMS outputs **only if** they transit LMS delivery (unproven per build). **Independent boundary: yes until proven otherwise.** `[INF-M]` (conservative rule from `[DF-H]` premises)
- **Mock:** GMS mock needs `ACCESS_MOCK_LOCATION` + developer-option selection; scoped to FLP (does not inject into framework providers visible via LMS, per deprecated-API docs). Framework test providers are separate (`isMock` detectable). Cross-flow must be tested both directions. `[DF-H/M]`

---

## 7. GNSS Analysis

- **Gates:** `registerGnssMeasurementsCallback` (incl. `GnssMeasurementRequest` overload), `registerGnssNavigationMessageCallback`, `registerGnssStatusCallback`, `addNmeaListener`, `registerAntennaInfoListener`, legacy GPS-status listeners — **all FINE-only**; COARSE callers get `false`/`SecurityException`; NMEA additionally requires GPS enabled + foreground. `getGnssCapabilities` is capability metadata, not location. `[DF-H]`
- **Content:** pseudoranges (`ReceivedSvTimeNanos`), rates, CN0, carrier phase (`AccumulatedDeltaRange`), frequencies; nav subframes (ephemeris); NMEA `GGA/RMC` carry **direct lat/lon**; status carries sv geometry/CN0/used-in-fix. `[DF-H]`
- **Side-channel verdict:** YES — raw pseudoranges admit independent weighted-least-squares fixes (Google's own raw-GNSS guide + GnssLogger compute positions from them). Mediating `Location` while passing raw measurements/NMEA = full bypass. `[DF-H]` + `[INF-H]`
- **Enforcement point:** `GnssManagerService` per-UID listener fan-out (`reportMeasurements/NavigationMessage/Status/NMEA`) + LMS registration gates; all inside `system_server`, hence E2-coverable in the same AOSP change as LMS delivery. `[SRC-M/H]`
- **Classification: E2** (framework `Location` + GNSS gates together). If GNSS is left ungated while coordinates are mediated: **E4 bypass** (see §19).

---

## 8. Wi-Fi/Cell Network Location Analysis

- **Framework network location:** `NETWORK_PROVIDER` via `ProxyLocationProvider` overlay (GMS NLP on GMS devices, platform fallback otherwise). Apps receive only the derived `Location`; underlying RF inputs are **not** exposed through location APIs. `[SRC-H]` + `[DF-H]`
- **Underlying inputs are separately gated:** Wi-Fi scans need FINE + location-on (targetSdk≥29; throttled 4/2 min fg, 1/30 min combined bg); AP-RTT needs FINE (only Wi-Fi-Aware-peer ranging may use `neverForLocation`); cell info needs FINE; BLE unfiltered scans need FINE (`neverForLocation` yields filtered feed); API-36 Ranging needs `RANGING` + FINE. `[DF-H]` (details §12)
- **Consequence:** denying FINE closes fine-grained RF positioning on 14/15/16; coarse-permission RF access does not exist for location-bearing uses. RF hooks live in `WifiService`/`RttService`/`Telephony`/Ranging stack — **outside** LMS — so an LMS-only change does not noise them, but it does not need to while FINE is denied. `[INF-H]`
- **Classification: E1** (deny FINE / permission policy) for v1; per-service RF transformation would be **E2** future work, not required while FINE-gated.

---

## 9. Passive Location Analysis

- **Mechanism:** `PASSIVE_PROVIDER` requires FINE to arm; `PassiveLocationProviderManager` receives **every** non-passive provider report and re-applies per-passive-registration permission fudge, interval/displacement filters, and AppOps notes. Cannot be mocked; merge returns 0-interval request. `[SRC-H]`
- **Policy question:** the passive recipient IS evaluated (its own registration state), but the fix was generated for someone else. LocShield must evaluate **at passive delivery against the recipient's current policy** — passive is a distinct delivery path, not inheritance. `[SRC-H]` + `[INF-H]`
- **Bypass verdict:** passive does not bypass LMS gates, but a naive request-time-only policy layer would miss it. Delivery-time evaluation (frozen engine semantics) is mandatory. **Classification: E2** (delivery-gate coverage in the same AOSP change).
- GMS "passive" (`PRIORITY_NO_POWER`): GMS-side semantics; same two-boundary caveat as §6. `[DF-M]`/`[INF-M]`

---

## 10. Cached Location Analysis

- **Framework:** per-user `LastLocation{fine, coarse, bypass variants}`; coarse writes throttled to 10-min freshness; `getLastLocation` checks active-state, AppOps (`noteOp`, emergency-aware on 15/16), then **fudges at read time per caller**. Settings/ADAS/ignore-settings allowlists consulted. `[SRC-H]`
- **GMS:** separate GMS-side cache with its own null semantics (location-off clears; GMS restart empties). `[DF-H]`
- **Bypass verdict:** requesting old data does **not** bypass policy — retrieval is a fresh policy decision against the *current* policy (frozen v0.1 semantics already require this). Staleness is a utility question, not a privilege question. **Classification: E2** (read-path gate in same AOSP change). GMS cache: **E3**.

---

## 11. Geofencing Analysis

- **Framework (`addProximityAlert` → `GeofenceManager`):** FINE required to arm; evaluated in `system_server` from fused feed (+ last-location seed, 5-min max age); state machine UNKNOWN/INSIDE/OUTSIDE with accuracy-aware enter/exit; delivers PI boolean only (no `Location`); expiry/removal handled. No 14→16 behavioral delta observed. `[SRC-H]` + `[DF-H]`
- **GMS (`GeofencingClient`):** FINE (+background for background use); computed in GMS-core from FLP; delivers `GeofencingEvent` with transition + IDs + **triggering Location**; background-optimized (more responsive than bg FLP). `[DF-H]`
- **Information content:** even a boolean transition is location information (presence at a caller-chosen point); with the triggering `Location` it is a full fix. Registration itself reveals interest. `[INF-H]`
- **LocShield on geofencing:** spatial policy → arm/deny + event suppression; temporal policy → transition delivery gating (note: OS-level dwell/loitering timers still run — suppress *delivery*, do not pretend the timer stopped); background policy → PI delivery gating consistent with location background rules. Framework: **E2**. GMS: **E3** (or deny GMS location: E1).
- **Classification: framework E2 / GMS E3.**

---

## 12. Location-Derived Side Channels

| Channel | Reveals | Permission (14/15/16) | Ordinary APK? | LocShield control | v1 scope | Class |
|---|---|---|---|---|---|---|
| GNSS measurements/nav/NMEA/status | Independent fix (WLS) / direct lat-lon (NMEA GGA/RMC) | FINE only | Yes (w/ FINE) | Gate with Location (E2) | **In scope** | E2 |
| Wi-Fi scans / `WifiInfo` BSSID | 20–50 m via DB (inference) | FINE + location-on | Yes (w/ FINE) | Deny FINE (E1); hook WifiService (E2 future) | In scope as denial | E1 |
| Wi-Fi AP RTT | Multilateration to known APs | FINE (+NEARBY on 33+) | Yes (w/ FINE) | Deny FINE | In scope as denial | E1 |
| BLE unfiltered beacons | Beacon-DB fix | FINE (or filtered w/o) | Filtered w/o FINE | Deny FINE | In scope as denial | E1 |
| Cell info | Cell-radius fix / trilateration | FINE | Yes (w/ FINE) | Deny FINE | In scope as denial | E1 |
| RangingManager (16) | Peer ranges (relative) | RANGING + FINE | Yes (w/ both) | Deny either | In scope as denial | E1 |
| Activity/sensor (IMU/baro/steps) | Dead-reckoning w/ seed only | None / ACTIVITY_RECOGNITION | Partial | Not worth hooking (E5); deny activity perm (E1) | Future | E5 |
| Geocoder forward | Interest, not device fix | None listed | Yes | Allow + log (E5 policy) | Out (log only) | E5 |
| MCC/MNC, GeoIP, NSD/`.local`, timezone | Country/city/venue (coarse) | None / INTERNET | Yes | Separate mitigations (E5); 16 opt-in / 17 mandatory local-net perm | Residual | E5 |
| CompanionDeviceManager | User-picked device only | Delegated scan, consent | Constrained | System-enforced; timeout oracle closed on 16 | — | E5 |
| Nearby Connections/UWB | Proximity/relative | NEARBY_* (+FINE for loc use) | Yes | Deny perms (E1) | Denial scope | E1 |
| Proximity alerts (framework) | 1-bit presence at own point | FINE to arm | Yes | E2 gate | **In scope** | E2 |
| GMS geofence events | Presence + triggering fix | FINE + background | Yes | E3 / deny GMS loc | **In scope** | E3 |

**Bottom line:** no public API yields fine lat/lon with coarse/none on 14/15/16 `[INF-M/H]`; every fine channel is FINE-gated, so **FINE denial is a complete coarse backstop**. `[DF-H]` premises.

---

## 13. Permission and Authorization Model

- **Runtime axes:** category (foreground/background) × accuracy (precise/approximate); `ACCESS_COARSE_LOCATION` always requested with FINE on 12+; user may grant approximate-only; **process restart on precise→approximate downgrade**; one-time grants; staged background request; auto-revoke of unused apps. `[DF-H]`
- **Platform accuracy semantics:** precise ≈50 m or better; approximate ≈3 km² (static ~2 km fudge grid on 14/15; S2 density-aware path behind flags on 16). `[DF-H]`/`[SRC-H]`
- **AppOps layer:** `OP_FINE/COARSE_LOCATION` + `MONITOR(_HIGH_POWER)_LOCATION`; providers call `noteOp/startOp`; `MODE_IGNORED` → placeholder (no callbacks); proxy ops attributed for forwarders; foreground capability tracked from proc state. LMS watches noted ops and logs location-off access. `[DF-H]`/`[SRC-H]`
- **Background:** while-in-use + FGS-`location` (mandatory type since 10, per-type permission since 14); background FLP throttled (few/hour), geofence more responsive (documented behavior, unchanged 14–16). `[DF-H]`
- **Ceiling mapping (frozen engine ↔ Android):** `locationAllowed` = FINE-or-COARSE granted ∧ location-on ∧ AppOps allowed; `approximateOnly` = COARSE-without-FINE effective level; `backgroundAllowed` = background grant ∧ (foreground state ∨ location FGS). Downgrade/deny at any layer → engine DENY. No engine change required. `[INF-H]`
- **Emergency:** 15/16 `LOCATION_BYPASS` (signature) promotes NONE→FINE with `OP_EMERGENCY_LOCATION` during emergency; LocShield MUST NOT mediate emergency flows (pass through, audit only). `[SRC-H]`

---

## 14. AOSP Framework Analysis

| Class / area | Path (`platform/frameworks/base`) | Versions | Responsibility / boundary | LocShield relevance |
|---|---|---|---|---|
| `LocationManager` | `location/java/android/location/LocationManager.java` | 14/15/16 stable | App API; builds identity inputs; Binder calls | Entry inventory only — no hook here |
| `ILocationManager.aidl` | `location/java/android/location/` | 14→15 geocode migration; 15 `@EnforcePermission` additions; 16 deprecation flags | Binder boundary | Interposition surface definition |
| `LocationManagerService` | `services/core/.../location/LocationManagerService.java` | Stable path all three | Entry validation, identity, permission level, AppOps watch, geofence/GNSS fan-out | Control-plane anchor; policy-cache invalidation source |
| `CallerIdentity` | `location/.../util/identity/CallerIdentity.java` | Stable | Trusted identity (uid/pid/pkg-validated/attribution/listener) | **Reuse pattern** for adapter identity |
| `LocationPermissions` + helpers | `services/core/.../location/` | Stable + 15/16 bypass | FINE/COARSE/NONE resolution | Ceiling source |
| `LocationProviderManager` | `.../provider/LocationProviderManager.java` | Stable | Registration state, clamping, delivery gates, last-location cache | **Primary hook: delivery + read paths** |
| `PassiveLocationProviderManager` | `.../provider/` | Stable | Cross-provider passive fan-out | Passive delivery gate |
| `GeofenceManager` | `.../geofence/GeofenceManager.java` | Stable, no delta observed | In-system geofence eval + PI | Framework geofence gate |
| `GnssManagerService` + providers | `.../gnss/` | Stable | GNSS listener fan-out | GNSS gate (same change) |
| `LocationFudger` (+16 `LocationFudgerCache`) | `.../fudger/` | 14/15 grid-only; 16 +S2 flags-gated | Existing coarse mediation | Precedent + reuse for APPROX ceiling |
| `ProxyLocationProvider` overlays | `.../provider/proxy/` | Stable | NLP/fused slots (GMS occupies) | GMS-dependence observation point |
| AOSP `FusedLocationProvider` | `packages/FusedLocation/` | 16: GPS-interval/attribution tweaks | Fallback fusion (gps+network) | Serves `FUSED_PROVIDER` w/o GMS |

`[SRC-H]` throughout (verified per-branch diffs; flag names literal).

---

## 15. Google Play Services Boundary

1. GMS location is a **separate process, service, cache, and fusion stack** (closed source). Public docs never state it is "just an LMS client". `[DF-H]`
2. Documented couplings to framework state: provider slots, mock-above-providers language, availability tracking location-mode/accuracy, cache cleared on location-off. Consistent with GMS-as-framework-client **but also** with a hybrid (framework inputs + proprietary fusion). `[DF-M/H]` + `[INF-M]`
3. Consequences: LMS-delivery hooks cover AOSP providers + any GMS output that transits them; they provably do NOT cover GMS-side cache reads, GMS computed fixes, or GMS geofence events served from GMS-core. `[INF-M]` (conservative)
4. GMS absence/staleness: check `isGooglePlayServicesAvailable`; framework `LocationManager` is the documented fallback; migration guide implies dual-path support. LocShield must handle GMS-less devices (framework-only: fully E2-coverable) and GMS-present devices (two boundaries) as distinct configurations. `[DF-H]`
5. GMS version varies independently of OS version (cache/DB/behavior drift). Matrix rows must record GMS version in evidence (§22). `[DF-M]`

---

## 16. Enforcement Point Matrix

Per-channel record (`·` = same as framework LMS row unless noted):

| Channel | API entry | Request process | Framework service | Provider | Generation → transform → delivery | Permission | Binder | Interception | APK? | Priv-Svc? | AOSP? | GMS dep? | Bypass | Conf | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Continuous updates (listener) | `LM.requestLocationUpdates` | app | LMS→LPM | gps/network/fused | provider→LPM fudge+AppOps→Transport | COARSE min (FINE: gps) | ILocationManager | LPM delivery gate | No | No | **Yes** | No | GMS analogue | H | §25 A1/A5 |
| Updates (PendingIntent) | `LM.requestLocationUpdates(PI)` | app | LMS→LPM | · | · →PI send | · | · | LPM PI gate | No | No | **Yes** | No | · | H | A1/A5 |
| Current location | `LM.getCurrentLocation` | app | LMS→LPM | · | 30 s clamp → same gates | · | · | LPM gate | No | No | **Yes** | No | · | H | A1/A5 |
| Last known | `LM.getLastKnownLocation` | app | LMS→LPM cache | cache | read-time fudge+noteOp | COARSE min | · | LPM `getLastLocation` | No | No | **Yes** | No | stale only | H | A5 |
| Passive | `PASSIVE_PROVIDER` req | app | LMS→passive LPM | all (fan-in) | per-passive-reg re-filter | FINE to arm | · | passive LPM gate | No | No | **Yes** | No | none in-fw | H | A6 |
| Framework geofence/proximity | `addProximityAlert` | app | LMS→GeofenceManager | fused feed | in-system eval → PI bool | FINE to arm | · | GeofenceManager | No | No | **Yes** | No | GMS geofence | H | A7 |
| GNSS meas/nav/NMEA/status | `registerGnss*` | app | LMS→GnssManagerService | GNSS HAL | per-UID fan-out | FINE only | · | Gnss providers | No | No | **Yes** | No | raw fix if ungated | H | §25 refs |
| GMS updates/current/last | `FLPClient.*` | app→GMS-core | **GMS-core** | GMS fusion | GMS cache/compute → callback/PI | COARSE/FINE +bg | GMS IPC | **GMS coop only** | No | No | No¹ | **Yes** | full bypass of LMS | M | §25 GMS |
| GMS geofence | `GeofencingClient` | app→GMS-core | **GMS-core** | GMS FLP | GMS eval → event+fix | FINE+bg | GMS IPC | **GMS coop only** | No | No | No¹ | **Yes** | · | M/H | §25 GMS |
| AOSP fused provider | `FUSED_PROVIDER` | app | LMS→LPM | AOSP fused (gps+net) | same LMS gates | COARSE min | · | LPM gate | No | No | **Yes** | No | — | H | A10 |
| Wi-Fi scans/RTT | `WifiManager/RttManager` | app | WifiService | RF | system result | FINE+loc-on | Wifi Binder | deny FINE (E1) / hook (E2 fut.) | No | Partial² | For hook | No | DB inference | H | §25 wifi |
| BLE scans | `BluetoothLeScanner` | app | BT stack | RF | filtered w/o FINE | SCAN(+FINE/unfilt.) | BT Binder | deny FINE | No | Partial² | For hook | No | beacon DB | H | §25 bt |
| Cell info | `TelephonyManager` | app | Telephony | RF | gated result | FINE | Telephony Binder | deny FINE | No | Partial² | For hook | No | cell DB | M/H | §25 tel |
| Ranging (16) | `RangingManager` | app | Ranging stack | UWB/BLE/Wi-Fi | gated ranges | RANGING+FINE | — | deny either | No | Partial² | For hook | No | peer-relative | H | §25 ranging |
| Sensors/IMU/baro/activity | `SensorManager` | app | SensorService | phys | raw | none/ACTIVITY | — | E5 (no hook) | N/A | N/A | — | No | seed-only DR | H/M | §25 sens |
| Geocoder fwd/rev | `Geocoder` | app | GeocodeProvider/GMS | backend | address⇄fix | none listed | · | allow+log | N/A | N/A | — | Partial | interest only | H | §25 geo |
| IP/NSD/MCC | various net APIs | app | Connectivity | net | identifiers | none/INTERNET | — | E5 mitigations | N/A | N/A | — | No | coarse only | M | §25 net |

¹ AOSP change covers these only if their data transits LMS (unproven per build — verify in §22).
² Privileged-device-owner/permisssion-policy denial; interception itself needs AOSP.

---

## 17. APK vs System Service vs AOSP Matrix

| Capability | Ordinary APK | Privileged/system component (no AOSP rebuild) | AOSP modification |
|---|---|---|---|
| See another app's `LocationManager` calls/results | **No** — sandbox + Binder identity; no intercept API `[DF-H]` | No | Yes (LPM gates) |
| Transform another app's framework fix | **No** | No (permission mgmt only, e.g. owner toggles) | **Yes** |
| Enforce on GMS FLP/geofence outputs | **No** | No | Only with GMS cooperation (E3) |
| Mediate GNSS/NMEA per app | **No** | No | **Yes** (same change) |
| Mediate passive/cached/geofence(framework) | **No** | No | **Yes** (same change) |
| Manage policies (CRUD UI) | Yes (own data) | Yes (+ secure storage, cross-user) | N/A (APK is correct layer) |
| Deny FINE / toggle location globally | No (own perms only) | Partial (owner: global toggle, permission policy) | Yes (full) |
| Close RF side channels | No | Via permission denial only | Yes (per-service hooks) |
| Emergency bypass preservation | N/A | N/A | **Must** pass through + audit |

Prototype options A–D: see §21.

---

## 18. Binder/AIDL Boundary Analysis (conceptual — NOT frozen syntax)

- **Across Binder (Control APK ↔ LocShield service):** `getPolicies / getPolicy / setPolicy / removePolicy / preview / getStatus` (management plane only, cf. Doc 11). Every call validates caller identity from Binder UID (never trusts a client-supplied package alone — same `CallerIdentity` pattern as LMS); mutating calls require a policy-admin permission (exact name frozen at AOSP integration); read-own vs manage-other split enforced server-side. `[INF-H]` (pattern from `[SRC-H]`)
- **Parcelable (future):** `ApplicationSelector{packageName,userId}`, `AppPolicy` + sub-policies, `PolicyDecision` (+`appliedSpatial/appliedTemporal`), `AuditEvent` (no coordinates), `LocShieldStatus`. Versioned `schemaVersion`; unknown enums rejected. `[INF-H]`
- **NEVER across Binder to untrusted clients:** raw provider fixes, `LastLocation` entries, GNSS raw, GMS caches, per-delivery hot-path calls (policy reads stay in-process snapshots; §4.1 `LocalService` pattern). No `transformLocation()` oracle method (Doc 11 §5 rationale retained). `[INF-H]`
- **In-process only:** PolicyEngine ↔ Temporal/Transformation/Sanitizer; LPM delivery gates ↔ engine snapshot; snapshot publication. `[INF-H]`

---

## 19. Bypass Matrix

| Bypass | Req. permission | Req. privilege | Versions | LocShield visibility (LMS-only layer) | Mitigation | Residual risk |
|---|---|---|---|---|---|---|
| GMS FLP instead of LMS | COARSE/FINE | none (Play app) | 14/15/16 | **Invisible** (GMS-core) | E3 coop, or deny GMS location/FINE | High until traced |
| GMS geofence | FINE + bg | none | 14/15/16 | **Invisible** + delivers fix | E3 coop, or deny | High until traced |
| Raw GNSS/NMEA positioning | FINE | none | 14/15/16 | Visible only if Gnss gates hooked | Gate in same AOSP change (E2) | None after gating |
| Passive subscription | FINE | none | 14/15/16 | Visible at passive gate | Delivery-time eval (E2) | None after gating |
| Stale cache read after tightening | COARSE/FINE | none | 14/15/16 | Visible at read gate | Read-time re-eval (E2) | None after gating |
| Unfiltered BLE / Wi-Fi / cell DB positioning | FINE | none | 14/15/16 | Invisible to LMS | Deny FINE (E1) | None if FINE denied; else needs E2 hooks |
| RangingManager peer ranges (16) | RANGING+FINE | none | 16 | Invisible to LMS | Deny either | Relative-only; low absolute risk |
| Mock injection (GMS or framework) | MOCK + dev opts | user action | 14/15/16 | GMS mock invisible to LMS | Detect `isMock`, policy on mocks | Cross-flow untested |
| `LOCATION_BYPASS` emergency | signature | system/emergency | 15/16 | Visible (distinct op) | Pass through + audit, never mediate | Accepted (safety) |
| IP/GeoIP/NSD/MCC inference | none/INTERNET | none | 14/15/16 (+17 LAN perm) | Invisible | E5 mitigations | Coarse (country/city/venue) accepted residual |
| Sensor dead-reckoning | none/ACTIVITY | none | 14/15/16 | Invisible | Needs seed fix (already mediated) | Negligible alone |
| Downgrade FINE→? (privilege down) | — | — | — | N/A (restriction direction) | None needed | None |
| Kernel/baseband/carrier/OEM-HAL direct | — | exploit/OEM | any | Invisible | Out of boundary (Doc 04/08) | Explicit non-claim |

**Data-vs-inference split:** rows 1–9 are *data delivery* (interceptible at some layer); rows 10–11 are *inference* (no hook point — mitigated by permission denial, coarse residuals accepted). `LOCATION_BYPASS` is *authorized override* (passed through by design).

---

## 20. Threat/Enforcement Analysis

Per Doc 04 threats: T1/T2 (direct/request-vs-delivery) → LPM delivery gates E2; T3/T4 (cached/passive) → read/passive gates E2; T5 (geofence) → framework E2 + GMS E3; T6–T9 (GNSS/Wi-Fi/BT/cell) → GNSS E2, RF via FINE-denial E1; T10 (temporal) → engine (frozen) + delivery gating; T11/T17 (metadata) → sanitizer (frozen) at LPM gate; T12 (GMS path) → **E3, the highest residual**; T13/T14 (tampering/identity) → Binder-UID identity + policy-admin permission (same pattern as `CallerIdentity`); T15 (TOCTOU) → delivery-time re-eval + generation checks (frozen); T16 (replay/stale) → read-time re-eval; T18 (audit leak) → coordinate-free audit (frozen).

Two-boundary enforcement rule: **no channel is "controlled" without (a) an identified hook in its own process path, and (b) a per-build dynamic trace proving traffic crosses it.** LMS hooks prove framework channels; they prove nothing about GMS-core.

---

## 21. Prototype Feasibility Matrix (factual comparison — no selection made)

| Dimension | A: Ordinary APK only | B: APK + privileged/system component (no AOSP rebuild) | C: APK + service + targeted AOSP changes | D: Full AOSP-integrated LocShield |
|---|---|---|---|---|
| Framework fix transformation per app | Impossible | Impossible | **Possible** (LPM delivery + cache + geofence + GNSS gates) | Possible (same, hardened) |
| GMS FLP/geofence mediation | Impossible | Impossible | Only via GMS coop (E3) | Only via GMS coop (E3) |
| RF side channels | Impossible | Denial only (owner/perm policy) | Denial; hooks possible per service | Denial + hooks |
| Policy management UX | Possible | Possible (more secure storage) | Possible | Possible |
| Identity trust | Self-asserted only (insufficient) | Binder UID (service side) | `CallerIdentity` pattern | Same |
| Effort | Low | Medium (signing/provisioning) | High (build + test per version) | Highest |
| Testing | API-level only | + privilege matrix | + per-version traces, CTS-ish, perf | + full hardening/perf/battery |
| Enforceable | Own-app behavior only | Permission posture only | Framework channels (E2 set) | Framework channels, robustly |
| Unenforceable | Everything cross-app | Cross-app data plane | GMS-core internals (E3/E4), inference (E5) | Same residuals |

---

## 22. Emulator Experiment Plan

Harness per version: AVDs API 34, 35, 36 (GMS images AND AOSP images separately), `adb root` + `dumpsys location/appops`, binder tracing (`adb shell perfetto` / transaction logging), test apps (LMS-direct, GMS-FLP, passive, cache, geofence-fw, geofence-GMS, GNSS-reg, RF-scan). Record OS build + GMS version per run.

| ID | Objective | Setup / permissions | API used | Expected observation | Evidence to capture | Conclusion criteria |
|---|---|---|---|---|---|---|
| E-LMS-01 | Attribute LMS vs GMS delivery | Same fix requested via LMS and via GMS FLP | `requestLocationUpdates` vs `FLPClient.requestLocationUpdates` | `dumpsys location` shows LMS registrations for LMS app; GMS app path determined | dumpsys location, logcat, binder trace | PASS if GMS-app traffic provenance classified |
| E-LMS-02 | Coarse-contract check | COARSE-only grant | Both APIs | Obfuscated + throttled (10-min clamp fw) | Delivered fixes + intervals | PASS if bounds match §13 |
| E-PAS-01 | Passive recipient eval | App A active, app B passive | `PASSIVE_PROVIDER` | B receives A's fixes, per-B fudge/filter | Both apps' fixes | PASS if B's output follows B's grant |
| E-CACHE-01 | Read-time policy | Tighten grant after fix | `getLastKnownLocation` | Post-tightening reads degraded | Before/after reads | PASS if no stale precise leak |
| E-GEO-01 | Framework geofence content | FINE, fence armed | `addProximityAlert` | PI boolean only, no Location | Intent extras dump | PASS if no coordinate |
| E-GEO-02 | GMS geofence content | FINE+bg | `GeofencingClient` | Event + triggering Location | Intent extras dump | PASS if classified E3 (bypass of LMS) |
| E-GNSS-01 | GNSS gate with COARSE | COARSE-only | `registerGnssMeasurementsCallback` etc. | Rejected (false/exception) | Return values/logcat | PASS if no callbacks |
| E-GNSS-02 | NMEA content with FINE | FINE | `addNmeaListener` | Sentences incl. GGA/RMC fix | NMEA log | PASS if side-channel confirmed → must gate |
| E-RF-01 | Wi-Fi/BT/cell gates | COARSE-only, then FINE | `getScanResults`, BLE scan, `getAllCellInfo` | Denied/empty/filtered → full w/ FINE | Results + exceptions | PASS if FINE-denial backstop confirmed |
| E-MOCK-01 | Mock cross-flow | Mock app set | GMS `setMockLocation` + fw test provider | Record which clients see which mock | Both client outputs + `isMock` | PASS if flows mapped |
| E-DOWN-01 | Downgrade restart | FINE→approximate in settings | Running LMS+GMS clients | Process restart; post-restart coarse | logcat + fixes | PASS if restart + coarse observed |
| E-BYP-01 | Emergency path (15/16) | (system test) | `LOCATION_BYPASS` flow | `OP_EMERGENCY_LOCATION`, unmediated | AppOps dump | PASS if pass-through + audit identified |

---

## 23. Known Unknowns

1. GMS-core fusion inputs and IPC topology (closed source) — E-LMS-01/E-GEO-02 are designed to bound it behaviorally. `[INF-M]`
2. OEM HAL/vendor NLP deviations from Pixel/AOSP behavior (Samsung/Chinese ROMs, non-GMS devices with own NLP). `[INF-M]`
3. Per-build defaults of 16 S2 density flags and population-density provider availability (backend-dependent). `[SRC-M]`
4. `gps_hardware` standalone provider routing on 16 (passive feed exclusion) edge cases. `[SRC-M]`
5. PendingIntent delivery allowlist/foreground-service interactions for restricted apps. `[SRC-M]`
6. Exact GMS version drift in cache/availability semantics across the 14/15/16 window. `[DF-M]`
7. OEM backports of 17 local-network enforcement onto 16 builds. `[INF-L]`

---

## 24. Research Limitations

- Static research only: official docs + AOSP source snapshots (`android14/15/16-release`, `main`). **No dynamic (emulator/device) verification yet** — every `[INF]` and several `[SRC-M]` items require §22 experiments before becoming enforcement claims.
- GMS internals are closed; GMS claims rest on public references + behavioral inference, deliberately conservative (two-boundary rule).
- AOSP snapshots move (`main` vs release branches); exact line numbers are omitted on purpose — file + symbol + branch are the stable coordinates.
- Third-party mirrors (GitHub AOSP copies, aosp-internal-book) used only as discovery aids; load-bearing claims cite googlesource/developer docs.
- No OEM-firmware, baseband, or carrier analysis (out of boundary per Docs 04/08).

---

## 25. Evidence / Source Register

**Official docs (DOCUMENTED FACT sources):**
- `developer.android.com/about/versions/14/behavior-changes-14`, `.../14/changes/fgs-types-required`, `.../14/behavior-changes-all`
- `developer.android.com/about/versions/15/behavior-changes-15`, `.../15/behavior-changes-all`, `.../15/changes/foreground-service-types`
- `developer.android.com/about/versions/16/behavior-changes-16`, `.../16/behavior-changes-all`, `.../16/features`
- `developer.android.com/develop/sensors-and-location/location/permissions`, `.../permissions/runtime`, `.../location/background`, `.../location/request-updates`, `.../location/retrieve-current`, `.../location/geofencing`, `.../location/change-location-settings`, `.../location/migration`
- `developer.android.com/develop/sensors-and-location/sensors/gnss`, `.../gnss-analyze-raw`
- `developer.android.com/develop/background-work/services/fgs/service-types`, `.../fgs/launch`, `.../fgs/changes`
- `developer.android.com/reference/android/location/LocationManager`, `.../GnssMeasurement(s)(Event)`, `.../GnssNavigationMessage`, `.../GnssStatus`, `.../GnssCapabilities`, `.../GnssAntennaInfo`, `.../OnNmeaMessageListener`, `.../Geocoder`, `.../telephony/TelephonyManager`, `.../hardware/Sensor`, `.../app/AppOpsManager`, `.../app/admin/DevicePolicyManager`
- `developer.android.com/develop/connectivity/wifi/wifi-scan`, `.../wifi/wifi-permissions`, `.../wifi/wifi-rtt`, `.../connectivity/ranging`, `.../connectivity/uwb`, `.../connectivity/bluetooth/bt-permissions`, `.../bluetooth/companion-device-pairing`, `.../connectivity/wifi/use-nsd`, `.../net/nsd/NsdManager`
- `developer.android.com/privacy-and-security/local-network-permission`
- `developers.google.com/android/reference/com/google/android/gms/location/` (`FusedLocationProviderClient`, `FusedLocationProviderApi`, `Granularity`, `CurrentLocationRequest`, `LocationRequest`, `GeofencingClient`, `GeofencingEvent`, `GeofenceStatusCodes`, `SettingsClient`, `LocationServices`)
- `developers.google.com/location-context/fused-location-provider`, `.../geofencing`, `developers.google.com/nearby/connections/android/get-started`, `developers.google.com/android/guides/setup`
- `blog.google/security/whats-new-in-android-security-privacy-2026`, `android-developers.googleblog.com/2026/03/location-privacy` (17-preview context only)

**AOSP source (SOURCE FINDING coordinates — `platform/frameworks/base`, branches `android14-release` / `android15-release` / `android16-release` / `main`):**
- A1 `location/java/android/location/LocationManager.java`, `location/java/android/location/ILocationManager.aidl`
- A2 `location/java/android/location/util/identity/CallerIdentity.java` (`fromBinder`/`fromBinderUnsafe`)
- A3 `services/core/java/com/android/server/location/LocationManagerService.java` (entry points, `validateLocationRequest`, AppOps watch, geofence/GNSS fan-out, fudger binding)
- A4 `services/core/java/com/android/server/location/LocationPermissions.java`, `injector/*Helper`, `AppForegroundHelper`
- A5 `services/core/java/com/android/server/location/provider/LocationProviderManager.java` (`Registration`, clamping, `onReportLocation`, `acceptLocationChange`, `getPermittedLocation*`, `LastLocation`, transports)
- A6 `services/core/java/com/android/server/location/provider/PassiveLocationProvider*.java`
- A7 `services/core/java/com/android/server/location/geofence/GeofenceManager.java`
- A8 `services/core/java/com/android/server/location/fudger/LocationFudger.java`, (16+) `LocationFudgerCache.java`, `provider/proxy/ProxyPopulationDensityProvider.java`
- A9 `services/core/java/com/android/server/location/gnss/` (`GnssManagerService`, `Gnss{Measurements,Status,Nmea}Provider`)
- A10 `packages/FusedLocation/src/com/android/location/fused/FusedLocationProvider.java`
- A11 `core/java/android/app/AppOpsManager.java`, `core/java/android/app/AppOps.md` (`android16-release`)
- A12 `location/lib/java/com/android/location/provider/LocationProviderBase.java`
- A13 `source.android.com/docs/automotive/location/coarse-location` (AOSP fused "simple implementation"; GNSS degradation for coarse)

---

## Appendix A — Master Matrix (required columns)

| Channel | API | 14 | 15 | 16 | Process | Framework service | Provider | Permission | Enforcement point | APK possible | SysSvc possible | AOSP required | PlaySvc dep | Bypass | Class | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Continuous updates (listener) | `LM.requestLocationUpdates` | gated §4.1 | +bypass-flags path | +S2 fudge flags | app→system_server | LMS→LPM | gps/net/fused | COARSE min | LPM delivery | No | No | Yes | No | GMS analogue | E2 | A1/A5 |
| Updates (PI) | `LM.requestLocationUpdates(PI)` | gated | gated | gated | app→system_server | LMS→LPM | · | COARSE min | LPM PI gate | No | No | Yes | No | · | E2 | A1/A5 |
| Current location | `LM.getCurrentLocation` | gated, 30 s clamp | · | · | app→system_server | LMS→LPM | · | COARSE min | LPM gate | No | No | Yes | No | · | E2 | A1/A5 |
| Last known | `LM.getLastKnownLocation` | read-fudge+noteOp | +emergency-aware | · | app→system_server | LPM cache | cache | COARSE min | LPM read gate | No | No | Yes | No | stale only | E2 | A5 |
| Passive | `PASSIVE_PROVIDER` | fan-in+refilter | · | · (`gps_hardware` excl.) | app→system_server | passive LPM | all | FINE to arm | passive LPM gate | No | No | Yes | GMS P_NO_POWER sep. | none in-fw | E2 | A6 |
| Framework geofence | `addProximityAlert` | FINE-arm, sys eval, PI bool | · | · | app→system_server | GeofenceManager | fused feed | FINE | GeofenceManager | No | No | Yes | No | GMS geofence | E2 | A7 |
| GNSS raw/status/NMEA | `registerGnss*`/`addNmeaListener` | FINE-only | · | · | app→system_server | GnssManagerService | GNSS HAL | FINE | Gnss providers | No | No | Yes | No | raw fix if ungated | E2 | §25 refs |
| GMS updates/current/last | `FusedLocationProviderClient` | GMS-core served | · | · | app→GMS-core | GMS-core | GMS fusion | COARSE/FINE+bg | GMS coop only | No | No | No¹ | Yes | full LMS bypass | E3 | §25 GMS |
| GMS geofence | `GeofencingClient` | GMS-core eval+fix | · | · | app→GMS-core | GMS-core | GMS FLP | FINE+bg | GMS coop only | No | No | No¹ | Yes | full LMS bypass | E3 | §25 GMS |
| AOSP fused | `FUSED_PROVIDER` | gps+net pick | · | GPS-interval tweak | app→system_server | LMS→LPM | AOSP fused | COARSE min | LPM gate | No | No | Yes | No | — | E2 | A10 |
| Wi-Fi scan/RTT | `WifiManager`/`RttManager` | FINE+on, throttled | · | · | app→system | WifiService | RF | FINE | deny FINE / hook fut. | No | Deny² | Hook | No | DB inference | E1 | §25 wifi |
| BLE scan | `BluetoothLeScanner` | filtered w/o FINE | · | CDM timeout priv. | app→system | BT stack | RF | SCAN(+FINE) | deny FINE | No | Deny² | Hook | No | beacon DB | E1 | §25 bt |
| Cell info | `TelephonyManager` | FINE | · | · | app→system | Telephony | RF | FINE | deny FINE | No | Deny² | Hook | No | cell DB | E1 | §25 tel |
| Ranging | `RangingManager` | — | — | RANGING+FINE | app→system | Ranging stack | UWB/BLE/Wi-Fi | RANGING+FINE | deny either | No | Deny² | Hook | No | relative only | E1 | §25 ranging |
| Sensors/activity | `SensorManager` | no-perm/ACTIVITY | · | · | app→system | SensorService | phys | none/ACTIVITY | E5 | N/A | N/A | — | No | seed-only DR | E5 | §25 sens |
| Geocoder | `Geocoder` | no loc perm | provider migrated | · | app→system/GMS | GeocodeProvider | backend | none listed | allow+log | N/A | N/A | — | Partial | interest only | E5 | §25 geo |
| IP/NSD/MCC | net APIs | open | · | LAN opt-in | app→system/net | Connectivity | net | none/INTERNET | E5 mitigations | N/A | N/A | — | No | coarse only | E5 | §25 net |

¹ Unless proven to transit LMS per build (§22). ² Permission-posture denial via privileged management, not interception.

---

## Appendix B — Critical Research Questions (explicit answers)

1. **Can an ordinary APK enforce per-app precision on another APK?** No. Sandbox + Binder identity + no intercept API. `[DF-H]`
2. **Can a normal service intercept another app's LocationManager calls?** No — calls terminate in `system_server` LMS; a normal service is just another client. `[SRC-H]`
3. **Can a normal APK intercept GMS FLP results for another APK?** No — GMS-core IPC is app↔GMS per-client. `[DF-H]`
4. **Where does LocationManager cross Binder?** Every method via `ILocationManager` to LMS (`registerLocationListener/PendingIntent`, `getCurrentLocation`, `getLastLocation`, `requestGeofence`, `registerGnss*`). `[SRC-H]`
5. **Where does LMS enforce permissions?** Entry (`enforceLocationPermission` + `getPermissionLevel`), registration (`validateLocationRequest`, coarse clamp), delivery (`getPermittedLocation*`, `noteOpNoThrow`, permitted/foreground gates), read (`getLastLocation` gate). `[SRC-H]`
6. **Where is location generated?** Providers: `GnssLocationProvider` (GNSS HAL), NLP/fused overlays, passive fan-in, mocks; reported via `onReportLocation`. `[SRC-H]`
7. **Where is fused processing?** AOSP: `packages/FusedLocation` (gps+network pick). GMS: GMS-core proprietary fusion. Two different things. `[SRC-H]`/`[DF-H]`
8. **Does GMS FLP necessarily pass through LMS?** Not proven; treat as no. Two-boundary rule. `[INF-M]`
9. **Can passive bypass per-app policy?** Not LMS gates, but only if delivery-time evaluation covers the passive gate — a request-time-only layer would miss it. `[SRC-H]`
10. **Can getLastKnownLocation bypass delivery-time policy?** No — read-time fudge + noteOp per caller; still needs current-policy evaluation. `[SRC-H]`
11. **Can geofencing reveal location without a location callback?** Yes — boolean presence at a known point (framework) or event + fix (GMS). `[SRC-H]`/`[DF-H]`
12. **Can GNSS/NMEA side-channel?** Yes — independent WLS fixes; NMEA carries direct lat/lon. Must gate. `[DF-H]`
13. **Can Wi-Fi/cell independently infer?** Yes via external DBs — but all fine uses are FINE-gated, so FINE denial closes them. `[DF-H]`
14. **What can a privileged system service enforce?** Management plane (policy CRUD, storage, diagnostics), permission posture (owner controls), in-process policy evaluation for its own clients — but NOT another app's data plane without AOSP hooks. `[INF-H]`
15. **What requires AOSP changes?** LPM delivery + cache-read + passive + framework-geofence + GNSS gates; any RF interception beyond denial; any in-framework identity/policy plumbing. `[INF-H]`
16. **What cannot be reliably controlled even then?** GMS-core internals (E3), vendor/HAL paths, inference channels IP/NSD/sensors (E5), kernel/baseband/carrier (out of boundary). `[INF-H]`
17. **Minimum AOSP modification for true per-app transformation?** Hook set: (a) `LocationProviderManager` delivery (`acceptLocationChange`-equivalent: transform + sanitize + noteOp + generation check), (b) `getLastLocation` read path, (c) `PassiveLocationProviderManager` delivery, (d) `GeofenceManager` arm/event gates, (e) `GnssManagerService` listener gates, (f) in-process policy snapshot + `CallerIdentity`-derived subject key + AppOps/permission invalidation wiring; pass through `LOCATION_BYPASS` emergency. All in `system_server` location tree — no app-process hooks. `[INF-H]` (design synthesis from `[SRC-H]` hook inventory)
18. **What of Policy Engine v0.1 is reusable unchanged?** Everything: domain model, validation, resolution/intersection, decision/temporal/spatial/metadata logic, fail-closed and isolation semantics. Only Android-specific adapters (identity from `CallerIdentity`, ceiling from permission+AppOps+location-mode, monotonic clock, RNG source, city table enrichment) are new code around it. The `appliedSpatial/appliedTemporal` decision fields exist precisely so LPM gates enforce per-decision without re-deriving. `[INF-H]`

---

## Implementation recommendation (first experiments, in order)

1. **E-LMS-01 (path attribution)** — decides the entire GMS strategy; do first on all three AVD generations, GMS and AOSP images.
2. **E-GEO-02 + E-GNSS-02** — confirm the two highest-risk bypasses (GMS fix in event; NMEA direct fix).
3. **E-PAS-01 + E-CACHE-01** — confirm delivery-time/read-time semantics the frozen engine already assumes.
4. **E-RF-01 + E-GNSS-01** — confirm the FINE-denial backstop (cheapest mitigation, highest leverage).
5. **E-MOCK-01, E-DOWN-01, E-BYP-01, E-GEO-01, E-LMS-02** — complete the matrix; E-BYP-01 defines the emergency pass-through contract.

STOP — no service/APK/AIDL/AOSP/emulator-app implementation in this milestone. Next stage designs the System Service and the §B-Q17 hook set using this matrix, gated on §22 results.
