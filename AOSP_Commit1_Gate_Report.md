# LocShield AOSP Commit 1 — Build & Review Gate Report

- **Date:** 2026-09-29
- **Target:** AOSP `android-14.0.0_r74` (`platform/frameworks/base` @ `5fc1588`, `platform/system/sepolicy` @ `3be6813`), `aosp_x86_64-userdebug`
- **Scope attempted:** Commit 1 — "LocShield foundation: permission, service registration, SELinux, build wiring"
- **Authoritative baselines:** Enforcement_Architecture_Specification_v2.1.1.md, LocShield_Document_11_AIDL_and_Internal_API_Specification_v1.1.1.md, LocShield_AOSP_PreImplementation_Plan_v1.0.1.md, Matrix v1, Runtime Validation v1, Policy Engine v0.1, Documents 01–13

## 0. Verdict: STOP — Commit 1 NOT implemented

**No commit was created. No commit hash exists.** Per the task's own STOP rule
("If any source path, API, build rule, permission mechanism, or SELinux
relationship differs from the frozen plan, STOP before adapting it"), and
because the preconditions for an honest implementation are not met in this
environment, no AOSP tree was modified and no build was executed. Fabricating
a commit hash, build log, or file diff would be a false engineering record.
This report documents exactly what was verified, what differs, what blocks,
and the precise resolution path.

## 1. Verification evidence collected (read-only, against real r74 sources)

All checks below were performed against the genuine `android-14.0.0_r74`
revisions (partial/blobless clones; no files modified).

| # | Task scope item | Expected (frozen plan) | Actual (r74 source) | Result |
|---|---|---|---|---|
| 1 | Permission declaration point | `frameworks/base/core/res/AndroidManifest.xml`, `signature\|privileged`, no label required | Confirmed: `LOCATION_BYPASS` precedent at line ~1345 uses exactly `android:protectionLevel="signature\|privileged"` with no label/description; `ACCESS_FINE_LOCATION` block nearby for placement | **MATCH** |
| 2 | SystemService lifecycle API | `onStart()` + `publishBinderService` + `publishLocalService` | Confirmed in `services/core/java/com/android/server/SystemService.java`: `onStart()` abstract (line 412), `publishBinderService` (578/590/606), `publishLocalService` (625); header docs mandate exactly this split | **MATCH** |
| 3 | SystemServer registration pattern | `mSystemServiceManager.startService(X.Lifecycle.class)` | Confirmed: `SystemServer.java:2215` uses `startService(LocationManagerService.Lifecycle.class)`; `LocationManagerService.Lifecycle extends SystemService` pattern present | **MATCH** |
| 4 | LMS path for later commits | `services/core/java/com/android/server/location/LocationManagerService.java` | Confirmed present with `Lifecycle` inner class | **MATCH** (info only; not touched) |
| 5 | SELinux `service.te` pattern | `type location_privacy_service, app_api_service, system_server_service, service_manager_type;` | Confirmed pattern: `type location_service, app_api_service, ephemeral_app_api_service, system_server_service, service_manager_type;` (service.te:166) | **MATCH** |
| 6 | SELinux `service_contexts` format | `location_privacy  u:object_r:location_privacy_service:s0` | Confirmed format: `location  u:object_r:location_service:s0` (service_contexts:299) | **MATCH** |
| 7 | `add_service` macro semantics | macro grants add+find + neverallow others | Confirmed in `public/te_macros:704`: `allow $1 $2:service_manager { add find };` + `neverallow { domain -$1 } $2:service_manager add;` | **MATCH** |
| 8 | Policy Engine artifact | `locshield-policy-0.1.0.jar` (140 KB, 93 classes, `locshield/*` only) exists from frozen build | Confirmed present, unmodified (timestamp 2026-09-28). `jdeps` proves external deps = `java.base` + kotlin-stdlib (`kotlin/jvm/internal/Intrinsics` refs) | **MATCH** (artifact) / **GAP** (stdlib linkage, see D2) |
| 9 | PrivApp allowlist path (Option 2) | `privapp-permissions-*.xml` allowlist | Confirmed: `data/etc/privapp-permissions-platform.xml` header states it declares "which signature\|privileged permissions should be granted to privileged applications" | **MATCH** |

## 2. Discrepancies (expected vs actual, with proposed resolution)

### D1. `java_import` has zero precedent in frameworks/base @ r74 — REQUIRES SOURCE VERIFICATION
- **Expected (plan §10/26):** `java_import { name: "locshield-policy-core", jars: [...], sdk_version: "current" }` in `services/core/Android.bp`, linked via `static_libs`.
- **Actual:** `git grep -l "java_import" -- '*.bp'` across all 825 `Android.bp` files in frameworks/base @ r74 returns **zero matches** (grep verified functional via control query). The `services.core.unboosted` module links only `static_libs`/`libs` of in-tree `java_library`/`aidl` modules.
- **Assessment:** `java_import` is a valid Soong module type (defined in `build/soong`, outside the inspected tree), so this is **not proven incompatible** — but it is an **unproven mechanism in this tree**, and the plan presents it as settled.
- **Proposed resolution:** In the provisioned workspace, first attempt the frozen `java_import` form under `m services.core`; if Soong rejects the `jars:` + `static_libs` combination, fall back (with explicit architecture note, not silently) to either (a) a `java_library` filegroup wrapper, or (b) shadowing the engine bytecode into the LocShield package sources. Do not proceed past build wiring until one form compiles.

### D2. Kotlin-stdlib linkage for the Policy Engine jar is unaddressed — GAP
- **Expected (plan):** Android.bp snippet links only `locshield-policy-core`; no stdlib handling.
- **Actual:** `jdeps` + string inspection proves the frozen jar references `kotlin/jvm/internal/*`, `kotlin/collections/*`. No `kotlin-stdlib` module is referenced by any `Android.bp` in frameworks/base @ r74 (only hit for "kotlin" tree-wide is a comment about **jarjar'ed** Kotlin deps being pruned in `services/Android.bp:81`).
- **Assessment:** The established in-tree convention is to **jarjar-relocate** Kotlin dependencies (`framework-jarjar-rules.txt`, `jarjar_prefix`), not to link a shared stdlib into `services.core`.
- **Proposed resolution (no Policy Engine source change):** relocate `kotlin-stdlib` into the prebuilt artifact at Gradle build time (Shadow/jarjar plugin, e.g. relocate `kotlin.**` under `locshield.internal.kotlin.**`), keeping the AOSP side to the plain `java_import`. Verify with `jdeps` that the rebuilt artifact references only `java.base` + relocated packages. The frozen engine's *sources and semantics* stay untouched; only packaging changes, recorded as a build note.

### D3. Two SELinux rules are redundant (harmless) — MINOR
- **Expected (plan):** explicit `allow system_server location_privacy_service:service_manager { add find };` and `allow untrusted_app_all location_privacy_service:service_manager find;`.
- **Actual:** `add_service(system_server, system_server_service)` (system_server.te:952) already grants add+find on **all** `system_server_service`-typed services (which `location_privacy_service` will carry); `allow untrusted_app_all app_api_service:service_manager find;` (untrusted_app_all.te:99) already covers find via the `app_api_service` attribute.
- **Assessment:** Both explicit rules are redundant but harmless (duplicate allows compile cleanly). Recommend replacing the explicit system_server rule with the one-line `add_service(system_server, location_privacy_service);` macro form and dropping the untrusted_app rule, recording the change as a review note — not a silent redesign.

### D4. Separate privapp-permissions XML needs wiring outside the 10-component set — MINOR
- **Expected (plan Option 2):** new file `/etc/permissions/privapp-permissions-locshield.xml`.
- **Actual:** New permission XML files require `PRODUCT_COPY_FILES` wiring (device/build config), which sits outside the frozen 10-component set.
- **Proposed resolution:** For the API-34 prototype, use **Option 1 (platform signature)** exclusively; defer Option 2 to Control-APK packaging phase.

## 3. Environment blockers (why no build was executed)

| Blocker | Fact |
|---|---|
| No AOSP workspace exists here | Only blobless inspection clones in `/tmp` were created; no `repo`-managed tree, no `frameworks/base` checkout to modify |
| Disk | 4.9 GB free; a buildable AOSP tree requires ~100 GB+ (full sync) and a `m services.core` build requires the complete lunch environment |
| No Soong/build environment | No `build/`, `prebuilts/`, or lunch config; module compilation unverifiable here |
| No `checkpolicy`/`secilc` | Not installed; full-policy compile additionally requires the whole tree, so even with the binary only textual verification (done above) is meaningful |
| No device/emulator target running this tree | Nothing to flash or `service check` against |

## 4. Changed-file traceability (Commit 1 scope → status)

| Planned file | Requirement → Doc 11 → plan § | Status |
|---|---|---|
| `frameworks/base/core/res/AndroidManifest.xml` (permission) | MANAGE_LOCATION_PRIVACY → Doc11 §3/§21 → v1.0.1 §7 | **NOT MODIFIED** (no tree) |
| `system/sepolicy/public/service.te` | service type → Doc11 §2 → v1.0.1 §8 | **NOT MODIFIED** |
| `system/sepolicy/private/service_contexts` | object label → Doc11 §2 → v1.0.1 §8 | **NOT MODIFIED** |
| `system/sepolicy/private/system_server.te` | add/find → Doc11 §2 → v1.0.1 §8 | **NOT MODIFIED** |
| `services/core/Android.bp` + prebuilt jar | build wiring → Doc11 §31 → v1.0.1 §10/26 | **NOT MODIFIED** (blocked by D1/D2) |
| `services/java/.../SystemServer.java` | lifecycle → Doc11 §2/ADR-013 → v1.0.1 §4 | **NOT MODIFIED** |
| New `com/android/server/locshield/*` skeleton | service skeleton + UID guard → Doc11 §2/§9 → v1.0.1 §4–6 | **NOT CREATED** (would be speculative without tree) |

## 5. Proposed resolution (exact steps in a provisioned workspace)

1. Provision a machine with ≥300 GB disk; `repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_r74 && repo sync -c --no-tags` (or the pinned manifest).
2. Create branch `feature/locshield-android14` (per plan §29).
3. Resolve D2 first: rebuild the engine jar with stdlib relocation (Gradle Shadow, no source change), re-run `jdeps`, attach output to the commit message.
4. Apply permission + SELinux + SystemServer + skeleton files exactly per plan §§4–8, using the `add_service` macro form (D3 note).
5. Attempt `java_import` wiring; on failure switch to the documented fallback and record it (D1).
6. Build narrowly: `m services.core` (or `m -j SystemServicesCore`), then full `m sepolicy` (validates checkpolicy), then boot `aosp_x86_64-userdebug` and run `service check location_privacy` + permission grant checks.
7. Commit only with green build + this report's traceability table updated to COMMITTED paths.

## 6. Final output items (per task §FINAL OUTPUT)

1. **Commit hash:** NONE — no commit created (no repository). Fabricating one would be a false record.
2. **Exact files changed:** NONE — see §4 table for the planned set and its NOT MODIFIED status.
3. **Build/test commands and results:** `m services.core` / `m sepolicy` NOT EXECUTED (no workspace; see §3). Substituted verification: r74 source-pattern checks (§1 table, all MATCH except noted) + `jdeps` stdlib proof (§1.8).
4. **SELinux verification:** Textual verification against r74 policy sources only (§1.5–1.7 + D3). `checkpolicy` compile NOT RUN (no binary/tree).
5. **Permission verification:** Declaration-point + `signature|privileged`-without-label precedent verified in r74 manifest (§1.1). `aapt`/`android.jar` compile NOT RUN (no tree).
6. **Service publication verification:** `SystemService.onStart/publishBinderService/publishLocalService` API + `SystemServer.startService(Lifecycle)` pattern verified present in r74 (§1.2–1.3). Runtime `service check` NOT RUN (no build/device).
7. **Policy Engine integration verification:** Frozen jar present and characterized (140 KB, 93 classes, `java.base` + kotlin-stdlib deps); Soong linkage UNVERIFIED pending D1/D2 resolution in a workspace.
8. **Diff statistics:** Empty — no working tree exists to diff.
9. **Discrepancies:** D1 (java_import zero-precedent), D2 (stdlib gap), D3 (redundant SELinux rules), D4 (privapp file wiring) — see §2.
10. **Commit 2+ confirmation:** No Commit 2+ work performed. No location interception, no GNSS/geofence/GMS logic, no temporal/transformation code, no Control APK. No frozen baseline was modified (Policy Engine, Documents 01–13, Matrix v1, Runtime Validation v1, v2.1.1, Doc 11 v1.1.1 all untouched).

---
*This report is the complete and honest record of the Commit 1 gate attempt. It intentionally contains no implementation artifacts.*
