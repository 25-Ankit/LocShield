# LocShield AOSP Host Environment (Phase 0 — observed 2026-09-29)

| Item | Observed | Required (AOSP 14 build) | Missing | Blocker | Action |
|---|---|---|---|---|---|
| OS distribution | Kali GNU/Linux Rolling 2026.3 | Any modern Linux x86_64 (Ubuntu 22.04+ recommended) | — | No | None |
| Kernel | 7.1.5+kali-amd64 | 5.x+ | — | No | None |
| Architecture | x86_64 | x86_64 | — | No | None |
| CPU model | Intel i5-8500 | 8+ cores recommended | — | No (marginal) | None (6 threads usable but slow) |
| CPU cores/threads | 6 | 16+ recommended | headroom | No | None |
| RAM total/avail | 7.6 GiB / ~4.2 GiB | 16 GiB min, 32+ recommended | ~12 GiB | **Yes (marginal)** | Provision bigger host before full builds |
| Disk free (/) | 5.0 GiB (4.94 GiB bytes: 5302112256) | ~250–400 GB practical (source+repo+outputs+caches+headroom) | ~245+ GB | **Yes — STORAGE BLOCKER** | Provision storage; no clone attempted |
| Filesystem | ext4 on /dev/sda7 | ext4, case-sensitive | — | No | None |
| System JDK | OpenJDK 25.0.4 | — | — | No | None (not used for AOSP) |
| Tooling JDK 21.0.12.1 | `/home/kali/.locshield-tooling/jdk21` (headless, javac+jdeps present) | — | — | No | None |
| JDK 17 (AOSP 14 required) | **Absent** (only 21/25 present) | OpenJDK 17 for android-14.0.0_r74 builds | JDK 17 | **Yes** | Install openjdk-17-jdk-headless on build host |
| `javac`/`jdeps` | Present via tooling JDK 21 | Present | — | No | None |
| git | 2.53.0 | Recent git | — | No | None |
| `repo` tool | **Not installed** | Required for manifest sync | repo | **Yes** | Install on build host |
| python3 | 3.14.6 | Python 3.9+ | — | No | None |
| ninja | **Not installed** (apt candidate 1.13.2-1) | Required by Soong | ninja | **Yes** | Install on build host |
| make/gcc/clang | Present (system) | Present | — | No | None (AOSP uses prebuilt clang anyway) |
| m4 | Present | Present | — | No | None |
| checkpolicy/secilc | **Not installed** (apt candidates 3.11-1) | Required for SELinux verification | Both | **Yes** | Install on build host (full-tree compile still required) |
| sudo | Password-required (non-interactive unavailable) | Needed for apt installs | — | **Yes (procedural)** | Manual package install on build host |
| Emulator/KVM | No `/dev/kvm`, no vmx/svm flags | KVM for practical emulator validation | KVM | **Yes (for later validation)** | Use KVM-capable host for emulator gates |

Notes:
- AOSP 14 (API 34) AOSP-build JDK requirement (OpenJDK 17) is a DOCUMENTED
  platform requirement (source.android.com build requirements), not host inference.
- No packages were installed during this phase (per "do not install large
  packages blindly"); missing items are recorded, not fetched, because the
  storage blocker makes a local workspace impossible regardless.
