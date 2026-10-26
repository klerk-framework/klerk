package dev.klerkframework.klerk.datatypes

/** An email address. The format is only checked loosely: the only way to know that it works is to send a message. */
public class EmailAddress(value: String) : StringContainer(value) {
    override val category: DataCategory = DataCategory.PersonalData
    override val minLength: Int = 3
    override val maxLength: Int = 254
    override val maxLines: Int = 1
    override val regexPattern: String = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"

    public companion object {
        /** An [EmailAddress] from user input: trimmed and lower-cased. */
        public fun normalized(input: String): EmailAddress = EmailAddress(input.trim().lowercase())
    }
}
