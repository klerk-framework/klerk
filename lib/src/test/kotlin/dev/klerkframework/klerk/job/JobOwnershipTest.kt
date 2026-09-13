package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A job's owner comes back from storage as a [ModelReferenceIdentity], while the actor asking about it is typically a
 * [ModelIdentity]. The same user must be recognised across that difference.
 */
class JobOwnershipTest {

    private fun jobOwnedBy(owner: ActorIdentity) = JobInfo(
        id = JobId(1),
        name = JobName("test"),
        step = 0,
        attempt = 0,
        created = Instant.DISTANT_PAST,
        priority = JobPriority.Interactive,
        parent = null,
        root = JobId(1),
        depth = 0,
        status = JobStatus.Ready,
        progress = null,
        owner = owner,
    )

    @Test
    fun `a job scheduled by a model is owned by that model, whichever identity asks`() {
        runBlocking {
            val bookViews = BookViews()
            val views = Views(bookViews, AuthorViews(bookViews.all))
            val klerk = createKlerk(views)
            klerk.meta.start()
            val authorId = createAuthorJKRowling(klerk)
            val author = klerk.read(Ctx.system()) { get(authorId) }

            val args = klerk.read(Ctx.system()) {
                ArgsForJobRead(jobOwnedBy(ModelReferenceIdentity(authorId)), Ctx.system(), this)
            }

            assertTrue(args.isOwnedBy(ModelReferenceIdentity(authorId)))
            // The actor arrives as a ModelIdentity while the owner was stored as a reference: same user.
            assertTrue(args.isOwnedBy(ModelIdentity(author)))
            assertFalse(args.isOwnedBy(ModelReferenceIdentity(ModelID<Author>(authorId.value + 1))))
        }
    }

    @Test
    fun `id-less actors are told apart by their type`() {
        runBlocking {
            val bookViews = BookViews()
            val views = Views(bookViews, AuthorViews(bookViews.all))
            val klerk = createKlerk(views)
            klerk.meta.start()
            val args = klerk.read(Ctx.system()) {
                ArgsForJobRead(jobOwnedBy(SystemIdentity), Ctx.system(), this)
            }
            assertTrue(args.isOwnedBy(SystemIdentity))
            assertFalse(args.isOwnedBy(Unauthenticated))
            assertFalse(args.isOwnedBy(ModelReferenceIdentity(ModelID<Author>(1))))
        }
    }
}
