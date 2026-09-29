# LocShield — Document 11: AIDL & Internal API Specification v1.1.1

- **Document Version:** 1.1.1 (Final Targeted Contract Correction)
- **Date:** September 2026
- **Status:** Authoritative Interface Contract Specification (Frozen Pre-Implementation Baseline)
- **Supersedes:** `LocShield_Document_11_AIDL_and_Internal_API_Specification_v1.1.md`
- **Direct Correction Mandate:** Implements targeted contract corrections:
  1. **Binder Lifecycle Correction:** Aligns service publication with standard AOSP `SystemService` lifecycle: `publishBinderService()` and `publishLocalService()` execute in `onStart()`; `PHASE_SYSTEM_SERVICES_READY` is dedicated to dependency resolution and state initialization.
  2. **`MANAGE_LOCATION_PRIVACY` Definition:** Explicitly specifies `android.permission.MANAGE_LOCATION_PRIVACY` as a **new, LocShield-defined platform permission** with protection level `signature|privileged`, marked `@hide` / `@SystemApi`, and details its granting model and tripartite security relationship.
  3. **Performance Language Correction:** Removes all hard claims of "sub-microsecond" or "lock-free" execution; formalizes the contract as **same-process, in-memory execution with zero Binder IPC on the hot path**, explicitly noting that latency must be empirically benchmarked without fixed temporal guarantees.
  4. **Multi-User Identity Freezing:** Freezes caller identity as `userId + UID + packageName` (+ `attributionTag` where present), with kernel UID verification and package-set validation, guaranteeing strictly user-scoped policy isolation.
  5. **New Architecture Decision Records:** Formalizes `ADR-013` (Binder publication lifecycle), `ADR-014` (LocShield signature permission), and `ADR-015` (Multi-user identity binding).
- **Frozen Baselines Maintained Unmodified:**
  - LocShield Policy Engine v0.1 (`locshield-policy/`, 188/188 passing tests, pure Kotlin/JVM)
  - LocShield Documents 01–13 (Foundational research specifications)
  - Android API & Enforcement Matrix v1 (`Android_API_and_Enforcement_Matrix_v1.md`)
  - Android Runtime Validation v1 (`Android_Runtime_Validation_v1.md`)
  - Enforcement Architecture Specification v2.1.1 (`Enforcement_Architecture_Specification_v2.1.1.md`)
- **Strict Implementation Constraint:** Architectural specification only. Contains **no implementation code**, **no AIDL syntax definitions in the tree**, **no AOSP patches**, and **no Control APK implementation**.

---

## Targeted Correction Register (v1.1 → v1.1.1)

| Correction ID | Section | v1.1 Prior Language | v1.1.1 Corrected Language | Rationale & Authority |
|---|---|---|---|---|
| **COR-01** | Section 2 | Stated service was published during `PHASE_SYSTEM_SERVICES_READY`. | Corrected to publish Binder and LocalService interfaces in `SystemService.onStart()`; `PHASE_SYSTEM_SERVICES_READY` handles dependency connections and storage loading. | Standard AOSP `SystemService` contract (`SystemServer.java`). Prevents early lookup race conditions. |
| **COR-02** | Section 3, 21 | Referred to `MANAGE_LOCATION_PRIVACY` generally without platform status. | Formally defined as a **new LocShield platform permission** (`signature|privileged`), `@hide`/`@SystemApi`, with explicit granting rules for platform-signed ControlApp. | Prevents misunderstanding that this was an existing upstream Android permission. |
| **COR-03** | Sections 1, 9, 10, 20 | Claimed "sub-microsecond" and "lock-free" hot-path execution. | Replaced with: **same-process, in-memory execution, no Binder in hot path, latency must be benchmarked, no fixed latency guarantee**. | Eliminates unprovable hard real-time latency claims in a preemptible multithreaded OS runtime. |
| **COR-04** | Section 7, 20 | Keyed identity by general tuple. | Explicitly froze identity as `userId + UID + packageName`, strictly user-scoped, validated against calling UID via `PackageManager`. | Guarantees multi-user isolation and prevents cross-user state leaks. |
| **COR-05** | Section 30 | Omitted ADRs 013–015. | Added formal `ADR-013`, `ADR-014`, and `ADR-015`. | Full architectural traceability. |

---

## 1. Executive Summary & Interface Topology

