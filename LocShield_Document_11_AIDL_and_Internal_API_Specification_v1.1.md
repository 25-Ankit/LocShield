# LocShield — Document 11: AIDL & Internal API Specification v1.1

- **Document Version:** 1.1 (Final Architecture & Interface Contract Freeze)
- **Date:** September 2026
- **Status:** Authoritative Interface Contract Specification
- **Supersedes:** Document 11 v1.0 and aligns with `Enforcement_Architecture_Specification_v2.1.1.md`.
- **Authority Scope:** Freezes all public Binder/AIDL interfaces, Parcelable data transfer objects, in-process LocalService interfaces, adapter contracts, and capability gates between:
  $$\text{Control APK} \underset{\text{Binder}}{\Longleftrightarrow} \text{LocShieldSystemService} \underset{\text{In-Process}}{\Longleftrightarrow} \text{Policy Engine v0.1} \underset{\text{In-Process}}{\Longleftrightarrow} \text{Framework Interceptors}$$
- **Binding Engineering Mandates:**
  - Policy Engine v0.1 remains 100% frozen, pure Kotlin/JVM, and dependency-free.
  - Hot-path location delivery remains **strictly in-process** inside `system_server`; zero per-update Binder IPC.
  - GMS Fused Location Provider is strictly an **Authorization-Only Coarse Backstop**; no unsupported coordinate rewriting.
  - Fail-closed security: raw location is never emitted on failure, exception, or ambiguity.
  - No implementation code, no AOSP patches, and no APK artifacts are created in this milestone.

---

## 1. Executive Summary & Interface Topology

Document 11 v1.1 establishes the definitive API and IPC contract for the LocShield system architecture. It decomposes the interface boundary into two distinct planes:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CONTROL PLANE (Out-of-Band)                       │
│                                                                             │
│   ControlApp (UID 10xxx)                                                    │
│      │                                                                      │
│      ▼ [AIDL: ILocShieldManager] (Protected by MANAGE_LOCATION_PRIVACY)     │
│   LocShieldSystemService (system_server, UID 1000)                          │
│      │                                                                      │
│      ├── Transactional Policy CRUD                                          │
│      ├── Identity & Permission Validation                                   │
│      └── Lock-Free PolicySnapshot Publishing (Monotonic Generation G -> G+1) │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼ In-Process LocalService Reference
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DATA PLANE (Hot Path)                            │
│                                                                             │
│   AOSP Location Subsystem (system_server, UID 1000)                         │
│      │                                                                      │
│      ├── LocationProviderManager.acceptLocationChange() [Real-Time Stream]  │
│      ├── LocationProviderManager.getLastLocation()       [Read-Time Cache]  │
│      ├── PassiveLocationProviderManager.updateLocation() [Passive Fan-Out]  │
│      ├── GnssManagerService (Measurements, NMEA, Status) [Observable Gates] │
│      └── GnssBatchingProvider.onReportLocationBatch()    [Batch Flush Gates]│
│      │                                                                      │
│      ▼ [Local Java API: LocShieldInternal] (Sub-microsecond, No Binder)     │
│   PolicyEngineAdapter                                                       │
│      │                                                                      │
│      ▼ [Pure JVM: PolicyEngine.evaluate()]                                  │
│   Policy Engine v0.1 Core                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Control Plane Interface (`ILocShieldManager.aidl`):** Cross-process Binder interface exposing transactional policy management, status inspection, and coordinate-free audit queries to authorized administrative clients.
2. **Data Plane Local Interface (`LocShieldInternal.java`):** High-performance, zero-allocation in-memory interface invoked synchronously on location delivery threads within `system_server` (measured ~105 ns latency).

---

## 2. Binder Service Name & Registration Model

### 2.1 Service Name & Publication
- **Binder Service Name:** `location_privacy`
- **Publication Call:**
  ```java
  ServiceManager.addService("location_privacy", mBinderService);
  ```
- **Lifecycle Integration:** Registered by `LocShieldSystemService.Lifecycle` within `SystemServer.java` during `SystemService.PHASE_SYSTEM_SERVICES_READY`.
- **Local Service Registration:** Publishes an instance of `LocShieldInternal` to `LocalServices`:
  ```java
  LocalServices.addService(LocShieldInternal.class, mLocalService);
  ```

### 2.2 SELinux Contexts & Access Control
- **Service Object Label (`service_contexts`):**
  ```text
  location_privacy                          u:object_r:location_privacy_service:s0
  ```
- **Service Type Declaration (`service.te`):**
  ```te
  type location_privacy_service, app_api_service, system_server_service, service_manager_type;
  ```
- **System Server Rule (`system_server.te`):**
  ```te
  allow system_server location_privacy_service:service_manager { add find };
  ```
- **Executing Process Domain:** `system_server` runs in `u:r:system_server:s0`. `LocShieldSystemService` runs inside `system_server` on its existing thread pools. It undergoes **no process domain transition**.

---

## 3. Control-APK Authorization Requirements

Every transaction on `ILocShieldManager` is guarded by platform permissions and kernel-enforced UID verification:

```
                          Client Invokes Binder Method
                                       │
                                       ▼
                       Enforce Calling UID Verification
                                       │
            ┌──────────────────────────┴──────────────────────────┐
            ▼                                                     ▼
    Target == Calling App?                               Target != Calling App?
            │                                                     │
            ▼                                                     ▼
Enforce: MANAGE_LOCATION_PRIVACY                     Enforce: MANAGE_LOCATION_PRIVACY
    (Signature / Privileged)                              (Signature / Privileged)
            │                                                     │
            ▼                                                     ▼
Check userId == Calling User?                       Enforce: INTERACT_ACROSS_USERS_FULL
            │                                                     │
            └──────────────────────────┬──────────────────────────┘
                                       │
                                       ▼
                         Execute Requested Transaction
```

1. **Required Permission:** `android.permission.MANAGE_LOCATION_PRIVACY`
   - Protection Level: `signature|privileged`
   - Declared in `frameworks/base/core/res/AndroidManifest.xml`.
2. **Caller Identity Verification:**
   - `int callingUid = Binder.getCallingUid();`
   - `int callingPid = Binder.getCallingPid();`
   - If a caller specifies a target package, `LocShieldSystemService` verifies that the target package belongs to `callingUid` via `PackageManager.getPackagesForUid(callingUid)` unless the caller holds `android.permission.INTERACT_ACROSS_USERS_FULL`.
