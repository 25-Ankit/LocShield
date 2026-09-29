# LocShield AOSP Commit-1 Readiness Report v1

- **Date:** 2026-09-29
- **Task:** AOSP Build Environment & Commit-1 Readiness (prerequisite only — no implementation)
- **Authoritative plan:** Task-phase specification Phases 0–15 (the referenced
  `LocShield_AOSP_Build_Environment_and_Commit1_Readiness_Plan_v1.docx` was
  **not present** in the repository or workspace; the embedded phase
  specification in the task directive was used as operative authority and this
  absence is recorded here rather than assumed away.)
- **Frozen references (all untouched):** Documents 01–13, Policy Engine v0.1,
  Android API & Enforcement Matrix v1, Runtime/AOSP gate findings
  (`AOSP_Commit1_Gate_Report.md`).

---

## 1. Executive summary

[OBSERVED] This host cannot host a buildable Android 14 AOSP workspace:
**5.0 GiB free** versus ~250–400 GB practical requirement — a **STORAGE BLOCKER**
that is decisive on its own. Independent blockers compound it: no JDK 17, no
`repo` tool, no ninja, no checkpolicy/secilc, no KVM, 7.6 GiB RAM, and no
passwordless sudo for provisioning. No AOSP tree was cloned (per the explicit
rule against partial giant clones), no baseline was built, and no D1–D4 gate
can honestly exceed BLOCKED, since every one of them requires build evidence
by the task's own PASS standard. **Verdict: COMMIT 1 BLOCKED** (see §21).

## 2. Host environment

[OBSERVED] Full table in `HOST_ENVIRONMENT.md` (Phase 0 output, same
directory). Summary: Kali Rolling 2026.3, kernel 7.1.5+kali-amd64, x86_64,
i5-8500 (6 threads), 7.6 GiB RAM (~4.2 avail), ext4. JDKs present: system
OpenJDK 25.0.4 and tooling JDK 21.0.12.1 (with javac+jdeps). git 2.53.0,
python 3.14.6, make/gcc/clang/m4 present. Missing: JDK 17, `repo`, ninja,
checkpolicy, secilc, KVM (`/dev/kvm` absent, no vmx/svm flags), sudo
non-interactive unavailable.

## 3. Storage assessment

[OBSERVED] `avail_bytes=5302112256` (~4.94 GiB) on `/` (45 GB total, 89% used).
[DOCUMENTED] A practical AOSP workspace (manifest sync + `frameworks/base` +
`build/` + `prebuilts/` + Soong outputs for `services.core` + SELinux
intermediates + headroom) requires on the order of **250–400 GB**; even a
minimal manifest checkout alone exceeds this host by an order of magnitude.
Remaining safety margin if attempted: **negative (~−245 GB)**.
**Decision: STORAGE BLOCKER.** No clone initiated. No partial sync performed.

## 4. AOSP source revision

[OBSERVED] Verified live via `git ls-remote` (metadata only, zero bytes cloned):
- `platform/frameworks/base` @ `android-14.0.0_r74` = `5fc1588069a3e8a3ed5b8528f2396a91b2e3ac2d`
- `platform/system/sepolicy` @ `android-14.0.0_r74` = `3be68133049f2b01420592c78a79a2fc50901a7f`
- `platform/build/soong` @ `android-14.0.0_r74` = `02206b3ab5ea09ab5589f7ee137deb192d461681`
- `platform/build` @ `android-14.0.0_r74` = `82c0cb1146616b08d8af556c4116ff2e86c09dbf`
- `platform/manifest` tag query returned no ref via this method → manifest
  revision recorded as UNVERIFIED (to be pinned at sync time on the build host).
These SHAs exactly match the prior gate report. No substitution of android-15,
android-16, or main was made or is proposed.

## 5. Repository state

[OBSERVED] No AOSP workspace exists on this host; there is nothing whose
`git status` could be clean or dirty. Prior read-only inspection clones lived
in `/tmp` and were removed. `~/LOCSHIELD/` is not a git repository (unchanged).
Baseline snapshot: not applicable — **no tree to snapshot**. Any future
workspace must be created at `~/android/aosp-android14/` (separate from
`~/LOCSHIELD/`), synced to the SHAs in §4, with clean-tree evidence captured
before any modification.