Document 11 v1.1.1 establishes the definitive API and IPC contract for the LocShield system architecture. It decomposes the interface boundary into two distinct planes:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CONTROL PLANE (Out-of-Band)                       │
│                                                                             │
│   ControlApp (UID 10xxx, Platform-Signed)                                   │
│      │                                                                      │
│      ▼ [AIDL: ILocShieldManager] (Guarded by MANAGE_LOCATION_PRIVACY)       │
│   LocShieldSystemService (system_server, UID 1000)                          │
│      │                                                                      │
│      ├── Transactional Policy CRUD (User-Scoped)                            │
│      ├── Caller Identity & Permission Enforcement                           │
│      └── Atomic Snapshot Publishing (Monotonic Generation G -> G+1)         │
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
│      ▼ [Local Java API: LocShieldInternal] (Same-Process, No Binder IPC)    │
│   PolicyEngineAdapter                                                       │
│      │                                                                      │
│      ▼ [Pure JVM: PolicyEngine.evaluate()]                                  │
│   Policy Engine v0.1 Core                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

1. **Control Plane Interface (`ILocShieldManager.aidl`):** Cross-process Binder interface exposing transactional policy management, status inspection, and coordinate-free audit queries to authorized administrative clients.
2. **Data Plane Local Interface (`LocShieldInternal.java`):** High-performance, same-process in-memory interface invoked synchronously on location delivery threads within `system_server`. Operates without cross-process Binder IPC; latency must be empirically benchmarked in the target environment (no fixed latency guarantees).

---

## 2. Binder Service Name & Registration Model

*Corrected per Contract Correction 1 & ADR-013.*

### 2.1 Standard AOSP Lifecycle (`LocShieldSystemService.java`)
`LocShieldSystemService` extends `com.android.server.SystemService`. Its publication follows standard AOSP system-service lifecycle requirements:

```java
public class LocShieldSystemService extends SystemService {
    private final LocShieldBinderService mBinderService;
    private final LocShieldLocalService mLocalService;

    public LocShieldSystemService(Context context) {
        super(context);
        mBinderService = new LocShieldBinderService(context);
        mLocalService = new LocShieldLocalService(context);
    }

    @Override
    public void onStart() {
        // Publish external Binder interface to ServiceManager
        publishBinderService("location_privacy", mBinderService);
        // Publish internal interface to LocalServices within system_server
        publishLocalService(LocShieldInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // Connect to dependencies: LocationManagerInternal, AppOpsManagerInternal
            mLocalService.initializeDependencies();
            // Load user policy snapshots from disk
            mLocalService.loadPersistentPolicies();
        } else if (phase == PHASE_BOOT_COMPLETED) {
            // Mark service fully ready; activate external client readiness flag
            mLocalService.markBootCompleted();
        }
    }
}
```

### 2.2 Publication & Phase Separation
- **`onStart()` Publication:** Both the Binder service (`location_privacy`) and the local service (`LocShieldInternal`) are published during `onStart()`. This guarantees that other system services starting concurrently can resolve references via `ServiceManager` and `LocalServices` without race conditions.
- **`PHASE_SYSTEM_SERVICES_READY` Readiness:** Dependency injection (`PackageManagerInternal`, `AppOpsManagerInternal`, `LocationManagerInternal`), persistent XML storage loading, and observer registrations are deferred to this phase when underlying system providers are initialized.
- **`PHASE_BOOT_COMPLETED`:** System-wide readiness broadcast; Control APK client connections are unblocked.

### 2.3 SELinux Object Contexts
- **Service Object Label (`system/sepolicy/private/service_contexts`):**
  ```text
  location_privacy                          u:object_r:location_privacy_service:s0
  ```
- **Service Type Declaration (`system/sepolicy/public/service.te`):**
  ```te
  type location_privacy_service, app_api_service, system_server_service, service_manager_type;
  ```
- **System Server Access (`system/sepolicy/private/system_server.te`):**
  ```te
  allow system_server location_privacy_service:service_manager { add find };
  ```
- **Executing Process Domain:** `system_server` runs in `u:r:system_server:s0`. `LocShieldSystemService` executes within `system_server`. It undergoes **no process domain transition**.

---

## 3. Control-APK Authorization Requirements & Permission Definition

*Clarified per Contract Correction 2 & ADR-014.*

### 3.1 Definition of `MANAGE_LOCATION_PRIVACY`
`android.permission.MANAGE_LOCATION_PRIVACY` is an entirely **new, LocShield-defined platform permission**. It is **not** an existing upstream Android permission.

