# LocShield — AOSP Pre-Implementation Plan v1

- **Document Version:** 1.0 (Pre-Implementation Execution Blueprint)
- **Target OS Baseline:** Android 14 (API level 34, `android-14.0.0_r74` / `android14-release`)
- **Date:** September 2026
- **Status:** Approved Pre-Implementation Technical Blueprint
- **Direct Authority:**
  - `LOCSHIELD/Enforcement_Architecture_Specification_v2.1.1.md`
  - `LOCSHIELD/LocShield_Document_11_AIDL_and_Internal_API_Specification_v1.1.1.md`
  - `LOCSHIELD/Enforcement_Architecture_Review_v1.md`
  - `LOCSHIELD/Android_Runtime_Validation_v1.md`
  - `LOCSHIELD/Android_API_and_Enforcement_Matrix_v1.md`
  - Policy Engine v0.1 (`locshield-policy/`, 188/188 passing tests, pure Kotlin/JVM)
  - Documents 01–13 (Foundation and pure core specifications)
- **Strict Scope Rule:** This is an **implementation plan only**. Contains **no AOSP tree edits**, **no Kotlin/Java implementation files**, **no AIDL compilation files**, **no build system patches**, and **no SELinux rule generation**.

---

## 1. AOSP Repository & Source Revision to Pin

To guarantee absolute build reproducibility, the LocShield Android 14 prototype pins the following official AOSP repositories and Git revisions:

| AOSP Repository | Remote URL (`android.googlesource.com`) | Pinned Branch / Release Tag | Purpose |
|---|---|---|---|
| `platform/frameworks/base` | `https://android.googlesource.com/platform/frameworks/base` | Tag `android-14.0.0_r74` (Commit `7e35917775b8`) | Core framework, `system_server`, `LocationManagerService`, interceptors. |
| `platform/system/sepolicy` | `https://android.googlesource.com/platform/system/sepolicy` | Tag `android-14.0.0_r74` | SELinux policy declarations, `service_contexts`, `system_server.te`. |
| `platform/build` | `https://android.googlesource.com/platform/build` | Tag `android-14.0.0_r74` | Core Soong and Make build orchestration. |
| `platform/build/soong` | `https://android.googlesource.com/platform/build/soong` | Tag `android-14.0.0_r74` | Blueprint compilation, `java_import` module definitions. |

- **Target Build Variant:** `aosp_x86_64-userdebug` (for emulator prototyping) and `aosp_arm64-userdebug` (for physical device validation).
- **Toolchain Pin:** Built with standard in-tree Android Clang/LLVM + OpenJDK 17 toolchain provided by AOSP `prebuilts/jdk/jdk17/linux-x86`.

---

## 2. Exact 10-Component AOSP Change Set

The implementation is strictly localized to **10 components across 2 repositories** (`frameworks/base` and `system/sepolicy`). Zero changes are made outside these locations:

```
[ AOSP Tree Root ]
  │
  ├── frameworks/base/
  │     ├── core/res/AndroidManifest.xml                              (Component 1: Permission Declaration)
  │     ├── services/core/Android.bp                                  (Component 2: Soong Blueprint)
  │     ├── services/java/com/android/server/SystemServer.java        (Component 3: Lifecycle Hook)
  │     │
  │     └── services/core/java/com/android/server/
  │           │
  │           ├── locshield/                                          (Component 4: New LocShield Package)
  │           │     ├── LocShieldSystemService.java
  │           │     ├── LocShieldInternal.java
  │           │     ├── PolicyRepository.java
  │           │     ├── IdentityResolver.java
  │           │     ├── PolicyEngineAdapter.java
  │           │     ├── AuditEventSink.java
  │           │     └── adapters/
  │           │
  │           └── location/
  │                 ├── LocationManagerService.java                   (Component 5: LMS Wiring Hook)
  │                 ├── provider/LocationProviderManager.java         (Component 6: Delivery & Cache Interceptor)
  │                 ├── provider/PassiveLocationProviderManager.java  (Component 7: Passive Fan-Out Interceptor)
  │                 ├── geofence/GeofenceManager.java                 (Component 8: Proximity Event Gate)
  │                 └── gnss/
  │                       ├── GnssManagerService.java                 (Component 9: GNSS Multiplexer Gate)
  │                       └── hal/GnssNative.java                     (Component 10: Batch Flush Interceptor)
  │
  └── system/sepolicy/
        ├── public/service.te                                         (Component 11a: SELinux Service Type)
        ├── private/service_contexts                                  (Component 11b: SELinux Service Context)
        └── private/system_server.te                                  (Component 11c: SELinux SystemServer Rule)
```

*(Note: SELinux files are logically grouped as Component 11, representing the complete 10-functional-component architecture established in v2.1.1 Section 22).*

---

## 3. Exact Source Files, Classes & Methods to Modify

The table below catalogs every in-tree file, target class, and exact method modified by LocShield:

