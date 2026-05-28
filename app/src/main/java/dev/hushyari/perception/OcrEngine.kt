package dev.hushyari.perception

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.hushyari.data.model.OcrBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device OCR engine using Google ML Kit Text Recognition.
 *
 * Recognizes text from screen captures, returning structured [OcrBlock] entries
 * with bounding boxes and confidence scores. Caches results by bitmap content hash
 * for 500ms to avoid redundant processing in hot capture loops.
 *
 * **Mechanics:**
 * - PokeClaw: OCR feeds Layer 2 (decision) LLM prompts with numeric values,
 *   resource counts, and timer readings extracted from game UIs.
 * - 4x-game-agent Layer 1: OCR runs during FULL capture mode for maximum fidelity
 *   perception before decision-critical agent steps.
 */
@Singleton
class OcrEngine @Inject constructor() {

    companion object {
        private const val CACHE_TTL_MS = 500L
        private val NUMBER_PATTERN = Regex("\\d+[.,]?\\d*")
        private val TIMER_PATTERN = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
    }

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class CachedResult(
        val blocks: List<OcrBlock>,
        val recognizedAt: Long,
    )

    @Volatile
    private var cachedResult: CachedResult? = null
    private var cachedBitmapHash: String? = null

    // ------------------------------------------------------------------
    // Text recognition
    // ------------------------------------------------------------------

    /**
     * Recognizes text in [bitmap] and returns structured [OcrBlock] entries.
     *
     * Results are cached by bitmap content hash for [CACHE_TTL_MS] to avoid
     * redundant ML Kit calls when the screen hasn't changed meaningfully.
     *
     * **PokeClaw mechanic:** OCR text extracts numeric values (resource counts,
     * timers, stats) that augment the accessibility tree's structural data for
     * richer LLM context.
     */
    suspend fun recognizeText(bitmap: Bitmap): List<OcrBlock> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) return@withContext emptyList<OcrBlock>()

        val hash = computeBitmapHash(bitmap)

        cachedResult?.let { cached ->
            if (cachedBitmapHash == hash &&
                System.currentTimeMillis() - cached.recognizedAt < CACHE_TTL_MS
            ) {
                Timber.d("OcrEngine: returning cached OCR result (${cached.blocks.size} blocks)")
                return@withContext cached.blocks
            }
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val result = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val blocks = mutableListOf<OcrBlock>()

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val bounds = line.boundingBox ?: Rect()
                            val confidence = line.confidence ?: 1.0f
                            val text = line.text

                            if (text.isNotBlank()) {
                                blocks.add(
                                    OcrBlock(
                                        text = text,
                                        bounds = bounds,
                                        confidence = confidence,
                                    )
                                )
                            }
                        }
                    }

                    cachedResult = CachedResult(blocks, System.currentTimeMillis())
                    cachedBitmapHash = hash

                    Timber.d("OcrEngine: recognized ${blocks.size} text blocks")
                    continuation.resume(blocks)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "OcrEngine: text recognition failed")
                    continuation.resume(emptyList())
                }
        }

        result
    }

    /**
     * Recognizes text and returns it as a single concatenated string.
     */
    suspend fun recognizeTextAsString(bitmap: Bitmap): String {
        val blocks = recognizeText(bitmap)
        return blocks.joinToString(" ") { it.text }
    }

    /**
     * Extracts all numeric values (integers and decimals) from OCR text.
     *
     * **PokeClaw mechanic:** Numeric extraction feeds resource tracking and
     * world model updates. Example: "Gold: 12,345" -> [12345].
     */
    suspend fun extractNumbers(bitmap: Bitmap): List<Float> {
        val text = recognizeTextAsString(bitmap)
        return NUMBER_PATTERN.findAll(text)
            .map { it.value.replace(",", "").toFloatOrNull() }
            .filterNotNull()
            .toList()
    }

    /**
     * Extracts timer values in HH:MM:SS or MM:SS format from OCR text.
     *
     * **4x-game-agent mechanic:** Timer extraction feeds [WorldState.activeTimers]
     * for real-time countdown tracking in game automation loops.
     */
    suspend fun extractTimers(bitmap: Bitmap): List<String> {
        val text = recognizeTextAsString(bitmap)
        return TIMER_PATTERN.findAll(text)
            .map { it.value }
            .toList()
    }

    /**
     * Finds the first [OcrBlock] whose text contains [query] (case-insensitive).
     *
     * **ClickClickClick Finder mechanic:** Used as strategy 2 (OCR text location)
     * in the multi-strategy element finding pipeline.
     */
    suspend fun findText(bitmap: Bitmap, query: String): OcrBlock? {
        val blocks = recognizeText(bitmap)
        return blocks.firstOrNull { block ->
            block.text.contains(query, ignoreCase = true)
        }
    }

    /**
     * Finds all [OcrBlock] entries whose text contains [query] (case-insensitive).
     */
    suspend fun findAllText(bitmap: Bitmap, query: String): List<OcrBlock> {
        val blocks = recognizeText(bitmap)
        return blocks.filter { block ->
            block.text.contains(query, ignoreCase = true)
        }
    }

    /**
     * Finds text near a specific coordinate, returning the closest block.
     */
    suspend fun findTextNear(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        maxDistance: Int = 100,
    ): OcrBlock? {
        val blocks = recognizeText(bitmap)
        return blocks
            .mapNotNull { block ->
                val dx = block.bounds.centerX() - x
                val dy = block.bounds.centerY() - y
                val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
                if (dist <= maxDistance) block to dist else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    // ------------------------------------------------------------------
    // Cache management
    // ------------------------------------------------------------------

    /**
     * Invalidates the OCR result cache, forcing the next [recognizeText] call
     * to run fresh recognition.
     */
    fun invalidateCache() {
        cachedResult = null
        cachedBitmapHash = null
    }

    /**
     * Closes the ML Kit recognizer and releases resources.
     */
    fun close() {
        recognizer.close()
        invalidateCache()
        Timber.d("OcrEngine: closed")
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun computeBitmapHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Sample every 4th pixel for fast hashing
        val stride = 4
        for (i in pixels.indices step stride) {
            val pixel = pixels[i]
            digest.update((pixel shr 24).toByte())
            digest.update((pixel shr 16).toByte())
            digest.update((pixel shr 8).toByte())
            digest.update(pixel.toByte())
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
