# Attached data

Models should be kept small so they fit in the internal cache. Klerk lets you attach large immutable data to a model
instead of storing it in the model itself. There are two kinds: strings (e.g. JSON) and blobs (images, videos, PDFs).

The model holds a reference to the data in a property of type `LargeStringID` or `LargeBlobID`, similar to how a model
holds a reference to another model via `ModelID`.

```kotlin
data class Author(val name: Name, val portrait: LargeBlobID?)
```

Attached data is **immutable** — you never update a value in place, you attach a new one and drop the reference to the
old one. It is also **exclusively owned**: a given `LargeBlobID` belongs to exactly one model.

## Writing

Updating a model must be quick, but uploading an image may take a while. To avoid blocking command processing, writing
is done in two steps:

1. insert the data into Klerk (can be slow)
2. update the model with the ID of the data (fast)

```kotlin
val blobID: LargeBlobID = klerk.largeData.prepare(inputStream, context, authKey)
// use blobID in a Command that creates or updates the model
```

`prepare` does not need to know which model the data will belong to, so it works for events that *create* a model just
as well as for events that update one. Ownership is recorded when the command commits.

If you call `prepare` but no committed command references the ID within **1 minute**, the data is deleted. A later
attempt to use that ID fails the command.

### Attaching to a model that already has data

Assigning a new ID to a property that already held one deletes the old data when the command commits:

```kotlin
// old portrait is deleted, new one takes its place
klerk.handle(Command(UpdateAuthorPortrait, authorID, UpdatePortraitParams(newBlobID)), context, options)
```

## Reading

Use `klerk.largeData.get(id, context)`. If you don't have the ID, read it from the model first:

```kotlin
// find the blob ID of the author's portrait. Keep the read lock short.
val blobID: LargeBlobID = klerk.read(context) {
    get(authorID).props.portrait
}

// then fetch the data, outside the read block
val image: InputStream = klerk.largeData.get(blobID, context)
```

`get` **must be called outside a read block**. Calling it inside `klerk.read { }` or `klerk.readSuspend { }` throws —
attached data is often large, and holding the read lock while streaming it would block every command and every read in
the application. `get` acquires the lock briefly on its own to make the authorization decision, releases it, and then
returns the stream.

`get` throws `NoSuchElementException` if there is no data for that ID. Data that has been prepared but not yet claimed
by a command is not readable either — attached data is always read through the model that owns it, and until a command
commits there is no owner (and so nothing for the authorization rule to decide on).

## Deleting

You never delete attached data directly. It is deleted when the last reference to it goes away:

* the owning model is deleted, or
* the reference is set to `null` (only possible if the property is nullable), or
* the reference is replaced by another ID.

Deletion happens in the same transaction as the command, so a command that fails leaves the data intact.

## Ownership

A `LargeBlobID` or `LargeStringID` belongs to the first model that references it in a committed command. A command that
tries to attach data already owned by *another* model is rejected.

This means data cannot be shared or moved between models. To give a second model the same content, upload it again with
a second `prepare`.

The owner is the *model*, not the property, so the same ID may appear in two properties of the same model. Klerk only
deletes the data once no property of that model refers to it any more.

## Authorization

You declare the rules for reading and writing attached data in the config, as usual — in the `readLargeData` and
`writeLargeData` blocks of `authorization` (see [authorization](authorization.md)). The rules receive a `Reader`, so
they can look up whatever they need — including the actor from the context and, when reading, the model that owns the
data.

Because the owning model is reachable from the rule, model-relative policies work and stay correct over time:

```kotlin
fun onlyProjectMembersCanReadAttachments(args: ArgsForLargeDataRead<Ctx, Views>): PositiveAuthorization { ... }
```

At `prepare` time there is no model yet — the data has not been attached to anything — so a write rule can only see the
context and the `authKey`.

### authKey

`prepare` takes an optional `authKey`. It is stored with the data and handed to the authorization rules. You can use it
any way you want, but here are some examples:

* Set it to `"public"`, and write a rule that allows reads when the authKey is `"public"`.
* Set it to `"user:123456"`, and write a rule that allows reads when it matches the logged-in user's id.
* Set it to `"group:admins"`, and write a rule that allows reads when the actor is in the group `admins`.

The authKey is fixed at upload time and never changes. For anything that has to follow the data over its lifetime,
prefer a rule that reads the owning model.

## What attached data is not

Attached data is always reachable through a model. There is no way to store a standalone value, and there is no TTL
beyond the 1-minute window for unclaimed uploads.

If you need a short-lived standalone value — a password-reset token, an email confirmation code, an OAuth state
parameter — model it: a small model with a state machine and a [time trigger](state-machines.md) that moves it to an
expired state. You get authorization, the audit log and views for free, which an opaque key-value entry could never
give you.
