package dev.klerkframework.klerk.job

import kotlinx.serialization.json.Json

/**
 * The JSON format job cursors are persisted in.
 *
 * - `encodeDefaults` is on so that every field is written out explicitly. A cursor is a persisted schema, and a value
 *   that was defaulted at write time must not silently pick up a *different* default after a deploy.
 * - `ignoreUnknownKeys` is on so that rolling back a release that added a cursor field does not strand every job that
 *   has already checkpointed with it. Removing a field a job still needs is still caught, because a missing required
 *   field is an error.
 */
internal val cursorJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
