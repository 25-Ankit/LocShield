package locshield.policy

import locshield.model.AppPolicy
import locshield.model.ApplicationSelector
import locshield.model.ReasonCode
import locshield.model.ValidationResult
import java.util.concurrent.atomic.AtomicReference

/**
 * Transaction outcome for policy commits. The generation increases ONLY on
 * successful commits (Doc 13 P8/P9); rejected commits leave state untouched.
 */
sealed interface TransactionResult {
    data class Committed(val generation: Long) : TransactionResult

    data class Rejected(val validation: ValidationResult.Rejected) : TransactionResult
}

/**
 * Read-only snapshot view consumed by the resolver. The hot evaluation path only
 * ever sees an immutable [Snapshot]; persistence lives in the future system
 * service (Doc 11 section 21: "Persistent storage is off the hot path").
 */
interface PolicySnapshotProvider {
    fun get(selector: ApplicationSelector): AppPolicy?

    fun getDefault(userId: Int): AppPolicy?

    /** Monotonic generation of the currently published snapshot (Doc 11 section 29). */
    fun getGeneration(userId: Int): Long
}

/**
 * In-memory transactional store. v0.1 test double for the future trusted
 * Policy Store (Doc 07 C03): single-writer-serialized commits, lock-free
 * atomic snapshot publication so concurrent readers only ever observe a complete
 * old or complete new snapshot (Doc 13 RES-023), never partial state.
 */
class InMemoryPolicyStore : PolicySnapshotProvider {
    private data class Snapshot(
        val policies: Map<ApplicationSelector, AppPolicy> = emptyMap(),
        val defaults: Map<Int, AppPolicy> = emptyMap(),
        val generation: Long = 0L,
    )

    private val state = AtomicReference(Snapshot())

    fun put(policy: AppPolicy): TransactionResult {
        val validation = PolicyValidator.validate(policy)
        if (validation is ValidationResult.Rejected) {
            return TransactionResult.Rejected(validation)
        }
        var committed = -1L
        state.updateAndGet { prev ->
            val next = prev.copy(
                policies = prev.policies + (ApplicationSelector(policy.packageName, policy.userId) to policy),
                generation = prev.generation + 1,
            )
            committed = next.generation
            next
        }
        return TransactionResult.Committed(committed)
    }

    fun putDefault(userId: Int, policy: AppPolicy): TransactionResult {
        if (policy.userId != userId) {
            return TransactionResult.Rejected(
                ValidationResult.Rejected(
                    reason = ReasonCode.CONTRADICTORY_FIELDS,
                    message = "Default policy userId must match target user",
                    field = "userId",
                ),
            )
        }
        val validation = PolicyValidator.validate(policy)
        if (validation is ValidationResult.Rejected) {
            return TransactionResult.Rejected(validation)
        }
        var committed = -1L
        state.updateAndGet { prev ->
            val next = prev.copy(
                defaults = prev.defaults + (userId to policy),
                generation = prev.generation + 1,
            )
            committed = next.generation
            next
        }
        return TransactionResult.Committed(committed)
    }

    fun delete(selector: ApplicationSelector): TransactionResult {
        var committed = -1L
        state.updateAndGet { prev ->
            val next = prev.copy(
                policies = prev.policies - selector,
                generation = prev.generation + 1,
            )
            committed = next.generation
            next
        }
        return TransactionResult.Committed(committed)
    }

    override fun get(selector: ApplicationSelector): AppPolicy? = state.get().policies[selector]

    override fun getDefault(userId: Int): AppPolicy? = state.get().defaults[userId]

    override fun getGeneration(userId: Int): Long = state.get().generation
}
