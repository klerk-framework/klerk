package dev.klerkframework.klerk.attacheddata

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.BlobRejected
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.impl
import dev.klerkframework.klerk.job.*
import kotlinx.serialization.Serializable

/** The name of the built-in job, registered in every configuration. An application cannot use it for its own type. */
internal const val PROCESS_ATTACHED_DATA: String = "klerk-process-attached-data"

/**
 * Which value is being processed, and what it was prepared for.
 *
 * The declaration is a class name rather than the container itself: a cursor is persisted, so it must survive a
 * restart, and the class is rebuilt from its name by [instantiateDeclaration]. Nothing records how far the pipeline
 * has got — the value's own `completedSteps` does that, which is what makes a yield, a retry and a restart all resume
 * the same way.
 */
@Serializable
internal data class ProcessBlobCursor(val blobId: Int, val declaration: String)

/**
 * Runs the steps a [dev.klerkframework.klerk.datatypes.AttachedBlobContainer] declares against a prepared blob.
 *
 * Scheduled by `prepare` when the destination declares any step, and by nothing else. One step of the job runs one
 * [dev.klerkframework.klerk.datatypes.BlobPreAttachStep], so an expensive pipeline checkpoints between its stages and a
 * failure retries only the stage that failed.
 */
internal class ProcessAttachedData<C : KlerkContext, V> : JobType.Local<ProcessBlobCursor, C, V>() {

    override val name: JobName = JobName(PROCESS_ATTACHED_DATA)
    override val agent: JobAgent = JobAgent.System

    override suspend fun step(args: JobStepArgs.Local<ProcessBlobCursor, C, V>): JobResult<ProcessBlobCursor> {
        val attachedData = args.klerk.impl().attachedDataImpl
        val declaration = instantiateDeclaration(args.cursor.declaration, AttachedBlobID(args.cursor.blobId))
        return try {
            when (val progress = attachedData.processNextStep(declaration)) {
                is BlobProcessing.More -> JobResult.Yield(
                    cursor = args.cursor,
                    progress = JobProgress(progress.completed, progress.total),
                    log = listOf(args.info("Executed '${progress.ran}'")),
                )

                is BlobProcessing.Done -> JobResult.Success(
                    progress = JobProgress(progress.total, progress.total),
                    log = listOf(args.info("The blob ${args.cursor.blobId} is through every step")),
                )
            }
        } catch (e: BlobRejected) {
            // A verdict, not a failure: retrying would reach the same conclusion, and the file must not be stored.
            val reason = e.message ?: "The file was rejected"
            attachedData.rejected(args.cursor.blobId, reason)
            JobResult.Abort(reason, log = listOf(args.warn(reason)))
        }
    }
}

/** What one step of the pipeline accomplished. */
internal sealed interface BlobProcessing {

    /** A step ran and more remain. [ran] is its name; [completed] of [total] steps are now done. */
    data class More(val ran: String, val completed: Int, val total: Int) : BlobProcessing

    /** Every declared step has run. */
    data class Done(val total: Int) : BlobProcessing
}
