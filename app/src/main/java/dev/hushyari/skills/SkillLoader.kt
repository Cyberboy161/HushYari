package dev.hushyari.skills

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hushyari.data.model.Skill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and saves skills from JSON files on assets and external storage.
 *
 * Parses skills using kotlinx.serialization with validation and error handling.
 * Supports version migration for older skill file formats.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Data-driven skill loading from JSON definitions.
 */
@Singleton
class SkillLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val currentVersion = 1

    /**
     * Load all skills from an assets directory.
     *
     * @param assetPath Path within assets (e.g. "skills" or "skills/strategy").
     * @return List of parsed and validated Skills.
     */
    fun loadFromAsset(assetPath: String): List<Skill> {
        val manager: AssetManager = context.assets
        val skills = mutableListOf<Skill>()

        try {
            val files = manager.list(assetPath) ?: emptyArray()
            for (fileName in files) {
                if (!fileName.endsWith(".json")) continue
                val path = if (assetPath.endsWith("/")) "$assetPath$fileName" else "$assetPath/$fileName"
                try {
                    manager.open(path).use { stream ->
                        val skill = loadFromStream(stream, path)
                        if (skill != null) {
                            skills.add(skill)
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed skill files silently
                }
            }
        } catch (e: Exception) {
            // Asset directory may not exist — return empty
        }

        return skills
    }

    /**
     * Load a single skill from a JSON file.
     *
     * @param file JSON file containing a skill definition.
     * @return Parsed Skill, or null on failure.
     */
    fun loadFromFile(file: File): Skill? {
        return try {
            file.inputStream().use { stream ->
                loadFromStream(stream, file.name)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load from an input stream.
     */
    fun loadFromStream(stream: InputStream, sourceName: String = "stream"): Skill? {
        return try {
            val raw = json.decodeFromStream<JsonObject>(stream)
            parseSkill(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a raw string to a Skill.
     */
    fun parseJson(jsonString: String): Skill? {
        return try {
            val raw = json.decodeFromString<JsonObject>(jsonString)
            parseSkill(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save a skill to a JSON file.
     *
     * @param skill The skill to save.
     * @param file Destination file.
     * @return true if successfully written.
     */
    fun saveToFile(skill: Skill, file: File): Boolean {
        return try {
            val skillJson = json.encodeToString(Skill.serializer(), skill)
            file.parentFile?.mkdirs()
            file.writeText(skillJson)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Export a skill as a JSON string.
     */
    fun exportToString(skill: Skill): String {
        return json.encodeToString(Skill.serializer(), skill)
    }

    /**
     * Validate the structure of a skill definition.
     *
     * @return List of validation error messages (empty if valid).
     */
    fun validateSkill(skill: Skill): List<String> {
        val errors = mutableListOf<String>()

        if (skill.id.isBlank()) errors.add("Skill id is blank")
        if (skill.name.isBlank()) errors.add("Skill name is blank")
        if (skill.steps.isEmpty()) errors.add("Skill has no steps")

        for ((index, step) in skill.steps.withIndex()) {
            if (step.id.isBlank()) errors.add("Step $index: id is blank")
            if (step.tool.isBlank()) errors.add("Step $index: tool name is blank")
        }

        if (skill.retryPolicy.maxRetries < 0) {
            errors.add("retryPolicy.maxRetries must be >= 0")
        }

        return errors
    }

    // ── Version migration ───────────────────────────────────────────────

    /**
     * Migrate a skill from an older version to the current version format.
     */
    fun migrate(skill: Skill, fromVersion: Int): Skill {
        if (fromVersion >= currentVersion) return skill

        var migrated = skill

        if (fromVersion < 1) {
            migrated = migrated.copy(version = 1)
        }

        return migrated
    }

    // ── Internal parsing ────────────────────────────────────────────────

    private fun parseSkill(raw: JsonObject): Skill? {
        try {
            val version = raw["version"]?.jsonPrimitive?.int ?: 1

            val skill = json.decodeFromJsonElement(Skill.serializer(), raw)

            val migrated = if (version < currentVersion) {
                migrate(skill, version)
            } else {
                skill
            }

            val errors = validateSkill(migrated)
            if (errors.isNotEmpty()) {
                return null
            }

            return migrated
        } catch (e: Exception) {
            return null
        }
    }
}