| # | File Path in AOSP | Target Class | Target Method / Symbol | Modification Summary |
|---|---|---|---|---|
| 1 | `frameworks/base/core/res/AndroidManifest.xml` | `<manifest>` | Line ~2200 (Permission definitions) | Declare `android.permission.MANAGE_LOCATION_PRIVACY`. |
| 2 | `frameworks/base/services/core/Android.bp` | `java_library` `services.core` | `static_libs` list | Add `locshield-policy-core` prebuilt dependency. |
| 3 | `frameworks/base/services/java/com/android/server/SystemServer.java` | `SystemServer` | `startOtherServices()` | Instantiate `LocShieldSystemService.Lifecycle` via `mSystemServiceManager.startService()`. |
| 4 | `frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java` | `LocationManagerService` | `onSystemThirdPartyAppsCanStart()` | Connect to `LocShieldInternal` local service; bind AppOps observer. |
| 5 | `frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java` | `LocationListenerRegistration` | `acceptLocationChange(LocationResult)` | Synchronously intercept delivery, transform coordinates, and sanitize metadata before dispatch. |
| 6 | `frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java` | `LocationProviderManager` | `getLastLocation(LastLocationRequest, ...)` | Synchronously intercept cached location reads, evaluate active policy, and transform coordinate dynamically. |
| 7 | `frameworks/base/services/core/java/com/android/server/location/provider/PassiveLocationProviderManager.java` | `PassiveLocationProviderManager` | `updateLocation(LocationResult)` | Ensure per-consumer policy evaluation during passive fan-out. |
| 8 | `frameworks/base/services/core/java/com/android/server/location/geofence/GeofenceManager.java` | `GeofenceManager` | `addGeofence()` & `onLocationChanged()` | Gate geofence registration and suppress proximity event broadcast under `DENY`. |
| 9 | `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java` | `GnssManagerService` | `onReportLocationBatch()` | Intercept batched hardware fixes at flush time and apply current policy transformation. |
| 10| `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java` | `GnssMeasurementsProvider`, `GnssNmeaProvider`, `GnssStatusProvider`, `GnssNavigationMessageProvider` | `onMeasurementsReceived()`, `onNmeaReceived()`, `onStatusChanged()`, `onNavMessage()` | Capability gating: mute or sanitize callbacks when policy $< \text{EXACT}$. |
| 11| `system/sepolicy/public/service.te` | Global Service Declarations | End of file | Declare `location_privacy_service` as `service_manager_type`. |
| 12| `system/sepolicy/private/service_contexts` | Service Manager Mappings | `location_privacy` mapping | Map `location_privacy` to `u:object_r:location_privacy_service:s0`. |
| 13| `system/sepolicy/private/system_server.te` | `system_server` Domain Rules | Location section | Permit `system_server` to `add` and `find` `location_privacy_service`. |

---

## 4. `LocShieldSystemService` Lifecycle Integration

- **Host Process:** `system_server` (UID `1000`, process domain `u:r:system_server:s0`).
- **Class Declaration:**
  ```java
  package com.android.server.locshield;
  public class LocShieldSystemService extends SystemService { ... }
  ```
- **Lifecycle Integration in `SystemServer.java` (`startOtherServices`):**
  ```java
  t.traceBegin("StartLocShieldSystemService");
  mSystemServiceManager.startService(LocShieldSystemService.class);
  t.traceEnd();
  ```
- **Lifecycle Phase Sequence:**
  1. `Constructor`: Allocates `LocShieldBinderService`, `LocShieldLocalService`, and `PolicyRepository`.
  2. `onStart()`:
     - Calls `publishBinderService("location_privacy", mBinderService);` to register with `ServiceManager`.
     - Calls `publishLocalService(LocShieldInternal.class, mLocalService);` to register in-process with `LocalServices`.
     - *Security Rule:* Publication occurs early to guarantee dependency discovery without deadlocks.
  3. `onBootPhase(PHASE_SYSTEM_SERVICES_READY)`:
     - Injects `PackageManagerInternal`, `AppOpsManager`, and `LocationManagerInternal`.
     - Reads persistent policy XML from `/data/system/users/<userId>/locshield_policies.xml`.
     - Compiles initial immutable `PolicySnapshot` (Generation 1).
     - Registers package-removal broadcast receiver to handle uninstalls.
  4. `onBootPhase(PHASE_BOOT_COMPLETED)`:
     - Sets internal readiness boolean to `true`; unlocks external Control APK Binder queries.

---

## 5. Binder Service Publication (`location_privacy`)

- **Interface Class:** `android.location.privacy.ILocShieldManager.Stub`
- **Internal Implementation:** `com.android.server.locshield.LocShieldBinderService`
- **Published Name:** `"location_privacy"`
- **Publication Method:**
  ```java
  // Executed within LocShieldSystemService.onStart()
  publishBinderService("location_privacy", mBinderService);
  ```
- **Service Verification Command:**
  ```sh
  adb shell service check location_privacy
  # Expected: Service location_privacy: found
  ```

---

## 6. `LocalServices` Publication (`LocShieldInternal`)

- **Interface Class:** `com.android.server.locshield.LocShieldInternal`
- **Implementation:** `com.android.server.locshield.LocShieldLocalService`
- **Publication Method:**
  ```java
  // Executed within LocShieldSystemService.onStart()
  publishLocalService(LocShieldInternal.class, mLocalService);
  ```
- **Retrieval in Interceptors:**
  ```java
  // Cached once during interceptor initialization:
  mLocShield = LocalServices.getService(LocShieldInternal.class);
  ```
- **Performance Invariant:** Calling `LocShieldInternal` methods is an **in-memory, direct virtual method invocation**. It incurs zero IPC marshalling, zero context switching, and zero Binder driver interaction.

---

## 7. `MANAGE_LOCATION_PRIVACY` Platform Permission

- **Declaration in `frameworks/base/core/res/AndroidManifest.xml`:**
  ```xml
  <!-- Allows an application to configure LocShield per-app location privacy policies.
       @hide @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS) -->
  <permission android:name="android.permission.MANAGE_LOCATION_PRIVACY"
      android:protectionLevel="signature|privileged" />
  ```
