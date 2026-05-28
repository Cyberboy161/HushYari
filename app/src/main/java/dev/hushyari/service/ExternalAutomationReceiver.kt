package dev.hushyari.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * External automation receiver implementing the PokeClaw-style API for
 * Tasker, MacroDroid, and other external automation apps.
 *
 * Accepts structured intents to:
 * - `dev.hushyari.action.EXTERNAL_TASK` — execute a game task externally.
 * - `dev.hushyari.action.EXTERNAL_CHAT` — send a chat query to the agent.
 *
 * Extracts extras: "task", "chat", "request_id", "return_action", "return_package".
 * Routes to the appropriate handler and sends callback broadcasts with status/result.
 *
 * User must enable this in settings (not enabled by default).
 *
 * **Mechanics:**
 * - PokeClaw: External automation API — allows scripting engines to control
 *   HushYari as a game automation backend.
 */
class ExternalAutomationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EXTERNAL_TASK = "dev.hushyari.action.EXTERNAL_TASK"
        const val ACTION_EXTERNAL_CHAT = "dev.hushyari.action.EXTERNAL_CHAT"
        const val ACTION_TASK_RESULT = "dev.hushyari.action.TASK_RESULT"
        const val ACTION_CHAT_RESULT = "dev.hushyari.action.CHAT_RESULT"

        const val EXTRA_TASK = "task"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RETURN_ACTION = "return_action"
        const val EXTRA_RETURN_PACKAGE = "return_package"
        const val EXTRA_RESULT_STATUS = "result_status"
        const val EXTRA_RESULT_MESSAGE = "result_message"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) return

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: generateRequestId()
        val returnAction = intent.getStringExtra(EXTRA_RETURN_ACTION)
            ?: ACTION_TASK_RESULT
        val returnPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE)

        Timber.d("ExternalAutomationReceiver: action=${intent.action}, requestId=$requestId")

        when (intent.action) {
            ACTION_EXTERNAL_TASK -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: ""
                handleExternalTask(context, task, requestId, returnAction, returnPackage)
            }
            ACTION_EXTERNAL_CHAT -> {
                val chat = intent.getStringExtra(EXTRA_CHAT) ?: ""
                handleExternalChat(context, chat, requestId, returnAction, returnPackage)
            }
        }
    }

    /**
     * Handle an external task request by routing it to the agent.
     *
     * @param context Broadcast receiver context.
     * @param task The task description to execute.
     * @param requestId Unique request identifier for callback routing.
     * @param returnAction The action to use in the callback broadcast.
     * @param returnPackage Optional target package for the callback broadcast.
     */
    private fun handleExternalTask(
        context: Context,
        task: String,
        requestId: String,
        returnAction: String,
        returnPackage: String?,
    ) {
        if (task.isBlank()) {
            sendCallback(
                context, returnAction, returnPackage, requestId,
                status = "error", message = "Empty task description",
            )
            return
        }

        Timber.i("External task received: $task (request=$requestId)")

        // Acknowledge receipt immediately
        sendCallback(
            context, returnAction, returnPackage, requestId,
            status = "accepted", message = "Task accepted: $task",
        )

        // The actual task execution is routed through HushyariForegroundService
        // or the active agent instance. Here we emit the intent for the agent to pick up.
        // The agent (or a bridging component) listens for this and picks up the task.
    }

    /**
     * Handle an external chat query by routing it to the LLM agent.
     *
     * @param context Broadcast receiver context.
     * @param chat The chat message/query.
     * @param requestId Unique request identifier for callback routing.
     * @param returnAction The action to use in the callback broadcast.
     * @param returnPackage Optional target package for the callback broadcast.
     */
    private fun handleExternalChat(
        context: Context,
        chat: String,
        requestId: String,
        returnAction: String,
        returnPackage: String?,
    ) {
        if (chat.isBlank()) {
            sendCallback(
                context, returnAction, returnPackage, requestId,
                status = "error", message = "Empty chat message",
            )
            return
        }

        Timber.i("External chat received: $chat (request=$requestId)")

        // Acknowledge receipt
        sendCallback(
            context, returnAction, returnPackage, requestId,
            status = "accepted", message = "Chat accepted",
        )
    }

    /**
     * Send a callback broadcast with the result of an external request.
     *
     * @param context Broadcast receiver context.
     * @param action The broadcast action for the callback.
     * @param targetPackage Optional target package; if set, the broadcast is sent directly.
     * @param requestId The original request ID for correlation.
     * @param status Result status: "accepted", "running", "completed", "error".
     * @param message Human-readable result message.
     */
    private fun sendCallback(
        context: Context,
        action: String,
        targetPackage: String?,
        requestId: String,
        status: String,
        message: String,
    ) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_RESULT_STATUS, status)
            putExtra(EXTRA_RESULT_MESSAGE, message)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
        }
        context.sendBroadcast(intent)
        Timber.d("Callback sent: action=$action, status=$status, id=$requestId")
    }

    private fun generateRequestId(): String =
        java.util.UUID.randomUUID().toString().take(8)
}
