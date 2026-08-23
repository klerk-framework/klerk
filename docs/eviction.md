# Eviction: trading memory for model count

Klerk keeps model data in memory (see [persistence.md](persistence.md)). By default it keeps *all* of it, which is what
makes reads fast without any tuning. When there is more model data than is worth keeping resident, Klerk can instead
hold only part of it and read the rest back from storage when it is next needed.

This is for the case "quite a lot of models, but rarely used" — more data than fits comfortably in memory, but a working
set that one machine can serve. It is not sharding, and it does not help with a working set that doesn't fit on one
disk.

## Configuring it

```kotlin
KlerkSettings(
    persistence = SqlPersistence(dataSource),
    modelCache = ModelCacheSettings(maxResidentModels = 50_000),
)
```

`maxResidentModels` defaults to "everything", so no existing application changes behaviour. It belongs in
`KlerkSettings` rather than the specification because it is a deployment choice: the same application legitimately runs
with different amounts of memory in a test and in production, and it changes no behaviour a domain expert would
recognise. See [specification-and-settings.md](specification-and-settings.md).

The bound counts models, not bytes, and it is a target rather than a hard cap — eviction happens shortly after the
limit is passed, not at the instant it is. Klerk evicts the models it judges least likely to be used again, which is
not quite least-recently-used: a one-off scan over many models will not push a frequently used model out.

## What is never evicted

Only the model *body* — its properties, state and timestamps — is evictable. These stay in memory always, whatever
`maxResidentModels` says:

- **Which models exist.** This is what tells "no such model" apart from "not in memory right now", and it is what
  `klerk.meta.modelsCount` reports.
- **Relations.** `getRelated`, `getRelatedInCollection` and `getAllRelatedIds` keep working against evicted models, and
  a model cannot be deleted while something refers to it even if neither body is resident.
- **View membership**, so a view still knows which models it contains.

All three are just ids, so they cost little, and everything else depends on them being complete.

## What it costs

A read of an evicted model does a keyed lookup against the database before it returns. Concurrent readers that miss the
same model share one lookup rather than each doing their own. Reads still run concurrently (see
[concurrency.md](concurrency.md)), so one reader's lookup does not hold up the others.

Commands are unaffected in the ordinary case: a command handler produces the new model itself, so a commit writes
through rather than reading anything back.

The cost lands on whatever is not resident, so the useful question is not "how many models are there" but "how many are
touched in a typical minute". Two metrics answer that:

| Metric | Meaning |
|---|---|
| `klerk.models.count` | Models that exist |
| `klerk.models.resident` | Model bodies currently in memory |

If `resident` sits at the configured maximum while reads feel slow, the working set is larger than the cache and the
bound is too low.

## Interaction with views

`filter` and `filterStates` views are indexed (see [views.md](views.md#how-views-are-kept)), so querying one reads back
only the models it contains — which is what makes them usable at all under eviction. Their index is built the first
time they are queried, and that first build does read every model in the parent view.

Two things still read a whole view: `sorted`, which must see every model to order them, and a custom `ModelView`, which
is evaluated on every query.
