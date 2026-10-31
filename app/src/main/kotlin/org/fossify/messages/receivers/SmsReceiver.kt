package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.AiConfigProvider
import org.fossify.messages.helpers.AiDecisionLogger
import org.fossify.messages.helpers.AiRequestHelper
import org.fossify.messages.helpers.AiResult
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                if (isMessageFilteredOut(appContext, body)) return@ensureBackgroundThread
                if (appContext.isNumberBlocked(address)) return@ensureBackgroundThread
                if (appContext.baseConfig.blockUnknownNumbers) {
                    val privateCursor =
                        appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                    val result = SimpleContactsHelper(appContext).existsSync(address, privateCursor)
                    if (result == ContactLookupResult.NotFound) return@ensureBackgroundThread
                }

                val date = System.currentTimeMillis()
                val threadId = appContext.getThreadId(address)
                val subscriptionId = intent.getIntExtra("subscription", -1)

                handleMessageSync(
                    context = appContext,
                    address = address,
                    subject = subject,
                    body = body,
                    date = date,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    status = status
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int = 0,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int
    ) {
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)

        val newMessageId = context.insertNewSMS(
            address = address,
            subject = subject,
            body = body,
            date = date,
            read = read,
            threadId = threadId,
            type = type,
            subscriptionId = subscriptionId
        )

        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        val decision = classifyMessage(context, newMessageId, threadId, senderName, body)

        refreshMessages()
        refreshConversations()

        if (decision.notify) {
            context.showReceivedMessageNotification(
                messageId = newMessageId,
                isMms = false,
                address = address,
                senderName = senderName,
                body = body,
                threadId = threadId,
                bitmap = bitmap,
                otp = decision.otp
            )
        }
    }

    private fun classifyMessage(
        context: Context,
        messageId: Long,
        threadId: Long,
        senderName: String,
        body: String
    ): AiNotificationDecision {
        val config = AiConfigProvider(context)
        if (!config.isConfigured()) {
            return AiNotificationDecision(notify = true, otp = "")
        }

        return when (val result = AiRequestHelper.classify(context, senderName, body)) {
            is AiResult.Classified -> {
                val classification = result.classification
                val category = classification.category.wireName
                val reasoning = classification.reasoning
                runCatching {
                    context.messagesDB.updateAiClassification(messageId, reasoning, category)
                }
                runCatching {
                    context.conversationsDB.updateAiCategory(threadId, category)
                }
                val status = if (classification.notify) {
                    AiDecisionLogger.STATUS_NOTIFIED
                } else {
                    AiDecisionLogger.STATUS_SUPPRESSED
                }
                AiDecisionLogger.logDecision(
                    context = context,
                    senderName = senderName,
                    messagePreview = body,
                    notify = classification.notify,
                    reasoning = reasoning,
                    category = category,
                    status = status
                )
                AiNotificationDecision(classification.notify, classification.otp)
            }

            is AiResult.Failure -> {
                AiDecisionLogger.logDecision(
                    context = context,
                    senderName = senderName,
                    messagePreview = body,
                    notify = true,
                    reasoning = result.reason,
                    category = "",
                    status = AiDecisionLogger.STATUS_ERROR
                )
                AiNotificationDecision(notify = true, otp = "")
            }
        }
    }

    private data class AiNotificationDecision(
        val notify: Boolean,
        val otp: String
    )
}
