package dev.hushyari.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SocialDistance
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.SkillCategory

@Composable
fun SkillCard(
    skill: Skill,
    onRun: (Skill) -> Unit = {},
    onEdit: (Skill) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit(skill) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = skillCategoryIcon(skill.category),
                contentDescription = null,
                tint = categoryColor(skill.category),
                modifier = Modifier.size(40.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (skill.description.isNotEmpty()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = "${skill.steps.size} steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (skill.isBuiltIn) {
                        Text(
                            text = "Built-in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (skill.tags.isNotEmpty()) {
                        Text(
                            text = skill.tags.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Run skill",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onRun(skill) },
            )
        }
    }
}

private fun skillCategoryIcon(category: SkillCategory): ImageVector = when (category) {
    SkillCategory.GENERIC -> Icons.Outlined.Build
    SkillCategory.NAVIGATION -> Icons.Outlined.Explore
    SkillCategory.COMBAT -> Icons.Outlined.Shield
    SkillCategory.RESOURCE -> Icons.Outlined.Build
    SkillCategory.BUILDING -> Icons.Outlined.Build
    SkillCategory.QUEST -> Icons.Outlined.Explore
    SkillCategory.SOCIAL -> Icons.Outlined.SocialDistance
    SkillCategory.CUSTOM -> Icons.Default.Category
}

@Composable
private fun categoryColor(category: SkillCategory): Color = when (category) {
    SkillCategory.COMBAT -> Color(0xFFD32F2F)
    SkillCategory.RESOURCE -> Color(0xFFFFC107)
    SkillCategory.BUILDING -> Color(0xFF00C853)
    SkillCategory.QUEST -> Color(0xFF1A73E8)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
