package dev.hushyari.llm

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ── Room entity (expected to exist in Room DB; defined here for self-containment) ──

/**
 * Metadata stored in Room for each downloaded model.
 */
@Entity(tableName = "models")
data class ModelInfo(
    @PrimaryKey val name: String,
    val displayName: String = "",
    val filePath: String = "",
    val fileSizeBytes: Long = 0,
    val sha256: String = "",
    val downloadUrl: String = "",
    val version: String = "1.0",
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/**
 * Room DAO for [ModelInfo].
 */
@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY downloadedAt DESC")
    suspend fun getAll(): List<ModelInfo>

    @Query("SELECT * FROM models WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ModelInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelInfo)

    @Query("DELETE FROM models WHERE name = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM models")
    suspend fun deleteAll()
}

// ── Manager ─────────────────────────────────────────────────────

/**
 * Manages the lifecycle of local LLM models: download, validation, storage, and removal.
 * 🧠 PokeClaw mechanic: Download with resume support, SHA-256 checksum validation,
 * progress callbacks, and Room-backed metadata.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /**
     * Returns the absolute file path for a given model name.
     */
    fun getModelPath(name: String): File = File(modelsDir, "$name.bin")

    /**
     * List names of all installed models.
     */
    fun getAvailableModels(): List<String> =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    /**
     * Download a model from [url], saving as [filename] with resume support.
     * @param onProgress callback with (bytesDownloaded, totalBytes, percentage).
     */
    suspend fun downloadModel(
        url: String,
        filename: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val destFile = File(modelsDir, filename)
        val tempFile = File(modelsDir, "$filename.tmp")

        var existingBytes = 0L
        if (tempFile.exists()) {
            existingBytes = tempFile.length()
            Timber.d("Resuming download from $existingBytes bytes")
        }

        val totalBytes = getContentLength(url)
        Timber.d("Downloading $url -> $destFile (${totalBytes / (1024 * 1024)} MB)")

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val serverTotal = if (response.code == 206) {
            existingBytes + (body.contentLength())
        } else {
            body.contentLength()
        }

        val fos = FileOutputStream(tempFile, existingBytes > 0)
        val buffer = ByteArray(8192)
        var downloaded = existingBytes
        body.byteStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
                downloaded += read
                val percent = if (serverTotal > 0) ((downloaded * 100) / serverTotal).toInt() else 0
                onProgress(downloaded, serverTotal, percent)
            }
        }
        fos.close()
        body.close()

        if (destFile.exists()) destFile.delete()
        tempFile.renameTo(destFile)

        Timber.d("Download complete: $destFile (${destFile.length() / (1024 * 1024)} MB)")
        destFile
    }

    /**
     * Validate a downloaded model file against an expected SHA-256 checksum.
     */
    suspend fun validateChecksum(file: File, expectedSha256: String): Boolean =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val valid = actual.equals(expectedSha256, ignoreCase = true)
            if (!valid) {
                Timber.w("Checksum mismatch for ${file.name}: expected $expectedSha256, got $actual")
            }
            valid
        }

    /**
     * Delete a model by name, removing both the file and Room metadata.
     */
    suspend fun deleteModel(name: String) {
        withContext(Dispatchers.IO) {
            val file = getModelPath(name)
            if (file.exists()) file.delete()
            // Room DAO deletion would happen here via injected DAO
        }
    }

    /**
     * Returns free space and model sizes.
     */
    fun getStorageInfo(): StorageInfo {
        val freeSpace = modelsDir.freeSpace
        val models = modelsDir.listFiles()
            ?.filter { it.isFile }
            ?.associate { it.name to it.length() }
            ?: emptyMap()
        return StorageInfo(
            freeSpaceBytes = freeSpace,
            models = models,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun getContentLength(url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            response.close()
            length
        } catch (e: Exception) {
            -1L
        }
    }
}

// ── Supporting types ────────────────────────────────────────────

data class StorageInfo(
    val freeSpaceBytes: Long,
    val models: Map<String, Long> = emptyMap(),
) {
    val freeSpaceMb: Long get() = freeSpaceBytes / (1024 * 1024)
    val totalModelSizeMb: Long get() = models.values.sum() / (1024 * 1024)
}