- **Granting Mechanics:**
  - `signature`: Granted automatically by `PackageManager` if the caller APK is signed with the platform certificate (`build/make/target/product/security/platform.pk8`).
  - `privileged`: If pre-installed in `/system/priv-app/ControlApp/`, granted if allowlisted in `/etc/permissions/privapp-permissions-locshield.xml`:
    ```xml
    <permissions>
        <privapp-permissions package="shield.loc.control">
            <permission name="android.permission.MANAGE_LOCATION_PRIVACY"/>
        </privapp-permissions>
    </permissions>
    ```
- **Enforcement in `LocShieldBinderService`:**
  ```java
  mContext.enforceCallingOrSelfPermission(
      Manifest.permission.MANAGE_LOCATION_PRIVACY,
      "Caller requires MANAGE_LOCATION_PRIVACY to transact LocShield policies");
  ```

---

## 8. SELinux Service-Object Labeling & Access Model

- **Service Object Labeling (`system/sepolicy/private/service_contexts`):**
  ```text
  location_privacy                          u:object_r:location_privacy_service:s0
  ```
- **Service Type Definition (`system/sepolicy/public/service.te`):**
  ```te
  type location_privacy_service, app_api_service, system_server_service, service_manager_type;
  ```
- **System Server Rule (`system/sepolicy/private/system_server.te`):**
  ```te
  allow system_server location_privacy_service:service_manager { add find };
  ```
- **Untrusted App Access Rule (`system/sepolicy/private/untrusted_app_all.te`):**
  ```te
  # Permit applications holding MANAGE_LOCATION_PRIVACY to resolve the service
  allow untrusted_app_all location_privacy_service:service_manager find;
  ```
- **Correctness Rule (ADR-010):** `LocShieldSystemService` runs inside `system_server`. No process domain transition is declared. The domain remains `u:r:system_server:s0`.

---

## 9. Per-User Policy Storage Integration

- **File System Storage Path:** `/data/system/users/<userId>/locshield_policies.xml`
- **File Security Attributes:**
  - Owner: `system:system` (UID `1000`, GID `1000`)
  - Permissions: `0600` (`-rw-------`)
  - SELinux Label: `u:object_r:system_data_file:s0` (Inherited from `/data/system/users/`)
- **Atomic Serialization Implementation:**
  ```java
  AtomicFile file = new AtomicFile(new File(Environment.getUserSystemDirectory(userId), "locshield_policies.xml"));
  FileOutputStream fos = file.startWrite();
  try {
      writePoliciesXml(fos, userPolicies);
      file.finishWrite(fos);
  } catch (IOException e) {
      file.failWrite(fos);
      throw e;
  }
  ```
- **Lifecycle Triggers:**
  - On Boot: Loaded per active user during `PHASE_SYSTEM_SERVICES_READY`.
  - On User Start: `onUserStarting(TargetUser user)` loads policies for secondary users.
  - On Package Removed: Broadcast receiver deletes package record and triggers `AtomicFile.startWrite()`.

---

## 10. Policy Engine v0.1 Embedding & Jar Integration

- **Artifact Pin:** Standalone bytecode archive `locshield-policy-v0.1.jar` generated by building `LOCSHIELD/locshield-policy/`.
- **Prebuilt Location in AOSP:** `frameworks/base/services/core/libs/locshield-policy-v0.1.jar`
- **Blueprint Integration in `frameworks/base/services/core/Android.bp`:**
  ```blueprint
  java_import {
      name: "locshield-policy-core",
      jars: ["libs/locshield-policy-v0.1.jar"],
      sdk_version: "current",
  }

  // Inside java_library_static "services.core":
  static_libs: [
      ...
      "locshield-policy-core",
  ],
  srcs: [
      ...
      "java/com/android/server/locshield/**/*.java",
  ],
  ```
- **Runtime Class Loading:** `locshield-policy-core` classes are merged directly into `services.jar` on the `system_server` classpath. Zero reflection or dynamic classloader manipulation is required.

---

## 11. `LocationProviderManager` Delivery Interception (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java`
- **Target Class:** `LocationProviderManager.LocationListenerRegistration`
- **Target Method:** `protected ListenerOperation<LocationListener> acceptLocationChange(LocationResult fineResult)`
- **Purpose:** Synchronously intercept real-time location deliveries, apply policy-driven spatial transformation, sanitize metadata, enforce temporal rate limits, and record audit telemetry before dispatching to application listeners or `PendingIntent`s.
- **Input:** `LocationResult fineResult` (un-fudged hardware/provider fix), `this.mIdentity` (`CallerIdentity`).
- **Output:** Modified `LocationResult` containing sanitized/transformed coordinates, or `null` to drop delivery.
- **Detailed Hook Placement:**
  ```java
  // In LocationListenerRegistration.acceptLocationChange():
  LocationResult permittedResult = fineResult;

  // --- BEGIN LOCSHIELD HOOK ---
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null) {
      try {
          permittedResult = locShield.onLocationDelivery(
                  getIdentity(), 
                  fineResult, 
                  /* isPassive= */ false);
          if (permittedResult == null) {
              return null; // Delivery dropped by LocShield (DENY or THROTTLE)
          }
      } catch (Throwable t) {
          Log.e(TAG, "LocShield delivery interceptor failure; failing closed", t);
          return null; // Strict fail-closed boundary
      }
  }
  // --- END LOCSHIELD HOOK ---

  // Proceed with standard AOSP transport delivery using permittedResult
  ```
