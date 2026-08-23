# Attached data

Models should be kept small so they fit in the internal cache. Klerk lets you attach large immutable data to a model
instead of storing it in the model itself. There are two kinds: strings (e.g. JSON) and blobs (images, videos, PDFs).

Both are referenced by a container — `AttachedBlobContainer` for a blob, `AttachedStringContainer` for a string —
which is a `DataContainer` like any other property's: it holds the reference *and* declares what the value is allowed
to be. Klerk rejects a bare `AttachedBlobID` or `AttachedStringID` property; every attached value is declared this
way.

```kotlin
class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept = setOf("image/png", "image/jpeg")
    override val maxSize = 5_000_000L
    override val preAttachSteps = listOf(::stripExif)   // required — see below
}

class Biography(id: AttachedStringID) : AttachedStringContainer(id) {
    override val accept = setOf("text/plain")
    override val maxSize = 20_000L
}

data class Author(val name: Name, val portrait: Portrait?, val biography: Biography?)
```

Attached data is **immutable** — you never update a value in place, you attach a new one and drop the reference to the
old one. It is also **exclusively owned**: a given blob belongs to exactly one model.

### Blob or string?

The two are stored identically (a string is its UTF-8 bytes) and share one id space, so an id identifies a value on its
own. What separates them is the type of the id: it says what the value means, decides how you read it back, and lets a
model property declare which of the two it holds. An id used through the wrong type is rejected.

Pick by how you want to read the value:

|                     | `AttachedStringID` | `AttachedBlobID`         |
|---------------------|--------------------|--------------------------|
| `prepare` takes     | a `String`         | an `InputStream`         |
| `get` returns       | a `String`         | an `InputStream`         |
| in memory on upload | the whole value    | never more than a buffer |

So a string is the right choice for text you are going to want as a `String` anyway — JSON, Markdown, a diff. For text
too large to hold in memory while uploading it, use a blob. (A string that is merely large to *read* is fine: see
`getStream` below.)

## What an attached property declares

Everything an `AttachedBlobContainer` or `AttachedStringContainer` declares is checked when a command attaches the
value, against what Klerk itself found the bytes to be. That is the point of declaring it on the property rather than
at the upload: the check holds for a command from a web form, from klerk-graphql, from a job and from a test alike,
and it is still true a year later when somebody adds a second way to create the model.

|                      |                                                                                                              |
|----------------------|--------------------------------------------------------------------------------------------------------------|
| `accept`             | the content types this property takes, as IANA media types. Empty (the default) means anything.              |
| `acceptUnrecognised` | whether a value whose type could not be recognised is acceptable. Only consulted when `accept` is non-empty. |
| `maxSize`            | the largest value, in bytes.                                                                                 |
| `visibility`         | `Private` (the default) or `Public` — see below.                                                             |
| `preAttachSteps`     | what has to happen to the file before this property will hold it — required for a blob; a string has none, see below. |

`acceptUnrecognised` has to be a decision rather than a default, because "unrecognised" is the normal state of affairs
for CSV, for plain text and for any format Klerk has no signature for. A property that accepts those must say so; one
that accepts images must not.

### What the bytes are

Klerk recognises the content type from the first bytes of every value as it is written, and reports it as
`metadata.contentType`. That is the only statement about a value's type Klerk will make — what a client *said* it was
uploading is the application's own metadata and is never treated as fact.

