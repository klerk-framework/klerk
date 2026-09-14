package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.KlerkContext

/**
 * A job that a command is in the middle of scheduling: it has an id, but nothing is committed until the command is.
 *
 * If the command fails, no job is scheduled — which is why an id is allocated during processing rather than after.
 */
public class PendingJob<C : KlerkContext, V> internal constructor(
    public val id: JobId,
    internal val scheduled: DeclaredJob<C, V>,
) {
    public val name: JobName get() = scheduled.name

    override fun toString(): String = "PendingJob($id, ${name.value})"
}

