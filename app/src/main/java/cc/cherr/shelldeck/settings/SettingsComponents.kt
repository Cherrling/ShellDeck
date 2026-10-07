package cc.cherr.shelldeck.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.cherr.shelldeck.R

@Composable
internal fun SettingsLayout(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun settingsItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun SettingsLink(title: String, summary: String, @DrawableRes icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = settingsItemColors(), modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}

@Composable
internal fun SettingsValue(title: String, value: String) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, colors = settingsItemColors())
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsSwitch(title: String, summary: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) }, colors = settingsItemColors(),
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change))
}

@Composable
internal fun SettingsRadio(title: String, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, leadingContent = { RadioButton(selected, onClick = null) },
        trailingContent = trailing, colors = settingsItemColors(),
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick))
}

@Composable
internal fun <T> SettingsChoiceDialog(title: String, values: List<T>, selected: T?, label: (T) -> String, dismiss: () -> Unit, choose: (T) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            values.forEach { value ->
                Row(Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton) { choose(value) }
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioButton(selected == value, onClick = null)
                    Text(label(value), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
