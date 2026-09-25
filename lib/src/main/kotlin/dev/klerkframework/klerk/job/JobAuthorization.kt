package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.JobReadRuleArgs
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
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
): Boolean = jobAuthorizationFailure(job, context, specification, reader) == null

/** Like [isJobAuthorized], but tells why the actor may not see [job], or null if it may. */
internal fun <C : KlerkContext, V> jobAuthorizationFailure(
    job: JobInfo,
    context: C,
    specification: Specification<C, V>,
    reader: ReaderWithoutAuth<C, V>,
): KlerkErrorCode? {
    if (context.actor == SystemIdentity) {
        return null
    }
    val args = JobReadRuleArgs(job, context, reader)
    if (specification.authorization.jobNegativeRules.any { it.invoke(args) == NegativeAuthorization.Deny }) {
        return KlerkErrorCode.JobReadNegativeAuthorizationExist
    }
    if (specification.authorization.jobPositiveRules.none { it.invoke(args) == PositiveAuthorization.Allow }) {
        return KlerkErrorCode.JobReadPositiveAuthorizationMissing
    }
    return null
}