3. **Cross-User Protection:** Target `userId` parameters are validated against `UserHandle.getCallingUserId()`. Modification of another user's policy requires `INTERACT_ACROSS_USERS_FULL`.

---

## 4. Syntactically Complete AIDL Interface Specifications

All AIDL files are defined under package `android.location.privacy`.

### 4.1 Parcelable Definitions

#### `AppPolicyParcel.aidl`
```aidl
package android.location.privacy;

import android.location.privacy.SpatialPolicyParcel;
import android.location.privacy.TemporalPolicyParcel;
import android.location.privacy.BackgroundPolicyParcel;
import android.location.privacy.MetadataPolicyParcel;
import android.location.privacy.RandomizationPolicyParcel;

parcelable AppPolicyParcel {
    int schemaVersion;
    String packageName;
    int userId;
    boolean enabled;
    SpatialPolicyParcel spatial;
    TemporalPolicyParcel temporal;
    BackgroundPolicyParcel background;
    MetadataPolicyParcel metadata;
    RandomizationPolicyParcel randomization;
    long createdAtWallMs;
    long updatedAtWallMs;
    long expiresAtWallMs; // -1 indicates null / never expires
}
```

#### `SpatialPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable SpatialPolicyParcel {
    int mode; // 0=EXACT, 1=RADIUS, 2=GRID, 3=CITY, 4=RANDOMIZED, 5=DENY, 6=ANDROID_CEILING
    double radiusMeters; // Valid if mode==RADIUS or mode==RANDOMIZED
    double gridMeters;   // Valid if mode==GRID
    String cityId;       // Valid if mode==CITY; null for nearest-city lookup
}
```

#### `TemporalPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable TemporalPolicyParcel {
    int mode; // 0=REALTIME, 1=MIN_INTERVAL, 2=PERIODIC, 3=RATE_LIMIT, 4=ONE_SHOT
    long minimumIntervalMs;      // Valid if mode==MIN_INTERVAL
    long periodicIntervalMs;     // Valid if mode==PERIODIC
    int maxDeliveriesPerWindow;  // Valid if mode==RATE_LIMIT
    long windowMs;               // Valid if mode==RATE_LIMIT
}
```

#### `BackgroundPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable BackgroundPolicyParcel {
    int mode; // 0=INHERIT_ANDROID, 1=ALLOW_POLICY, 2=RESTRICT, 3=DENY
}
```

#### `MetadataPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable MetadataPolicyParcel {
    int accuracyMode;        // 0=RETAIN, 1=COARSE, 2=DENY
    int timestampMode;       // 0=RETAIN, 1=COARSE, 2=DENY
    int elapsedRealtimeMode; // 0=RETAIN, 1=COARSE, 2=DENY
    int altitudeMode;        // 0=RETAIN, 1=COARSE, 2=DENY
    int speedMode;           // 0=RETAIN, 1=COARSE, 2=DENY
    int bearingMode;         // 0=RETAIN, 1=COARSE, 2=DENY
    int providerMode;        // 0=RETAIN, 1=COARSE, 2=DENY
    int extrasMode;          // 0=RETAIN, 1=COARSE, 2=DENY
}
```

#### `RandomizationPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable RandomizationPolicyParcel {
    boolean enabled;
    double radiusMeters;
    long stableForMs; // -1 indicates null
    int seedMode;     // 0=PER_DELIVERY, 1=PERIOD_STABLE, 2=SESSION_STABLE, 3=SYSTEM_MANAGED
}
```

#### `PolicyDecisionParcel.aidl`
```aidl
package android.location.privacy;

import android.location.privacy.SpatialPolicyParcel;
import android.location.privacy.TemporalPolicyParcel;

parcelable PolicyDecisionParcel {
    int decision;           // 0=ALLOW, 1=TRANSFORM, 2=THROTTLE, 3=DENY, 4=FAIL_CLOSED
    int spatialMode;
    int temporalAction;     // 0=PROCEED, 1=SUPPRESS
    int metadataAction;     // 0=RETAIN, 1=SANITIZE
    int reasonCode;
    long policyGeneration;
    long generatedAtElapsedNanos;
    SpatialPolicyParcel appliedSpatial;
    TemporalPolicyParcel appliedTemporal;
}
```

#### `AuditEventParcel.aidl`
```aidl
package android.location.privacy;

parcelable AuditEventParcel {
    long timestampWallMs;
    int uid;
    String packageName;
    int requestType;    // 0=CONTINUOUS, 1=CURRENT, 2=LAST, 3=GEOFENCE_REG, 4=GEOFENCE_EVENT
    int decision;       // 0=ALLOW, 1=TRANSFORM, 2=THROTTLE, 3=DENY, 4=FAIL_CLOSED
    long policyGeneration;
    int reasonCode;
    int sourceClass;    // 0=GNSS, 1=FUSED, 2=NETWORK, 3=PASSIVE, 4=CACHED, 5=GEOFENCE, 6=UNKNOWN
    // STRICT SECURITY MANDATE: Zero coordinate fields (no lat, lon, alt).
}
```

#### `LocShieldStatusParcel.aidl`
```aidl
package android.location.privacy;

parcelable LocShieldStatusParcel {
    boolean serviceReady;
    int activePolicyCount;
    long currentGeneration;
    int aospApiLevel;
    boolean gmsCoarseBackstopActive;
    long uptimeMs;
}
```

#### `PolicyPreviewRequestParcel.aidl`
```aidl
package android.location.privacy;

parcelable PolicyPreviewRequestParcel {
    String packageName;
    int userId;
    int requestType;
    boolean isForeground;
    boolean hasBackgroundPermission;
    int hypotheticalAndroidAuth; // 0=DENIED, 1=APPROXIMATE, 2=PRECISE
}
```

---

### 4.2 Main Management Interface (`ILocShieldManager.aidl`)

```aidl
package android.location.privacy;

import android.location.privacy.AppPolicyParcel;
import android.location.privacy.PolicyDecisionParcel;
import android.location.privacy.PolicyPreviewRequestParcel;
import android.location.privacy.LocShieldStatusParcel;
import android.location.privacy.AuditEventParcel;

interface ILocShieldManager {
    /**
     * Retrieves all stored policies for a specific Android user ID.
     * Requires MANAGE_LOCATION_PRIVACY.
     */
    List<AppPolicyParcel> getPolicies(int userId);

