package dev.hushyari.perception

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Template matching engine for locating known UI elements by image.
 *
 * Supports preloading named templates and performing multi-scale template matching
 * against screenshots. Falls back to Kotlin pixel-by-pixel matching when native
 * JNI implementation is unavailable.
 *
 * **Mechanics:**
 * - ClickClickClick: Finder role — template matching is strategy 3 in the multi-strategy
 *   element finding pipeline.
 * - PokeClaw: NDK bridge for performance — native template matching via JNI for
 *   real-time game automation; Kotlin fallback for broader device compatibility.
 */
@Singleton
class TemplateMatcher @Inject constructor() {

    companion object {
        private const val DEFAULT_THRESHOLD = 0.85f
        private const val MAX_SCALES = 4
        private const val SCALE_STEP = 0.1f
        private const val SUBSAMPLE_STEP = 4
    }

    /**
     * Result of a template match operation.
     */
    data class Match(
        val templateName: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val confidence: Float,
    ) {
        val centerX: Int get() = x + width / 2
        val centerY: Int get() = y + height / 2
        val bounds: Rect get() = Rect(x, y, x + width, y + height)
    }

    private val templates = ConcurrentHashMap<String, Bitmap>()

    // ------------------------------------------------------------------
    // Template management
    // ------------------------------------------------------------------

    /**
     * Preloads a named template for subsequent matching.
     * Overwrites any existing template with the same name.
     */
    fun loadTemplate(name: String, template: Bitmap) {
        val recycled = templates.put(name, template)
        if (recycled != null && recycled != template) {
            Timber.d("TemplateMatcher: replaced template '$name'")
        }
    }

    /**
     * Removes a loaded template.
     */
    fun unloadTemplate(name: String) {
        templates.remove(name)?.let {
            if (!it.isRecycled) {
                Timber.d("TemplateMatcher: unloaded template '$name'")
            }
        }
    }

    /**
     * Clears all loaded templates.
     */
    fun clearTemplates() {
        templates.clear()
        Timber.d("TemplateMatcher: all templates cleared")
    }

    /**
     * Returns the number of currently loaded templates.
     */
    fun templateCount(): Int = templates.size

    // ------------------------------------------------------------------
    // Single template matching
    // ------------------------------------------------------------------