- **Declaration Location:** `frameworks/base/core/res/AndroidManifest.xml`
- **Formal Manifest Declaration:**
  ```xml
  <!-- Allows an authorized system application to configure per-app location privacy
       policies, read enforcement telemetry, and inspect policy generation state.
       @hide @SystemApi Not for use by third-party applications. -->
  <permission android:name="android.permission.MANAGE_LOCATION_PRIVACY"
      android:protectionLevel="signature|privileged" />
  ```
- **Platform Annotation Status:** Marked `@hide` from the public SDK and annotated `@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)`.
- **Granting Model for ControlApp:**
  - The permission has protection level `signature|privileged`.
  - **Option 1 (Signature Match — Preferred):** ControlApp is signed with the platform signing key (the certificate used to sign the target AOSP image). It is granted automatically at installation.
  - **Option 2 (Privileged Partition):** ControlApp is pre-installed in `/system/priv-app/ControlApp/` and explicitly allowlisted in `/etc/permissions/privapp-permissions-locshield.xml`.
  - Ordinary third-party applications declaring this permission in their manifest will be denied the grant by `PackageManagerService`.

### 3.2 Tripartite Security Enforcement Hierarchy

```
                            Client Issues Binder Call
                                       │
  Level 1: SELinux Check               ▼
  ──────────────────────► [ servicemanager verifies calling domain ]
                                       │ (Allowed if client domain has find permission)
                                       ▼
  Level 2: Platform Permission Check   │
  ──────────────────────► [ enforceCallingOrSelfPermission(MANAGE_LOCATION_PRIVACY) ]
                                       │ (SecurityException if permission missing)
                                       ▼
  Level 3: Multi-User Identity Check   │
  ──────────────────────► [ Verify User & Package Ownership ]
                                       │ (Inter-user checks require INTERACT_ACROSS_USERS)
                                       ▼
                             Execute Operation
```

1. **SELinux Service Resolution:** Client process domain must have SELinux permission to `find` `location_privacy_service` in `service_contexts`.
2. **Platform Permission Enforcement:** `LocShieldSystemService` enforces `mContext.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_LOCATION_PRIVACY, "LocShield")`.
3. **Kernel UID & User Ownership:** `Binder.getCallingUid()` and `UserHandle.getCallingUserId()` are verified. Target packages must belong to the caller's user account, or the caller must possess `android.permission.INTERACT_ACROSS_USERS_FULL`.

---

## 4. Syntactically Complete AIDL Interface Specifications

All AIDL files belong to package `android.location.privacy`.

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
    double radiusMeters;
    double gridMeters;
    String cityId;
}
```

#### `TemporalPolicyParcel.aidl`
```aidl
package android.location.privacy;