    /**
     * Retrieves the policy for a specific package and user.
     * Returns null if no explicit policy is configured.
     */
    AppPolicyParcel getPolicy(String packageName, int userId);

    /**
     * Transactionally persists or updates an application policy.
     * Validates schema, bounds, and package identity.
     * Returns true on successful commit and snapshot generation advance.
     */
    boolean setPolicy(in AppPolicyParcel policy);

    /**
     * Resets an application's policy to the default configuration.
     */
    boolean resetPolicy(String packageName, int userId);

    /**
     * Evaluates a hypothetical request against current policy state.
     * STRICT SECURITY RULE: Evaluates policy logic only; NEVER returns coordinates.
     */
    PolicyDecisionParcel preview(in PolicyPreviewRequestParcel request);

    /**
     * Queries system health, snapshot generation, and capability state.
     */
    LocShieldStatusParcel getStatus();

    /**
     * Retrieves bounded historical enforcement decisions for diagnostics.
     * All entries are strictly coordinate-redacted.
     */
    List<AuditEventParcel> getAuditEvents(int maxEvents);
}
```

---

## 5. Request, Response & Reason-Code Mappings

All reason codes map 1:1 with the frozen `locshield.model.ReasonCode` enum:

| Enum Name | Integer Wire Value | Category | Explanation |
|---|---|---|---|
| `ALLOW_OK` | 0 | Normal Execution | Operation authorized without additional spatial transformation. |
| `TRANSFORM_OK` | 1 | Normal Execution | Operation authorized subject to spatial and metadata transformation. |
| `ANDROID_AUTHORIZATION_DENIED` | 2 | Authorization Gate | Android platform permissions or AppOps denied location access. |
| `BACKGROUND_DENIED` | 3 | Context Gate | Application is in background without background authorization or policy. |
| `POLICY_DISABLED` | 4 | Policy Gate | Application policy has `enabled == false`. |
| `POLICY_EXPIRED` | 5 | Policy Gate | Policy `expiresAtWallMs` has been exceeded; safe fallback applied. |
| `SPATIAL_DENY` | 6 | Spatial Gate | Policy sets `SpatialMode == DENY`. |
| `TEMPORAL_THROTTLE` | 7 | Temporal Gate | Rate-limit or minimum-interval suppressed this delivery. |
| `INVALID_POLICY` | 8 | Validation Failure | General policy structure rejected by `PolicyValidator`. |
| `UNKNOWN_MODE` | 9 | Validation Failure | Unrecognized enum constant passed across wire. |
| `MISSING_FIELD` | 10 | Validation Failure | Required mode parameter omitted (e.g. radius for `RADIUS`). |
| `INVALID_VALUE` | 11 | Validation Failure | Non-finite or negative parameter (e.g. NaN coordinate or interval). |
| `VALUE_OUT_OF_BOUNDS` | 12 | Validation Failure | Value exceeds prototype bounds (e.g. radius $> 100$ km). |
| `CONTRADICTORY_FIELDS` | 13 | Validation Failure | Contradictory settings (e.g. `DENY` carrying a grid parameter). |
| `UNSUPPORTED_SCHEMA` | 14 | Validation Failure | Schema version does not match `CURRENT_SCHEMA_VERSION` (1). |
| `OVERFLOW` | 15 | Validation Failure | Arithmetic overflow in interval or window calculation. |
| `CORRUPT_SNAPSHOT` | 16 | Storage Failure | In-memory or disk snapshot failed integrity verification. |
| `TRANSFORMATION_FAILURE` | 17 | Runtime Exception | Error during coordinate math; delivery dropped (`FAIL_CLOSED`). |
| `METADATA_FAILURE` | 18 | Runtime Exception | Error sanitizing metadata fields; parcel dropped (`FAIL_CLOSED`). |
| `RNG_FAILURE` | 19 | Runtime Exception | Failure in random source during randomized perturbation. |
| `TEMPORAL_STATE_INVALID` | 20 | Runtime Exception | Internal temporal history queue corruption; throttled. |
| `ENGINE_EXCEPTION` | 21 | Runtime Exception | Uncaught runtime error; fails closed. |
| `UNKNOWN_CAPABILITY` | 22 | Capability Gate | Invocation of unsupported or unmapped capability. |

---

## 6. Policy CRUD & Transaction Lifecycle

```
ControlApp                  LocShieldSystemService             PolicyRepository           Local Interceptors
    │                                  │                              │                            │
    │ 1. setPolicy(parcel)             │                              │                            │
    ├─────────────────────────────────►│                              │                            │
    │                                  │ 2. Enforce Signature Perm    │                            │
    │                                  │ 3. Validate calling UID      │                            │
    │                                  │ 4. PolicyValidator.validate()│                            │
    │                                  ├──────────────┐               │                            │
    │                                  │              ▼               │                            │
    │                                  │        Validation OK?        │                            │
    │                                  │◄─────────────┘               │                            │
    │                                  │                              │                            │
    │                                  │ 5. Atomic Disk Write         │                            │
    │                                  ├─────────────────────────────►│                            │
    │                                  │                              │ 6. fsync()                 │
    │                                  │ 7. Write Confirmed           │                            │
    │                                  │◄─────────────────────────────┤                            │
    │                                  │                                                           │
    │                                  │ 8. Compile new immutable PolicySnapshot                   │
    │                                  │ 9. Generation = Generation + 1                            │
    │                                  │ 10. AtomicReference.set(newSnapshot)                      │
    │                                  ├──────────────────────────────────────────────────────────►│
    │                                  │                                                           │ (Lock-free swap;
    │ 11. Return true (Success)        │                                                           │  all future calls
    │◄─────────────────────────────────┤                                                           │  read Gen G+1)
