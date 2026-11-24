package org.fossify.messages.extensions

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.PhoneNumberUtils
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.models.Conversation
import java.util.concurrent.ConcurrentHashMap

// Kept for as long as the app process runs. Refreshed from the unknown senders screen menu, and per number by
// notifications and by saving a number to contacts from the unknown senders screen.
private val senderLookupCache = ConcurrentHashMap<String, ContactLookupResult>()
private val sendersToRecheck = ConcurrentHashMap.newKeySet<String>()

private val SENT_SMS_TYPES = listOf(
    Sms.MESSAGE_TYPE_SENT, Sms.MESSAGE_TYPE_OUTBOX, Sms.MESSAGE_TYPE_FAILED, Sms.MESSAGE_TYPE_QUEUED
)
private val SENT_MMS_BOXES = listOf(Mms.MESSAGE_BOX_SENT, Mms.MESSAGE_BOX_OUTBOX, Mms.MESSAGE_BOX_FAILED)

fun clearSenderLookupCache() = senderLookupCache.clear()

// For numbers that may be saved to contacts outside the app; call recheckSenders() once back.
fun markSenderForRecheck(number: String) {
    sendersToRecheck.add(number)
}

fun recheckSenders() {
    sendersToRecheck.toList().forEach {
        senderLookupCache.remove(it)
        sendersToRecheck.remove(it)
    }
}

fun Context.getPrivateContacts(): ArrayList<SimpleContact> {
    val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    return MyContactsContentProvider.getSimpleContacts(this, privateCursor)
}

// All of these query contacts or messages, so call them off the main thread.

fun Context.lookupSender(address: String, privateContacts: List<SimpleContact>): ContactLookupResult {
    if (address.isBlank()) return ContactLookupResult.Undetermined
    if (privateContacts.any { it.doesHavePhoneNumber(address) }) return ContactLookupResult.Found
    return SimpleContactsHelper(this).existsSync(address)
}

fun Context.isKnownSender(address: String): Boolean {
    return config.knownSenders.any { it == address || PhoneNumberUtils.compare(it, address) }
}

fun Context.getThreadNumbers(threadId: Long): List<String> {
    return getThreadPhoneNumbers(getThreadRecipientIds(threadId)).filter { it.isNotBlank() }
}

fun Context.getConversationNumbers(conversation: Conversation): List<String> {
    return if (conversation.isGroupConversation) {
        getThreadNumbers(conversation.threadId)
    } else {
        listOf(conversation.phoneNumber).filter { it.isNotBlank() }
    }
}

// Includes failed and queued messages: trying to text someone is enough to know them.
fun Context.hasSentMessageInThread(threadId: Long): Boolean {
    val threadArgs = arrayOf(threadId.toString())
    return hasAnyRow(
        uri = Sms.CONTENT_URI,
        selection = "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} IN (${SENT_SMS_TYPES.joinToString()})",
        selectionArgs = threadArgs
    ) || hasAnyRow(
        uri = Mms.CONTENT_URI,
        selection = "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} IN (${SENT_MMS_BOXES.joinToString()})",
        selectionArgs = threadArgs
    )
}

private fun Context.hasAnyRow(uri: Uri, selection: String, selectionArgs: Array<String>): Boolean {
    return try {
        contentResolver.query(uri, arrayOf(BaseColumns._ID), selection, selectionArgs, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) {
        false
    }
}

// A thread is unknown when no participant is a contact or a known sender and we never sent a message in it, so a group
// conversation is known if any participant is. An undetermined lookup (e.g. no contacts permission) is never unknown.
fun Context.isUnknownThread(
    threadId: Long,
    numbers: List<String>,
    privateContacts: List<SimpleContact>,
    useCache: Boolean = true,
): Boolean {
    if (numbers.isEmpty()) return false
    if (numbers.any { isKnownSender(it) }) return false

    val results = numbers.map { number ->
        val cached = if (useCache) senderLookupCache[number] else null
        cached ?: lookupSender(number, privateContacts).also {
            if (it != ContactLookupResult.Undetermined) senderLookupCache[number] = it
        }
    }

    if (!results.all { it == ContactLookupResult.NotFound }) return false
    return !hasSentMessageInThread(threadId)
}

// Always does a fresh lookup, since contacts may have changed since the lists were loaded.
fun Context.shouldSilenceSender(address: String, threadId: Long): Boolean {
    if (!config.silenceUnknownSenders) return false
    // blocking takes precedence: the receivers already dropped messages from numbers not in contacts, using contacts only
    if (baseConfig.blockUnknownNumbers) return false
    val numbers = getThreadNumbers(threadId).ifEmpty { listOf(address) }
    return isUnknownThread(threadId, numbers, getPrivateContacts(), useCache = false)
}

fun Context.isUnknownConversation(conversation: Conversation, privateContacts: List<SimpleContact>): Boolean {
    return isUnknownThread(conversation.threadId, getConversationNumbers(conversation), privateContacts)
}

// Returns the known conversations first and the unknown ones second
fun Context.partitionUnknownConversations(
    conversations: List<Conversation>,
): Pair<ArrayList<Conversation>, ArrayList<Conversation>> {
    val privateContacts = getPrivateContacts()
    val known = ArrayList<Conversation>()
    val unknown = ArrayList<Conversation>()
    conversations.forEach {
        if (isUnknownConversation(it, privateContacts)) unknown.add(it) else known.add(it)
    }
    return Pair(known, unknown)
}

fun Context.filterUnknownConversations(
    conversations: List<Conversation>,
    unknown: Boolean,
): ArrayList<Conversation> {
    val (knownConversations, unknownConversations) = partitionUnknownConversations(conversations)
    return if (unknown) unknownConversations else knownConversations
}
