package dev.klerkframework.klerk

import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.storage.AttachedBlobStore
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import dev.klerkframework.klerk.view.*

/**
 * The read authorization of a property must belong to the read that produced the model, not to the container
 * instance — the containers in `model.props` are shared with the model cache and with every other reader.
 */
class PropertyAuthorizationTest {

    @Test
    fun `a snapshot is not affected by reads made afterwards by somebody else`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            val readByAnonymous = klerk.read(Ctx.unauthenticated()) { get(rowling) }
            assertNull(readByAnonymous.props.lastName.valueOrNullIfNotAuthorized)

            val readByAuthenticated = klerk.read(Ctx.authenticationIdentity()) { get(rowling) }
            assertEquals("Rowling", readByAuthenticated.props.lastName.value)

            // the snapshot the anonymous actor got must not have been unmasked by the read above
            assertNull(readByAnonymous.props.lastName.valueOrNullIfNotAuthorized)
            assertFailsWith<AuthorizationException> { readByAnonymous.props.lastName.value }
            assertFalse(readByAnonymous.props.lastName.toString().contains("Rowling"), "toString must stay masked")

            // ...and the authenticated actor's snapshot must not become masked by a later anonymous read
            klerk.read(Ctx.unauthenticated()) { get(rowling) }
            assertEquals("Rowling", readByAuthenticated.props.lastName.value)
        }
    }

    @Test
    fun `the containers in the model cache are never mutated by a read`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            klerk.read(Ctx.unauthenticated()) { get(rowling) }

            // the system (and everything else that reads without authorization, such as view filters) must not
            // inherit the decision that was made for the anonymous actor above
            val readBySystem = klerk.read(Ctx.system()) { get(rowling) }
            assertEquals("Rowling", readBySystem.props.lastName.value)
            assertEquals("Rowling", klerk.read(Ctx.system()) { get(rowling) }.props.lastName.value)
        }
    }

    @Test
    fun `containers nested in data classes and in collections are authorized too`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)
            val book = createBookHarryPotter1(klerk, rowling)

            val author = klerk.read(Ctx.unauthenticated()) { get(rowling) }
            assertNull(author.props.address.street.valueOrNullIfNotAuthorized)
            assertEquals("J.K", author.props.firstName.value)

            val anonymousBook = klerk.read(Ctx.unauthenticated()) { get(book) }
            assertEquals(2, anonymousBook.props.tags.size)
            assertTrue(anonymousBook.props.tags.all { it.valueOrNullIfNotAuthorized == null })
            assertEquals("Harry Potter and the Philosopher's Stone", anonymousBook.props.title.value)

            val bookAsSystem = klerk.read(Ctx.system()) { get(book) }
            assertTrue(bookAsSystem.props.tags.all { it.valueOrNullIfNotAuthorized != null })
        }
    }

    @Test
    fun `the rules are evaluated when the model is handed out, and only once per read`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            propertyRuleEvaluations = 0
            klerk.read(Ctx.unauthenticated()) {
                val author = get(rowling)
                val afterFirstGet = propertyRuleEvaluations
                assertTrue(afterFirstGet > 0, "the decisions should be made when the model is handed out")

                author.props.firstName.value
                author.props.firstName.value
                assertEquals(afterFirstGet, propertyRuleEvaluations, "reading a property must not evaluate any rule")

                get(rowling)
                assertEquals(afterFirstGet, propertyRuleEvaluations, "the decisions should be remembered by the read")
            }
        }
    }

    @Test
    fun `views and getOrNull apply property authorization`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            val listed = klerk.read(Ctx.unauthenticated()) { views.authors.all.asSequence().toList() }
            assertTrue(listed.isNotEmpty())
            assertTrue(listed.all { it.props.lastName.valueOrNullIfNotAuthorized == null })

            val fetched = klerk.read(Ctx.unauthenticated()) { getOrNull(rowling) }
            assertNotNull(fetched)
            assertNull(fetched.props.lastName.valueOrNullIfNotAuthorized)
        }
    }

    @Test
    fun `the models in a command result are property authorized`() {
        runBlocking {
            val klerk = startKlerk()

            val result = klerk.handle(
                Command(AnEventWithoutParameters),
                Ctx.unauthenticated(),
            ).getOrThrow()

            val author = assertNotNull(result.authorizedPrimaryModel).props
            assertEquals("Auto", author.firstName.value)
            assertNull(author.lastName.valueOrNullIfNotAuthorized)
        }
    }

    @Test
    fun `valueWithoutAuthorization is not allowed on a read result by default`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            val author = klerk.read(Ctx.unauthenticated()) { get(rowling) }
            assertFailsWith<AuthorizationException> { author.props.lastName.valueWithoutAuthorization }
            assertFailsWith<AuthorizationException> { author.props.firstName.valueWithoutAuthorization }
            assertEquals("J.K", author.props.firstName.value)
        }
    }

    @Test
    fun `valueWithoutAuthorization bypasses the rules when allowBypassAuthRead is enabled`() {
        runBlocking {
            val klerk = startKlerk(allowBypassAuthRead = true)
            val rowling = createAuthorJKRowling(klerk)

            val author = klerk.read(Ctx.unauthenticated()) { get(rowling) }
            assertNull(author.props.lastName.valueOrNullIfNotAuthorized)
            assertEquals("Rowling", author.props.lastName.valueWithoutAuthorization)
        }
    }

    @Test
    fun `system reads and containers created by the application allow valueWithoutAuthorization`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)

            assertEquals("Rowling", klerk.read(Ctx.system()) { get(rowling) }.props.lastName.valueWithoutAuthorization)
            assertEquals("Rowling", LastName("Rowling").valueWithoutAuthorization)
        }
    }

    @Test
    fun `valueWithoutAuthorization is not allowed on the models in a command result by default`() {
        runBlocking {
            val klerk = startKlerk()

            val result = klerk.handle(
                Command(AnEventWithoutParameters),
                Ctx.unauthenticated(),
            ).getOrThrow()

            val author = assertNotNull(result.authorizedModels[result.createdModels.single()]).props as Author
            assertFailsWith<AuthorizationException> { author.firstName.valueWithoutAuthorization }
        }
    }

    @Test
    fun `containers from a read result can be passed to a command`() {
        runBlocking {
            val klerk = startKlerk()
            val rowling = createAuthorJKRowling(klerk)
            val readByAnonymous = klerk.read(Ctx.unauthenticated()) { get(rowling) }

            val result = klerk.handle(
                Command(
                    CreateAuthor,
                    CreateAuthorParams(
                        firstName = readByAnonymous.props.firstName,
                        lastName = readByAnonymous.props.lastName,
                        phone = PhoneNumber("+46123456"),
                        secretToken = SecretPasscode(234234902359245345),
                    ),
                ),
                Ctx.system(),
            ).getOrThrow()

            // the restrictions of the anonymous read must not follow the containers into the model cache
            val created = klerk.read(Ctx.system()) { get(assertNotNull(result.primaryModel)) }
            assertEquals("Rowling", created.props.lastName.value)
            assertEquals("Rowling", created.props.lastName.valueWithoutAuthorization)
        }
    }

    private suspend fun startKlerk(
        storage: Persistence = RamStorage(),
        allowBypassAuthRead: Boolean = false,
    ): Klerk<Ctx, Views> {
        val bookViews = BookViews()
        val collections = Views(bookViews, AuthorViews(bookViews.all))
        val settings = testSettings(storage).copy(allowBypassAuthRead = allowBypassAuthRead)
        val klerk = Klerk.create(createPropertyAuthConfig(collections), settings)
        klerk.meta.start()
        return klerk
    }
}

/**
 * Counts every evaluation of the property rule below, so that the tests can tell when a decision is actually made.
 */
internal var propertyRuleEvaluations: Int = 0

fun createPropertyAuthConfig(collections: Views): Specification<Ctx, Views> {
    return SpecificationBuilder<Ctx, Views>(collections).build {
        managedModels {
            model(Book::class, bookStateMachine(collections), collections.books)
            model(Author::class, authorStateMachine(collections), collections.authors)
        }
        authorization {
            readModels {
                positive(::`Everybody can read`)
            }
            readProperties {
                positive(::canReadAllProperties)
                negative(::anonymousCannotReadSensitiveProperties)
            }
            commands {
                positive(::`Everybody can do everything`)
            }
            eventLog {
                positive(::`Everybody can read event log`)
            }
        }
        systemContextProvider(::myContextProvider)
    }
}

fun anonymousCannotReadSensitiveProperties(args: PropertyReadRuleArgs<Ctx, Views>): NegativeAuthorization {
    propertyRuleEvaluations++
    val sensitive = args.property is LastName || args.property is Street || args.property is BookTag
    return if (sensitive && args.context.actor == Unauthenticated) Deny else Pass
}
