package dev.hushyari.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.SkillCategory
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import dev.hushyari.data.repository.SkillRepository
import dev.hushyari.skills.SkillLoader
import dev.hushyari.skills.SkillRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SkillEditorUiState(
    val skillId: String = "",
    val skillName: String = "",
    val skillDescription: String = "",
    val skillCategory: SkillCategory = SkillCategory.CUSTOM,
    val gamePackage: String = "",
    val steps: List<SkillStep> = emptyList(),
    val editingStepIndex: Int? = null,
    val editingStep: SkillStep? = null,
    val isRecording: Boolean = false,
    val isTesting: Boolean = false,
    val testLog: List<String> = emptyList(),
    val jsonPreview: String = "",
    val isValid: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val saved: Boolean = false,
    val error: String? = null,
)

data class SkillListItem(
    val skill: Skill,
    val canEdit: Boolean = true,
    val canRun: Boolean = true,
)

@HiltViewModel
class SkillViewModel @Inject constructor(
    private val skillRepository: SkillRepository,
    private val skillRegistry: SkillRegistry,
    private val skillLoader: SkillLoader,
) : ViewModel() {

    private val _editorState = MutableStateFlow(SkillEditorUiState())
    val editorState: StateFlow<SkillEditorUiState> = _editorState.asStateFlow()

    private val _allSkills = MutableStateFlow<List<Skill>>(emptyList())
    val allSkills: StateFlow<List<Skill>> = _allSkills.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        loadAllSkills()
    }

    fun loadAllSkills() {
        viewModelScope.launch {
            skillRepository.getAllSkills().collect { skills ->
                _allSkills.value = skills
            }
        }
    }

    fun createNewSkill() {
        _editorState.update {
            SkillEditorUiState(skillId = UUID.randomUUID().toString())
        }
    }

    fun loadSkill(skillId: String) {
        val skill = skillRegistry.getSkill(skillId) ?: return
        _editorState.update {
            SkillEditorUiState(
                skillId = skill.id,
                skillName = skill.name,
                skillDescription = skill.description,
                skillCategory = skill.category,
                gamePackage = skill.gamePackage ?: "",
                steps = skill.steps,
                jsonPreview = skillLoader.exportToString(skill),
                isValid = true,
            )
        }
    }

    fun setSkillName(name: String) {
        _editorState.update { it.copy(skillName = name) }
        validate()
    }

    fun setSkillDescription(desc: String) {
        _editorState.update { it.copy(skillDescription = desc) }
    }

    fun setSkillCategory(category: SkillCategory) {
        _editorState.update { it.copy(skillCategory = category) }
    }

    fun setGamePackage(pkg: String) {
        _editorState.update { it.copy(gamePackage = pkg) }
    }

    fun addStep() {
        val step = SkillStep(
            id = UUID.randomUUID().toString(),
            tool = "find_and_tap",
        )
        _editorState.update {
            it.copy(
                steps = it.steps + step,
                editingStepIndex = it.steps.size,
                editingStep = step,
            )
        }
    }

    fun editStep(index: Int) {
        val step = _editorState.value.steps.getOrNull(index) ?: return
        _editorState.update { it.copy(editingStepIndex = index, editingStep = step) }
    }

    fun updateEditingStep(step: SkillStep) {
        _editorState.update { it.copy(editingStep = step) }
    }

    fun saveEditingStep() {
        val state = _editorState.value
        val step = state.editingStep ?: return
        val index = state.editingStepIndex ?: return

        val newSteps = state.steps.toMutableList()
        if (index < newSteps.size) {
            newSteps[index] = step
        } else {
            newSteps.add(step)
        }

        _editorState.update {
            it.copy(
                steps = newSteps,
                editingStepIndex = null,
                editingStep = null,
            )
        }
        validate()
    }

    fun removeStep(index: Int) {
        _editorState.update {
            it.copy(steps = it.steps.toMutableList().also { steps -> steps.removeAt(index) })
        }
        validate()
    }

    fun moveStep(fromIndex: Int, toIndex: Int) {
        _editorState.update { state ->
            val steps = state.steps.toMutableList()
            if (fromIndex in steps.indices && toIndex in steps.indices) {
                val item = steps.removeAt(fromIndex)
                steps.add(toIndex, item)
            }
            state.copy(steps = steps)
        }
    }

    fun startRecording() {
        _editorState.update { it.copy(isRecording = true) }
    }

    fun stopRecording() {
        _editorState.update { it.copy(isRecording = false) }
    }

    fun addRecordedTap(x: Float, y: Float) {
        if (!_editorState.value.isRecording) return
        val step = SkillStep(
            id = UUID.randomUUID().toString(),
            description = "Tap at ($x, $y)",
            tool = "tap",
            target = TargetSpec(
                type = TargetType.COORDINATES,
                x = x.toInt(),
                y = y.toInt(),
            ),
        )
        _editorState.update {
            it.copy(steps = it.steps + step)
        }
        validate()
    }

    fun startTest() {
        _editorState.update { it.copy(isTesting = true, testLog = emptyList()) }
    }

    fun stopTest() {
        _editorState.update { it.copy(isTesting = false) }
    }

    fun appendTestLog(message: String) {
        _editorState.update { it.copy(testLog = it.testLog + message) }
    }

    fun saveSkill() {
        viewModelScope.launch {
            try {
                val state = _editorState.value
                val skill = Skill(
                    id = state.skillId.ifEmpty { UUID.randomUUID().toString() },
                    name = state.skillName,
                    gamePackage = state.gamePackage.ifEmpty { null },
                    description = state.skillDescription,
                    category = state.skillCategory,
                    steps = state.steps,
                    isBuiltIn = false,
                    version = 1,
                )

                skillRepository.saveSkill(skill)
                _editorState.update {
                    it.copy(saved = true, jsonPreview = skillLoader.exportToString(skill))
                }
            } catch (e: Exception) {
                _editorState.update { it.copy(error = "Failed to save: ${e.message}") }
            }
        }
    }

    fun deleteSkill(skillId: String) {
        viewModelScope.launch {
            skillRepository.deleteSkill(skillId)
            loadAllSkills()
        }
    }

    fun importSkill(json: String) {
        try {
            val skill = skillRepository.importSkill(json)
            if (skill != null) {
                loadAllSkills()
                loadSkill(skill.id)
            } else {
                _editorState.update { it.copy(error = "Failed to parse skill JSON") }
            }
        } catch (e: Exception) {
            _editorState.update { it.copy(error = "Import failed: ${e.message}") }
        }
    }

    fun exportCurrentSkill(): String {
        return _editorState.value.jsonPreview
    }

    fun search(query: String) {
        _searchQuery.value = query
        val skills = skillRegistry.searchSkills(query)
        _allSkills.value = skills
    }

    fun clearSearch() {
        _searchQuery.value = ""
        loadAllSkills()
    }

    fun clearError() {
        _editorState.update { it.copy(error = null) }
    }

    private fun validate() {
        val state = _editorState.value
        val errors = mutableListOf<String>()
        if (state.skillName.isBlank()) errors.add("Name is required")
        if (state.steps.isEmpty()) errors.add("At least one step is required")
        val valid = errors.isEmpty()
        _editorState.update {
            it.copy(
                isValid = valid,
                validationErrors = errors,
                jsonPreview = if (valid) buildJsonPreview() else it.jsonPreview,
            )
        }
    }

    private fun buildJsonPreview(): String {
        val state = _editorState.value
        val skill = Skill(
            id = state.skillId,
            name = state.skillName,
            description = state.skillDescription,
            category = state.skillCategory,
            gamePackage = state.gamePackage.ifEmpty { null },
            steps = state.steps,
        )
        return skillLoader.exportToString(skill)
    }
}
