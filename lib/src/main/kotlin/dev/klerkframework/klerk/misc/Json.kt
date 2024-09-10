package dev.klerkframework.klerk.misc

import com.google.gson.*
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.decode64bitMicroseconds
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration.Companion.microseconds

internal fun <V, C: KlerkContext> createGson(config: Config<C, V>): Gson {

    val valueClasses = config.managedModels
        .flatMap { it.stateMachine.getAllEvents() }
        .flatMap { config.getParameters(it)?.all ?: emptyList() }
        .flatMap { extractValueClasses(it.valueClass) }
        .toMutableSet()

    val fromModels = config.managedModels.map { it.kClass }.flatMap { extractValueClasses(it) }
    valueClasses.addAll(fromModels)

    return GsonBuilder()
        .serializeNulls()
        .registerTypeHierarchyAdapter(StringContainer::class.java, StringValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(IntContainer::class.java, IntValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(LongContainer::class.java, LongValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(FloatContainer::class.java, FloatValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(BooleanContainer::class.java, BooleanValueSerializer(valueClasses))
        //.registerTypeHierarchyAdapter(EnumContainer::class.java, EnumValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(InstantContainer::class.java, InstantValueSerializer(valueClasses))
        .registerTypeHierarchyAdapter(DurationContainer::class.java, DurationValueSerializer(valueClasses))
        .create()
}

internal class StringValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<StringContainer>,
    JsonDeserializer<StringContainer> {
    override fun serialize(
        src: StringContainer?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): StringContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.firstOrNull { it.qualifiedName == typeOfT.typeName } ?: throw IllegalStateException("Could not find string container for $typeOfT")
        return paramClass.primaryConstructor!!.call(json.asString) as StringContainer
    }
}

internal class IntValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<IntContainer>,
    JsonDeserializer<IntContainer> {
    override fun serialize(src: IntContainer?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): IntContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asInt) as IntContainer
    }
}

internal class LongValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<LongContainer>,
    JsonDeserializer<LongContainer> {
    override fun serialize(src: LongContainer?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): LongContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asLong) as LongContainer
    }
}

internal class ULongValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<ULongContainer>,
    JsonDeserializer<ULongContainer> {
    override fun serialize(src: ULongContainer?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization?.toLong())
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): ULongContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asLong.toULong()) as ULongContainer
    }
}

internal class FloatValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<FloatContainer>,
    JsonDeserializer<FloatContainer> {
    override fun serialize(
        src: FloatContainer?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): FloatContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asFloat) as FloatContainer
    }
}

internal class BooleanValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<BooleanContainer>,
    JsonDeserializer<BooleanContainer> {
    override fun serialize(
        src: BooleanContainer?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): BooleanContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asBoolean) as BooleanContainer
    }
}

internal class InstantValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<InstantContainer>,
    JsonDeserializer<InstantContainer> {
    override fun serialize(
        src: InstantContainer?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): InstantContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(decode64bitMicroseconds(json.asLong)) as InstantContainer
    }
}

internal class DurationValueSerializer(private val valueClasses: Set<KClass<*>>) : JsonSerializer<DurationContainer>,
    JsonDeserializer<DurationContainer> {
    override fun serialize(
        src: DurationContainer?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): DurationContainer {
        requireNotNull(json)
        requireNotNull(typeOfT)
        val paramClass = valueClasses.first { it.qualifiedName == typeOfT.typeName }
        return paramClass.primaryConstructor!!.call(json.asLong.microseconds) as DurationContainer
    }
}