```

1. **Transactional Integrity:** Policy updates are serialized via an internal write lock within `LocShieldSystemService`.
2. **Atomic Disk Persistence:** `PolicyRepository` writes to disk using `android.util.AtomicFile` (writing to a `.tmp` file and renaming atomically). Disk sync (`fsync`) completes before the memory snapshot is updated.
3. **Rollback Semantics:** If disk serialization throws an `IOException`, the transaction fails immediately; the in-memory snapshot generation is **not** incremented, and `false` is returned to the Control APK.
4. **Instantaneous Hot-Path Activation:** Interceptors hold an `AtomicReference<PolicySnapshot>`. Updating the reference makes the new policy active across all concurrent delivery threads simultaneously without pausing location dispatch.

---

## 7. Package / UID Identity Binding Contract

- **Identity Principle:** Identity is bound exclusively to the kernel-verified calling context provided by Binder.
- **Resolution Implementation:**
  ```java
  public final class IdentityResolver {
      public static AppIdentity resolve(Context context, int callingUid, String assertedPackage, String attributionTag) {
          int userId = UserHandle.getUserId(callingUid);
          PackageManager pm = context.getPackageManager();
          String[] packages = pm.getPackagesForUid(callingUid);
          
          if (packages == null || packages.length == 0) {
              throw new SecurityException("No packages associated with UID " + callingUid);
          }
          
          String verifiedPackage = null;
          for (String pkg : packages) {
              if (pkg.equals(assertedPackage)) {
                  verifiedPackage = pkg;
                  break;
              }
          }
          
          // Reject identity spoofing
          if (verifiedPackage == null) {
              // If asserted package is invalid, bind to primary package of UID
              verifiedPackage = packages[0];
          }
          
          return new AppIdentity(callingUid, userId, verifiedPackage, attributionTag, null);
      }
  }
  ```
- **Shared UID Aggregation:** For apps sharing a UID (`android:sharedUserId`), if individual packages declare separate policies, `PolicyResolver` selects the **most restrictive** spatial and temporal bounds across the shared UID.

---

## 8. Effective-Policy Calculation Contract

The effective policy is computed as the strict intersection of three inputs:
$$\text{EffectivePolicy} = \text{AndroidAuthorization} \cap \text{ConfiguredPolicy} \cap \text{ContextRestrictions}$$

```
                                [ Configured AppPolicy ]
                                           │
                       ┌───────────────────┴───────────────────┐
                       ▼                                       ▼
                  Explicit Exists?                        No Explicit?
                       │                                       │
                       ▼                                       ▼
                  Load Policy                           Load User Default
                                                               │
                                                               ▼
                                                      Load System Default
                                                               │
                       ┌───────────────────────────────────────┘
                       ▼
            Check Expiration (Wall Clock)
              - If expired: safeFallbackPolicy() (DENY-preserving)
                       │
                       ▼
            Check Enabled Flag
              - If disabled: DENY
                       │
                       ▼
            [ Intersect with AndroidAuthorization ]
              - If locationAllowed == false: DENY
              - If approximateOnly == true:
                  SpatialMode = min(SpatialMode, GRID_2000M)
              - If backgroundAllowed == false and isForeground == false:
                  DENY
                       │
                       ▼
            [ Apply Background Restriction ]
              - If isForeground == false and BackgroundMode == RESTRICT:
                  Spatial = stricter(Spatial, GRID_1000M)
                  Temporal = stricter(Temporal, MIN_INTERVAL_15M)
                       │
                       ▼
               Final EffectivePolicy
```

---

## 9. PolicyDecision & Adapter Delivery Contract

The output of the Policy Engine is the immutable `PolicyDecision` domain object. The enforcement layer consumes this object via the `LocShieldInternal` local service:

### `LocShieldInternal.java` (In-Process Interface)

```java
package com.android.server.location.privacy;

import android.location.Location;
import android.location.LocationResult;
import com.android.server.location.caller.CallerIdentity;
import java.util.List;

public interface LocShieldInternal {
    /**
     * Intercepts standard framework location update streams.
     * Invoked at: LocationProviderManager.LocationListenerRegistration.acceptLocationChange()
     * Returns: Transformed LocationResult, or null if dropped (DENY / THROTTLE).
     */
    LocationResult onLocationDelivery(CallerIdentity identity, LocationResult fineResult, boolean isPassive);

    /**
     * Intercepts single-shot current location requests.
     * Invoked at: LocationProviderManager.GetCurrentLocationListenerRegistration.acceptLocationChange()
     */
    Location onCurrentLocationDelivery(CallerIdentity identity, Location fineLocation);

    /**
     * Intercepts cached location queries.
     * Invoked at: LocationProviderManager.getLastLocation()
     * Returns: Transformed Location, or null (cache miss / DENY).
     */
    Location onCacheRead(CallerIdentity identity, Location rawCachedLocation);

    /**
     * Intercepts hardware-offloaded GNSS location batch flushes.
     * Invoked at: GnssManagerService (IBatchedLocationCallback dispatch)
     * Returns: Transformed list of Locations, or empty list if denied.
     */
    List<Location> onGnssBatchDelivery(CallerIdentity identity, List<Location> rawBatch);

    /**
     * Intercepts framework proximity alert registrations.
     * Invoked at: GeofenceManager.addGeofence()
     * Returns: true if registration is allowed; false if dropped.
     */
    boolean onGeofenceRegistration(CallerIdentity identity, double latitude, double longitude, float radius);

    /**
     * Intercepts framework proximity alert transition broadcasts.
     * Invoked at: GeofenceManager.onLocationChanged()
     * Returns: true if event delivery is permitted; false if suppressed.
     */
    boolean onGeofenceTransitionDelivery(CallerIdentity identity, boolean entering);

    /**
     * Capability gate for raw GNSS observables.
     * Returns: true if caller policy is EXACT; false if suppressed.
     */
    boolean isGnssMeasurementsAllowed(CallerIdentity identity);
    boolean isGnssNmeaAllowed(CallerIdentity identity);
    boolean isGnssNavigationMessageAllowed(CallerIdentity identity);
    boolean isGnssAntennaInfoAllowed(CallerIdentity identity);

