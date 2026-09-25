package dev.klerkframework.klerk

import dev.klerkframework.klerk.attacheddata.ContentTypeDetector
import dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector
import dev.klerkframework.klerk.job.JobSettings
import dev.klerkframework.klerk.misc.envBoolean
import dev.klerkframework.klerk.misc.envDuration
import dev.klerkframework.klerk.misc.makeExactSerializable
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.ModelCacheSettings
import dev.klerkframework.klerk.storage.Persistence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How *this instance* runs: where it stores its data, what it reads the time from, where it publishes metrics and how
 * hard the job dispatcher works. Passed to [Klerk.Companion.create] alongside the [Specification].
 *
 * Two deployments of the same application share a [Specification] and differ here. Nothing in this class changes what
 * the application does, only how it is operated — the one thing that does, whether the event log is erased on
 * deletion, is [Specification.eraseEventLogAfterModelDeletion].
 */
public data class KlerkSettings(

    /**
     * The storage backend this instance uses, e.g. [dev.klerkframework.klerk.storage.SqlPersistence] in production
     * and [dev.klerkframework.klerk.storage.RamStorage] in a test.
     */
    val persistence: Persistence,

    /**
     * Where the bytes of attached blobs are kept: [AttachedBlobStore.Database] or
     * [dev.klerkframework.klerk.storage.FileBlobStore]. Null means the application has no blobs.
     *
     * Required as soon as any model property or event parameter is an [AttachedBlobID] — the choice decides what a
     * database backup contains, so Klerk will not pick one for you. Attached *strings* are unaffected; they always
     * live in the database.
     *
     * Choose before the application has data: Klerk does not move blobs between stores, and refuses to start if the
     * configured store does not have the bytes it expects.
     */
    val attachedBlobStore: AttachedBlobStore? = null,

    /**
     * Where *background* work gets the current time from: job scheduling, retry backoff, cron, delay-based admission
     * and state-machine time triggers.
     *
     * Actor-driven work reads its time from the caller's [KlerkContext.time] instead and is unaffected by this. The
     * split is deliberate: a test can control actor-driven time simply by constructing a context, and this clock is
     * how it controls everything else. See [dev.klerkframework.klerk.misc.MutableClock].
     */
    val clock: Clock = Clock.System,

    /**
     * The [MeterRegistry] Klerk and its plugins publish metrics to. Defaults to a private [SimpleMeterRegistry] that
     * isn't exported anywhere, so set this to integrate with your application's metrics backend. The metrics are
     * listed in the Metrics documentation page.
     */
    val meterRegistry: MeterRegistry = SimpleMeterRegistry(),

    /** How the job module is operated: parallelism, polling, retention and retry backoff. */
    val jobs: JobSettings = JobSettings(),

    /**
     * How much model data is kept in memory.
     */
    val modelCache: ModelCacheSettings = ModelCacheSettings(),

    /**
     * Gates the "escape hatch" functions on [Klerk.unsafe] ([KlerkUnsafe.create], [KlerkUnsafe.update],
     * [KlerkUnsafe.delete]), which bypass the state machine, validation and authorization entirely. Off by
     * default; enable only if you understand the risk.
     */
    val allowUnsafeOperations: Boolean = false,

    /**
     * Whether [dev.klerkframework.klerk.datatypes.DataContainer.valueWithoutAuthorization] may be used on the models
     * returned by a read or a command result. When false (the default) it throws there, so that application code
     * cannot bypass the `readProperties` rules. Reads made by the system, and containers you create yourself, are not
     * affected.
     */
    val allowBypassAuthRead: Boolean = false,

    /**
     * The lease [KlerkAttachedData.prepare] grants when the caller does not ask for one: how long prepared data
     * survives before a command claims it. Mainly here so that tests do not have to wait a minute.
     */
    val defaultAttachedDataLease: Duration = 1.minutes,

    /**
     * The longest lease [KlerkAttachedData.prepare] will grant. A lease keeps storage occupied by data that no model
     * refers to, so there is an upper bound; who may ask for a long one is decided by the `writeAttachedData` rules.
     */
    val maxAttachedDataLease: Duration = 24.hours,

    /**
     * How Klerk recognises the content type of an attached value from its first bytes (see
     * [dev.klerkframework.klerk.attacheddata.ContentTypeDetector]). Defaults to
     * [dev.klerkframework.klerk.attacheddata.DefaultContentTypeDetector], a small dependency-free set of magic-byte
     * signatures; replace it to recognise more formats, e.g. with a detector backed by Apache Tika.
     */
    val contentTypeDetector: ContentTypeDetector = DefaultContentTypeDetector,
) {

    /**
     * The current time for background work, at the precision Klerk persists timestamps with (see
     * [makeExactSerializable]), so that a value read here survives a round-trip through storage unchanged.
     */
    internal fun now(): Instant = makeExactSerializable(clock.now())

    public companion object {

        /**
         * Builds a [KlerkSettings] from environment variables, falling back to the regular default for any variable
         * that is unset. Only the settings with simple (boolean/[Duration]) types are read this way — `KLERK_` plus
         * the property name in `SCREAMING_SNAKE_CASE`, e.g. [maxAttachedDataLease] from
         * `KLERK_MAX_ATTACHED_DATA_LEASE`. A [Duration] variable is parsed with [Duration.parse], so both `"24h"` and
         * `"PT24H"` work. [jobs] and [modelCache] default to [JobSettings.fromEnvVars] and
         * [ModelCacheSettings.fromEnvVars], so their settings are read from `KLERK_JOBS_`- and
         * `KLERK_MODEL_CACHE_`-prefixed variables unless passed explicitly.
         *
         * The remaining settings ([persistence], [attachedBlobStore], [clock], [meterRegistry],
         * [contentTypeDetector]) are not derived from the environment, since they carry objects rather than
         * primitive values; pass them as ordinary parameters.
         */
        public fun fromEnvVars(
            persistence: Persistence,
            attachedBlobStore: AttachedBlobStore? = null,
            clock: Clock = Clock.System,
            meterRegistry: MeterRegistry = SimpleMeterRegistry(),
            jobs: JobSettings = JobSettings.fromEnvVars(),
            modelCache: ModelCacheSettings = ModelCacheSettings.fromEnvVars(),
            contentTypeDetector: ContentTypeDetector = DefaultContentTypeDetector,
        ): KlerkSettings {
            val defaults = KlerkSettings(persistence = persistence)
            return KlerkSettings(
                persistence = persistence,
                attachedBlobStore = attachedBlobStore,
                clock = clock,
                meterRegistry = meterRegistry,
                jobs = jobs,
                modelCache = modelCache,
                allowUnsafeOperations = envBoolean("KLERK_ALLOW_UNSAFE_OPERATIONS") ?: defaults.allowUnsafeOperations,
                allowBypassAuthRead = envBoolean("KLERK_ALLOW_BYPASS_AUTH_READ") ?: defaults.allowBypassAuthRead,
                defaultAttachedDataLease = envDuration("KLERK_DEFAULT_ATTACHED_DATA_LEASE")
                    ?: defaults.defaultAttachedDataLease,
                maxAttachedDataLease = envDuration("KLERK_MAX_ATTACHED_DATA_LEASE") ?: defaults.maxAttachedDataLease,
                contentTypeDetector = contentTypeDetector,
            )
        }
    }
}
