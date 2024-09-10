package dev.klerkframework.webutils


import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import kotlinx.html.*
import java.security.SecureRandom
import kotlin.reflect.KType

internal val secureRandom = SecureRandom.getInstanceStrong()


internal fun <C:KlerkContext> lowCodeHtmlHead(config: LowCodeConfig<C>): HTML.() -> Unit = {
    head {
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        styleLink(config.cssPath)
    }
}


internal fun isModelId(type: KType): Boolean {
    return type.toString().startsWith(ModelID::class.qualifiedName!!, false)
}

internal fun navMenu(basePath: String, modelPathPart: String, humanName: String): BODY.() -> Unit = {
    nav {
        div {
            a(href = basePath) { +"Home" }
            span { +" / " }
            a(href = "$basePath/$modelPathPart") { +humanName }
        }
    }
}

private val allAllowed = "abcdefghijklmnopqrstuvwxyzABCDEFGJKLMNPRSTUVWXYZ0123456789".toCharArray()

internal fun generateRandomString(): String {
    val password = StringBuilder()
    for (i in 0 until 20) {
        password.append(allAllowed[secureRandom.nextInt(allAllowed.size)])
    }
    return password.toString()
}

public fun isDevelopmentMode(): Boolean {
    return System.getenv("DEVELOPMENT_MODE")?.lowercase() == "true" ||
            System.getProperty("DEVELOPMENT_MODE")?.lowercase() == "true"
}