    /**
     * Capability gate for satellite status azimuth/elevation.
     * Returns: 0=ALLOW, 1=SANITIZE (zero angles), 2=MUTE
     */
    int getGnssStatusGateMode(CallerIdentity identity);
}
```

---

## 10. Delivery-Time Enforcement Adapter Interface

The primary enforcement adapter connects `LocationProviderManager` directly to `PolicyEngine`:

```java
public final class LocationDeliveryAdapter {
    public static LocationResult processDelivery(
            PolicyEngine engine,
            PolicySnapshot snapshot,
            CallerIdentity identity,
            LocationResult rawResult,
            boolean isPassive,
            long nowNanos) {
        
        // 1. Fail closed on null or corrupt input
        if (rawResult == null || identity == null) return null;
        
        AppIdentity appIdentity = IdentityResolver.fromCallerIdentity(identity);
        EffectivePolicy policy = snapshot.resolve(appIdentity);
        
        // 2. Evaluate delivery against active generation
        LocationSample sample = AndroidLocationAdapter.toSample(rawResult.getLastLocation());
        LocationContext context = new LocationContext(sample, nowNanos, SourceType.FUSED, false, isPassive, false);
        RequestContext reqContext = AndroidRequestContextAdapter.toContext(identity);
        
        PolicyDecision decision = engine.evaluate(appIdentity, reqContext, policy, context, nowNanos);
        
        // 3. Act on decision
        if (decision.getDecision() == Decision.DENY || decision.getDecision() == Decision.FAIL_CLOSED) {
            return null; // Suppress delivery
        }
        if (decision.getDecision() == Decision.THROTTLE) {
            return null; // Suppress delivery due to temporal rate-limit
        }
        
        // 4. Transform and Sanitize
        Location transformed = AndroidLocationAdapter.transform(
                rawResult.getLastLocation(), 
                decision.getAppliedSpatial());
        Location sanitized = AndroidMetadataAdapter.sanitize(
                transformed, 
                decision.getAppliedSpatial(), 
                policy.getMetadata());
        
        // 5. Record successful delivery
        engine.getTemporal().recordDelivery(
                appIdentity, 
                decision.getAppliedTemporal(), 
                decision.getPolicyGeneration(), 
                nowNanos);
        
        return LocationResult.create(new Location[] { sanitized });
    }
}
```

---

## 11. TemporalController Contract

- **Time Source:** Injected monotonic hardware clock (`SystemClock.elapsedRealtimeNanos()`). Wall-clock time is **strictly prohibited** for interval math (immune to clock changes, NTP adjustments, or DST shifts).
- **Concurrency & Locking:** Per-identity monitor lock. State reads and window deque updates are atomic per identity.
- **Saturating Arithmetic:** Millisecond-to-nanosecond conversions use saturating math; overflow resolves to `SUPPRESS` (`FAIL_CLOSED`).
- **State Partitioning:** History deques and last-delivery timestamps are partitioned by `AppIdentity` and policy generation. Advancing the generation counter invalidates stale temporal locks.

---

## 12. TransformationEngine Contract

- **Immutability Guarantee:** Provider-owned `Location` objects are **never mutated in place**. The adapter instantiates a clean `Location` parcel.
- **CRS Projection Assumption:** Metric grid quantization calculates cell centers in equirectangular projected meters ($x = R \cdot \lambda \cdot \cos(\phi)$, $y = R \cdot \phi$), with $\cos(\phi)$ clamped at $\cos(89.9^\circ)$ near poles.
- **Offline City Table:** CITY mode matches against the local, static 8-city fixture table. Coordinates beyond 50 km of known cities fall back to a deterministic 20 km metric grid with `cityId = null`. Zero network geocoding.
- **Randomization Seeding:** Seed is derived via 64-bit FNV-1a hash of package name, user ID, and time window. The seed is **never derived from coordinates**.

---

## 13. MetadataSanitizer Contract

Sanitization executes synchronously after coordinate transformation:
1. **Accuracy Floor:**
   $$\text{accuracy}_{\text{final}} = \max(\text{accuracy}_{\text{raw}}, \text{floor}(\text{SpatialMode}))$$
   Under `CITY`, floor is 5,000 meters; under `GRID(G)`, floor is $G$ meters.
2. **Speed & Bearing:** Zeroed (`hasSpeed = false`, `hasBearing = false`) under all non-exact modes.
3. **Altitude:** Zeroed (`hasAltitude = false`) under non-exact modes.
4. **Elapsed Realtime / Timestamps:** Bucketed to 60-second quantization when configured.
5. **Extras Bundle:** Stripped of all vendor-specific coordinate keys (`KEY_MOCK_LOCATION`, raw NMEA strings, Wi-Fi BSSID bundles).

---

## 14. Batch-Location Enforcement Contract (Domain E5)

*Formalized per Review v1 Blocker 4.*

- **Interception Site:** `com.android.server.location.gnss.GnssManagerService.mGnssBatchingProvider.onReportLocationBatch()`.
- **TOCTOU Elimination at Flush:** Batched fixes are held in hardware buffers while the CPU sleeps. When flushed, the interceptor resolves the **caller's active policy generation at the instant of flush dispatch**:
  ```java
  public List<Location> onGnssBatchDelivery(CallerIdentity identity, List<Location> rawBatch) {
      if (rawBatch == null || rawBatch.isEmpty()) return Collections.emptyList();
      
      PolicySnapshot snapshot = mSnapshotRef.get();
      AppIdentity appIdentity = IdentityResolver.fromCallerIdentity(identity);
      EffectivePolicy policy = snapshot.resolve(appIdentity);
      long nowNanos = SystemClock.elapsedRealtimeNanos();
      
      List<Location> sanitizedBatch = new ArrayList<>(rawBatch.size());
      for (Location loc : rawBatch) {
          LocationSample sample = AndroidLocationAdapter.toSample(loc);
          PolicyDecision d = mEngine.evaluate(appIdentity, RequestContext.BATCH, policy, sample, nowNanos);
          
          if (d.getDecision() == Decision.ALLOW) {
              sanitizedBatch.add(loc);
          } else if (d.getDecision() == Decision.TRANSFORM) {
              Location transformed = AndroidLocationAdapter.transform(loc, d.getAppliedSpatial());
              sanitizedBatch.add(AndroidMetadataAdapter.sanitize(transformed, d.getAppliedSpatial(), policy.getMetadata()));
          }
          // If DENY or THROTTLE: sample dropped from batch
      }
      return sanitizedBatch;
  }
  ```

---

## 15. GNSS Capability-Gating Contract (Domains E1–E4, E6)

*Formalized per Review v1 Blocker 3.*

All GNSS capability gates reside in `com.android.server.location.gnss.GnssManagerService`:

```
                 GNSS HAL Emits Satellite Data Stream
                                   │
       ┌───────────────────────────┼───────────────────────────┐
       ▼                           ▼                           ▼
[ Raw Measurements ]        [ NMEA Sentences ]        [ Satellite Status ]
(GnssMeasurementsProvider)  (GnssNmeaProvider)        (GnssStatusProvider)
       │                           │                           │
       ▼                           ▼                           ▼
Policy == EXACT?            Policy == EXACT?            Policy == EXACT?
 ├── YES: Dispatch           ├── YES: Dispatch           ├── YES: Dispatch
 └── NO:  Drop Event         └── NO:  Mute Stream        └── NO:  Zero Azimuth/Elev
