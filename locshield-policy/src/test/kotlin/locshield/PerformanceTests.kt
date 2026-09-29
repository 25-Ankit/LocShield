package locshield

import locshield.metadata.MetadataSanitizer
import locshield.model.MetadataPolicy
import locshield.model.RandomizationPolicy
import locshield.model.SpatialMode
import locshield.model.SpatialPolicy
import locshield.spatial.TransformOutcome
import locshield.spatial.TransformationEngine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Performance benchmarks (Doc 13 Table 19). Targets are engineering goals
 * (Doc 11 Table 12: sub-ms lookup, bounded transform, O(1) metadata/temporal);
 * results record the environment. Bounds here are generous to avoid flaky
 * failures while still catching algorithmic regressions (e.g. accidental
 * linear scans or unbounded growth).
 */
class PerformanceTests {
    private fun env(): String {
        val rt = Runtime.getRuntime()
        return "cpus=${rt.availableProcessors()} maxMemMb=${rt.maxMemory() / 1_048_576} " +
            "java=${System.getProperty("java.version")} env ci=${System.getenv("CI")}"
    }

    @Test
    fun `policy lookup plus evaluation averages well under one millisecond`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val id = Fixtures.identity()
        // Warmup.
        repeat(2_000) {
            rig.engine.resolveAndEvaluate(id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L)
        }
        val n = 20_000
        val start = System.nanoTime()
        repeat(n) {
            rig.engine.resolveAndEvaluate(id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L)
        }
        val avgNs = (System.nanoTime() - start).toDouble() / n
        println("lookup+evaluate avg=${"%.1f".format(avgNs)}ns over $n iterations [${env()}]")
        assertTrue(avgNs < 1_000_000.0, "avg=$avgNs ns must be sub-millisecond")
    }

    @Test
    fun `grid transformation is bounded`() {
        val spatial = SpatialPolicy(SpatialMode.GRID, gridMeters = 500.0)
        val ctx = Fixtures.transformCtx()
        val raw = Fixtures.sample()
        repeat(1_000) { TransformationEngine.transform(raw, spatial, RandomizationPolicy(false), ctx) }
        val n = 10_000
        val start = System.nanoTime()
        repeat(n) { TransformationEngine.transform(raw, spatial, RandomizationPolicy(false), ctx) }
        val avgNs = (System.nanoTime() - start).toDouble() / n
        println("grid transform avg=${"%.1f".format(avgNs)}ns over $n [${env()}]")
        assertTrue(avgNs < 1_000_000.0, "avg=$avgNs ns")
    }

    @Test
    fun `metadata sanitization is negligible relative to delivery`() {
        val spatial = SpatialPolicy(SpatialMode.CITY)
        val policy = Fixtures.strictMetadata()
        val input = Fixtures.sample()
        // Sanitize a transformed input (realistic pipeline shape).
        val transformed = (TransformationEngine.transform(
            input, spatial, RandomizationPolicy(false), Fixtures.transformCtx(),
        ) as TransformOutcome.Ok).location
        repeat(1_000) { MetadataSanitizer.sanitize(transformed, spatial, policy) }
        val n = 20_000
        val start = System.nanoTime()
        repeat(n) { MetadataSanitizer.sanitize(transformed, spatial, policy) }
        val avgNs = (System.nanoTime() - start).toDouble() / n
        println("metadata sanitize avg=${"%.1f".format(avgNs)}ns over $n [${env()}]")
        assertTrue(avgNs < 1_000_000.0, "avg=$avgNs ns")
    }

    @Test
    fun `temporal evaluation is O(1) and bounded`() {
        val c = locshield.temporal.TemporalController()
        val id = Fixtures.identity()
        val p = locshield.model.TemporalPolicy(locshield.model.TemporalMode.RATE_LIMIT, maxDeliveriesPerWindow = 100, windowMs = 60_000L)
        repeat(200) { i -> c.recordDelivery(id, p, 1L, i.toLong() * 1_000_000L) }
        val n = 50_000
        val start = System.nanoTime()
        repeat(n) { i -> c.evaluate(id, p, 1L, 1_000_000_000L + i) }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        println("temporal 50k evaluates total=${"%.1f".format(totalMs)}ms [${env()}]")
        assertTrue(totalMs < 5_000.0, "total=$totalMs ms")
    }

    @Test
    fun `thousand-policy snapshot has no linear hot-path scan`() {
        val store = locshield.policy.InMemoryPolicyStore()
        repeat(1_000) { i ->
            store.put(Fixtures.exactPolicy(pkg = "com.app.$i", user = i % 4))
        }
        val sel = locshield.model.ApplicationSelector("com.app.999", 3)
        repeat(1_000) { store.get(sel) }
        val n = 10_000
        val start = System.nanoTime()
        repeat(n) { store.get(sel) }
        val avgNs = (System.nanoTime() - start).toDouble() / n
        println("1000-policy lookup avg=${"%.1f".format(avgNs)}ns over $n [${env()}]")
        assertTrue(avgNs < 100_000.0, "avg=$avgNs ns (map lookup, must be micro-scale)")
    }

    @Test
    fun `concurrent evaluation shows no severe contention or errors`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (0 until 8).map { t ->
            Thread {
                try {
                    val id = Fixtures.identity(pkg = "com.concurrent.$t", uid = 30000 + t)
                    repeat(2_000) {
                        rig.engine.resolveAndEvaluate(
                            id, Fixtures.request(id), Fixtures.preciseCeiling(), Fixtures.locationCtx(), 2_000L,
                        )
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        val start = System.nanoTime()
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        println("concurrent 8x2000 total=${"%.1f".format(totalMs)}ms errors=${errors.get()} [${env()}]")
        assertTrue(errors.get() == 0)
        assertTrue(threads.none { it.isAlive }, "No deadlocks: all threads must finish")
    }

    @Test
    fun `full pipeline per-delivery cost is bounded`() {
        val rig = Fixtures.rig(Fixtures.cityPolicy())
        val id = Fixtures.identity()
        val ctx = Fixtures.transformCtx()
        repeat(500) { Pipeline.runDelivery(rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), 0L, ctx) }
        val n = 2_000
        val start = System.nanoTime()
        repeat(n) { i ->
            Pipeline.runDelivery(rig, id, Fixtures.preciseCeiling(), Fixtures.sample(), i.toLong(), ctx)
        }
        val avgNs = (System.nanoTime() - start).toDouble() / n
        println("full pipeline avg=${"%.1f".format(avgNs)}ns over $n [${env()}]")
        assertTrue(avgNs < 1_000_000.0, "avg=$avgNs ns")
    }
}