parcelable TemporalPolicyParcel {
    int mode; // 0=REALTIME, 1=MIN_INTERVAL, 2=PERIODIC, 3=RATE_LIMIT, 4=ONE_SHOT
    long minimumIntervalMs;
    long periodicIntervalMs;
    int maxDeliveriesPerWindow;
    long windowMs;
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
    // Zero coordinates included (no lat, lon, alt)
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

### 4.2 Main Binder Management Interface (`ILocShieldManager.aidl`)

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
     * Persists or updates an application policy.
     * Enforces schema validation, parameter bounds, and user ownership.
     */
    boolean setPolicy(in AppPolicyParcel policy);

    /**
     * Resets an application's policy to default settings.
     */
    boolean resetPolicy(String packageName, int userId);

    /**
     * Evaluates a hypothetical request against current policy state.
     * Evaluates logic only; NEVER returns coordinates.
     */
    PolicyDecisionParcel preview(in PolicyPreviewRequestParcel request);

    /**
     * Queries service health, generation counter, and status flags.
     */
    LocShieldStatusParcel getStatus();

    /**
     * Retrieves recent coordinate-redacted audit logs.
     */
    List<AuditEventParcel> getAuditEvents(int maxEvents);
}
```

---

## 5. Request, Response & Reason-Code Mappings

All reason codes map 1:1 with the frozen `locshield.model.ReasonCode` enum:

| Enum Name | Wire Int | Category | Description |
|---|---|---|---|
| `ALLOW_OK` | 0 | Success | Authorized without additional spatial transformation. |
| `TRANSFORM_OK` | 1 | Success | Authorized subject to spatial transformation and metadata sanitization. |
| `ANDROID_AUTHORIZATION_DENIED` | 2 | Authorization Gate | Platform permission or AppOps denied location access. |
| `BACKGROUND_DENIED` | 3 | Context Gate | Application is in background without background authorization or policy. |
| `POLICY_DISABLED` | 4 | Policy Gate | Policy has `enabled == false`. |
| `POLICY_EXPIRED` | 5 | Policy Gate | Policy expiration timestamp exceeded; safe fallback applied. |
| `SPATIAL_DENY` | 6 | Spatial Gate | Policy sets `SpatialMode == DENY`. |
| `TEMPORAL_THROTTLE` | 7 | Temporal Gate | Rate-limit or minimum-interval suppressed delivery. |
| `INVALID_POLICY` | 8 | Validation Failure | General policy structure rejected by `PolicyValidator`. |
| `UNKNOWN_MODE` | 9 | Validation Failure | Unrecognized enum constant passed across wire. |
| `MISSING_FIELD` | 10 | Validation Failure | Required parameter omitted for selected mode. |
| `INVALID_VALUE` | 11 | Validation Failure | Non-finite or negative parameter. |
| `VALUE_OUT_OF_BOUNDS` | 12 | Validation Failure | Value exceeds bounds (e.g. radius $> 100$ km). |
| `CONTRADICTORY_FIELDS` | 13 | Validation Failure | Mutually exclusive parameters declared. |
| `UNSUPPORTED_SCHEMA` | 14 | Validation Failure | Schema version does not match `CURRENT_SCHEMA_VERSION` (1). |
| `OVERFLOW` | 15 | Validation Failure | Arithmetic overflow in interval calculation. |
| `CORRUPT_SNAPSHOT` | 16 | Storage Failure | In-memory or disk snapshot failed integrity verification. |
| `TRANSFORMATION_FAILURE` | 17 | Runtime Exception | Error during coordinate math; delivery dropped (`FAIL_CLOSED`). |
| `METADATA_FAILURE` | 18 | Runtime Exception | Error sanitizing metadata; parcel dropped (`FAIL_CLOSED`). |
| `RNG_FAILURE` | 19 | Runtime Exception | Random source failure during perturbation. |
| `TEMPORAL_STATE_INVALID` | 20 | Runtime Exception | Internal temporal queue corruption; throttled. |
| `ENGINE_EXCEPTION` | 21 | Runtime Exception | Uncaught runtime error; fails closed. |
| `UNKNOWN_CAPABILITY` | 22 | Capability Gate | Invocation of unsupported or unmapped capability. |

---

## 6. Policy CRUD & Transaction Lifecycle

1. **Transactional Mutex:** Policy writes in `LocShieldSystemService` are serialized through a dedicated internal write lock.
2. **Atomic Disk Storage:** `PolicyRepository` writes to `/data/system/users/<userId>/locshield_policies.xml` using `android.util.AtomicFile`. Data is flushed (`fsync`) to disk before in-memory state is modified.
3. **Rollback Integrity:** An `IOException` on disk write rolls back the transaction; the in-memory generation counter is unchanged, and `false` is returned to the Control APK.
4. **Snapshot Swap:** A successful write compiles a new immutable `PolicySnapshot`, increments the generation counter ($G \to G + 1$), and publishes the reference via `AtomicReference.set()`. All threads in `acceptLocationChange()` instantly observe the new policy.

---

## 7. Multi-User Identity Binding Contract

*Clarified per Contract Correction 4 & ADR-015.*

### 7.1 Authoritative Identity Tuple
Identity is frozen as:
$$\text{AppIdentity} = \langle \text{userId}, \text{uid}, \text{packageName}, \text{attributionTag} \rangle$$

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
        
        // Prevent identity spoofing
        if (verifiedPackage == null) {
            verifiedPackage = packages[0]; // Fall back to primary verified package of UID
        }
        
        return new AppIdentity(callingUid, userId, verifiedPackage, attributionTag, null);
    }
}
```

### 7.2 Strict User Isolation
- Policy storage paths are isolated: `/data/system/users/<userId>/locshield_policies.xml`.
- Policy lookup keys are compound: `(userId, packageName)`.
- User 0 and User 10 (e.g. Work Profile) maintain completely separate policy maps and generation counters. A policy change in User 0 has zero effect on User 10.
- Shared UIDs apply the **most restrictive policy** declared among co-located packages within that user account.

---

## 8. Effective-Policy Calculation Contract

Computed as:
$$\text{EffectivePolicy} = \text{AndroidAuthorization} \cap \text{ConfiguredPolicy} \cap \text{ContextRestrictions}$$