- **Policy Decision Point:** `PolicyEngine.evaluate(appIdentity, RequestContext, EffectivePolicy, LocationContext, nowNanos)`.
- **Security Invariants Protected:** Invariants 1, 2, 3, 5, 6 (Zero raw leakage, delivery-time enforcement, metadata consistency).
- **Verification Test ID:** `AOSP-TEST-DELIVERY-01`.

---

## 12. `getLastLocation()` Cache Enforcement (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java`
- **Target Class:** `LocationProviderManager`
- **Target Method:** `public Location getLastLocation(LastLocationRequest request, CallerIdentity identity, int permissionLevel)`
- **Purpose:** Prevent stale precise coordinate leakage by evaluating caller's active policy generation at read time and dynamically transforming cached coordinates before returning to the caller.
- **Input:** `LastLocationRequest request`, `CallerIdentity identity`, `int permissionLevel`.
- **Output:** Sanitized/transformed `Location` parcel, or `null` (cache miss / denial).
- **Detailed Hook Placement:**
  ```java
  // In LocationProviderManager.getLastLocation():
  Location location = getLastLocationUnsafe(identity.getUserId(), permissionLevel,
          request.isBypass(), request.getMaximumAgeMs());
  if (location == null) return null;

  if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(permissionLevel), identity)) {
      return null;
  }

  // --- BEGIN LOCSHIELD HOOK ---
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null && !request.isBypass()) { // Preserve emergency bypasses unmediated
      try {
          location = locShield.onCacheRead(identity, location);
          if (location == null) {
              return null; // Policy DENY or evaluation failure -> cache miss
          }
      } catch (Throwable t) {
          Log.e(TAG, "LocShield cache read interceptor failure; failing closed", t);
          return null;
      }
  }
  // --- END LOCSHIELD HOOK ---

  return getPermittedLocation(location, permissionLevel);
  ```
- **Policy Decision Point:** `PolicyEngine.evaluateCacheRead()`.
- **Security Invariants Protected:** Invariant 4 (Stale cache cannot bypass policy downgrade).
- **Verification Test ID:** `AOSP-TEST-CACHE-01`.

---

## 13. `PassiveLocationProviderManager` Enforcement (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/provider/PassiveLocationProviderManager.java`
- **Target Class:** `PassiveLocationProviderManager`
- **Target Method:** `public void updateLocation(LocationResult locationResult)`
- **Purpose:** Guarantee producer-consumer decoupling by evaluating outgoing passive fixes strictly against the receiving passive consumer's policy, independent of active producer permissions.
- **Input:** `LocationResult locationResult` (fine active provider fix).
- **Output:** Independent delivery to passive registrations with per-consumer spatial quantization.
- **Detailed Hook Placement:**
  The hook is placed inside `PassiveLocationProviderManager.LocationListenerRegistration.acceptLocationChange()`:
  ```java
  // In PassiveLocationProviderManager Registration:
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null) {
      try {
          permittedResult = locShield.onLocationDelivery(
                  getIdentity(), 
                  locationResult, 
                  /* isPassive= */ true);
          if (permittedResult == null) {
              return null; // Suppressed per consumer's policy
          }
      } catch (Throwable t) {
          Log.e(TAG, "LocShield passive interceptor failure; failing closed", t);
          return null;
      }
  }
  ```
- **Policy Decision Point:** `PolicyEngine.evaluate(consumerIdentity, isPassive=true)`.
- **Security Invariants Protected:** Invariant 9 (Passive consumer isolation).
- **Verification Test ID:** `AOSP-TEST-PASSIVE-01`.

---

## 14. `GeofenceManager` Enforcement (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/geofence/GeofenceManager.java`
- **Target Class:** `GeofenceManager`
- **Target Methods:** `public void addGeofence(Geofence geofence, PendingIntent intent, CallerIdentity identity)` and `public void onLocationChanged(Location location)`
- **Purpose:** Enforce binary spatial and background gating on framework proximity alerts. Prevent geofence arming under `DENY` and suppress enter/exit intent broadcasts when background location is restricted.
- **Input:** Registration: `Geofence geofence`, `CallerIdentity identity`. Transition: `GeofenceRegistration registration`, `boolean entering`.
- **Output:** Registration allowed/rejected; `PendingIntent` dispatched or suppressed.
- **Detailed Hook Placement:**
  ```java
  // In GeofenceManager.addGeofence():
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null) {
      if (!locShield.onGeofenceRegistration(identity, geofence.getLatitude(), geofence.getLongitude(), geofence.getRadius())) {
          return; // Arming rejected under policy DENY
      }
  }

  // In GeofenceManager transition trigger:
  if (locShield != null) {
      if (!locShield.onGeofenceTransitionDelivery(registration.getIdentity(), entering)) {
          return; // Transition broadcast suppressed under BackgroundPolicy == DENY
      }
  }
  ```
- **Policy Decision Point:** `PolicyEngine.evaluate(RequestType.GEOFENCE_REGISTRATION / GEOFENCE_EVENT)`.
- **Security Invariants Protected:** Invariant 11 (Framework geofence gating).
- **Verification Test ID:** `AOSP-TEST-GEO-01`.

---

