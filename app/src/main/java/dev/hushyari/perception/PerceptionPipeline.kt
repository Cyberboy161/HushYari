package dev.hushyari.perception

import android.graphics.Bitmap
import dev.hushyari.data.model.OcrBlock
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.TemplateMatch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Perception orchestrator — captures screen state at varying fidelity levels
 * using parallel coroutine execution when visual/OCR capture is requested.
 *
 * **Mechanics:**
 * - 4x-game-agent Layer 1: Fast UI tree capture (~5ms) for real-time agent loop.
 * - PokeClaw: Multi-modal perception combining accessibility tree + screenshot + OCR.
 * - Roubao: Cached reads with 50ms TTL prevent duplicate processing in hot loops.
 */
@Singleton
class PerceptionPipeline @Inject constructor(
    private val accessibilityReader: AccessibilityReader,
    private val screenCapture: ScreenCapture,
    private val templateMatcher: TemplateMatcher,
    private val pixelClassifier: PixelClassifier,
    private val ocrEngine: OcrEngine,
    private val elementFinder: ElementFinder,
) {

    /**
     * Capture fidelity level controlling which perception sources are sampled.
     */
    enum class CaptureMode {
        /** UI tree only — ~5ms, no visual processing. */
        FAST,

        /** UI tree + screenshot — ~20ms. */
        VISUAL,

        /** UI tree + screenshot + OCR + template matching — ~50ms. */
        FULL,
    }

    private data class CachedState(
        val state: ScreenState,
        val capturedAt: Long,
        val mode: CaptureMode,
    )

    private var cached: CachedState? = null
    private val cacheTtlMs = 50L

    /**
     * Captures the current screen state at the requested [mode] fidelity level.
     *
     * Returns the cached state if a previous capture was made within the 50ms TTL
     * at an equal or higher fidelity mode. Otherwise, performs fresh capture,
     * running sub-captures in parallel where applicable.
     *
     * **4x-game-agent Layer 1 mechanic:** FAST mode feeds the tight agent loop;
     * FULL mode is used for decision-critical frames where OCR and template
     * matching improve LLM context quality.
     */
    suspend fun capture(mode: CaptureMode): ScreenState = coroutineScope {
        cached?.let { c ->
            if (System.currentTimeMillis() - c.capturedAt < cacheTtlMs && c.mode.ordinal >= mode.ordinal) {
                return@coroutineScope c.state
            }
        }

        val timedStart = System.currentTimeMillis()

        val treeDeferred = async { accessibilityReader.readUITree() }

        val screenshotDeferred = if (mode == CaptureMode.VISUAL || mode == CaptureMode.FULL) {
            async { screenCapture.capture() }
        } else {
            null
        }

        val ocrDeferred: Deferred<List<OcrBlock>>? = if (mode == CaptureMode.FULL) {
            async {
                val bitmap = screenshotDeferred?.await()
                if (bitmap != null) ocrEngine.recognizeText(bitmap) else emptyList()
            }
        } else {
            null
        }

        val templateDeferred: Deferred<List<TemplateMatcher.Match>>? = if (mode == CaptureMode.FULL) {
            async {
                val bitmap = screenshotDeferred?.await()
                if (bitmap != null) templateMatcher.matchActive(bitmap) else emptyList()
            }
        } else {
            null
        }

        val baseState = treeDeferred.await()
        val screenshot = screenshotDeferred?.await()
        val ocrBlocks = ocrDeferred?.await() ?: emptyList()
        val templateMatches = templateDeferred?.await()?.map {
            TemplateMatch(templateName = it.templateName, x = it.x, y = it.y, confidence = it.confidence)
        } ?: emptyList()

        val ocrText = if (ocrBlocks.isNotEmpty()) {
            ocrBlocks.joinToString(" ") { it.text }
        } else {
            ""
        }

        val classifiedScreen = if (screenshot != null && mode == CaptureMode.FULL) {
            pixelClassifier.classifyScreen(screenshot, null)?.screenName
        } else {
            null
        }

        val screenState = baseState.copy(
            screenshot = screenshot?.let { compressToJpegBytes(it, 80) },
            ocrText = ocrText,
            ocrBlocks = ocrBlocks,
            templateMatches = templateMatches,
            classifiedScreen = classifiedScreen,
        )

        val elapsed = System.currentTimeMillis() - timedStart
        Timber.d("PerceptionPipeline: captured %s mode in %dms", mode.name, elapsed)

        cached = CachedState(screenState, System.currentTimeMillis(), mode)
        screenState
    }

    /**
     * Invalidates the internal cache, forcing the next [capture] call to
     * perform a fresh capture regardless of TTL.
     */
    fun invalidateCache() {
        cached = null
    }

    /**
     * Convenience: returns only the UI tree parsed from the accessibility service.
     */
    suspend fun readUITree(): ScreenState = accessibilityReader.readUITree()

    /**
     * Convenience: captures a screenshot as a [Bitmap].
     */
    suspend fun captureScreenshot(): Bitmap? = screenCapture.capture()

    /**
     * Convenience: finds an element using all strategies.
     */
    suspend fun findElement(params: dev.hushyari.data.model.ElementQuery): dev.hushyari.data.model.UIElement? {
        val findParams = ElementFinder.FindParams(
            text = params.text,
            textContains = params.textContains,
            contentDesc = params.contentDesc,
            contentDescContains = params.contentDescContains,
            resourceId = params.resourceId,
            className = params.className,
            isClickable = params.isClickable,
            isScrollable = params.isScrollable,
            isEditable = params.isEditable,
        )
        return elementFinder.find(findParams)?.let { found ->
            dev.hushyari.data.model.UIElement(
                className = found.className ?: "android.view.View",
                text = found.text ?: "",
                contentDescription = found.contentDescription ?: "",
                resourceId = found.resourceId ?: "",
                bounds = found.bounds ?: android.graphics.Rect(found.x, found.y, found.x + 10, found.y + 10),
                isClickable = found.isClickable,
                isScrollable = found.isScrollable,
                isEditable = found.isEditable,
                isChecked = false,
                isFocused = false,
                isEnabled = true,
                depth = 0,
                childCount = 0,
                drawingOrder = 0,
            )
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun compressToJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
