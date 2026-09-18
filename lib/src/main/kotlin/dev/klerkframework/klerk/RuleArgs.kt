package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.read.ModelReader
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What every rule is given: the [context] of the operation and a [reader] for looking up other models. Write a
 * helper against this to use it from rules of any kind.
 */
public interface RuleArgs<C : KlerkContext, V> {
    /** The context of the operation, including the actor. */
    public val context: C

    /** Reads other models, as of the operation. */
    public val reader: ModelReader<C, V>
}

/** What every rule and executable that acts on an existing model is given. */
public interface ModelArgs<T : Any, C : KlerkContext, V> : RuleArgs<C, V> {
    /** The model the operation acts on. */
    public val model: Model<T>
}

/**
 * Arguments handed to context-only rules, e.g. rules deciding void events not tied to a model instance.
 */
public data class EventLogRuleArgs<C : KlerkContext, V>(
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * Arguments handed to rules that need to inspect the [command] being processed (e.g. event authorization rules).
 */
public data class CommandRuleArgs<P, C : KlerkContext, V>(
    val command: Command<out Any, P>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * Arguments handed to rules that evaluate against an existing [model], e.g. read/authorization rules for instance
 * events.
 */
public data class ModelReadRuleArgs<C : KlerkContext, V>(
    val model: Model<out Any>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * Arguments handed to property-level authorization rules deciding whether [property] on [model] may be read.
 */
public data class PropertyReadRuleArgs<C : KlerkContext, V>(
    val property: DataContainer<*>,
    val model: Model<out Any>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * @property reader Note that the reader will give you access to the data as it was _before_ the current event was
 * executed. I.e. if the current event has modified data in a previous step, the updated data will __not__ be accessible
 * through the reader.
 */
public data class VoidEventArgs<T : Any, P, C : KlerkContext, V>(
    val command: Command<T, P>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * What a rule or executable of an instance event sees. [model] is the model as it is in the uncommitted state, i.e. it
 * may differ from the model as it was before the current processing (of an event or time-trigger).
 */
public data class InstanceEventArgs<T : Any, P, C : KlerkContext, V>(
    override val model: Model<T>,
    val command: Command<T, P>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : ModelArgs<T, C, V>

/**
 * What a rule or executable of a lifecycle block (enter, exit, time) sees. [model] is the model as it is in the
 * uncommitted state, i.e. it may differ from the model as it was before the current processing (of an event or
 * time-trigger).
 */
public data class LifecycleArgs<T : Any, C : KlerkContext, V>(
    override val model: Model<T>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : ModelArgs<T, C, V>

/**
 * The arguments given to the rules deciding who may read attached data (see [KlerkAttachedData.get]).
 *
 * Note that these rules are only consulted for [AttachedDataVisibility.Private] data — public data is readable by
 * anyone.
 *
 * @property owner the model that owns the data. A rule can use this to express model-relative policies (e.g. "the
 * actor may read the file if it belongs to a project the actor is a member of").
 */
public data class AttachedDataReadRuleArgs<C : KlerkContext, V>(
    val owner: Model<out Any>,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V>

/**
 * The arguments given to the rules deciding who may prepare attached data (see [KlerkAttachedData.prepare]).
 *
 * Note that there is no model at this point since the data has not been attached to anything yet. The meaningful
 * checks here are the actor in the [context], the [kind] and the [lease] — a rule can allow uploads in general but
 * restrict who may upload a blob as opposed to a string, or who may keep unclaimed data around for hours. The real
 * gate on *attaching* data to a model is the normal event authorization of the command that claims it.
 */
public data class AttachedDataWriteRuleArgs<C : KlerkContext, V>(
    val kind: AttachedDataKind,

    override val context: C,
    override val reader: ModelReader<C, V>,

    /**
     * How long the value may stay unclaimed. Long leases keep storage occupied by data no model refers to, so this is
     * the place to decide who may ask for one.
     */
    val lease: Duration,
) : RuleArgs<C, V>

/**
 * What `SpecificationBuilder.jobContextProvider` is given when a job step is about to run.
 *
 * @property actor `SystemIdentity` for a [dev.klerkframework.klerk.job.JobAgent.System] job, or the actor that
 * scheduled the job for a [dev.klerkframework.klerk.job.JobAgent.Scheduler] one. Note that the latter is rebuilt from
 * what was persisted, so an actor identified by a model arrives as a [ModelReferenceIdentity].
 * @property time the current time according to the configured clock. Use it as the context's time so that job steps,
 * and the event log entries of the commands they emit, follow the clock a test controls.
 */
public data class JobContextRequest(val actor: ActorIdentity, val time: Instant, val job: JobInfo)

/**
 * The arguments given to the rules deciding who may see a job's metadata (see [JobManager.get]).
 *
 * The same rules gate cancellation, so allowing an actor to watch a job also lets them stop it.
 *
 * @property job everything Klerk knows about the job, including who scheduled it — see [isOwnedBy] for the common
 * case of "may an actor see their own jobs".
 */
public data class JobReadRuleArgs<C : KlerkContext, V>(
    val job: JobInfo,
    override val context: C,
    override val reader: ModelReader<C, V>,
) : RuleArgs<C, V> {
    /** True if [actor] scheduled the job, compared with [ActorIdentity.isSameAs]. */
    public fun isOwnedBy(actor: ActorIdentity): Boolean = job.owner.isSameAs(actor)

    /** True if [context]'s own actor scheduled the job. */
    public fun isOwnedByActor(): Boolean = isOwnedBy(context.actor)
}
