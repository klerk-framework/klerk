package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.collection.QueryResponse
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.read.Reader
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionTest {

    @Test
    fun testingADslFunction() {
        val command = Command(
            CreateAuthor,
            null,
            CreateAuthorParams(
                firstName = FirstName("Mike"),
                lastName = LastName("Litoris"),
                phone = PhoneNumber("234"),
                age = PositiveEvenIntContainer(44),
                secretToken = SecretPasscode(234)))
        val args = ArgForVoidEvent(command, Context.system(), DummyReader)

        val result = onlyAuthenticationIdentityCanCreateDaniel(args)
        assertEquals(Validity.Valid, result)
    }

}

object DummyReader : Reader<Context, MyCollections> {
    private val exception = UnsupportedOperationException("DummyReader is not meant to be used")
    override val data: MyCollections
        get() = throw exception

    override fun <T : Any> get(id: ModelID<T>): Model<T> {
        throw exception
    }

    override fun <T : Any> getOrNull(id: ModelID<T>): Model<T>? {
        throw exception
    }

    override fun <T : Any> getIfAuthorizedOrNull(id: ModelID<T>): Model<T>? {
        throw exception
    }

    override fun getAllRelatedIds(id: ModelID<*>): Set<ModelID<*>> {
        throw exception
    }

    override fun <T : Any> getRelated(clazz: KClass<T>, id: ModelID<*>): Set<Model<T>> {
        throw exception
    }

    override fun <T : Any, U : Any> getRelated(property: KProperty1<T, ModelID<U>?>, id: ModelID<*>): Set<Model<T>> {
        throw exception
    }

    override fun <T : Any, U : Any> getRelatedInCollection(
        property: KProperty1<T, Collection<ModelID<U>>?>,
        id: ModelID<*>
    ): Set<Model<T>> {
        throw exception
    }

    override fun <T : Any> getPossibleVoidEvents(clazz: KClass<T>): Set<EventReference> {
        throw exception
    }

    override fun <T : Any> getPossibleEvents(id: ModelID<T>): Set<EventReference> {
        throw exception
    }

    override fun <T : Any> query(
        collection: ModelCollection<T, Context>,
        options: QueryOptions?,
        filter: ((Model<T>) -> Boolean)?
    ): QueryResponse<T> {
        throw exception
    }

    override fun <T : Any> list(
        modelCollection: ModelCollection<T, Context>,
        filter: ((Model<T>) -> Boolean)?
    ): List<Model<T>> {
        throw exception
    }

    override fun <T : Any> listIfAuthorized(collection: ModelCollection<T, Context>): List<Model<T>> {
        throw exception
    }

    override fun <T : Any> firstOrNull(
        collection: ModelCollection<T, Context>,
        filter: (Model<T>) -> Boolean
    ): Model<T>? {
        throw exception
    }

    override fun <T : Any> getFirstWhere(
        collection: ModelCollection<T, Context>,
        filter: (Model<T>) -> Boolean
    ): Model<T> {
        throw exception
    }
}