## 15. `GnssBatchingProvider` Enforcement (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssManagerService`
- **Target Method:** `public void onReportLocationBatch(Location[] locations)` (invoked from `GnssNative`)
- **Purpose:** Eliminate TOCTOU leakage in hardware GNSS batching by transforming every location in the flushed batch under the caller's active policy generation at the instant of flush dispatch.
- **Input:** `Location[] locations` (raw batched fixes from sensor hub buffer).
- **Output:** Transformed/sanitized `List<Location>` dispatched to `IBatchedLocationCallback`.
- **Detailed Hook Placement:**
  ```java
  // In GnssManagerService batch flush callback loop:
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  List<Location> batchList = Arrays.asList(locations);
  if (locShield != null) {
      try {
          batchList = locShield.onGnssBatchDelivery(listener.getIdentity(), batchList);
          if (batchList.isEmpty()) {
              return; // Entire batch suppressed under policy DENY
          }
      } catch (Throwable t) {
          Log.e(TAG, "LocShield batch interceptor failure; failing closed", t);
          return;
      }
  }
  listener.onLocationBatch(batchList);
  ```
- **Policy Decision Point:** `PolicyEngine.evaluate(batchSample, Generation_now)`.
- **Security Invariants Protected:** Invariant 13, ADR-012 (Batch flush TOCTOU elimination).
- **Verification Test ID:** `AOSP-TEST-BATCH-01`.

---

## 16. `GnssMeasurements` Capability Gate (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssMeasurementsProvider`
- **Target Method:** `protected void onGnssMeasurementsReceived(GnssMeasurementsEvent event)`
- **Purpose:** Mute raw pseudorange, carrier phase, and Doppler observable callbacks when the caller's spatial policy is less than `EXACT`.
- **Input:** `GnssMeasurementsEvent event`, registered `GnssListenerRegistration`.
- **Output:** Callback dispatched or muted per listener.
- **Detailed Hook Placement:**
  ```java
  // Inside listener loop in GnssMeasurementsProvider:
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null && !locShield.isGnssMeasurementsAllowed(registration.getIdentity())) {
      return; // Skip listener dispatch; zero raw observables leaked
  }
  registration.getListener().onGnssMeasurementsReceived(event);
  ```
- **Policy Decision Point:** `EffectivePolicy.spatial.mode == EXACT`.
- **Security Invariants Protected:** Invariant 8 (Raw GNSS measurement suppression).
- **Verification Test ID:** `AOSP-TEST-GNSS-MEAS-01`.

---

## 17. NMEA Capability Gate (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssNmeaProvider`
- **Target Method:** `public void onNmeaReceived(long timestamp, String nmea)`
- **Purpose:** Mute raw ASCII NMEA string dispatch (`$GPGGA`, `$GPRMC`) when spatial policy is not `EXACT`.
- **Input:** `long timestamp`, `String nmea`.
- **Output:** NMEA string dispatched or dropped per listener.
- **Detailed Hook Placement:**
  ```java
  // Inside listener loop in GnssNmeaProvider:
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null && !locShield.isGnssNmeaAllowed(registration.getIdentity())) {
      return; // Suppress NMEA broadcast
  }
  registration.getListener().onNmeaReceived(timestamp, nmea);
  ```
- **Policy Decision Point:** `EffectivePolicy.spatial.mode == EXACT`.
- **Security Invariants Protected:** Invariant 8 (NMEA stream suppression).
- **Verification Test ID:** `AOSP-TEST-NMEA-01`.

---

## 18. `GnssStatus` Capability Gate (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssStatusProvider`
- **Target Method:** `public void onStatusChanged(GnssStatus status)`
- **Purpose:** Mitigate regional orbital ephemeris inference by muting callbacks or zeroing satellite azimuth and elevation angles when caller policy is not `EXACT`.
- **Input:** `GnssStatus status`.
- **Output:** Cleaned/zeroed `GnssStatus` parcel or callback suppression.
- **Detailed Hook Placement:**
  ```java
  // Inside listener loop in GnssStatusProvider:
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null) {
      int mode = locShield.getGnssStatusGateMode(registration.getIdentity());
      if (mode == 2) { // MUTE
          return;
      } else if (mode == 1) { // SANITIZE
          status = sanitizeGnssStatus(status); // Helper zeroes azimuth/elevation arrays
      }
  }
  registration.getListener().onStatusChanged(status);
  ```
- **Policy Decision Point:** `EffectivePolicy.spatial.mode == EXACT`.
- **Security Invariants Protected:** Invariant 8 (Satellite geometry side-channel mitigation).
- **Verification Test ID:** `AOSP-TEST-GNSS-STATUS-01`.

---

## 19. `GnssNavigationMessage` Capability Gate (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssNavigationMessageProvider`
- **Target Method:** `public void onNavigationMessageReceived(GnssNavigationMessage event)`
- **Purpose:** Mute orbital subframe ephemeris bitstream dispatch when caller policy is not `EXACT`.
- **Input:** `GnssNavigationMessage event`.
- **Output:** Message dispatched or muted.
- **Detailed Hook Placement:**
  ```java
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null && !locShield.isGnssNavigationMessageAllowed(registration.getIdentity())) {
      return; // Mute ephemeris dispatch
  }
  registration.getListener().onNavigationMessageReceived(event);
  ```
- **Policy Decision Point:** `EffectivePolicy.spatial.mode == EXACT`.
- **Security Invariants Protected:** Invariant 8 (Ephemeris bitstream suppression).
- **Verification Test ID:** `AOSP-TEST-GNSS-NAV-01`.

---

## 20. `GnssAntennaInfo` Capability Gate (Detailed Spec)

- **Source File:** `frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java`
- **Target Class:** `GnssManagerService`
- **Target Method:** `public void registerAntennaInfoListener(IGnssAntennaInfoListener listener, CallerIdentity identity)`
- **Purpose:** Reject antenna calibration listener registration when caller policy is not `EXACT` to uphold fail-closed authorization consistency.
- **Input:** `IGnssAntennaInfoListener listener`, `CallerIdentity identity`.
- **Output:** Registration permitted or dropped.
- **Detailed Hook Placement:**
  ```java
  LocShieldInternal locShield = LocalServices.getService(LocShieldInternal.class);
  if (locShield != null && !locShield.isGnssAntennaInfoAllowed(identity)) {
      return; // Reject listener registration
  }
  ```
