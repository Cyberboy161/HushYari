package dev.hushyari.data.model

import android.graphics.Rect

/**
 * Represents a single UI element extracted from the Accessibility tree.
 * 🧠 PokeClaw + Roubao mechanic: Structured UI element model for tool targeting.
 */
data class UIElement(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val depth: Int,
    val childCount: Int,
    val drawingOrder: Int,
    val isVisible: Boolean = true,
) {
    val centerX: Float get() = bounds.exactCenterX()
    val centerY: Float get() = bounds.exactCenterY()
    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()

    val isButton: Boolean
        get() = isClickable && (className.contains("Button", ignoreCase = true) ||
                className.contains("ImageButton", ignoreCase = true))

    val isTextInput: Boolean
        get() = isEditable && className.contains("Edit", ignoreCase = true)

    val isText: Boolean
        get() = className.contains("Text", ignoreCase = true) && !isClickable

    val isImage: Boolean
        get() = (className.contains("Image", ignoreCase = true) ||
                className.contains("ImageView", ignoreCase = true)) && !isClickable

    val isContainer: Boolean
        get() = childCount > 0 && !isClickable

    val hasText: Boolean get() = text.isNotEmpty() || contentDescription.isNotEmpty()

    fun matches(query: ElementQuery): Boolean {
        query.text?.let { t ->
            if (!text.equals(t, ignoreCase = true) &&
                !contentDescription.equals(t, ignoreCase = true)
            ) return false
        }
        query.textContains?.let { t ->
            if (!text.contains(t, ignoreCase = true) &&
                !contentDescription.contains(t, ignoreCase = true)
            ) return false
        }
        query.contentDesc?.let { cd ->
            if (!contentDescription.equals(cd, ignoreCase = true)) return false
        }
        query.contentDescContains?.let { cd ->
            if (!contentDescription.contains(cd, ignoreCase = true)) return false
        }
        query.resourceId?.let { rid ->
            if (!resourceId.endsWith(rid)) return false
        }
        query.className?.let { cn ->
            if (!className.contains(cn, ignoreCase = true)) return false
        }
        query.isClickable?.let { c -> if (isClickable != c) return false }
        query.isScrollable?.let { s -> if (isScrollable != s) return false }
        query.isEditable?.let { e -> if (isEditable != e) return false }
        query.isEnabled?.let { e -> if (isEnabled != e) return false }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UIElement) return false
        return resourceId == other.resourceId &&
                bounds == other.bounds &&
                className == other.className
    }

    override fun hashCode(): Int {
        var result = resourceId.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + className.hashCode()
        return result
    }
}

data class ElementQuery(
    val text: String? = null,
    val textContains: String? = null,
    val contentDesc: String? = null,
    val contentDescContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val isClickable: Boolean? = null,
    val isScrollable: Boolean? = null,
    val isEditable: Boolean? = null,
    val isEnabled: Boolean? = null,
    val depthLimit: Int? = null,
    val requiresVisible: Boolean = true,
)
