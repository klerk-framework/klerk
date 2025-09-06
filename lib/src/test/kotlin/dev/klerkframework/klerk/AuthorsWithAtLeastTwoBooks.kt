package dev.klerkframework.klerk

import dev.klerkframework.klerk.collection.AllModelView
import dev.klerkframework.klerk.collection.FilteredModelView
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.read.Reader

class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelView<Author, Context>,
    private val books: AllModelView<Book, Context>,
) : ModelView<Author, Context>(authors) {

    override fun filter(filter: ((Model<Author>) -> Boolean)?): ModelView<Author, Context> {
        return if (filter == null) this else FilteredModelView(this, filter)
    }

    override fun <V> withReader(reader: Reader<Context, V>, cursor: QueryListCursor?): Sequence<Model<Author>> {
        return authors.withReader(reader, cursor).filter { author ->
            books.withReader(reader, null).filter { it.props.author == author.id }.take(2).count() == 2
        }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<Context, V>): Boolean {
        return withReader(reader, null).any { it.id == value }
    }

}
