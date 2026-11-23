# Subscriptions

`klerk.subscriptions` tells you when something changes: a model, a view, the event log or a job. Each subscription is
a `Flow`:

```kotlin
klerk.subscriptions.model(bookId, context).collect { change ->
    when (change) {
        is ModelChange.Changed -> refresh()
        is ModelChange.Deleted -> close()
        is MissedChanges -> refresh()
    }
}
```

| Function                                       | Emits                                                         |
|------------------------------------------------|---------------------------------------------------------------|
| `model(id, context)`                           | `ModelChange.Changed` or `ModelChange.Deleted`                |
| `models(ids, context)`                         | `ModelsChange.Changed(changed, deleted)`, once per commit that affects any of them |
| `view(view, context)`                          | `ViewChange.Changed(ids)`, once per commit that affects the view |
| `events(context, modelClass = …, events = …)`  | `EventChange.Committed(entry)` for each new event log entry   |
| `job(id, context)`                             | the `JobInfo`, first as it is now, then on every change       |
| `jobs(context)`                                | the `JobInfo` of every job that changes                       |

The changes say *that* something changed, not what it looks like now. Read the model or view again to get that.

## Views

A view change is emitted for every commit that changes a model that was in the view before the commit or is in it
after. A model that leaves the view is therefore reported too. `ids` are the models of the commit that were or are in
the view.

For a view built with `filter`, `filterStates` and `sorted`, Klerk decides membership with the view's predicates. For a
custom `ModelView` it cannot, so every change of a model of that type counts.

## Authorization

The actor in the context is checked when each change is delivered:

* A change of a model is delivered if the actor may read the model as it was before the change, or as it is after
  it. A subscriber that shows a model therefore learns when it becomes unreadable.
* `events` requires the [`eventLog` rules](authorization.md). The flow throws `AuthorizationException` when it is
  collected if they deny the actor. Entries are delivered as the event log keeps them, so erased parameters are not
  delivered.
* `job` and `jobs` follow the `readJobs` rules. `job` throws `AuthorizationException` if the actor may not see the job,
  and `NoSuchElementException` if there is no such job.

A subscription keeps the context it was created with. If the actor's permissions can change, subscribe again from time
to time.

## Continuing from a read

Every commit has a sequence number, the same as `EventLogEntry.sequenceNumber`. A change made between a read and
the start of a subscription would be missed. To avoid that, take the read's `sequenceNumber` and pass it as `after`:

```kotlin
val (books, seen) = klerk.read(context) { views.books.all.asSequence().toList() to sequenceNumber }
klerk.subscriptions.view(views.books.all, context, after = seen).collect { /* ... */ }
```

The subscription then starts with the changes that the read did not see. Every change also carries its
`sequenceNumber`, so a subscriber that reconnects can continue from the last change it got.

`klerk.meta.sequenceNumber` is the latest commit that reads can see. Take it before a read, never after: taken after,
it may include a commit that the read did not see.

The sequence number reveals how many commits the application has made, so do not show it to end users. Encrypt it if
it has to leave the server, e.g. in a web page.

## Missed changes

Klerk keeps the latest `KlerkSettings.subscriptionHistory` commits in memory (1024 by default). A subscription emits
`MissedChanges` instead of the changes when:

* `after` is older than the kept commits,
* `after` is from before a restart, or ahead of the latest commit,
* the subscriber falls so far behind that the commits it has not handled yet are dropped.

Then read everything that is shown again. The subscription continues with the changes after
`MissedChanges.sequenceNumber`.

Commands never wait for a subscriber, so a subscriber may issue commands itself.
