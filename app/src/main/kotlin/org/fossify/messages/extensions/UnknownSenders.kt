package org.fossify.messages.extensions

import android.content.Context
import android.util.LruCache
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.models.Conversation

// Only used for the conversation lists. Cleared whenever a list screen resumes, since contacts may have changed meanwhile.
private val senderLookupCache = LruCache<String, ContactLookupResult>(512)

fun clearSenderLookupCache() = senderLookupCache.evictAll()

fun Context.getPrivateContacts(): ArrayList<SimpleContact> {
    val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    return MyContactsContentProvider.getSimpleContacts(this, privateCursor)
}

// All of these query contacts, so call them off the main thread.

fun Context.lookupSender(address: String, privateContacts: List<SimpleContact>): ContactLookupResult {
    if (address.isBlank()) return ContactLookupResult.Undetermined
    if (privateContacts.any { it.doesHavePhoneNumber(address) }) return ContactLookupResult.Found
    return SimpleContactsHelper(this).existsSync(address)
}

// An undetermined lookup (e.g. no contacts permission) is never treated as unknown.
fun Context.shouldSilenceSender(address: String): Boolean {
    if (!config.silenceUnknownSenders) return false
    return lookupSender(address, getPrivateContacts()) == ContactLookupResult.NotFound
}

// A group conversation is known if any participant is a contact.
fun Context.isUnknownConversation(conversation: Conversation, privateContacts: List<SimpleContact>): Boolean {
    val numbers = if (conversation.isGroupConversation) {
        getThreadPhoneNumbers(getThreadRecipientIds(conversation.threadId))
    } else {
        listOf(conversation.phoneNumber)
    }.filter { it.isNotBlank() }

    if (numbers.isEmpty()) return false

    val results = numbers.map { number ->
        senderLookupCache.get(number) ?: lookupSender(number, privateContacts).also {
            if (it != ContactLookupResult.Undetermined) senderLookupCache.put(number, it)
        }
    }

    return results.all { it == ContactLookupResult.NotFound }
}

fun Context.filterUnknownConversations(
    conversations: List<Conversation>,
    unknown: Boolean,
): ArrayList<Conversation> {
    val privateContacts = getPrivateContacts()
    return conversations.filterTo(ArrayList()) { isUnknownConversation(it, privateContacts) == unknown }
}