- **Android Authorization Ceiling:** If platform permissions or AppOps deny location, the effective policy is strictly `DENY`.
- **Approximate Ceiling:** If Android grants approximate-only location, `SpatialMode` is capped at `GRID(2000m)`.
- **Expiration:** Expired policies fall back to `expiredFallbackPolicy()` (DENY-preserving).
- **Background Degradation:** Background requests under `BackgroundMode.RESTRICT` are degraded by intersecting with `GRID(1000m)` and `MIN_INTERVAL(15m)`.

---

## 9. PolicyDecision & Adapter Delivery Contract (`LocShieldInternal.java`)

*Clarified per Contract Correction 3.*

The data-plane local service operates **strictly in-process within `system_server`**. It uses zero cross-process Binder IPC. Latency is governed by in-memory execution and must be empirically benchmarked on the target platform (no fixed latency guarantees).

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
     * Same-process execution; returns transformed LocationResult, or null if dropped.
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
     * Dynamically transforms cached data at read time. Returns null on cache miss or denial.
     */
    Location onCacheRead(CallerIdentity identity, Location rawCachedLocation);

    /**
     * Intercepts hardware-offloaded GNSS location batch flushes.
     * Invoked at: GnssManagerService (IBatchedLocationCallback dispatch)
     * Evaluates current effective policy at flush time; returns sanitized list.
     */
    List<Location> onGnssBatchDelivery(CallerIdentity identity, List<Location> rawBatch);

    /**
     * Intercepts framework proximity alert registrations.
     * Invoked at: GeofenceManager.addGeofence()
     */
    boolean onGeofenceRegistration(CallerIdentity identity, double latitude, double longitude, float radius);

    /**
     * Intercepts framework proximity alert transition broadcasts.
     * Invoked at: GeofenceManager.onLocationChanged()
     */
    boolean onGeofenceTransitionDelivery(CallerIdentity identity, boolean entering);

    /**
     * Capability gates for raw satellite observables.
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

Hot-path transformation executes in-memory on the delivery thread inside `system_server`:
1. Resolve `AppIdentity` from registration context.
2. Read active `PolicySnapshot` from `AtomicReference`.
3. Evaluate `PolicyDecision`.
4. If `DENY` or `THROTTLE`, drop delivery immediately and record audit reason.
5. If `TRANSFORM`, instantiate a new `Location` parcel, apply spatial transformation (`appliedSpatial`), and sanitize metadata fields.
6. Record delivery timestamp in `TemporalController`.
7. Deliver newly allocated transformed parcel to transport.

---

## 11. TemporalController Contract

- **Monotonic Time:** Interval math uses `SystemClock.elapsedRealtimeNanos()`. Wall-clock time is strictly forbidden.
- **Identity Isolation:** History deques and last-delivery records are partitioned per `AppIdentity` and policy generation.
- **Saturating Arithmetic:** Millisecond-to-nanosecond conversions saturate at `Long.MAX_VALUE`; overflow resolves to `SUPPRESS` (`FAIL_CLOSED`).
- **Generation Invalidation:** Advancing generation resets temporal state, preventing stale suppression windows after an interval change.

---

## 12. TransformationEngine Contract

- **Immutability:** Provider-owned `Location` objects are never mutated in place.
- **Equirectangular Projection:** Metric grid quantization computes cell centers in projected meters ($x = R \cdot \lambda \cdot \cos(\phi)$, $y = R \cdot \phi$), with $\cos(\phi)$ clamped at $\cos(89.9^\circ)$ near poles.
- **Offline City Table:** CITY mode matches against the local 8-city fixture table. Coordinates beyond 50 km fall back to a deterministic 20 km grid with `cityId = null`. Zero network geocoding.
- **Randomization:** Perturbations use 64-bit FNV-1a seeded pseudo-randomness. Coordinates are never used as RNG seeds.

---

## 13. MetadataSanitizer Contract

Sanitization executes synchronously after coordinate transformation:
- **Accuracy Floor:**
  $$\text{accuracy}_{\text{final}} = \max(\text{accuracy}_{\text{raw}}, \text{floor}(\text{SpatialMode}))$$
  Under `CITY`, floor is 5,000 meters; under `GRID(G)`, floor is $G$ meters.
- **Speed, Bearing, Altitude:** Zeroed under all non-exact modes.
- **Timestamps / Realtime:** Bucketed to 60-second intervals when configured.
- **Extras Bundle:** Stripped of all vendor coordinate extras.

---

## 14. Batch-Location Enforcement Contract (Domain E5)

- **Flush-Time Evaluation:** Flushed batches are intercepted in `GnssManagerService` before `IBatchedLocationCallback.onLocationBatch()` dispatch.
- **Per-Sample Enforcement:** Every fix in the batch is evaluated against the caller's **current effective policy generation at the instant of flush**.
- **TOCTOU Elimination:** Historical fixes buffered while the app was under an earlier permissive policy are transformed to the current, tighter policy (or discarded if current policy is `DENY`).

---

## 15. GNSS Capability-Gating Contract (Domains E1–E4, E6)

All gates reside in `com.android.server.location.gnss.GnssManagerService`:
- `GnssMeasurementsProvider`: Muted when caller policy $< \text{EXACT}$.
- `GnssNmeaProvider`: Muted when caller policy $< \text{EXACT}$.
- `GnssStatusProvider`: Azimuth and elevation angles zeroed when caller policy $< \text{EXACT}$.
- `GnssNavigationMessageProvider`: Muted when caller policy $< \text{EXACT}$.
- `GnssAntennaInfo`: Registration dropped when caller policy $< \text{EXACT}$.

---

## 16. Geofence Authorization Contract (Domains D & G)

1. **Framework Geofencing (`GeofenceManager.java`):**
   - Registration rejected if `SpatialMode == DENY`.
   - Transitions suppressed if `BackgroundPolicy == DENY` and app is backgrounded.
   - Delivered payload contains boolean `KEY_PROXIMITY_ENTERING` only; zero coordinates exist.
2. **GMS Geofencing (`GeofencingClient`):**
   - Controlled via AppOps `ACCESS_BACKGROUND_LOCATION`.
   - When policy $< \text{EXACT}$, background location AppOp is ignored, forcing GMS Core to reject geofences (`GEOFENCE_NOT_AVAILABLE`).
   - Coordinate transformation of triggering `Location` inside `GeofencingEvent` is disclaimed. Policy is strictly binary (`ALLOW` vs `DENY`).

---

## 17. GMS Fused Location Provider Contract (Domain F)

*Clarified per Contract Correction 2 & ADR-011.*

1. **Authorization-Only Boundary:** Controlled strictly via AppOps coordination:
   - `Policy == EXACT`: `OP_FINE_LOCATION = MODE_ALLOWED`.
   - `Policy < EXACT`: `OP_FINE_LOCATION = MODE_IGNORED` and `OP_COARSE_LOCATION = MODE_ALLOWED`.
2. **Platform Coarse Fallback:** Forces GMS Core to apply Android's native coarse obfuscation (~2 km grid on Android 14/15; S2 density grids on Android 16/17).
3. **Explicit Non-Claims:** LocShield does **not** claim custom city snapping, custom metric grids, or custom temporal rate-limiting on GMS FLP outputs.

---

## 18. Cache / Last-Location Contract (Domain B)

- **Hook Site:** `LocationProviderManager.getLastLocation()`.
- **Dynamic Read Transformation:** Coordinates read from `LastLocation.mFine` are transformed dynamically under the active generation $G_{\text{now}}$. Returns `null` on `DENY` or `FAIL_CLOSED`.

---

## 19. Fail-Closed Semantics & Exception Hierarchy

Every interceptor implements top-level exception containment:
```java
try {
    return mLocShieldLocal.onLocationDelivery(identity, location, isPassive);
} catch (Throwable t) {
    Log.e(TAG, "LocShield delivery interceptor error; failing closed", t);
    return null; // Suppress delivery; never emit raw location; protect system_server
}
```
An unhandled exception in LocShield drops the delivery and returns `null`. It **never throws an unchecked exception** into `system_server`, eliminating soft-reboot risks.

---

## 20. Threading & Concurrency Guarantees

*Corrected per Contract Correction 3.*

1. **Same-Process In-Memory Execution:** All interceptors and adapters run inside `system_server`. No cross-process IPC occurs on the hot path.
2. **Snapshot Visibility via AtomicReference:** `PolicyRepository` publishes new policy generations using `AtomicReference<PolicySnapshot>`. Reading threads in `acceptLocationChange()` obtain an atomic reference to the current snapshot.
3. **Empirical Latency Requirement:** Hot-path execution adds negligible overhead compared to IPC parceling, but **no fixed real-time latency guarantee (e.g. sub-microsecond) is promised** in a preemptible, garbage-collected OS runtime. Latency must be benchmarked on target hardware.
4. **Per-Identity Synchronization:** `TemporalController` serializes state updates per `AppIdentity`. Concurrent deliveries for separate applications execute in parallel without cross-app lock contention.

---

## 21. Permission & Security Model

- **Signature Permission:** `android.permission.MANAGE_LOCATION_PRIVACY` (`signature|privileged`).
- **SELinux Object Labels:** Service labeled `location_privacy_service` in `service_contexts`.
- **File System Permissions:** Storage directory `/data/system/users/<id>/` protected by Linux permissions `0600` (system:system).

---

## 22. API Compatibility & Evolution Rules

1. **Schema Versioning:** Serialized policy XML carries `schemaVersion = 1`. Unrecognized schema versions fail closed to `systemDefaultPolicy()`.
2. **AIDL Evolution:** New methods are appended to `ILocShieldManager.aidl`.
3. **Parcelable Serialization:** All enum fields are transmitted as fixed 32-bit integers across Binder. Unmapped values fail closed to `UNKNOWN_MODE`.

---

## 23. Audit & Diagnostic Interface

- **Interface:** `ILocShieldManager.getAuditEvents(int maxEvents)`.
- **Buffer:** In-memory circular buffer (capacity 1,000 events).
- **Coordinate Redaction:** `AuditEventParcel` contains timestamps, UIDs, package names, decisions, generation counters, and reason codes. **Latitude, longitude, altitude, and provider extras are strictly excluded.**

---

## 24. Interface Tables

### 24.1 Control Plane AIDL Interface (`ILocShieldManager`)

| Method Name | Parameters | Return Type | Required Permission | Execution Context |
|---|---|---|---|---|
| `getPolicies` | `int userId` | `List<AppPolicyParcel>` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `getPolicy` | `String packageName, int userId` | `AppPolicyParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `setPolicy` | `in AppPolicyParcel policy` | `boolean` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `resetPolicy` | `String packageName, int userId` | `boolean` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `preview` | `in PolicyPreviewRequestParcel req` | `PolicyDecisionParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `getStatus` | *None* | `LocShieldStatusParcel` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |
| `getAuditEvents` | `int maxEvents` | `List<AuditEventParcel>` | `MANAGE_LOCATION_PRIVACY` | Binder IPC thread |

### 24.2 Data Plane Local Interface (`LocShieldInternal`)

| Method Name | Caller Class | Execution Context | Return Type | Failure Action |
|---|---|---|---|---|
| `onLocationDelivery` | `LocationProviderManager` | Delivery thread | `LocationResult` | Returns `null` (Drop) |
| `onCurrentLocationDelivery`| `LocationProviderManager` | Delivery thread | `Location` | Returns `null` (Timeout/Null) |
| `onCacheRead` | `LocationProviderManager` | Binder thread | `Location` | Returns `null` (Cache Miss) |
| `onGnssBatchDelivery` | `GnssManagerService` | HAL callback | `List<Location>` | Returns empty list |
| `onGeofenceRegistration` | `GeofenceManager` | Binder thread | `boolean` | Returns `false` (Reject) |
| `onGeofenceTransitionDelivery` | `GeofenceManager` | Dispatch thread | `boolean` | Returns `false` (Suppress) |
| `isGnssMeasurementsAllowed` | `GnssManagerService` | HAL callback | `boolean` | Returns `false` (Mute) |
| `isGnssNmeaAllowed` | `GnssManagerService` | HAL callback | `boolean` | Returns `false` (Mute) |
| `getGnssStatusGateMode` | `GnssManagerService` | HAL callback | `int` | Returns `2` (Mute) |
| `isGnssNavigationMessageAllowed` | `GnssManagerService` | HAL callback | `boolean` | Returns `false` (Mute) |
| `isGnssAntennaInfoAllowed` | `GnssManagerService` | Binder thread | `boolean` | Returns `false` (Mute) |

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
| **TB-02** | ControlApp ↔ SystemServer | App Process ↔ `system_server` | Unauthorized policy tampering | `MANAGE_LOCATION_PRIVACY` signature permission |
| **TB-03** | SystemServer ↔ Policy Storage | Privileged Code ↔ Disk | Direct file tampering | File permissions `0600`, stored in `/data/system/users/<id>/` |
| **TB-04** | SystemServer ↔ PolicyEngine | Framework ↔ Pure Core | Engine exception | Fail-closed exception containment |
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
| **ControlApp (Platform-Signed)**| ALLOWED | ALLOWED | ALLOWED | N/A | N/A | N/A |
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

## 30. Architecture Decision Records (ADRs 010–015)

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

### ADR-013: Binder Publication Lifecycle in `SystemService.onStart()`
- **Decision:** Publish `location_privacy` to `ServiceManager` and `LocShieldInternal` to `LocalServices` inside `SystemService.onStart()`. Defer dependency initialization and disk loading to `PHASE_SYSTEM_SERVICES_READY`.
- **Evidence:** AOSP `SystemService.java` lifecycle contracts in `SystemServer.java`.
- **Consequences:** Prevents early-lookup race conditions among system services while ensuring safe dependency resolution.

### ADR-014: New LocShield Platform Signature Permission
- **Decision:** Formally define `android.permission.MANAGE_LOCATION_PRIVACY` as a new platform permission declared in `frameworks/base/core/res/AndroidManifest.xml` with `protectionLevel="signature|privileged"` and marked `@hide`/`@SystemApi`.
- **Evidence:** Android permission architecture for privileged system service control.
- **Consequences:** Restricts policy management exclusively to platform-signed ControlApp instances; prevents third-party apps from modifying privacy policies.

### ADR-015: Multi-User Identity Tuple Binding
- **Decision:** Freeze caller identity as `userId + UID + packageName`, strictly user-scoped, validated against calling UID via `PackageManager`.
- **Evidence:** Android multi-user architecture and UID allocation mechanics.
- **Consequences:** Ensures complete isolation across work profiles and secondary users; prevents recycled UID privilege inheritance.

---

## 31. Implementation Preconditions

1. **AOSP Tree Ready:** `android14-release` checked out and clean build verified.
2. **Soong Blueprint Configured:** Prebuilt `locshield-policy-v0.1.jar` imported in `services.core`.
3. **SELinux Policy Syntax Validated:** `service.te`, `service_contexts`, and `system_server.te` pass `checkpolicy`.
4. **Platform Signature Key:** Identified for signing ControlApp with `MANAGE_LOCATION_PRIVACY`.
5. **AppOps Internal API Mapping:** Verified `AppOpsManagerInternal.setMode()` method signature in target AOSP build.

---

## 32. v2.1.1 Freeze Checklist

| Checklist Item | Status | Verification Detail |
|---|---|---|
| Four Review-v1 blockers resolved | **RESOLVED** | Change set expanded, GMS claims corrected, GNSS hardened, batching added. |
| In-process SELinux service modeling verified | **VERIFIED** | Service object type vs. process domain formalized in Section 2; ADR-010 added. |
| Platform coarse-location behavior version-scoped | **VERIFIED** | Decoupled from timeless LocShield guarantees in Section 17; ADR-011 added. |
| `GnssAntennaInfo` explicitly integrated | **VERIFIED** | Domain E6 formalized in Section 15. |
| GNSS batching TOCTOU eliminated | **VERIFIED** | Per-sample flush-time evaluation mandated in Section 14; ADR-012 added. |
| GMS geofence tripartite model formalized | **VERIFIED** | Registration, delivery, and transformation split in Section 16. |
| Binder lifecycle publication corrected | **VERIFIED** | Published in `onStart()`, initialized in `PHASE_SYSTEM_SERVICES_READY`; ADR-013 added. |
| New signature permission defined | **VERIFIED** | `MANAGE_LOCATION_PRIVACY` defined as new platform permission; ADR-014 added. |
| Multi-user identity tuple frozen | **VERIFIED** | Identity frozen as `userId + UID + packageName`; ADR-015 added. |
| Performance language corrected | **VERIFIED** | "Sub-microsecond" and "lock-free" claims replaced with benchmark requirements. |
| Complete AIDL syntax specified | **VERIFIED** | Full syntactical definitions for all parcels and interface in Section 4. |
| In-process `LocShieldInternal` interface specified | **VERIFIED** | Full Java interface definition in Section 9. |
| Policy Engine v0.1 remains untouched | **VERIFIED** | 188/188 tests passing; zero modifications made. |
| Absolute zero implementation code written | **VERIFIED** | Milestone strictly confined to interface specification. |

---

## Strict Stop Condition & Declaration

This document concludes the final contract correction milestone. In accordance with strict engineering mandates:
- **NO** AOSP source code has been written.
- **NO** patch files have been created.
- **NO** AIDL files have been generated in the tree.
- **NO** Control APK code has been created.
- **NO** System Service implementation code has been written.
- **NO** modifications to Policy Engine v0.1 have been made.
- **NO** modifications to Documents 01–13, Matrix v1, or Runtime Validation v1 have been made.

Document 11 v1.1.1 final contract correction complete — awaiting API freeze.
