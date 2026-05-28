package dev.hushyari.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val CLIPBOARD_PERMISSIONS = emptyList<String>()

private val CLIPBOARD_SAFETY_RULES = listOf(
    "checkScreenSensitivity",
    "checkAuthenticationScreen",
    "checkRateLimit",
)

/**
 * Clipboard tool: copy text to, read text from, and paste via the system clipboard.
 *
 * Actions:
 * - "copy_to_clipboard"    — copy text to the system clipboard
 * - "read_clipboard"       — read the current clipboard content
 * - "paste_from_clipboard" — trigger a paste into the focused field
 *
 * Params:
 * - "action" — one of the above actions
 * - "text"   — text to copy (required for copy_to_clipboard)
 * - "label"  — optional label for the clip (default "HushYari")
 *
 * Safety: read_clipboard is blocked on sensitive screens
 * (authentication, payment, privacy-sensitive).
 */
@Singleton
class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    override val name = "clipboard"
    override val description = "Copy, read, and paste text via the system clipboard"
    override val category = ToolCategory.CLIPBOARD
    override val requiredPermissions = CLIPBOARD_PERMISSIONS
    override val safetyRules = CLIPBOARD_SAFETY_RULES

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        if (action !in setOf("copy_to_clipboard", "read_clipboard", "paste_from_clipboard")) return false
        if (action == "copy_to_clipboard" && params["text"] == null) return false
        return true
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (copy_to_clipboard/read_clipboard/paste_from_clipboard)")

        return try {
            when (action) {
                "copy_to_clipboard" -> executeCopy(params)
                "read_clipboard" -> executeRead()
                "paste_from_clipboard" -> executePaste()
                else -> invalidParams("Unknown action: $action")
            }
        } catch (e: SecurityException) {
            ToolResult.Failure(
                toolName = name,
                error = "Clipboard access denied: ${e.message}",
                errorCode = ErrorCode.PERMISSION_DENIED,
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Clipboard error: ${e.message}",
                errorCode = ErrorCode.UNKNOWN,
            )
        }
    }

    private fun executeCopy(params: Map<String, Any?>): ToolResult {
        val text = params["text"]?.toString() ?: return invalidParams("text is required")
        val label = params["label"]?.toString() ?: "HushYari"

        if (text.length > 100_000) {
            return ToolResult.Failure(
                toolName = name,
                error = "Text too long for clipboard: ${text.length} chars (max 100000)",
                errorCode = ErrorCode.INVALID_PARAMS,
            )
        }

        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        return ToolResult.Success(
            toolName = name,
            message = "Copied ${text.length} characters to clipboard",
            data = mapOf(
                "char_count" to text.length,
                "label" to label,
                "preview" to text.take(100),
            ),
        )
    }

    private fun executeRead(): ToolResult {
        val clip = clipboard.primaryClip ?: return ToolResult.Success(
            toolName = name,
            message = "Clipboard is empty",
            data = mapOf("text" to "", "char_count" to 0),
        )

        if (clip.itemCount == 0) {
            return ToolResult.Success(
                toolName = name,
                message = "Clipboard has no items",
                data = mapOf("text" to "", "char_count" to 0),
            )
        }

        val item = clip.getItemAt(0)
        val text = item.coerceToText(context).toString()

        return ToolResult.Success(
            toolName = name,
            message = "Read ${text.length} characters from clipboard",
            data = mapOf(
                "text" to text,
                "char_count" to text.length,
                "preview" to text.take(200),
                "description_label" to (clip.description?.label ?: ""),
            ),
        )
    }

    private fun executePaste(): ToolResult {
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return ToolResult.Failure(
                toolName = name,
                error = "Clipboard is empty, nothing to paste",
                errorCode = ErrorCode.INVALID_PARAMS,
            )
        }

        return ToolResult.Success(
            toolName = name,
            message = "Paste from clipboard triggered",
            data = mapOf(
                "item_count" to clip.itemCount,
            ),
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
