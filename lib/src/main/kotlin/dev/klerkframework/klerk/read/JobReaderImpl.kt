package dev.klerkframework.klerk.read

import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.JobReader
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.impl
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.job.isJobAuthorized
import dev.klerkframework.klerk.job.jobAuthorizationFailure

/**
 * The `jobs` accessor of a [ReaderWithAuth]: the same rules `klerk.jobs.get` applies, but evaluated without
 * taking the read lock, since the surrounding read block already holds it.
 */
internal class AuthorizingJobReader<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val context: C,
    private val withoutAuth: ReaderWithoutAuth<C, V>,
) : JobReader {

    private var finished = false

    override fun get(id: JobID): JobInfo {
        val job = raw(id) ?: throw NoSuchElementException("There is no job with id $id")
        jobAuthorizationFailure(job, context, klerk.specification, withoutAuth)
            ?.let { throw AuthorizationException(it, "Not allowed to see job $id") }
        return job
    }

    override fun getOrNull(id: JobID): JobInfo? = raw(id)?.takeIf { authorized(it) }

    override fun all(): List<JobInfo> = allRaw().filter { authorized(it) }

    /** Mirrors `ReaderWithAuth.finishRead`: a reader smuggled out of its block must not keep reading. */
    fun finish() {
        finished = true
    }

    private fun raw(id: JobID): JobInfo? {
        checkUsable()
        return klerk.impl().jobs.jobInfoOrNull(id)
    }

    private fun allRaw(): List<JobInfo> {
        checkUsable()
        return klerk.impl().jobs.allJobInfo()
    }

    private fun authorized(job: JobInfo): Boolean = isJobAuthorized(job, context, klerk.specification, withoutAuth)

    private fun checkUsable() {
        check(!finished) { "The reader cannot be used after its read has finished" }
    }
}

/**
 * The `jobs` accessor of a [ReaderWithoutAuth]: everything, unfiltered, matching how that reader treats models.
 */
internal class UnauthorizedJobReader<C : KlerkContext, V>(private val klerk: Klerk<C, V>) : JobReader {

    override fun get(id: JobID): JobInfo = getOrNull(id) ?: throw NoSuchElementException("There is no job with id $id")

    override fun getOrNull(id: JobID): JobInfo? = klerk.impl().jobs.jobInfoOrNull(id)

    override fun all(): List<JobInfo> = klerk.impl().jobs.allJobInfo()
}
