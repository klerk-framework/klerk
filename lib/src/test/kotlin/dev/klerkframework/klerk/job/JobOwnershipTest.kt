package dev.klerkframework.klerk.job

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.Author
import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.JobReadRuleArgs
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelIdentity
import dev.klerkframework.klerk.ModelReferenceIdentity
import dev.klerkframework.klerk.MyJob
import dev.klerkframework.klerk.MyJobCursor
import dev.klerkframework.klerk.PluginIdentity
import dev.klerkframework.klerk.SQLiteInMemory
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Unauthenticated
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAuthorJKRowling
import dev.klerkframework.klerk.createKlerk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A job's owner comes back from storage as a [ModelReferenceIdentity], while the actor asking about it is typically a
 * [ModelIdentity]. The same user must be recognised across that difference.
 */
class JobOwnershipTest {

    private fun jobOwnedBy(owner: ActorIdentity) = JobInfo(
        id = JobID(1),
        name = JobName("test"),
        step = 0,
        attempt = 0,
        createdAt = Instant.DISTANT_PAST,
        priority = JobPriority.Interactive,
        agent = JobAgent.System,
        parent = null,
        root = JobID(1),
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
                JobReadRuleArgs(jobOwnedBy(ModelReferenceIdentity(authorId)), Ctx.system(), this)
            }

            assertTrue(args.isOwnedBy(ModelReferenceIdentity(authorId)))
            // The actor arrives as a ModelIdentity while the owner was stored as a reference: same user.
            assertTrue(args.isOwnedBy(ModelIdentity(author)))
            assertFalse(args.isOwnedBy(ModelReferenceIdentity(ModelID<Author>(authorId.value + 1))))
        }
    }

    @Test
    fun `a job scheduled by a plugin is still owned by it after being stored`() {
        runBlocking {
            val bookViews = BookViews()
            val storage = SQLiteInMemory.create()
            val klerk = createKlerk(Views(bookViews, AuthorViews(bookViews.all)), storage)
            klerk.meta.start(installShutdownHook = false)

            val plugin = PluginIdentity("images")
            val id = klerk.jobs.schedule(MyJob.declare(MyJobCursor("hi")), Ctx(plugin))

            val owner = storage.allJobs().single { it.id == id }.toJobInfo().owner
            assertEquals<ActorIdentity>(plugin, owner)
            assertTrue(owner.isSameAs(PluginIdentity("images")))
            assertFalse(owner.isSameAs(PluginIdentity("assets")))
            klerk.meta.stop()
        }
    }

    @Test
    fun `only the system owns a job scheduled by the system`() {
        runBlocking {
            val bookViews = BookViews()
            val views = Views(bookViews, AuthorViews(bookViews.all))
            val klerk = createKlerk(views)
            klerk.meta.start()
            val args = klerk.read(Ctx.system()) {
                JobReadRuleArgs(jobOwnedBy(SystemIdentity), Ctx.system(), this)
            }
            assertTrue(args.isOwnedBy(SystemIdentity))
            assertFalse(args.isOwnedBy(Unauthenticated))
            assertFalse(args.isOwnedBy(ModelReferenceIdentity(ModelID<Author>(1))))
        }
    }
}
