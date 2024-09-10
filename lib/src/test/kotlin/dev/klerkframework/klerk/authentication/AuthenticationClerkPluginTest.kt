package dev.klerkframework.klerk.authentication

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.storage.RamStorage
import kotlin.test.Test

class AuthenticationKlerkPluginTest {

    @Test
    fun `Can apply`() {
        System.setProperty("GOOGLE_CLIENT_ID", "123")
        System.setProperty("GOOGLE_CLIENT_SECRET", "456")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val config = createConfig(collections, RamStorage())
        val auth = AuthenticationPlugin<Context, MyCollections>()
        auth.applyConfig(config)
    }
}