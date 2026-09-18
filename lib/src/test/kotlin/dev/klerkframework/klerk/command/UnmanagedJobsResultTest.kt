package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.AuthorViews
import dev.klerkframework.klerk.BookViews
import dev.klerkframework.klerk.CreateAuthor
import dev.klerkframework.klerk.Ctx
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.Views
import dev.klerkframework.klerk.createAstridParameters
import dev.klerkframework.klerk.createConfig
import dev.klerkframework.klerk.onEnterAmateurStateAction
import dev.klerkframework.klerk.testSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains

class UnmanagedJobsResultTest {

    @Test
    fun `the result holds the functions of the started unmanaged jobs`() = runBlocking {
        val bc = BookViews()
        val klerk = Klerk.create(createConfig(Views(bc, AuthorViews(bc.all))), testSettings())
        klerk.meta.start()

        val result = klerk.handle(Command(CreateAuthor, createAstridParameters), Ctx.system()).getOrThrow()

        assertContains(result.unmanagedJobs, ::onEnterAmateurStateAction)
        klerk.meta.stop()
    }
}
