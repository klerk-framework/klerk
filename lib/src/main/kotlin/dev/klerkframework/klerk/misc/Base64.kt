package dev.klerkframework.klerk.misc

import dev.klerkframework.klerk.*
import java.util.*

// why does Ktor have its own implementation of these?
internal fun String.encodeBase64(): String = Base64.getEncoder().encodeToString(this.toByteArray())

internal fun String.decodeBase64String(): String = String(Base64.getDecoder().decode(this))

/** Base64 without `+`, `/` or `=`, so the result is safe to put in a URL without escaping. */
internal fun String.encodeBase64UrlSafe(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this.toByteArray())

/** @throws IllegalArgumentException if this is not valid URL-safe base64 */
internal fun String.decodeBase64UrlSafeString(): String = String(Base64.getUrlDecoder().decode(this))
