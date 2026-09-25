# Security

This page ties together the pieces elsewhere in these docs that make up Klerk's security posture. Klerk is secure by
design, i.e. none of it is optional or something you have to remember to turn on — it's evaluated automatically by the
framework, so a correctly configured application gets these properties without individual code paths having to enforce
them.

## Secure by design

All data interactions go through Klerk, ensuring that all authorization rules are enforced. This applies independently
to reading a model, reading a single property of a model, submitting a command, reading the event log, and reading or
preparing [attached data](attached-data.md). This design prevents developers from accidentally bypassing security
checks. If a developer needs to override a rule, they must explicitly state it, making such exceptions stand out in the
code.

However, it is the developer's responsibility to ensure that the system cannot be abused to infer information
(see [Authorization](authorization.md)).

## Deny by default

[Authorization](authorization.md) rules are attribute-based (they can key off anything on the actor, the model, or the
context) and default-deny: a rule category with no rules denies everything in it, and even with rules present, an
operation is only allowed if at least one positive rule explicitly says so — there is no implicit allow.

There is one deliberate exception. [Attached data](attached-data.md#visibility) that was uploaded as `Public` is
readable by anyone without a rule being evaluated. Publishing it is itself an authorized operation, decided once when
the data is uploaded — the point being that a decision which cannot change over time is one a cache can rely on.  
Attached data becomes `Private` by default, so setting it to `Public` is an explicit operation.

## No time-of-check-to-time-of-use gap

Because commands are processed one at a time and a read never observes a half-committed command (see
[concurrency.md](concurrency.md)), authorization and validation rules are evaluated against data that is guaranteed
still current the instant before the command is committed. There's no window between "checked" and "used" for another
command to sneak in and invalidate the check — the usual TOCTOU class of bug isn't something you can accidentally
introduce by racing two commands against each other.

## Idempotent, tamper-evident commands

Every command carries a `CommandToken` (see [events-and-commands.md](events-and-commands.md#commandtoken)), which
guarantees it is only ever applied once, and can optionally require that the model (s) it targets haven't changed since
the token was created. This is what makes it safe to retry a submission (e.g. after a dropped connection)
without risking a duplicate effect.

## Event log

Every successfully processed command is durably recorded and can be read back via `eventLog(...)` in a read block (see
[events-and-commands.md](events-and-commands.md#the-event-log)), gated by its own `eventLog` authorization rules. This
is what you reach for during an incident or a compliance review — "what happened, and who did it" is answered by the
framework itself rather than by whatever ad hoc logging individual code paths happened to include.

How long the log keeps its data must be declared in the specification with `eventLogRetention` (see
[retention](events-and-commands.md#retention)): the entries of a deleted model can be erased after a set time, and the
parameters of every entry after another, keeping who did what and when without keeping the personal data.

## Property-level access control

Authorization isn't just "can this actor see this model" — `readProperties` rules gate individual properties on a model
the actor can otherwise read (see [authorization.md](authorization.md#readproperties)), e.g. so that any actor can read
an `Author`'s name while only some can read a sensitive field on the same model.
