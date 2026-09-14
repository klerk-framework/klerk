package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.JobReadRuleArgs
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.Specification
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.read.ReaderWithoutAuth

/**
 * Whether [context]'s actor may see [job], by the `jobs` rules in [specification].
 *
 * Deliberately neither suspending nor lock-taking, mirroring the model-side `isAuthorized`: the caller is expected to
 * hold the read lock already, which is what lets this run inside a read block. A rule that reads a model through
 * [reader] is only sound while that lock is held.
 */
internal fun <C : KlerkContext, V> isJobAuthorized(
    job: JobInfo,
    context: C,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>,
): Boolean {
    if (context.actor == SystemIdentity) {
        return true
    }
    val args = JobReadRuleArgs(job, context, reader)
    return specification.authorization.jobPositiveRules.any { it.invoke(args) == PositiveAuthorization.Allow } &&
            specification.authorization.jobNegativeRules.none { it.invoke(args) == NegativeAuthorization.Deny }
}