**Recognising a format is not vouching for it.** A file can satisfy two formats at once (a valid PNG that is also valid
JavaScript), so `accept` keeps honest mistakes out, not a determined attacker. What makes serving safe is the response
headers and the origin the bytes are served from — see [serving through a CDN](#serving-through-a-cdn).

The detector itself is `KlerkSettings.contentTypeDetector`, a `ContentTypeDetector` with one method,
`detect(head: ByteArray): String?`. The default, `DefaultContentTypeDetector`, is a small dependency-free set of
magic-byte signatures for the formats an application is likely to declare — not a complete format database. Replace
it in `KlerkSettings` with something like a wrapper around Apache Tika if an application needs to recognise more
formats and can afford the extra dependency weight.

### Steps: looking at the bytes, and rewriting them

Blob-only: a string has no `preAttachSteps`, since a value small enough to hold as a `String` needs no scan or
rewrite before it is kept.

Metadata cannot answer everything. A virus scan has to read the file; a Content Disarm & Reconstruct pass reads it and
hands back *different bytes*. Both are declared as `preAttachSteps`:

```kotlin
class InventoryCsv(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept = setOf("text/plain")
    override val acceptUnrecognised = true
    override val preAttachSteps = listOf(::checkTheHeader, ::normaliseLineEndings)
}

suspend fun checkTheHeader(args: BlobPreAttachStepArgs): BlobPreAttachStepResult {
    val header = args.value.bufferedReader().buffered().readLine()
    return if (header == "name,quantity") BlobPreAttachStepResult.Pass
    else BlobPreAttachStepResult.Reject("the first line must be 'name,quantity'")
}

suspend fun normaliseLineEndings(args: BlobPreAttachStepArgs): BlobPreAttachStepResult =
    BlobPreAttachStepResult.Replace(args.value.readBytes().decodeToString().replace("\r\n", "\n").byteInputStream())
```

A step returns `Pass` (the file is fine), `Reject` (it must not be stored, with the reason the user is shown) or
`Replace` (these bytes instead). They run in declared order, each on the current bytes, and **nothing re-runs
implicitly** — if the scanner should see the disarmed output, declare it twice.

A step is a **pure function of the file**: it gets the bytes and the metadata, and nothing else. Anything that needs
the actor or the model graph is an authorization rule or a validator, not a step. Each must be a named function
reference (`::checkTheHeader`), because the name is what Klerk records when it has run.

**Klerk runs them itself**, in a [job](jobs.md) it schedules from `prepare` — see [Writing](#writing). A command
attaching a value whose declared steps have not all run is rejected, so this is a guarantee rather than a convention.

Bytes may be rewritten only while the value is unclaimed. Once a command has attached it to a model it never changes
again: no URL names an unclaimed value and no cache can have seen it, so the immutability that matters is untouched.

**Every container must declare at least one step.** An uploaded file arrives from whoever sent it, and `accept` alone
does not make it safe to keep or to serve — most files should be looked at, and many should be rewritten (an image
re-encoded, its EXIF stripped). A property that genuinely wants the bytes exactly as they arrived has to say so:

```kotlin
class CompressedAsset(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val preAttachSteps = listOf(::noPreAttachProcessing)
}
```

`noPreAttachProcessing` must then be the only step, and it costs nothing at all: no job is scheduled, `awaitProcessing`
returns immediately, and attaching never reads the value. A container that declares an empty list is refused when the
the specification is built.

## Where blob bytes are kept

Strings always live in the database. For blobs you choose, because the choice decides what a database backup contains:

```kotlin
KlerkSettings(
    persistence = SqlPersistence(dataSource),
    attachedBlobStore = AttachedBlobStore.Database,          // in the row, alongside everything else
    // attachedBlobStore = FileBlobStore(Path("/var/lib/myapp/blobs")),
    // attachedBlobStore = AttachedBlobStore.None,           // this application has no blobs
)
```

|                             | `Database`                                                                                            | `FileBlobStore`                     | `None`  |
|-----------------------------|-------------------------------------------------------------------------------------------------------|-------------------------------------|---------|
| Where the bytes are         | the attached-data row                                                                                 | one file per blob under a directory | nowhere |
| Largest value               | what the database can hold in one value — about 1 GB for SQLite, held in memory on the way in and out | whatever the filesystem allows      | —       |
| A database backup is enough | yes                                                                                                   | **no**, back up the directory too   | yes     |
| Delete is transactional     | yes                                                                                                   | no (see below)                      | —       |

Required as soon as any model property or event parameter is an `AttachedBlobID` — including one contributed by a
plugin, such as klerk-web's compressed assets. An application that declares none needs no store at all.

**Pick the store before you have data in it.** Klerk never moves blobs between stores, and refuses to start when the
configured store does not have the bytes the database refers to, rather than letting the first user to open an old
attachment discover it.

With a store outside the database, writing and deleting are no longer part of the transaction. Klerk writes the bytes
before committing the row and deletes them after, so a crash can only ever leave bytes that nothing refers to — those
are swept at the next startup. Bytes without a row are invisible, so this is safe, but it is a real difference from
`Database`.

## Writing

Updating a model must be quick, but uploading an image may take a while. To avoid blocking command processing, writing
is done in two steps:

1. insert the data into Klerk (can be slow)
2. update the model with the ID of the data (fast)

```kotlin
val blobID: AttachedBlobID = klerk.attachedData.prepare(inputStream, Portrait::class, context)
// use blobID in a Command that creates or updates the model
```

`prepare` does not need to know which *model* the data will belong to, so it works for events that create a model just
as well as for events that update one. Ownership is recorded when the command commits. It does need to know which
**property** the value is destined for: that is what says how large it may be, what it may be, and what has to happen
to it first.

### Waiting for the steps

When the declaration declares real [steps](#steps-looking-at-the-bytes-and-rewriting-them) — anything other than
`noPreAttachProcessing` — `prepare` schedules a job to run them and returns as soon as the bytes are written. Wait for that job before the command that attaches the value:

```kotlin
val blobID = klerk.attachedData.prepare(inputStream, InventoryCsv::class, context)
klerk.attachedData.awaitProcessing(blobID)   // throws BlobRejected if a step refused the file
```

`awaitProcessing` returns immediately when there is nothing to wait for, so it is safe to call for any value. A step
that refuses the file throws `BlobRejected` with the reason, and the value is deleted. A step that *fails* — a scanner
that is briefly unreachable — is retried with the usual backoff, and ends up in the dead-letter queue if it keeps
failing; the job is called `klerk-process-attached-data` in the admin UI.

Nothing forces you to wait: a longer-running application can prepare the value now and issue the command when the job
has finished. What is not allowed is attaching a value whose steps have not run — that command is rejected.

If you call `prepare` but no committed command references the ID within **1 minute**, the data is deleted. A later
attempt to use that ID fails the command.

### When a minute is not enough

A minute is right when the command follows immediately. When it cannot — a file that is uploaded as soon as the user
picks it but not attached until they submit the form — ask for a longer lease:

```kotlin
val blobID = klerk.attachedData.prepare(inputStream, Portrait::class, context, lease = 15.minutes)
```

A lease may not exceed `KlerkSettings.maxAttachedDataLease` (24 hours by default), and the `writeAttachedData` rules see
it, so who may keep unclaimed data around is a decision the application can make.

Data prepared inside a job needs no lease: it is kept for as long as the job lives, however many steps that takes.

### Taking over a file

When the value is already a file — a completed upload, say — `prepareFromFile` hands it over instead of copying it:

```kotlin
val blobID = klerk.attachedData.prepareFromFile(path, Portrait::class, context, lease = 15.minutes)
```

With a `FileBlobStore` on the same filesystem this is a rename: the bytes are read once to compute the size and hash,
and never written a second time, so the size of the file stops mattering. The file is *moved*, and no longer exists at
its old location. Any other store copies the bytes and leaves the file alone.

### Attaching to a model that already has data

Assigning a new ID to a property that already held one deletes the old data when the command commits:

```kotlin
// old portrait is deleted, new one takes its place
klerk.handle(Command(UpdateAuthorPortrait, authorID, UpdatePortraitParams(newBlobID)), context, options)
```

## Reading

Use `klerk.attachedData.get(id, context)`. If you don't have the ID, read it from the model first:

```kotlin
// find the blob ID of the author's portrait. Keep the read lock short.
val blobID: AttachedBlobID = klerk.read(context) {
    get(authorID).props.portrait
}

// then fetch the data, outside the read block
val image: InputStream = klerk.attachedData.get(blobID, context)
```

`get` **must be called outside a read block**. Calling it inside `klerk.read { }` or `klerk.readSuspend { }` throws —
attached data is often large, and holding the read lock while streaming it would block every command in the
application. `get` acquires the lock briefly on its own to make the authorization decision, releases it, and then
returns the stream.

`get` throws `NoSuchElementException` if there is no data for that ID, or if the ID refers to the other kind (a blob
read as a string, say). Data that has been prepared but not yet claimed by a command is not readable either — attached
data is always read through the model that owns it, and until a command commits there is no owner (and so nothing for
the authorization rule to decide on).

A string comes back as a `String`, which means all of it is brought into memory. When that is not what you want — you
are writing it straight to an HTTP response, say — use `getStream(id, context)` instead and get its UTF-8 bytes:

```kotlin
val notes: InputStream = klerk.attachedData.getStream(notesID, context)
```

### Metadata

`klerk.attachedData.getMetadata(id, context)` describes the data without fetching it:

```kotlin
val meta: AttachedDataMetadata = klerk.attachedData.getMetadata(blobID, context)
meta.kind           // Blob or String
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
val blobID = klerk.attachedData.prepare(
    inputStream, Portrait::class, context,
    metadata = mapOf("filename" to "rose.webp", "clientContentType" to "image/webp"),
)
```

Klerk does not interpret these values and never gives them to an authorization rule. They stay in memory for as long as
the data exists, so they are limited to 1000 characters in total — put anything bigger in the data itself. Keys starting
with `__` are reserved.

Note the difference between `meta.custom["clientContentType"]` and `meta.contentType`: the first is what somebody said,
the second is what the bytes are. Keeping a claim is useful — for showing the user the name they uploaded, or for
noticing that the two disagree — but only the second is a finding.

## Deleting

You never delete attached data directly. It is deleted when the last reference to it goes away:

* the owning model is deleted, or
* the reference is set to `null` (only possible if the property is nullable), or
* the reference is replaced by another ID.

Deletion happens in the same transaction as the command, so a command that fails leaves the data intact.

## Ownership

An `AttachedBlobID` or `AttachedStringID` belongs to the first model that references it in a committed command. A
command that tries to attach data already owned by *another* model is rejected.

This means data cannot be shared or moved between models. To give a second model the same content, upload it again with
a second `prepare`.

Sharing would mean counting references across models, and that count could not be maintained in the same transaction as
the command — which is exactly what makes deletion here reliable rather than a background chore. Exclusive ownership is
the price of never leaking a value.

The owner is the *model*, not the property, so the same ID may appear in two properties of the same model. Klerk only
deletes the data once no property of that model refers to it any more.

## Authorization

You declare the rules for reading and writing attached data in the specification, as usual — in the `readAttachedData` and
`writeAttachedData` blocks of `authorization` (see [authorization](authorization.md)). The rules receive a `Reader`, so
they can look up whatever they need — including the actor from the context and, when reading, the model that owns the
data.

Because the owning model is reachable from the rule, model-relative policies work and stay correct over time:

```kotlin
fun onlyProjectMembersCanReadAttachments(args: ArgsForAttachedDataRead<Ctx, Views>): PositiveAuthorization {
    ...
}
```

At `prepare` time there is no model yet — the data has not been attached to anything — so a write rule can only see the
context and the kind (blob or string).

The read rules apply to private data only. Public data is readable by anyone, as described next.

## Visibility

Data is either `Private` (the default) or `Public`. A blob or a string declares which on its container — the
[`AttachedBlobContainer`](#what-an-attached-property-declares) or `AttachedStringContainer` subclass the property holds —
never at `prepare` time:

```kotlin
class FlowerImage(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val visibility = AttachedDataVisibility.Public
}

class BookNotes(id: AttachedStringID) : AttachedStringContainer(id) {
    override val visibility = AttachedDataVisibility.Public
}
```

**No read rule is evaluated for public data — not even a negative one.** A rule such as "unauthenticated actors may
never read attached data" simply does not apply to it.

That is the whole point. An authorization rule answers "may this actor read this *right now*", and since a rule may look
at anything in the model graph, an answer today says nothing about tomorrow. `Public` is a decision made once about data
that is immutable anyway — so it cannot change later, and that is what makes it safe to cache.

The decision is applied when a command attaches the value, and never again. Declaring it on the property rather than
passing it to `prepare` puts it where it is known: whoever uploads a value has no idea what it will end up being used
for. It also means "who may publish" is decided by the ordinary command rules — whoever may execute the event that
attaches a public value is who may publish one.

Both kinds are therefore always prepared as `Private`, and there is no way to say otherwise — `prepare` takes a
container declaration, not a visibility, for either one.

## Serving through a CDN

A public image should be cached; a private one must not be. `getMetadata` gives a handler everything it needs to decide
before it touches the value:

```kotlin
val meta = klerk.attachedData.getMetadata(blobID, context)
// What Klerk recognised the bytes to be, and only for types that are safe to render inline. Anything else is a
// download: an HTML or SVG file served inline from your own origin is a script running as your application.
val inlineSafe = meta.contentType in setOf("image/png", "image/jpeg", "image/gif", "image/webp")
call.response.header(HttpHeaders.ContentType, if (inlineSafe) meta.contentType!! else "application/octet-stream")
call.response.header(HttpHeaders.XContentTypeOptions, "nosniff")
if (!inlineSafe) {
    call.response.header(HttpHeaders.ContentDisposition, "attachment")
}
call.response.header(HttpHeaders.ContentLength, meta.size.toString())
call.response.header(
    HttpHeaders.CacheControl,
    when (meta.visibility) {
        AttachedDataVisibility.Public -> "public, immutable, max-age=31536000"
        AttachedDataVisibility.Private -> "private, no-store"
    }
)
call.respondOutputStream { klerk.attachedData.get(blobID, context).copyTo(this) }
```

Three things to keep in mind:

**A declared `accept` is not a serving policy.** The property already refused anything that is not an image, and the
handler above still refuses to serve one inline unless the bytes say so. Both are cheap, and neither is sufficient
alone — a file can satisfy two formats at once. For public files, a separate origin is what actually contains the
damage.

**Put the hash in the URL.** IDs are random and unique across blobs and strings alike, but they are recycled once the
data they referred to has been deleted, so an ID alone is not a safe cache key: a URL could end up serving year-old
bytes for entirely new data. A URL like `/attached/{id}/{hash}` cannot, since different content always means a different
URL. It also lets you cache for as long as you like, because the URL changes whenever the content does. A route like
that need not know in advance what it is serving — `meta.kind` tells it.

**Deleted data keeps being served.** Deleting the owning model makes `get` throw immediately, but a CDN will happily go
on serving what it already cached. If that matters, purge the URL when the data goes away.

Private data is a different story: it has a hit rate of roughly zero at a shared cache, and getting it wrong leaks one
user's data to another. Keep the CDN out of the path for it entirely.

## What attached data is not

Attached data is always reachable through a model. There is no way to store a standalone value, and there is no TTL
beyond the 1-minute window for unclaimed uploads.

If you need a short-lived standalone value — a password-reset token, an email confirmation code, an OAuth state
parameter — model it: a small model with a state machine and a [time trigger](state-machines.md) that moves it to an
expired state. You get authorization, the audit log and views for free, which an opaque key-value entry could never give
you.
