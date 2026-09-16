package dev.klerkframework.klerk.command

import dev.klerkframework.klerk.*
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