## 6. Toolchain versions

[OBSERVED] git 2.53.0, python 3.14.6, make/gcc/clang present, m4 present.
[OBSERVED] Missing: `repo`, ninja (apt candidate 1.13.2-1 exists), checkpolicy
and secilc (apt candidates 3.11-1 exist). No AOSP prebuilt clang can be assessed
without the tree (`prebuilts/clang` ships with the sync).

## 7. JDK verification

[OBSERVED] Host JDKs: 25.0.4 (system) and 21.0.12.1 (tooling). JDK 17 absent.
[DOCUMENTED] AOSP android-14.0.0_r74 builds require OpenJDK 17 per platform
build requirements. Neither present JDK satisfies this. Gate BLOCKED on this
ground alone, independent of storage.

## 8. Build environment verification

[OBSERVED] `build/envsetup.sh` does not exist here (no tree), so no environment
was initialized. Nothing to verify; nothing claimed.

## 9. Lunch target

[OBSERVED] No lunch executed. Intended target for the provisioned host remains
`aosp_x86_64-userdebug` (emulator prototype path; GMS-less AOSP image), with
`aosp_arm64-userdebug` reserved for later physical-device validation. This is a
plan statement, not an observed configuration.

## 10. Baseline build

[OBSERVED] No build command was run — no tree, no lunch, no Soong. The narrowest
legitimate target (`services.core` / `SystemServicesCore`) remains unproven
here. Nothing in this report upgrades the prior source-inspection findings
(patterns observed in r74 files) into build evidence.

## 11. Baseline SELinux build

[OBSERVED] No policy build run: checkpolicy/secilc absent and the full
`system/sepolicy` build context requires the workspace. `m4` presence alone
proves nothing about policy compilation. Prior textual verification of
`add_service` macro, `service_manager_type` attributes, and per-service `find`
rules stands as DOCUMENTED source evidence only.

## 12. D1 java_import — BLOCKED

Prior finding carried forward as DOCUMENTED (not build evidence): repo-wide
`git grep` over all 825 `Android.bp` files in frameworks/base @ r74 returned
zero `java_import` usages (control query verified the method), while
`services/Android.bp` documents jarjar-relocation as the in-tree Kotlin
convention. Whether Soong accepts the frozen `java_import` wiring for
`services.core` is therefore **unproven**. The mandated temporary build-only
experiment was not created (no tree; creating one without the ability to
compile it would be theater). Status: **BLOCKED** — verdict on VERIFIED versus
alternative packaging is explicitly deferred to the provisioned host.

## 13. D2 Kotlin linkage — BLOCKED

Prior finding carried forward as DOCUMENTED: `jdeps` on the frozen artifact
proves runtime references to `kotlin/*` beyond `java.base`; no `kotlin-stdlib`
module is referenced by any frameworks/base `Android.bp` @ r74. The
shadow/relocate-vs-link decision cannot be proven without compiling the
candidate wiring. Status: **BLOCKED**. Policy Engine sources remain untouched;
this is purely a packaging/linkage question.

## 14. D3 SELinux rules — BLOCKED

Prior finding carried forward as DOCUMENTED: the `add_service(system_server,
system_server_service)` macro (te_macros:704) plus the `system_server_service`
and `app_api_service` attributes already cover the two explicit rules the
frozen plan drafts, making them redundant-but-harmless; exact minimal rule set
still requires a compiling policy tree to confirm zero-duplication and zero
neverallow conflict. Status: **BLOCKED**. No rules were added anywhere.

## 15. D4 permissions — BLOCKED

Prior finding carried forward as DOCUMENTED: `LOCATION_BYPASS` precedent
confirms `signature|privileged`-without-label declaration form, and
`data/etc/privapp-permissions-platform.xml` confirms the allowlist mechanism
for the priv-app route. Whether the platform build accepts the new permission
declaration and grants it to a platform-signed ControlApp requires the tree
and a booting image. Status: **BLOCKED**.

## 16. Policy Engine artifact verification — PASS (non-AOSP scope)

