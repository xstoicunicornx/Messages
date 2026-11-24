package org.fossify.messages.adapters

import android.view.Menu
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getConversationNumbers
import org.fossify.messages.extensions.markSenderForRecheck
import org.fossify.messages.helpers.refreshConversations

class UnknownSendersAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : ConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {

    override fun prepareActionMode(menu: Menu) {
        // adds to the regular conversation actions instead of duplicating cab_conversations
        if (menu.findItem(R.id.cab_move_to_known_senders) == null) {
            activity.menuInflater.inflate(R.menu.cab_unknown_senders, menu)
        }
        super.prepareActionMode(menu)
    }

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_move_to_known_senders -> moveToKnownSenders()
            R.id.cab_add_number_to_contact -> {
                // the contacts app may save it, so look it up again when we're back
                getSelectedItems().firstOrNull()?.let { markSenderForRecheck(it.phoneNumber) }
                super.actionItemPressed(id)
            }

            else -> super.actionItemPressed(id)
        }
    }

    // For group conversations every participant becomes known
    private fun moveToKnownSenders() {
        val conversations = getSelectedItems()
        if (conversations.isEmpty()) {
            return
        }

        ensureBackgroundThread {
            val numbers = conversations.flatMap { activity.getConversationNumbers(it) }
            activity.config.addKnownSenders(numbers)

            val newList = currentList.toMutableList().apply { removeAll(conversations) }
            activity.runOnUiThread {
                submitList(newList)
                finishActMode()
                refreshConversations()
            }
        }
    }
}