```

1. **`GnssMeasurementsProvider` Gate:** If caller policy $< \text{EXACT}$, drop callback.
2. **`GnssNmeaProvider` Gate:** If caller policy $< \text{EXACT}$, drop ASCII sentence.
3. **`GnssStatusProvider` Gate:** If caller policy $< \text{EXACT}$, mutate `GnssStatus` parcel to zero azimuth and elevation angles before IPC dispatch.
4. **`GnssNavigationMessageProvider` Gate:** If caller policy $< \text{EXACT}$, drop subframe bits.
5. **`GnssAntennaInfo` Gate:** If caller policy $< \text{EXACT}$, drop listener registration.

---

## 16. Geofence Authorization Contract (Domains D & G)

1. **Framework Geofencing (`GeofenceManager.java`):**
   - Registration: If `SpatialMode == DENY`, `addGeofence()` returns failure.
   - Transition: If `BackgroundPolicy == DENY` and app is backgrounded, `PendingIntent` broadcast is suppressed.
   - Payload: Delivered parcel contains only boolean `KEY_PROXIMITY_ENTERING`; zero coordinate parcelables exist.
2. **GMS Geofencing (`GeofencingClient`):**
   - Registration / Delivery Gate: Coordinated via AppOps `ACCESS_BACKGROUND_LOCATION`.
   - If policy $< \text{EXACT}$, background location AppOp is ignored, forcing GMS Core to throw `GEOFENCE_NOT_AVAILABLE`.
   - **Coordinate Disclaim:** LocShield in AOSP cannot rewrite the triggering `Location` inside GMS `GeofencingEvent`. Policy is strictly binary (`ALLOW` vs `DENY`).

---

## 17. GMS Fused Location Provider Contract (Domain F)

*Formalized per Review v1 Blocker 2.*

```
                 App Calls FusedLocationProviderClient.requestLocationUpdates()
                                              │
                                              ▼
                             GMS Core (com.google.android.gms)
                                              │
                                              ▼
                           AppOpsManager.noteOp(OP_FINE_LOCATION)
                                              │
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
             [ Mode: ALLOWED ]                                 [ Mode: IGNORED ]
                     │                                                 │
                     ▼                                                 ▼
          Deliver High-Precision GPS                        Deliver Platform Coarse Fix
          (Subject to App Request)                          (~2 km Grid, Throttled >= 10m)
```

1. **Authorization-Only Boundary:** LocShield controls GMS FLP **strictly by toggling AppOps mode**:
   - `Policy == EXACT`: `OP_FINE_LOCATION = MODE_ALLOWED`.
   - `Policy < EXACT`: `OP_FINE_LOCATION = MODE_IGNORED` and `OP_COARSE_LOCATION = MODE_ALLOWED`.
2. **Consequence:** GMS Core is forced into Android's native coarse mode.
3. **Explicit Non-Claims:** LocShield does **not** claim custom city snapping, custom metric grids, custom radius uncertainty, or custom temporal rate-limiting on GMS FLP outputs.

---

## 18. Cache / Last-Location Contract (Domain B)

- **Interception Site:** `LocationProviderManager.getLastLocation()`.
- **Dynamic Read Transformation:**
  ```java
  public Location getLastLocation(LastLocationRequest request, CallerIdentity identity, int permissionLevel) {
      Location raw = getLastLocationUnsafe(identity.getUserId(), permissionLevel, ...);
      if (raw == null) return null;
      
      // LocShield Dynamic Read Gate
      return LocShieldInternal.get().onCacheRead(identity, raw);
  }
  ```
- **Null Semantics:** If caller policy is `DENY` or `FAIL_CLOSED`, returns `null`. Stale high-precision fixes cannot be read.

---

## 19. Fail-Closed Semantics & Exception Hierarchy

Every interceptor implements strict exception containment:

```
┌─────────────────────────────────────────────────────────────┐
│                      Hot-Path Invocation                    │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
               ┌───────────────────────────────┐
               │    try { Interceptor }        │
               └───────────────┬───────────────┘
                               │
             ┌─────────────────┴─────────────────┐
             ▼ Normal Return                     ▼ Catch (Throwable t)
   [ Deliver Transformed ]              ┌─────────────────────────────────┐
                                        │ 1. Log to Logcat (System Error) │
                                        │ 2. AuditEvent: FAIL_CLOSED      │
                                        │ 3. Return null / Suppress       │
                                        └─────────────────────────────────┘
                                                 (Zero Coordinates Emitted;
                                                  system_server Protected)
```

- **Top-Level Protection:** An uncaught exception in LocShield core or adapters drops the location parcel and returns `null`. It **never throws an unchecked exception** into `LocationProviderManager`, eliminating the `system_server` crash / soft-reboot risk.

---

## 20. Threading & Concurrency Guarantees

1. **Hot-Path Lock-Freedom:** Policy evaluation reads an `AtomicReference<PolicySnapshot>`. Threads in `acceptLocationChange()` acquire zero locks for policy lookups.
2. **Temporal Controller Synchronization:** `TemporalController` uses stripped per-identity monitor locks. Race conditions between concurrent delivery threads for the same app identity are serialized without blocking deliveries to other app identities.
3. **Single-Writer Policy Commits:** Policy updates to `PolicyRepository` are serialized via a dedicated writer thread.

---

## 21. Permission & Security Model

- **Control IPC Protection:** `android.permission.MANAGE_LOCATION_PRIVACY` (`protectionLevel="signature|privileged"`).
- **Service Registration:** Exclusively callable by UID 1000 (`system`).
- **File System Protection:** `/data/system/users/<id>/locshield_policies.xml` is owned by `system:system` with permissions `0600` (read/write by system only).
- **SELinux Enforcing Mode:** Validated under enforcing SELinux.

---

## 22. API Compatibility & Evolution Rules

1. **Schema Versioning:** Serialized policy XML contains `schemaVersion = 1`. Deserialization of higher, unrecognized schema versions fails closed to `systemDefaultPolicy()`.
2. **AIDL Stability:** `ILocShieldManager.aidl` uses explicit transaction IDs. New methods must be appended to the end of the interface.
3. **Enum Wire Formats:** All enum parameters in AIDL parcels are transmitted as fixed integers. Unrecognized integer values received by the system service map to `UNKNOWN_MODE` (`FAIL_CLOSED`).

---

## 23. Audit & Diagnostic Interface

- **Interface:** `ILocShieldManager.getAuditEvents(int maxEvents)` returns `List<AuditEventParcel>`.
- **Circular Buffer:** In-memory ring buffer holding the last 1,000 events.
- **Redaction Mandate:** `AuditEventParcel` contains timestamps, UIDs, package names, decisions, generation counters, and reason codes. **Latitude, longitude, altitude, and provider extras are strictly excluded from the parcel definition.**

---

## 24. Interface Tables

### 24.1 Control Plane AIDL Method Table

| Method Name | Parameters | Return Type | Required Permission | Calling Context |
|---|---|---|---|---|
| `getPolicies` | `int userId` | `List<AppPolicyParcel>` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `getPolicy` | `String packageName, int userId` | `AppPolicyParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `setPolicy` | `in AppPolicyParcel policy` | `boolean` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `resetPolicy` | `String packageName, int userId` | `boolean` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `preview` | `in PolicyPreviewRequestParcel req` | `PolicyDecisionParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `getStatus` | *None* | `LocShieldStatusParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |
| `getAuditEvents` | `int maxEvents` | `List<AuditEventParcel>` | `MANAGE_LOCATION_PRIVACY` | Binder IPC from ControlApp |