[OBSERVED] The frozen artifact was re-verified without modification:
- Path: `locshield-policy/build/libs/locshield-policy-0.1.0.jar`
- Size: 141,124 bytes, timestamp 2026-09-28 (untouched)
- SHA-256: `da30d9c59f6fba5cacca33e637a6d95a02fa4b5d534a30d6db43c97feff27032`
- `jdeps -summary`: `-> java.base` + `-> not found` (the not-found edge is the
  kotlin-stdlib runtime dependency, consistent with D2)
- 188/188 engine tests last reported passing; no rebuild was triggered by this
  phase and none was needed for hash verification.
This gate PASSES because it depends only on the frozen artifact, not on AOSP.
Its linkage into Soong remains covered by D2 (BLOCKED).

## 17. Integration dry-run — BLOCKED

[OBSERVED] No dry run attempted: without a compiling tree, temporary build
files could not be tested, so none were created (nothing to revert; tree
remains conceptually clean). The dry-run questions (artifact consumability,
`services.core` visibility, stdlib satisfaction, module-structure acceptance)
are all deferred to the provisioned host.

## 18. Blockers (consolidated)

1. **STORAGE BLOCKER (decisive):** 5.0 GiB free vs ~250–400 GB required.
2. JDK 17 absent (AOSP 14 requirement).
3. `repo`, ninja, checkpolicy/secilc absent; no passwordless sudo to provision.
4. RAM 7.6 GiB (marginal for Soong + emulator).
5. No KVM (blocks later emulator validation, not the build itself).
6. Referenced readiness-plan .docx absent from repo (used embedded phase spec).

## 19. Resolved assumptions

- Exact r74 SHAs for frameworks/base, system/sepolicy, build/soong, build:
  recorded in §4 (no substitution).
- Intended workspace layout (`~/android/aosp-android14/` vs `~/LOCSHIELD/`)
  and lunch target: decided, pending hardware.
- Prior D1–D4 source-inspection findings: preserved as DOCUMENTED evidence;
  none promoted to build PASS.

## 20. Remaining unknowns

- Soong acceptance of the frozen `java_import` wiring (D1 experiment).
- stdlib packaging choice and its `services.core` size/latency impact (D2).
- Minimal exact SELinux rule set after compile (D3).
- New-permission acceptance/grant behavior on a booting image (D4).
- Manifest revision pinning at sync time (§4).

## 21. Commit-1 readiness decision

| Gate | Status | Evidence | Blocker |
|---|---|---|---|
| Storage | BLOCKED | [OBSERVED] 5.0 GiB free vs ~250–400 GB needed | STORAGE BLOCKER |
| AOSP source | BLOCKED | [OBSERVED] SHAs verified (§4); no tree synced | Storage |
| JDK | BLOCKED | [OBSERVED] 21/25 present, 17 absent; [DOCUMENTED] 17 required | JDK 17 + storage |
| Soong | BLOCKED | [OBSERVED] no tree/envsetup | Storage |
| lunch | BLOCKED | [OBSERVED] not executed | Storage |
| Baseline build | BLOCKED | [OBSERVED] no build run | Storage |
| SELinux | BLOCKED | [OBSERVED] no tooling/tree; textual patterns only | Storage + checkpolicy/secilc |
| D1 java_import | BLOCKED | [DOCUMENTED] zero in-tree precedent; no compile proof | Storage + build env |
| D2 Kotlin linkage | BLOCKED | [DOCUMENTED] stdlib edge proven; wiring unproven | Storage + build env |
| D3 SELinux rules | BLOCKED | [DOCUMENTED] macro coverage analyzed; compile unproven | Storage + build env |
| D4 permissions | BLOCKED | [DOCUMENTED] declaration precedent verified; grant unproven | Storage + build env |
| Policy Engine artifact | PASS | [OBSERVED] hash + jdeps (§16) | None |
| Integration dry-run | BLOCKED | [OBSERVED] not attempted (no tree) | Storage |

Authorization rule applied: source tree, toolchain, baseline build, SELinux
build, D1–D4, and linkage are not PASS — therefore Commit 1 is **not
authorized**. No implementation was performed; no frozen baseline was touched;
no temporary files remain in any tree.

**COMMIT 1 BLOCKED**
