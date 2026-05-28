package dev.hushyari.data.repository

import dev.hushyari.data.local.dao.SkillDao
import dev.hushyari.data.local.entities.SkillEntity
import dev.hushyari.data.model.Skill
import dev.hushyari.skills.SkillLoader
import dev.hushyari.skills.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRepository @Inject constructor(
    private val dao: SkillDao,
    private val skillRegistry: SkillRegistry,
    private val skillLoader: SkillLoader,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun getSkills(gamePackage: String): Flow<List<Skill>> = dao.getForGame(gamePackage).map { entities ->
        entities.mapNotNull { parseSkill(it.skillJson) }
    }

    fun getAllSkills(): Flow<List<Skill>> = dao.getAll().map { entities ->
        entities.mapNotNull { parseSkill(it.skillJson) }
    }

    suspend fun saveSkill(skill: Skill) {
        val skillJson = skillLoader.exportToString(skill)
        val entity = SkillEntity(
            id = skill.id,
            gamePackage = skill.gamePackage,
            skillJson = skillJson,
            updatedAt = System.currentTimeMillis(),
        )
        val existing = dao.getById(skill.id)
        if (existing != null) {
            dao.insert(entity.copy(createdAt = existing.createdAt))
        } else {
            dao.insert(entity)
        }
        skillRegistry.registerSkill(skill)
    }

    suspend fun deleteSkill(id: String) {
        dao.deleteById(id)
        skillRegistry.unregisterSkill(id)
    }

    fun importSkill(jsonString: String): Skill? {
        val skill = skillRegistry.importSkill(jsonString) ?: return null
        val entity = SkillEntity(
            id = skill.id,
            gamePackage = skill.gamePackage,
            skillJson = jsonString,
        )
        kotlinx.coroutines.runBlocking {
            dao.insert(entity)
        }
        return skill
    }

    fun exportSkill(skill: Skill): String = skillLoader.exportToString(skill)

    fun getBuiltInSkills(gamePackage: String): List<Skill> =
        skillRegistry.getSkillsForGame(gamePackage).filter { it.isBuiltIn }

    fun searchSkills(query: String): List<Skill> = skillRegistry.searchSkills(query)

    private fun parseSkill(skillJson: String): Skill? {
        return try {
            json.decodeFromString(Skill.serializer(), skillJson)
        } catch (_: Exception) {
            null
        }
    }
}