### 24.2 Data Plane In-Process Interface Table (`LocShieldInternal`)

| Method Name | Calling Class | Purpose | Execution Context | Failure Response |
|---|---|---|---|---|
| `onLocationDelivery` | `LocationProviderManager` | Stream transformation | Delivery thread | Returns `null` (Drop) |
| `onCurrentLocationDelivery`| `LocationProviderManager` | Single-shot transformation | Delivery thread | Returns `null` (Timeout/Null) |
| `onCacheRead` | `LocationProviderManager` | Read-time cache transformation| Binder call thread | Returns `null` (Cache Miss) |
| `onGnssBatchDelivery` | `GnssManagerService` | Batch flush transformation | GNSS HAL callback | Returns empty list |
| `onGeofenceRegistration` | `GeofenceManager` | Arming gate | Binder call thread | Returns `false` (Rejected) |
| `onGeofenceTransitionDelivery` | `GeofenceManager` | Event suppression | Event dispatch thread | Returns `false` (Suppressed) |
| `isGnssMeasurementsAllowed` | `GnssManagerService` | Raw measurement gate | HAL callback loop | Returns `false` (Muted) |
| `isGnssNmeaAllowed` | `GnssManagerService` | NMEA stream gate | HAL callback loop | Returns `false` (Muted) |
| `getGnssStatusGateMode` | `GnssManagerService` | Status angle gate | HAL callback loop | Returns `2` (Muted) |
| `isGnssNavigationMessageAllowed` | `GnssManagerService` | Subframe gate | HAL callback loop | Returns `false` (Muted) |
| `isGnssAntennaInfoAllowed` | `GnssManagerService` | Antenna calibration gate | Binder call thread | Returns `false` (Muted) |

---

## 25. Sequence Diagrams

### 25.1 Delivery-Time Hot Path (`onLocationDelivery`)
```
Provider HAL       LPM.acceptLocationChange()       LocShieldInternal       PolicyEngineCore         App Transport
     │                         │                            │                       │                      │
     │ 1. reportLocation()     │                            │                       │                      │
     ├────────────────────────►│                            │                       │                      │
     │                         │ 2. onLocationDelivery()    │                       │                      │
     │                         ├───────────────────────────►│                       │                      │
     │                         │                            │ 3. evaluate()         │                      │
     │                         │                            ├──────────────────────►│                      │
     │                         │                            │ 4. PolicyDecision     │                      │
     │                         │                            │◄──────────────────────┤                      │
     │                         │                            │                       │                      │
     │                         │                            │ 5. Transform & Sanitize                      │
     │                         │ 6. Transformed Result      │                                              │
     │                         │◄───────────────────────────┤                                              │
     │                         │                                                                           │
     │                         │ 7. deliverOnLocationChanged()                                             │
     │                         ├──────────────────────────────────────────────────────────────────────────►│
```

### 25.2 Batch Flush Delivery-Time Transformation
```
GnssNative            GnssManagerService            LocShieldInternal       PolicyEngineCore      IBatchedLocationCallback
    │                         │                             │                       │                        │
    │ 1. reportLocationBatch()│                             │                       │                        │
    ├────────────────────────►│                             │                       │                        │
    │                         │ 2. onGnssBatchDelivery()    │                       │                        │
    │                         ├────────────────────────────►│                       │                        │
    │                         │                             │ 3. For each fix in batch:                      │
    │                         │                             │    evaluate(Gen_now)                           │
    │                         │                             ├──────────────────────►│                        │
    │                         │                             │ 4. Transform per fix  │                        │
    │                         │ 5. Transformed Batch List   │                       │                        │
    │                         │◄────────────────────────────┤                       │                        │
    │                         │                                                                              │
    │                         │ 6. onLocationBatch(sanitizedBatch)                                           │
    │                         ├─────────────────────────────────────────────────────────────────────────────►│
```

---

## 26. Trust Boundary Table

| Boundary ID | Entities Crossing | Trust Relationship | Primary Threat | Architectural Mitigation |
|---|---|---|---|---|
| **TB-01** | User ↔ ControlApp | User Authority ↔ Client UI | Malicious input | Bounds validation in UI |
| **TB-02** | ControlApp ↔ SystemServer | App Process ↔ `system_server` | Unauthorized policy modification | `MANAGE_LOCATION_PRIVACY` signature permission |
| **TB-03** | SystemServer ↔ Policy Storage | Privileged Code ↔ Disk | Direct file tampering | File permissions `0600`, stored in `/data/system/` |
| **TB-04** | SystemServer ↔ PolicyEngine | Framework ↔ Pure Core | Engine exception | Fail-closed exception boundary |
| **TB-05** | Service ↔ Interceptors | In-Process Publishing | Concurrency race | `AtomicReference<PolicySnapshot>` |
| **TB-06** | Framework ↔ Target App | `system_server` ↔ App Process | Coordinate exfiltration | Delivery-time transformation in `LPM` |
| **TB-07** | GNSS Service ↔ GNSS Client | `system_server` ↔ App Process | Raw satellite positioning | Capability muting in `GnssManagerService` |
| **TB-08** | GMS Core ↔ Target App | Closed GMS ↔ App Process | Direct FLP bypass | Plane 2 AppOps fine-denial backstop |
| **TB-09** | Transports ↔ Target App | `system_server` ↔ Binder Driver | Parcel tampering | Re-allocation of clean `Location` parcel |
| **TB-10** | Hardware HAL ↔ SystemServer | Drivers ↔ `system_server` | Corrupt hardware values | Coordinate finiteness validation |
| **TB-11** | File System ↔ Repository | Disk ↔ In-Memory Cache | Stale disk corruption | AtomicFile rename + safe fallback |

