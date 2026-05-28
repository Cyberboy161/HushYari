package dev.hushyari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.hushyari.data.model.SkillCategory
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import dev.hushyari.ui.theme.AgentRunning
import dev.hushyari.ui.theme.AgentStopped
import dev.hushyari.ui.viewmodel.SkillViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    skillId: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: SkillViewModel = hiltViewModel(),
) {
    val editorState by viewModel.editorState.collectAsState()
    var showStepDialog by remember { mutableStateOf(false) }
    var showJsonPreview by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(skillId) {
        if (skillId != null) {
            viewModel.loadSkill(skillId)
        } else {
            viewModel.createNewSkill()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (skillId != null) "Edit Skill" else "New Skill",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveSkill() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { showJsonPreview = !showJsonPreview }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "JSON Preview")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = editorState.skillName,
                onValueChange = { viewModel.setSkillName(it) },
                label = { Text("Skill Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = editorState.skillDescription,
                onValueChange = { viewModel.setSkillDescription(it) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )

            Spacer(modifier = Modifier.height(8.dp))

            var categoryExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = editorState.skillCategory.name,
                    onValueChange = {},
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    enabled = false,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkillCategory.entries.forEach { cat ->
                    OutlinedButton(
                        onClick = { viewModel.setSkillCategory(cat) },
                        modifier = Modifier.weight(1f),
                        colors = if (editorState.skillCategory == cat)
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        else
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Text(
                            text = cat.name.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Steps (${editorState.steps.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isRecording = editorState.isRecording
                    OutlinedButton(onClick = {
                        if (isRecording) viewModel.stopRecording()
                        else viewModel.startRecording()
                    }) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = if (isRecording) AgentStopped else AgentRunning,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRecording) "Stop" else "Record")
                    }
                    Button(onClick = { showStepDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Step")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(editorState.steps) { index, step ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.tool,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text = step.description.ifEmpty { step.id },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.removeStep(index) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (!editorState.validationErrors.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                editorState.validationErrors.forEach { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (editorState.saved) {
                androidx.compose.material3.Snackbar(
                    modifier = Modifier.padding(8.dp),
                ) {
                    Text("Skill saved successfully")
                }
            }
        }

        if (showStepDialog) {
            var toolName by remember { mutableStateOf("find_and_tap") }
            var description by remember { mutableStateOf("") }
            var toolExpanded by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showStepDialog = false },
                title = { Text("Add Step") },
                text = {
                    Column {
                        Box {
                            OutlinedTextField(
                                value = toolName,
                                onValueChange = { toolName = it },
                                label = { Text("Tool") },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val tools = listOf("tap", "swipe", "find_and_tap", "wait", "scroll", "type", "screenshot")
                            tools.forEach { t ->
                                OutlinedButton(
                                    onClick = { toolName = t },
                                    modifier = Modifier.weight(1f),
                                    colors = if (toolName == t)
                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        )
                                    else
                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                                ) {
                                    Text(t, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val step = SkillStep(
                            id = java.util.UUID.randomUUID().toString(),
                            description = description,
                            tool = toolName,
                        )
                        viewModel.updateEditingStep(step)
                        viewModel.saveEditingStep()
                        showStepDialog = false
                    }) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStepDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (showJsonPreview) {
            val scrollState = rememberScrollState()
            AlertDialog(
                onDismissRequest = { showJsonPreview = false },
                title = { Text("JSON Preview") },
                text = {
                    Column(
                        modifier = Modifier
                            .height(400.dp)
                            .verticalScroll(scrollState),
                    ) {
                        Text(
                            text = editorState.jsonPreview.ifEmpty { "{}" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showJsonPreview = false }) {
                        Text("Close")
                    }
                },
            )
        }
    }
}
