package dev.hushyari.skills

import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.SkillCategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry managing the complete skill library.
 *
 * Skills can be built-in (loaded from assets), user-imported, or
 * dynamically registered at runtime. The registry supports lookup
 * by ID, searching by name/description/tags, and filtering by
 * game type and package.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Skill library management.
 */
@Singleton
class SkillRegistry @Inject constructor(
    private val skillLoader: SkillLoader,
) {

    private val skills = mutableMapOf<String, Skill>()
    private val builtInSkillIds = mutableSetOf<String>()

    /**
     * Get all registered skills.
     */
    fun getAllSkills(): List<Skill> = skills.values.toList()

    /**
     * Get skills applicable to a specific game package.
     *
     * @param gamePackage The Android package name of the game.
     * @return List of skills matching the game package or marked as generic.
     */
    fun getSkillsForGame(gamePackage: String): List<Skill> {
        return skills.values.filter { skill ->
            skill.gamePackage == null ||
                    skill.gamePackage == gamePackage ||
                    skill.gameType.isEmpty()
        }
    }

    /**
     * Get a skill by its unique ID.
     */
    fun getSkill(id: String): Skill? = skills[id]

    /**
     * Search skills by query string against name, description, and tags.
     *
     * @param query Search query.
     * @return Matching skills sorted by relevance.
     */
    fun searchSkills(query: String): List<Skill> {
        if (query.isBlank()) return getAllSkills()
        val lower = query.lowercase()

        return skills.values
            .filter { skill ->
                skill.name.lowercase().contains(lower) ||
                        skill.description.lowercase().contains(lower) ||
                        skill.tags.any { it.lowercase().contains(lower) }
            }
            .sortedByDescending { skill ->
                var score = 0
                if (skill.name.lowercase() == lower) score += 10
                else if (skill.name.lowercase().contains(lower)) score += 5
                if (skill.tags.any { it.lowercase() == lower }) score += 3
                if (skill.description.lowercase().contains(lower)) score += 1
                score
            }
    }

    /**
     * Register a skill in the registry.
     * Overwrites existing skill with the same ID.
     *
     * @param skill The skill to register.
     */
    fun registerSkill(skill: Skill) {
        skills[skill.id] = skill
    }

    /**
     * Register multiple skills at once.
     */
    fun registerSkills(skillList: List<Skill>) {
        skillList.forEach { registerSkill(it) }
    }

    /**
     * Unregister a skill by ID.
     *
     * @return true if the skill was removed, false if it didn't exist.
     */
    fun unregisterSkill(id: String): Boolean {
        if (id in builtInSkillIds) return false
        return skills.remove(id) != null
    }

    /**
     * Load all built-in skills from the assets/skills directory.
     */
    fun loadBuiltInSkills() {
        val loaded = skillLoader.loadFromAsset("skills")
        for (skill in loaded) {
            skills[skill.id] = skill
            builtInSkillIds.add(skill.id)
        }
    }

    /**
     * Import a skill from a JSON string.
     *
     * @param json JSON skill definition.
     * @return The imported Skill, or null if parsing failed.
     */
    fun importSkill(json: String): Skill? {
        val skill = skillLoader.parseJson(json) ?: return null
        skills[skill.id] = skill
        return skill
    }

    /**
     * Export a skill as a JSON string.
     *
     * @param skill Skill to export.
     * @return JSON string representation.
     */
    fun exportSkill(skill: Skill): String {
        return skillLoader.exportToString(skill)
    }

    /**
     * Get skills by category.
     */
    fun getSkillsByCategory(category: SkillCategory): List<Skill> {
        return skills.values.filter { it.category == category }
    }

    /**
     * Get the count of registered skills.
     */
    fun getSkillCount(): Int = skills.size

    /**
     * Get the count of built-in skills.
     */
    fun getBuiltInSkillCount(): Int = builtInSkillIds.size

    /**
     * Check if a skill ID is registered.
     */
    fun hasSkill(id: String): Boolean = skills.containsKey(id)

    /**
     * Clear all non-built-in skills.
     */
    fun clearUserSkills() {
        skills.keys.removeAll { it !in builtInSkillIds }
    }

    /**
     * Clear all skills including built-in.
     */
    fun reset() {
        skills.clear()
        builtInSkillIds.clear()
    }
}
