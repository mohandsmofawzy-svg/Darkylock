package dev.mo.darkylock

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.mo.darkylock.ui.DarkyApp
import dev.mo.darkylock.ui.LocalAppLanguage
import dev.mo.darkylock.ui.MainViewModel
import dev.mo.darkylock.ui.translateUiText
import dev.mo.darkylock.ui.theme.DarkyTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.loadSims()
            vm.refresh()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            getSharedPreferences("darkylock", Context.MODE_PRIVATE).edit {
                putBoolean("notificationPermissionDeclined", !granted)
            }
            if (granted) vm.refresh()
        }

    // يضبط اللغة قبل إنشاء Compose حتى تتطابق الموارد واتجاه التخطيط مع الاختيار المحفوظ.
    override fun attachBaseContext(newBase: Context) {
        val language =
            newBase.getSharedPreferences("darkylock", Context.MODE_PRIVATE)
                .getString("language", if (Locale.getDefault().language == "ar") "ar" else "en") ?: "en"
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            var showCellPermissionRationale by rememberSaveable { mutableStateOf(!hasCellPermissions()) }
            var showNotificationRationale by remember { mutableStateOf(false) }
            val hasActiveLocks = state.modem.anyBandLock || state.modem.anyCellLock || state.modem.anyPlmnLock
            val lockStateKnown = state.modem.readbackValid

            LaunchedEffect(hasActiveLocks, lockStateKnown, showCellPermissionRationale) {
                val notificationPermissionMissing =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                val prefs = getSharedPreferences("darkylock", Context.MODE_PRIVATE)
                if (lockStateKnown && !hasActiveLocks) {
                    prefs.edit { remove("notificationPermissionDeclined") }
                    showNotificationRationale = false
                } else if (
                    !showCellPermissionRationale &&
                    notificationPermissionMissing &&
                    !prefs.getBoolean("notificationPermissionDeclined", false)
                ) {
                    showNotificationRationale = true
                }
            }

            CompositionLocalProvider(
                LocalAppLanguage provides state.language,
                LocalLayoutDirection provides if (state.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                DarkyTheme {
                    DarkyApp(vm)
                    if (showCellPermissionRationale) {
                        AlertDialog(
                            onDismissRequest = { showCellPermissionRationale = false },
                            title = { Text(translateUiText("Cellular information access", state.language)) },
                            text = { Text(translateUiText("Cellular permission explanation", state.language)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showCellPermissionRationale = false
                                        val permissions =
                                            listOf(
                                                Manifest.permission.READ_PHONE_STATE,
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                            ).filter {
                                                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                                            }
                                        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
                                    },
                                ) {
                                    Text(translateUiText("Continue", state.language))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCellPermissionRationale = false }) {
                                    Text(translateUiText("Not now", state.language))
                                }
                            },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && showNotificationRationale) {
                        AlertDialog(
                            onDismissRequest = {
                                showNotificationRationale = false
                                getSharedPreferences("darkylock", Context.MODE_PRIVATE).edit {
                                    putBoolean("notificationPermissionDeclined", true)
                                }
                            },
                            title = { Text(translateUiText("Active lock notifications", state.language)) },
                            text = { Text(translateUiText("Lock notification explanation", state.language)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showNotificationRationale = false
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                ) {
                                    Text(translateUiText("Continue", state.language))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showNotificationRationale = false
                                        getSharedPreferences("darkylock", Context.MODE_PRIVATE)
                                            .edit { putBoolean("notificationPermissionDeclined", true) }
                                    },
                                ) {
                                    Text(translateUiText("Not now", state.language))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.resumed()
    }

    private fun hasCellPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            (
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            )
}