    /**
     * Matches a single [templateBitmap] against [screenBitmap] at [threshold]
     * confidence level.
     *
     * Performs multi-scale matching when [multiScale] is true, testing the template
     * at [MAX_SCALES] scales to handle resolution differences between the template
     * source and the current device screen.
     */
    fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Float = DEFAULT_THRESHOLD,
        multiScale: Boolean = true,
    ): List<Match> {
        if (screenBitmap.isRecycled || templateBitmap.isRecycled) {
            Timber.w("TemplateMatcher: recycled bitmap passed to matchTemplate")
            return emptyList()
        }

        val results = mutableListOf<Match>()

        if (isNativeAvailable()) {
            return nativeMatchTemplate(screenBitmap, templateBitmap, threshold).toList()
        }

        if (multiScale) {
            for (scale in 0 until MAX_SCALES) {
                val scaleFactor = 1.0f - scale * SCALE_STEP
                if (scaleFactor < 0.6f) break

                val scaledTemplate = if (scale == 0) {
                    templateBitmap
                } else {
                    val newW = (templateBitmap.width * scaleFactor).toInt()
                    val newH = (templateBitmap.height * scaleFactor).toInt()
                    if (newW < 4 || newH < 4) break
                    Bitmap.createScaledBitmap(templateBitmap, newW, newH, true)
                }

                val matches = matchTemplateKotlin(screenBitmap, scaledTemplate, threshold)
                results.addAll(matches)

                if (scaledTemplate != templateBitmap && !scaledTemplate.isRecycled) {
                    scaledTemplate.recycle()
                }

                if (results.isNotEmpty()) break
            }
        } else {
            results.addAll(matchTemplateKotlin(screenBitmap, templateBitmap, threshold))
        }

        // Non-maximum suppression to deduplicate overlapping detections
        return nonMaximumSuppression(results)
    }

    // ------------------------------------------------------------------
    // Active template matching
    // ------------------------------------------------------------------

    /**
     * Matches all currently loaded templates against [screenBitmap].
     * Returns a flattened list of matches across all templates.
     *
     * **ClickClickClick Finder mechanic:** Called during FULL capture mode to
     * simultaneously test all known UI templates against the current screen.
     */
    fun matchActive(screenBitmap: Bitmap): List<Match> {
        if (templates.isEmpty()) return emptyList()

        val allMatches = mutableListOf<Match>()

        for ((name, template) in templates) {
            val matches = matchTemplate(screenBitmap, template)
            allMatches.addAll(matches.map { it.copy(templateName = name) })
        }

        Timber.d("TemplateMatcher: matchActive found ${allMatches.size} matches across ${templates.size} templates")
        return allMatches
    }

    // ------------------------------------------------------------------
    // Native check and bridge
    // ------------------------------------------------------------------

    /**
     * Returns true when native template matching is available via JNI.
     */
    private fun isNativeAvailable(): Boolean {
        return try {
            System.loadLibrary("hushyari_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            Timber.d("TemplateMatcher: native library not available, using Kotlin fallback")
            false
        }
    }

    /**
     * Native template matching via JNI.
     * Expected JNI signature:
     *   jobjectArray nativeMatchTemplate(Bitmap screen, Bitmap template, jfloat threshold)
     */
    private external fun nativeMatchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Float,
    ): Array<Match>

    // ------------------------------------------------------------------
    // Kotlin fallback matching
    // ------------------------------------------------------------------

    private fun matchTemplateKotlin(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
    ): List<Match> {
        val results = mutableListOf<Match>()
        val screenW = screen.width
        val screenH = screen.height
        val templateW = template.width
        val templateH = template.height

        if (templateW > screenW || templateH > screenH) return results

        val screenPixels = IntArray(screenW * screenH)
        screen.getPixels(screenPixels, 0, screenW, 0, 0, screenW, screenH)

        val templatePixels = IntArray(templateW * templateH)
        template.getPixels(templatePixels, 0, templateW, 0, 0, templateW, templateH)

        val templatePixelCount = templateW * templateH
        val searchWidth = screenW - templateW + 1
        val searchHeight = screenH - templateH + 1

        for (y in 0 until searchHeight step SUBSAMPLE_STEP) {
            for (x in 0 until searchWidth step SUBSAMPLE_STEP) {
                var matchCount = 0
                for (ty in 0 until templateH) {
                    for (tx in 0 until templateW) {
                        val screenIdx = (y + ty) * screenW + (x + tx)
                        val templateIdx = ty * templateW + tx

                        if (colorsSimilar(screenPixels[screenIdx], templatePixels[templateIdx], 40)) {
                            matchCount++
                        }
                    }
                }

                val confidence = matchCount.toFloat() / templatePixelCount.toFloat()

                if (confidence >= threshold) {
                    // Refine: perform full-resolution check around this candidate
                    val refined = refineMatch(screenPixels, screenW, templatePixels, templateW, templateH, x, y)
                    if (refined >= threshold) {
                        results.add(
                            Match(
                                templateName = "",
                                x = x,
                                y = y,
                                width = templateW,
                                height = templateH,
                                confidence = refined,
                            )
                        )
                    }
                }
            }
        }

        return results
    }

    private fun refineMatch(
        screenPixels: IntArray,
        screenStride: Int,
        templatePixels: IntArray,
        templateW: Int,
        templateH: Int,
        startX: Int,
        startY: Int,
    ): Float {
        val templatePixelCount = templateW * templateH
        var bestConfidence = 0f

        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = startX + dx
                val y = startY + dy
                if (x < 0 || y < 0) continue

                var matchCount = 0
                for (ty in 0 until templateH) {
                    for (tx in 0 until templateW) {
                        val screenIdx = (y + ty) * screenStride + (x + tx)
                        if (screenIdx >= screenPixels.size) continue
                        val templateIdx = ty * templateW + tx
                        if (colorsSimilar(screenPixels[screenIdx], templatePixels[templateIdx], 30)) {
                            matchCount++
                        }
                    }
                }
                val confidence = matchCount.toFloat() / templatePixelCount.toFloat()
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                }
            }
        }

        return bestConfidence
    }

    private fun colorsSimilar(a: Int, b: Int, tolerance: Int): Boolean {
        val dr = kotlin.math.abs(Color.red(a) - Color.red(b))
        val dg = kotlin.math.abs(Color.green(a) - Color.green(b))
        val db = kotlin.math.abs(Color.blue(a) - Color.blue(b))
        return dr <= tolerance && dg <= tolerance && db <= tolerance
    }

    // ------------------------------------------------------------------
    // Non-maximum suppression
    // ------------------------------------------------------------------

    private fun nonMaximumSuppression(matches: List<Match>): List<Match> {
        if (matches.size <= 1) return matches

        val sorted = matches.sortedByDescending { it.confidence }
        val suppressed = BooleanArray(sorted.size)
        val result = mutableListOf<Match>()

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            result.add(sorted[i])

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i].bounds, sorted[j].bounds) > 0.3f) {
                    suppressed[j] = true
                }
            }
        }

        return result
    }

    private fun iou(a: Rect, b: Rect): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) return 0f

        val intersection = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - intersection

        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }
}