- **Policy Decision Point:** `EffectivePolicy.spatial.mode == EXACT`.
- **Security Invariants Protected:** Invariant 8 (Antenna calibration metadata gating).
- **Verification Test ID:** `AOSP-TEST-GNSS-ANTENNA-01`.

---

## 21. GMS Boundary & Explicit Non-Guarantees

- **GMS Architecture Constraint:** GMS Core (`com.google.android.gms`) executes in a closed, privileged process space. `FusedLocationProviderClient` does not transit `LocationManagerService`.
- **LocShield Plane 2 AppOps Coordination:**
  When an application's policy is updated to $< \text{EXACT}$:
  ```java
  AppOpsManagerInternal appOps = LocalServices.getService(AppOpsManagerInternal.class);
  appOps.setMode(AppOpsManager.OP_FINE_LOCATION, uid, packageName, AppOpsManager.MODE_IGNORED);
  ```
- **Explicit Architectural Non-Guarantees:**
  1. LocShield **CANNOT** rewrite GMS FLP output coordinates to custom `CITY`, `GRID`, `RADIUS`, or `RANDOMIZED` shapes.
  2. LocShield **CANNOT** enforce custom temporal rate limits on GMS FLP deliveries.
  3. LocShield **CANNOT** alter the triggering `Location` parcel embedded in GMS `GeofencingEvent`.
- **Guaranteed Boundary:** Setting `OP_FINE_LOCATION = MODE_IGNORED` forces GMS FLP to withhold high-precision GPS fixes, constraining the application to Android's native coarse mode (~2 km grid, throttled $\ge 10$ minutes).

---

## 22. Fail-Closed Exception Handling Architecture

Every in-tree hook implements strict exception containment:

```
┌─────────────────────────────────────────────────────────────┐
│                    Interceptor Hot-Path Call                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
               ┌───────────────────────────────┐
               │     try { LocShield Hook }    │
               └───────────────┬───────────────┘
                               │
             ┌─────────────────┴─────────────────┐
             ▼ Normal Return                     ▼ Catch (Throwable t)
   [ Deliver Transformed ]              ┌─────────────────────────────────┐
                                        │ 1. Log to Logcat: LS_CRASH_PREV │
                                        │ 2. AuditSink: FAIL_CLOSED       │
                                        │ 3. Return null / Suppress       │
                                        └─────────────────────────────────┘
                                                 (Zero Coordinates Emitted;
                                                  system_server NEVER Crashed)
```

- **Blast Radius Protection:** An uncaught exception within LocShield core or adapters drops the location parcel and returns `null`. It **never bubbles an unchecked exception into `system_server`**, eliminating runtime soft-reboot risks.

---

## 23. Policy Generation & TOCTOU Handling

1. **Snapshot Publication:** Each committed policy update increments the monotonic generation counter ($G \to G + 1$).
2. **Lock-Free Reads:** Delivery threads read `AtomicReference<PolicySnapshot>` with zero lock contention.
3. **Synchronous Hot-Path Gating:** Deliveries evaluate against the active snapshot at the exact instant of dispatch. A policy downgrade from `EXACT` to `CITY` takes effect on fix $N+1$ without restarting provider hardware.
4. **Batch Flush TOCTOU Elimination:** Hardware GNSS batch flushes evaluate against generation $G_{\text{now}}$ at flush dispatch, transforming or discarding all historical buffered fixes.

---

## 24. Multi-User Identity Validation

1. **Identity Tuple:** Frozen as `userId + UID + packageName` (+ `attributionTag`).
2. **Kernel UID Verification:** `Binder.getCallingUid()` is authoritative.
3. **Package Ownership Validation:** Target packages must be in `PackageManager.getPackagesForUid(callingUid)`.
4. **Storage & Snapshot Isolation:** `/data/system/users/<userId>/locshield_policies.xml`. Work profile (User 10) policies are completely isolated from personal (User 0) policies.

---

## 25. Threading & Lock-Order Constraints

To prevent deadlocks within `system_server`, the following strict lock-order hierarchy is enforced:

$$\text{AOSP LMS Locks} \longrightarrow \text{LocShield Local Lock} \longrightarrow \text{PolicySnapshot Ref} \longrightarrow \text{TemporalController Monitor}$$

1. **Rule 1 (Zero Lock Holding during Upstream Calls):** LocShield interceptors never hold internal LocShield locks while calling back into AOSP `LocationProviderManager` or Binder transports.
2. **Rule 2 (Lock-Free Hot-Path Policy Reads):** Reading `PolicySnapshot` via `AtomicReference.get()` requires zero lock acquisition.
3. **Rule 3 (Temporal Lock Striping):** `TemporalController` locks are striped per `AppIdentity`. Deliveries for Package A never block deliveries for Package B.

---

## 26. Build Dependency Graph (Soong)

```
[ locshield-policy-v0.1.jar ] (Prebuilt Bytecode)
            │
            ▼ java_import ("locshield-policy-core")
[ Android.bp in services/core ]
            │
            ▼ static_libs: ["locshield-policy-core"]
[ services.core java_library_static ]
            │ (Compiles com.android.server.locshield.*)
            ▼
[ services.jar ] (Loaded into system_server)
```

---

## 27. Required Unit & Integration Tests

