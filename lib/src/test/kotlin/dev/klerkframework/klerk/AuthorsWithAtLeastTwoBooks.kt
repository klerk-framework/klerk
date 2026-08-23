package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.AllModelView
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.read.Reader

/**
 * A view whose membership depends on a *different* model type, which is what Klerk's own index cannot do for you.
 *
 * It answers in ids, so a query only reads the authors it actually returns, and it answers [contains] without reading
 * anything — worth doing, since `validReferences` asks that question on the command path.
 */
class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelView<Author, Ctx>,
    private val books: AllModelView<Book, Ctx>,
) : ModelView<Author, Ctx>(authors) {

    override fun <V> memberIds(reader: Reader<Ctx, V>, cursor: QueryListCursor?): Sequence<ModelID<Author>> {
        val authorsWithTwoBooks = books.withReader(reader, null)
            .groupingBy { it.props.author }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
        return authors.memberIds(reader, cursor).filter { authorsWithTwoBooks.contains(it) }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<Ctx, V>): Boolean =
        memberIds(reader, null).any { it.value == value.value }

}
