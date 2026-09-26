package dev.klerkframework.klerk

import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.EventLogEntry
import dev.klerkframework.klerk.validation.Valid
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FunctionTest {

    @Test
    fun testingADslFunction() {
        val command = Command(
            CreateAuthor,
            CreateAuthorParams(
                firstName = FirstName("Mike"),
                lastName = LastName("Litoris"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234),
            ),

        )
        val args = VoidEventArgs(command, Ctx.system(), DummyReader)

        val result = onlyAuthenticationIdentityCanCreateDaniel(args)
        assertEquals(Valid, result)
    }
}

object DummyReader : Reader<Ctx, Views> {
    private val exception = UnsupportedOperationException("DummyReader is not meant to be used")
    override val views: Views
        get() = throw exception

    override val jobs: JobReader
        get() = throw exception

    override val attachedData: AttachedDataReader
        get() = throw exception

    override fun eventLog(id: ModelID<out Any>?, after: Instant, before: Instant): PendingRead<List<EventLogEntry>> =
        throw exception

    override fun eventLogEntry(sequenceNumber: Long): PendingRead<EventLogEntry?> = throw exception

    override fun <T : Any> get(id: ModelID<T>): Model<T> = throw exception

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? = throw exception

    override fun referencingIds(id: ModelID<*>): Set<ModelID<*>> = throw exception

    override fun <T : Any> referencing(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> = throw exception

    override fun <T : Any, U : Any> referencing(property: KProperty1<T, ModelID<U>?>, id: ModelID<*>): Set<Model<T>> =
        throw exception

    override fun <T : Any, U : Any> referencingInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>,
    ): Set<Model<T>> = throw exception

    override fun <T : Any> possibleVoidEvents(clazz: KClass<T>, visibility: EventVisibility): Set<VoidEvent<T, *>> =
        throw exception

    override fun <T : Any> possibleEvents(id: ModelID<T>, visibility: EventVisibility): Set<InstanceEvent<T, *>> =
        throw exception

    override fun isGenerallyPossible(eventRef: EventReference): Boolean = throw exception
}