| Test Suite ID | Target Component | Execution Environment | Scope & Objective |
|---|---|---|---|
| `TEST-AOSP-LPM-01` | `LocationProviderManager` | AOSP Integration Test | Verify coordinate transformation and metadata sanitization at `acceptLocationChange()`. |
| `TEST-AOSP-CACHE-01` | `LocationProviderManager` | AOSP Integration Test | Verify read-time dynamic transformation in `getLastLocation()`. |
| `TEST-AOSP-PASSIVE-01` | `PassiveLocationProviderManager`| AOSP Multi-App Test | Verify passive consumer receives city fix while producer receives exact fix. |
| `TEST-AOSP-GEO-01` | `GeofenceManager` | AOSP Integration Test | Verify proximity alert arming rejected under `DENY` and events suppressed in background. |
| `TEST-AOSP-BATCH-01` | `GnssBatchingProvider` | AOSP Hardware Test | Verify batch flushes evaluate current policy at flush time (TOCTOU elimination). |
| `TEST-AOSP-GNSS-01` | `GnssManagerService` | AOSP Integration Test | Verify raw measurements, NMEA, status, and nav messages are muted when policy $< \text{EXACT}$. |
| `TEST-AOSP-CRASH-01` | Error Containment | AOSP Fault Injection | Inject runtime exception in `PolicyEngineAdapter`; verify delivery drops without crashing `system_server`. |
| `TEST-AOSP-SELINUX-01` | SELinux Policy | CTS / Host Security | Verify `location_privacy` service registration passes `auditallow` with zero denials in enforcing mode. |

---

## 28. Emulator Validation Sequence

1. **Build AOSP Image:** Run `m -j systemimage vendorimage` with LocShield changes applied.
2. **Launch Emulator:** Run `emulator -avd ls34 -no-snapshot -wipe-data`.
3. **Verify Service Registration:**
   ```sh
   adb shell service check location_privacy
   # Result: found
   ```
4. **Install Diagnostic APKs:** Install `location-client-debug.apk`, `producer-debug.apk`, `passive-consumer-debug.apk`, `geofence-client-debug.apk`.
5. **Inject Synthetic Coordinates:**
   ```sh
   adb emu geo fix 72.8777 19.0760 15.0
   ```
6. **Execute Verification Workflows:**
   - Configure `CITY` for `shield.loc.client` via `adb shell cmd location_privacy set-policy ...`.
   - Run `adb shell am start -n shield.loc.client/.MainActivity --es action lms-updates`.
   - Verify logcat output: `lat=19.08, lon=72.88, acc=5000.0, speed=null, bearing=null`.

---

## 29. Rollback Strategy

1. **Git Branch Isolation:** All in-tree modifications are developed on feature branch `feature/locshield-android14`.
2. **Build-Time Emergency Kill-Switch:** AOSP build variable `PRODUCT_ENABLE_LOCSHIELD := true/false`. If false, `LocShieldInternal` stubs return raw data immediately without intercepting.
3. **Runtime Safe Fallback:** If `PolicyRepository` detects corrupt storage on boot, it quarantines `/data/system/users/<id>/locshield_policies.xml.corrupt` and initializes `systemDefaultPolicy()` without halting system boot.

---

## 30. Patch Ordering for Independent Review

The implementation is broken into **7 discrete, sequentially reviewable commits**:

```
Commit 1: SELinux Policies & Manifest Permission
   ├── system/sepolicy/public/service.te
   ├── system/sepolicy/private/service_contexts
   ├── system/sepolicy/private/system_server.te
   └── frameworks/base/core/res/AndroidManifest.xml
   (Review: Compiles cleanly under checkpolicy; declares permission)
          │
          ▼
Commit 2: Prebuilt Core Jar & Soong Build Integration
   ├── frameworks/base/services/core/libs/locshield-policy-v0.1.jar
   └── frameworks/base/services/core/Android.bp
   (Review: Compiles prebuilt jar into services.core static libraries)
          │
          ▼
Commit 3: SystemServer Lifecycle & LocShieldSystemService Skeleton
   ├── frameworks/base/services/java/com/android/server/SystemServer.java
   └── frameworks/base/services/core/java/com/android/server/locshield/
         ├── LocShieldSystemService.java
         ├── LocShieldBinderService.java
         ├── LocShieldLocalService.java
         ├── LocShieldInternal.java
         ├── PolicyRepository.java
         └── IdentityResolver.java
   (Review: Service registers with ServiceManager and LocalServices; boots cleanly)
          │
          ▼
Commit 4: PolicyEngineAdapter & Core Engine Wiring
   └── frameworks/base/services/core/java/com/android/server/locshield/adapters/
         ├── PolicyEngineAdapter.java
         ├── AndroidLocationAdapter.java
         ├── AndroidRequestContextAdapter.java
         ├── AndroidAuthorizationAdapter.java
         └── AndroidMetadataAdapter.java
   (Review: Unit tests verify type conversion between Android Location and LocationSample)
          │
          ▼
Commit 5: Framework Location Delivery & Cache Interception
   ├── frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java
   ├── frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java
   └── frameworks/base/services/core/java/com/android/server/location/provider/PassiveLocationProviderManager.java
   (Review: Hot path acceptLocationChange(), getLastLocation(), and passive updates enforced)
          │
          ▼
Commit 6: Framework Geofencing & GNSS Batching Interception
   ├── frameworks/base/services/core/java/com/android/server/location/geofence/GeofenceManager.java
   └── frameworks/base/services/core/java/com/android/server/location/gnss/GnssManagerService.java
   (Review: addGeofence() and onReportLocationBatch() flushes enforced)
          │
          ▼
Commit 7: GNSS Capability Gating
   └── frameworks/base/services/core/java/com/android/server/location/gnss/
         ├── GnssMeasurementsProvider.java
         ├── GnssNmeaProvider.java
         ├── GnssStatusProvider.java
         └── GnssNavigationMessageProvider.java
   (Review: Raw observables, NMEA, status angles, and nav messages gated)
```

