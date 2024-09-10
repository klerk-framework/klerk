package dev.klerkframework.klerk.misc

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.read.Reader
import java.lang.reflect.Type


class EventParamsContainerTest {
    /*
    @Test
    fun toJsonAndBack() {



        val gson = GsonBuilder().registerTypeHierarchyAdapter(StringValue::class.java, StringValueSerializer()).create()

        val allDatatypesParams = AllDatatypesParams(aPhone = PhoneNumber("+46123456"), firstContainer = FirstContainer(pelle = FirstName("hej"), secondContainer = SecondContainer(johan = FirstName("då"))))

        val json = gson.toJson(allDatatypesParams)
        assertEquals("""{"aBoolean":true,"aLong":123,"aPhone":"+46123456","aString":"pelle","anInt":43}""", json)

      //  val container = EventParamsContainer(gson, params = allDatatypesParams)
     //   assertEquals("""{"aBoolean":true,"aLong":123,"aPhone":"+46123456","aString":"pelle","anInt":43}""", container.json)
        //val p = container.get<AllDatatypesParams>()
//        assertEquals(p.aPhone.valueWithoutAuthorization, "+46123456")
    }

  */
}

class MyStringValueSerializer : JsonSerializer<StringContainer> {
    override fun serialize(src: StringContainer?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.valueWithoutAuthorization)
    }
}

class AllDatatypesParams(
    val aPhone: PhoneNumber,
    val firstContainer: FirstContainer
)

data class FirstContainer(val pelle: FirstName, val secondContainer: SecondContainer)

data class SecondContainer(val johan: FirstName)
