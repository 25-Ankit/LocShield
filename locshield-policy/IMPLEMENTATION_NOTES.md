# LocShield Policy Engine v0.1 — Implementation Notes

Pure Kotlin/JVM core. Authority: Documents 11–13; Documents 01–10 are background
and Android-stage boundaries. No Android SDK dependency.

## 1. Terminology compatibility (Docs 01–10 vs Docs 11–13)

| Earlier document term | v0.1 implementation | Note |
|---|---|---|
| Doc 03 `ALLOW_TRANSFORM` | `Decision.TRANSFORM` | Same meaning; Doc 11/12 name adopted |
| Doc 03 `ALLOW_WITH_TEMPORAL_RESTRICTION` | `Decision.THROTTLE` | Same meaning |
| Doc 11 `ERROR_FAIL_CLOSED` | `Decision.FAIL_CLOSED` | Same meaning; Doc 12 name adopted |
| Doc 01/07 `REGION`, Doc 10 `AREA` | not separate modes | Reserved aliases at CITY-scale; only `CITY` is implemented in v0.1 |
| Doc 07 `NOISE` | `SpatialMode.RANDOMIZED` | Same mechanism |
| Doc 10 candidate `PolicyDecision{action, spatialLevel, radiusMeters, …}` | `PolicyDecision{decision, spatialMode, temporalAction, metadataAction, reasonCode, policyVersion, generatedAtElapsedRealtimeNanos, appliedSpatial, appliedTemporal}` | Doc 11 §13 shape is frozen; Doc 10 fields were explicitly "candidate, freeze after review". `appliedSpatial/appliedTemporal` are additive: they carry the background-RESTRICT-adjusted policies the adapter MUST enforce with (Sec. 4 below) |
| Doc 07 `PolicyEngine.evaluate(app, policy, context, source)` | `PolicyEngine.evaluate(identity, request, effective, location, now)` + `resolveAndEvaluate(...)` | Same inputs, split into resolve/evaluate per Doc 11 §14 |
| Doc 11 `getGeneration(userId)` | global monotonic generation returned for any user | v0.1 single-snapshot simplification, documented here |
| Doc 11 `preview()` Binder API | NOT in pure core | Control-plane API; core has no live location to preview (would risk an oracle) |

## 2. Prototype implementation choices (sanctioned by Doc 12 Table 14)

1. **Bounds**: radius/grid ∈ [1 m, 100 km]; intervals/windows ∈ [0, 7 days];
   `RATE_LIMIT` max ∈ [1, 100 000]. Out-of-range input is rejected, never clamped.
2. **Approximate-only ceiling over EXACT** (RES-002) degrades to GRID 2000 m.
3. **Background RESTRICT** = restrictive intersection with GRID 1000 m +
   MIN_INTERVAL 15 min (characteristic-scale ordering in `Restriction`).
4. **RADIUS semantics**: deterministic metric-grid snap of side R, cell center
   representative; worst-case error R·√2/2 < R (bounded-uncertainty invariant).
5. **GRID projection**: equirectangular meters anchored at the point's own
   latitude (globally stable, ~metric); cos(lat) floored at cos(89.9°) near poles.
6. **CITY**: offline fixture table (8 cities); nearest within 50 km wins and
   returns the city center + 5 km accuracy; otherwise deterministic 20 km grid
   fallback with no city attribution. No network geocoding. A pinned `cityId`
   not present in the table fails closed.
7. **RANDOMIZED**: uniform-disc perturbation within R; seed from identity +
   period/session only (never raw coordinates); FNV-1a seed derivation.
8. **Metadata**: accuracy floor from spatial mode always wins over RETAIN;
   timestamps bucketed to 1 min; speed/5 m·s⁻¹, bearing/45°, altitude/50 m when
   COARSE; v0.1 location-bearing extras allowlist is empty (COARSE strips).
9. **Temporal boundaries**: MIN_INTERVAL allows at `now - last >= m` (inclusive);
   PERIODIC anchors `nextAllowed = delivery + p` on first recorded delivery;
   RATE_LIMIT uses a sliding window; ONE_SHOT is consumed per policy generation;
   generation change resets all per-subject temporal state.
10. **Expiration** compares wall-clock `expiresAtWallMs`; intervals use monotonic
    elapsed nanos exclusively. Expired policies fall back to a DENY-preserving
    policy (never broader).
11. **Overflow** in time math saturates and resolves to SUPPRESS/FAIL_CLOSED.

## 3. Non-goals restated (enforced by package structure + tests)

No Binder/AIDL, persistence, location acquisition, GMS/GNSS/Wi-Fi/BT/cell
handling, UI, network, or logging framework. `AuditEvent` is a value type only.
GNSS measurements/NMEA/navigation messages are separate capability paths and are
asserted as NOT covered by location decisions (`GnssBoundaryTests`).

## 4. Decision-carried enforcement policy (additive, security-driven)

Background RESTRICT derivation happens inside `PolicyEngine.evaluate`, but the
downstream transform/sanitize/record steps run outside the engine (in the
adapter). If the adapter re-read the stored policy it would silently enforce the
unrestricted variant. Therefore every `PolicyDecision` carries
`appliedSpatial`/`appliedTemporal` — the exact policies to transform with, to
derive the metadata floor from, and to record temporal state against. The
pipeline test `background RESTRICT degrades EXACT to coarse grid delivery` guards
this: it caught the gap during implementation.
