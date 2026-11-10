package org.stypox.dicio.skills.sms

/**
 * The owner's contact in memory, like [AndroidOwnContact]: the remembered contact's card (looked
 * up by name among [contacts]), then the [profile].
 */
class FakeOwnContact(
    private val contacts: List<OwnCard> = listOf(),
    private val profile: OwnCard? = null,
) : OwnContact {
    val remembered = mutableListOf<String>()

    override suspend fun cards(): List<OwnCard> = listOfNotNull(
        remembered.lastOrNull()?.let(::card),
        profile,
    )

    override fun contactNames(): List<String> = contacts.map { it.name }

    override fun card(name: String): OwnCard? = contacts.firstOrNull { it.name == name }
        ?.takeIf { it.numbers.isNotEmpty() || it.emails.isNotEmpty() }

    override suspend fun remember(name: String) {
        remembered.add(name)
    }

    companion object {
        /** The cards of contacts that only have phone numbers. */
        fun of(vararg contacts: SmsContact) =
            FakeOwnContact(contacts.map { OwnCard(it.name, it.numbers, listOf()) })
    }
}