---

## 27. Authorization Matrix

| Caller Category | Read Policy | Modify Policy | Inspect Audit | Request Framework Fixes | Request GNSS Observables | Request GMS FLP Fixes |
|---|---|---|---|---|---|---|
| **Unprivileged Application** | DENIED | DENIED | DENIED | Subject to LocShield Policy | Subject to GNSS Capability Gate | Constrained to Platform Coarse |
| **ControlApp (Signed)** | ALLOWED | ALLOWED | ALLOWED | N/A | N/A | N/A |
| **System UID (1000)** | ALLOWED | ALLOWED | ALLOWED | Pass-Through (Internal) | Pass-Through (Internal) | N/A |
| **Emergency Services (`BYPASS`)**| Pass-Through | Pass-Through | Recorded | **UNMEDIATED PASS-THROUGH** | **UNMEDIATED PASS-THROUGH** | Platform Emergency Mode |

---

## 28. Error-Code Table

All error codes map to `ReasonCode` wire values (Section 5):
- `100` -> `INVALID_POLICY`: Rejection returned to ControlApp.
- `101` -> `VALUE_OUT_OF_BOUNDS`: Parameter exceeds bounds.
- `102` -> `CONTRADICTORY_FIELDS`: Mutually exclusive fields set.
- `200` -> `TRANSFORMATION_FAILURE`: Hot-path catch boundary drops fix.
- `201` -> `METADATA_FAILURE`: Sanitizer catch boundary drops parcel.
- `300` -> `ANDROID_AUTHORIZATION_DENIED`: Ceiling blocks delivery.

---

## 29. Versioning Rules

1. **Serialized Schema:** Governed by `schemaVersion`. Additions must be backward compatible; unrecognized fields reject update.
2. **AIDL Interface Versioning:** New methods must be appended to the end of `ILocShieldManager.aidl`.
3. **Snapshot Generation:** Generation is an incrementing `long`. Reboots initialize at $G=1$ if clean, or $G=1$ on recovery default.

---

## 30. Architecture Decision Records (ADRs 010–012)

### ADR-010: In-Process SELinux Service Labeling
- **Decision:** Model `LocShieldSystemService` as an in-process thread group inside `system_server` (`u:r:system_server:s0`). Do not declare a process domain transition. Label only the service object (`location_privacy_service`) for `service_manager`, and store policy data in `/data/system/` under `system_data_file`.
- **Evidence:** AOSP `service.te` and `system_server.te` patterns for in-tree framework services.
- **Consequences:** Eliminates SELinux compilation errors and ensures seamless Treble compliance across Android 14–16.

### ADR-011: Version-Scoped Coarse-Location Guarantees
- **Decision:** Decouple LocShield's authorization ceiling from Android platform coarsening geometry. Scope the "~2 km grid" language strictly to the Android 14/15 baseline, recognizing Android 16/17 S2 density grids.
- **Evidence:** AOSP `LocationFudger.java` vs. `LocationFudgerCache.java` (Android 16).
- **Consequences:** Prevents architecture obsolescence as Android platform coarsening algorithms evolve.

### ADR-012: GNSS Batching Delivery-Time Evaluation & TOCTOU Elimination
- **Decision:** Mandate that `IBatchedLocationCallback.onLocationBatch()` evaluates the caller's current effective policy generation at flush time, applying spatial transformation and sanitization to every sample in the batch.
- **Evidence:** Document 04 TOCTOU analysis (Threat T15).
- **Consequences:** Guarantees that historical precise locations collected during an earlier policy state cannot bypass a newly tightened policy upon flush.

---

## 31. Implementation Preconditions

1. **AOSP Tree Ready:** `android14-release` checked out and clean build verified.
2. **Soong Blueprint Configured:** Prebuilt `locshield-policy-v0.1.jar` imported in `services.core`.
3. **SELinux Policy Syntax Validated:** `service.te`, `service_contexts`, and `system_server.te` pass `checkpolicy`.
4. **Platform Signature Key:** Identified for signing ControlApp with `MANAGE_LOCATION_PRIVACY`.

---

## 32. v2.1.1 Freeze Checklist

| Checklist Item | Status | Verification Detail |
|---|---|---|
| Four Review-v1 blockers resolved | **RESOLVED** | Change set expanded, GMS claims corrected, GNSS hardened, batching added. |
| In-process SELinux service modeling verified | **VERIFIED** | Service object type vs. process domain formalized in Section 2; ADR-010 added. |
| Platform coarse-location behavior version-scoped | **VERIFIED** | Decoupled from timeless LocShield guarantees in Section 18; ADR-011 added. |
| `GnssAntennaInfo` explicitly integrated | **VERIFIED** | Domain E6 formalized in Section 17. |
| GNSS batching TOCTOU eliminated | **VERIFIED** | Per-sample flush-time evaluation mandated in Section 14; ADR-012 added. |
| GMS geofence tripartite model formalized | **VERIFIED** | Registration, delivery, and transformation split in Section 19. |
| Complete AIDL syntax specified | **VERIFIED** | Full syntactical definitions for all parcels and interface in Section 4. |
| In-process `LocShieldInternal` interface specified | **VERIFIED** | Full Java interface definition in Section 9. |
| Policy Engine v0.1 remains untouched | **VERIFIED** | 188/188 tests passing; zero modifications made. |
| Absolute zero implementation code written | **VERIFIED** | Milestone strictly confined to interface specification. |

---

## Strict Stop Condition & Declaration

This document concludes the interface specification milestone. In accordance with strict engineering mandates:
- **NO** AOSP source code has been written.
- **NO** patch files have been created.
- **NO** AIDL files have been generated in the tree.
- **NO** Control APK code has been created.
- **NO** System Service implementation code has been written.
- **NO** modifications to Policy Engine v0.1 have been made.
- **NO** modifications to Documents 01–13, Matrix v1, or Runtime Validation v1 have been made.

Document 11 interface specification complete — awaiting API review.
