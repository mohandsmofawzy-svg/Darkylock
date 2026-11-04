@file:Suppress("FunctionName")

package dev.mo.darkylock.ui

import android.content.SharedPreferences
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mo.darkylock.data.LockNotifier
import dev.mo.darkylock.modem.reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GamePriorityButton(
    packageNames: String,
    onPackageNamesChange: (String) -> Unit,
    enabled: Boolean,
    onPrioritize: suspend (List<String>) -> Unit,
    onToggleDropFix: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<GamePerformanceDiagnostics?>(null) }
    val preferences = remember(context) { context.getSharedPreferences("darkylock", android.content.Context.MODE_PRIVATE) }
    var dropFixEnabled by remember {
        mutableStateOf(preferences.getBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, false))
    }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { changedPreferences, key ->
                if (key == LockNotifier.PREF_DROP_FIX_ENABLED) {
                    dropFixEnabled = changedPreferences.getBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, false)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = packageNames,
            onValueChange = onPackageNamesChange,
            label = { Text(translateUiText("Game package(s)", language)) },
            placeholder = { Text("com.tencent.ig") },
            supportingText = { Text(translateUiText("Separate multiple package names with commas or new lines.", language)) },
            minLines = 1,
            maxLines = 3,
            enabled = enabled && !dropFixEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = {
                val packages = packageNames.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
                processing = true
                scope.launch {
                    try {
                        onPrioritize(packages)
                        Toast.makeText(
                            context,
                            translateUiText("Game Priority enabled for the selected package(s).", language),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val detail = e.message ?: reason(e)
                        val message =
                            if (detail == "Game Priority requires an active Shizuku connection.") {
                                translateUiText(detail, language)
                            } else {
                                "${translateUiText("Game Priority failed.", language)} $detail"
                            }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    } finally {
                        processing = false
                    }
                }
            },
            enabled = enabled && packageNames.isNotBlank() && !processing,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(translateUiText("Enable Game Priority", language))
        }
        Text(
            translateUiText("Requires an active Shizuku connection.", language),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                translateUiText("Drop-Fix", language),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = dropFixEnabled,
                onCheckedChange = { turnOn ->
                    dropFixEnabled = turnOn
                    onToggleDropFix(packageNames, turnOn)
                },
                enabled = enabled && !processing && (dropFixEnabled || packageNames.isNotBlank()),
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2E7D32),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFC62828),
                    ),
            )
        }
        Text(
            translateUiText("Stabilizes frame rate by periodically applying system performance mode during gameplay.", language),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
        Text(
            translateUiText(
                "Applies Android's supported performance mode every 20 seconds; it does not force an FPS limit.",
                language,
            ),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(
            onClick = {
                val display = context.display
                val powerManager = context.getSystemService(PowerManager::class.java)
                diagnostics =
                    GamePerformanceDiagnostics(
                        currentRefreshRateHz = display.mode.refreshRate,
                        maxRefreshRateHz = display.supportedModes.maxOfOrNull { it.refreshRate },
                        thermalStatus = powerManager.currentThermalStatus,
                        batterySaverEnabled = powerManager.isPowerSaveMode,
                    )
            },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(translateUiText("Diagnose FPS", language))
        }
        diagnostics?.let { result ->
            GamePerformanceDiagnosticsCard(result, language)
        }
    }
}

private data class GamePerformanceDiagnostics(
    val currentRefreshRateHz: Float?,
    val maxRefreshRateHz: Float?,
    val thermalStatus: Int,
    val batterySaverEnabled: Boolean,
)

@Composable
private fun GamePerformanceDiagnosticsCard(
    diagnostics: GamePerformanceDiagnostics,
    language: String,
) {
    val thermalText =
        when (diagnostics.thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    val currentRate = diagnostics.currentRefreshRateHz?.let { "%.0f Hz".format(it) } ?: "Unavailable"
    val maxRate = diagnostics.maxRefreshRateHz?.let { "%.0f Hz".format(it) } ?: "Unavailable"
    val recommendation =
        when {
            diagnostics.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "The device reports severe heat. Stop playing and let it cool naturally; thermal limits remain enabled."
            diagnostics.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                "The device reports elevated heat, which can reduce performance. Let it cool and avoid charging while playing."
            diagnostics.batterySaverEnabled ->
                "Battery Saver is on and may reduce performance. Check Android battery settings."
            diagnostics.currentRefreshRateHz != null &&
                    diagnostics.maxRefreshRateHz != null &&
                    diagnostics.currentRefreshRateHz + 1f < diagnostics.maxRefreshRateHz ->
                "The screen is using a lower refresh mode than its highest listed mode. Check Android display settings; the game may still apply its own FPS cap."
            else ->
                "A 60 FPS cap can also come from the game's supported-device profile, graphics settings, or GPU load. This check does not measure PUBG's actual FPS."
        }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DiagnosticRow("Current screen refresh", currentRate, language)
        DiagnosticRow("Highest listed screen mode", maxRate, language)
        DiagnosticRow("Device thermal status", thermalText, language)
        DiagnosticRow("Battery Saver", if (diagnostics.batterySaverEnabled) "On" else "Off", language)
        Text(
            translateUiText(recommendation, language),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            translateUiText(
                "This is a device-condition check, not an FPS meter. Game Priority does not unlock unsupported frame rates or change thermal protections.",
                language,
            ),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    language: String,
) {
    Text(
        "${translateUiText(label, language)}: ${translateUiText(value, language)}",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
    )
}