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
val blobID: LargeBlobID = klerk.largeData.prepare(inputStream, context)
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

### Metadata

`klerk.largeData.getMetadata(id, context)` describes the data without fetching it:

```kotlin
val meta: LargeDataMetadata = klerk.largeData.getMetadata(blobID, context)
meta.visibility     // Public or Private
meta.createdAt      // when it was uploaded
meta.size           // bytes
meta.hash           // SHA-256, lowercase hex
meta.custom         // whatever you passed to prepare
```

Everything in it is fixed at upload time and never changes. It is authorized exactly like `get`, and refuses unclaimed
data for the same reason — so the hash is not available in the window between `prepare` and the command that claims the
ID. If you want the hash before then, compute it yourself while you have the bytes.

You can attach your own metadata when preparing:

```kotlin
val blobID = klerk.largeData.prepare(
    inputStream, context, LargeDataVisibility.Public,
    metadata = mapOf("contentType" to "image/webp", "width" to "1200"),
)
```

Klerk does not interpret these values and never gives them to an authorization rule. They stay in memory for as long as
the data exists, so they are limited to 1000 characters in total — put anything bigger in the data itself.

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
context and the visibility.

The read rules apply to private data only. Public data is readable by anyone, as described next.

## Visibility

Data is uploaded as either `Private` (the default) or `Public`:

```kotlin
val blobID = klerk.largeData.prepare(inputStream, context, LargeDataVisibility.Public)
```

**No read rule is evaluated for public data — not even a negative one.** A rule such as "unauthenticated actors may
never read attached data" simply does not apply to it.

That is the whole point. An authorization rule answers "may this actor read this *right now*", and since a rule may
look at anything in the model graph, an answer today says nothing about tomorrow. `Public` is a decision made once, at
upload time, about data that is immutable anyway — so it cannot change later, and that is what makes it safe to cache.
Visibility is chosen by whoever calls `prepare`, so the `writeLargeData` rules are the place to control who may publish:

```kotlin
fun onlyEditorsMayPublish(args: ArgsForLargeDataWrite<Ctx, Views>): NegativeAuthorization =
    if (args.visibility == LargeDataVisibility.Public && !args.context.isEditor()) Deny else Pass
```

## Serving through a CDN

A public image should be cached; a private one must not be. `getMetadata` gives a handler everything it needs to decide
before it touches the value:

```kotlin
val meta = klerk.largeData.getMetadata(blobID, context)
call.response.header(HttpHeaders.ContentType, meta.custom["contentType"] ?: "application/octet-stream")
call.response.header(HttpHeaders.ContentLength, meta.size.toString())
call.response.header(
    HttpHeaders.CacheControl,
    when (meta.visibility) {
        LargeDataVisibility.Public -> "public, immutable, max-age=31536000"
        LargeDataVisibility.Private -> "private, no-store"
    }
)
call.respondOutputStream { klerk.largeData.get(blobID, context).copyTo(this) }
```

Two things to keep in mind:

**Put the hash in the URL.** IDs are random, but they are recycled once the data they referred to has been deleted, so
an ID alone is not a safe cache key: a URL could end up serving year-old bytes for entirely new data. A URL like
`/images/{id}/{hash}` cannot, since different content always means a different URL. It also lets you cache for as long
as you like, because the URL changes whenever the content does.

**Deleted data keeps being served.** Deleting the owning model makes `get` throw immediately, but a CDN will happily go
on serving what it already cached. If that matters, purge the URL when the data goes away.

Private data is a different story: it has a hit rate of roughly zero at a shared cache, and getting it wrong leaks one
user's data to another. Keep the CDN out of the path for it entirely.

## What attached data is not

Attached data is always reachable through a model. There is no way to store a standalone value, and there is no TTL
beyond the 1-minute window for unclaimed uploads.

If you need a short-lived standalone value — a password-reset token, an email confirmation code, an OAuth state
parameter — model it: a small model with a state machine and a [time trigger](state-machines.md) that moves it to an
expired state. You get authorization, the audit log and views for free, which an opaque key-value entry could never
give you.