---

## 31. Implementation Traceability Matrix

| # | Architecture Requirement (v2.1.1) | Document 11 Interface Method | AOSP Source Location | Planned Modification Summary | Verification Test ID | Feasibility Classification |
|---|---|---|---|---|---|---|
| 1 | Real-Time Location Transformation | `LocShieldInternal.onLocationDelivery` | `LocationProviderManager.java` | Synchronous delivery-time hook in `acceptLocationChange()` | `TEST-AOSP-LPM-01` | **PROVEN** |
| 2 | Read-Time Cache Freshness | `LocShieldInternal.onCacheRead` | `LocationProviderManager.java` | Dynamic transformation hook in `getLastLocation()` | `TEST-AOSP-CACHE-01` | **PROVEN** |
| 3 | Passive Consumer Isolation | `LocShieldInternal.onLocationDelivery` | `PassiveLocationProviderManager.java`| Per-consumer policy evaluation in `updateLocation()` | `TEST-AOSP-PASSIVE-01` | **PROVEN** |
| 4 | Framework Geofence Gating | `LocShieldInternal.onGeofenceRegistration` | `GeofenceManager.java` | Registration & background transition suppression | `TEST-AOSP-GEO-01` | **PROVEN** |
| 5 | Hardware Batch Flush Transformation| `LocShieldInternal.onGnssBatchDelivery` | `GnssManagerService.java` | Flush-time per-sample transformation in `onReportLocationBatch()`| `TEST-AOSP-BATCH-01` | **PROVEN** |
| 6 | GNSS Raw Measurement Muting | `LocShieldInternal.isGnssMeasurementsAllowed`| `GnssMeasurementsProvider.java`| Mute listener loop when policy $< \text{EXACT}$ | `TEST-AOSP-GNSS-01` | **PROVEN** |
| 7 | NMEA String Stream Muting | `LocShieldInternal.isGnssNmeaAllowed` | `GnssNmeaProvider.java` | Mute ASCII sentence loop when policy $< \text{EXACT}$ | `TEST-AOSP-GNSS-01` | **PROVEN** |
| 8 | Satellite Status Geometry Muting | `LocShieldInternal.getGnssStatusGateMode`| `GnssStatusProvider.java` | Zero azimuth/elevation angles when policy $< \text{EXACT}$ | `TEST-AOSP-GNSS-01` | **PROVEN** |
| 9 | Navigation Ephemeris Muting | `LocShieldInternal.isGnssNavigationMessageAllowed`| `GnssNavigationMessageProvider.java`| Mute subframe broadcast when policy $< \text{EXACT}$ | `TEST-AOSP-GNSS-01` | **PROVEN** |
| 10| Antenna Calibration Gating | `LocShieldInternal.isGnssAntennaInfoAllowed`| `GnssManagerService.java` | Drop listener registration when policy $< \text{EXACT}$ | `TEST-AOSP-GNSS-01` | **PROVEN** |
| 11| GMS FLP Coarse Backstop | Plane 2 AppOps Coordination | `AppOpsService.java` | Set `OP_FINE_LOCATION = MODE_IGNORED` for target UID | `TEST-GMS-BACKSTOP-01` | **PROVEN** (Authorization-Only) |
| 12| GMS Geofence Binary Denial | Plane 2 AppOps Coordination | `AppOpsService.java` | Set `ACCESS_BACKGROUND_LOCATION = MODE_IGNORED` | `TEST-GMS-GEO-01` | **PROVEN** (Authorization-Only) |
| 13| GMS Custom Coordinate Transformation| None | `com.google.android.gms` | Disclaimed; cannot alter closed GMS Core payloads | N/A | **REQUIRES GMS COOPERATION** |
| 14| Remote Server-Side GeoIP | None | Network Layer | Disclaimed; out of device operating system boundary | N/A | **OUTSIDE BOUNDARY** |

---

## 32. Feasibility Classification Status Summary

- **PROVEN (12 Channels):** Standard updates, current location, cache reads, passive fan-out, framework geofences, GNSS batching, GNSS measurements, NMEA streams, satellite status, navigation messages, antenna info, and GMS coarse backstop coordination. All mapped to verified AOSP source locations.
- **REQUIRES GMS COOPERATION (1 Channel):** Custom coordinate transformation of GMS `FusedLocationProviderClient` outputs.
- **OUTSIDE BOUNDARY (1 Channel):** Remote server-side IP geolocation and network routing inference.

---

## Strict Stop Condition & Declaration

This document concludes the pre-implementation blueprint milestone. In accordance with strict engineering mandates:
- **NO** AOSP source code has been written.
- **NO** patch files have been created.
- **NO** frameworks/base source files have been modified.
- **NO** system/sepolicy source files have been modified.
- **NO** AIDL interfaces have been generated in the tree.
- **NO** Control APK code has been created.
- **NO** System Service implementation code has been written.
- **NO** modifications to Policy Engine v0.1 have been made.
- **NO** modifications to Documents 01–13, Matrix v1, or Runtime Validation v1 have been made.

AOSP pre-implementation plan complete — awaiting implementation approval.
