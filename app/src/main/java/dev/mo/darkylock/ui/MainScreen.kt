@file:Suppress("FunctionName")

package dev.mo.darkylock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import dev.mo.darkylock.data.Carrier
import dev.mo.darkylock.data.Cell
import dev.mo.darkylock.data.Rat
import dev.mo.darkylock.data.Signal
import dev.mo.darkylock.R
import dev.mo.darkylock.modem.ModemState
import dev.mo.darkylock.modem.RootState
import dev.mo.darkylock.modem.andList
import dev.mo.darkylock.modem.formatPlmn
import dev.mo.darkylock.ui.theme.LocalAccentPink
import dev.mo.darkylock.ui.theme.LocalSignalScale

private val RSRP_STEPS = listOf(-80, -90, -100)
private val PLAIN_DBM_STEPS = listOf(-70, -85, -100)
private val RSRQ_STEPS = listOf(-10, -15, -20)
private val SINR_STEPS = listOf(20, 13, 0)

@Composable
fun MainScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goTo: (Tab) -> Unit,
) {
    var gamePackageNames by rememberSaveable { mutableStateOf(vm.gamePackageNames()) }
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val carriers = state.modem.carriers
        item {
            SectionHeader("Live network")
            if (carriers.isEmpty()) {
                Panel { EmptyStateMessage("No serving cell") }
            } else {
                ConnectionCard(state.modem)
            }
        }

        item {
            Hero(
                state,
                onUnlock = vm::unlockEverything,
                onBands = { goTo(Tab.BANDS) },
                onCell = { goTo(Tab.CELL) },
                gamePackageNames = gamePackageNames,
                onGamePackageNamesChange = {
                    gamePackageNames = it
                    vm.saveGamePackageNames(it)
                },
                gamePriorityEnabled = !state.busy && !state.exiting && state.modem.root == RootState.Granted,
                onPrioritizeGames = vm::prioritizeGames,
                onToggleDropFix = vm::toggleDropFix,
            )
        }

        if (carriers.isNotEmpty()) {
            item {
                val anchoredOnLte = carriers.any { it.primary && it.rat == Rat.LTE }
                SectionHeader(if (carriers.size > 1) "Carrier composition" else "Serving carrier")
                Segmented {
                    carriers.forEach { carrier -> item { shapes -> CarrierItem(shapes, carrier, anchoredOnLte) } }
                }
            }
        }

        item {
            val device = state.modem.device
            val deviceName = "${device?.manufacturer.orEmpty()} ${device?.model.orEmpty()}".trim()
            val platform =
                listOfNotNull(
                    device?.socModel?.ifEmpty { null },
                    device?.platform?.ifEmpty { null },
                ).joinToString(", ")
            SectionHeader("Modem")
            Segmented {
                item { InfoItem(it, "Chipset", device?.vendorLabel.orEmpty()) }
                item { InfoItem(it, "Device", deviceName) }
                item { InfoItem(it, "Platform", platform) }
                item { InfoItem(it, "Baseband", device?.baseband.orEmpty()) }
            }
        }
    }
}

@Composable
private fun Hero(
    state: UiState,
    onUnlock: () -> Unit,
    onBands: () -> Unit,
    onCell: () -> Unit,
    gamePackageNames: String,
    onGamePackageNamesChange: (String) -> Unit,
    gamePriorityEnabled: Boolean,
    onPrioritizeGames: suspend (List<String>) -> Unit,
    onToggleDropFix: (String, Boolean) -> Unit,
) {
    val locked = state.modem.anyBandLock || state.modem.anyCellLock || state.modem.anyPlmnLock
    val strayLocation =
        state.modem.strayBand?.let { "$it, outside the locked bands" }
            ?: state.modem.strayChannel?.let { "$it, off the locked frequency" }
    val lockHeld = locked && strayLocation === null
    val accent = LocalAccentPink.current
    val containerColor by animateColorAsState(
        if (lockHeld) {
            accent.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "hero",
    )
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LOCK CONTROL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            strayLocation !== null -> "Ignored"
                            locked -> "Locked"
                            else -> "Free"
                        },
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
                        color =
                            when {
                                strayLocation !== null -> MaterialTheme.colorScheme.error
                                locked -> accent
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Text(
                        if (strayLocation !== null) "The phone is on $strayLocation." else pinnedSummary(state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Image(
                    painter = painterResource(if (lockHeld) R.drawable.happy_bunny else R.drawable.sleeping_bunny),
                    contentDescription = if (lockHeld) "Happy bunny" else "Sleeping bunny",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)),
                )
            }

            val lockRows = activeLockRows(state)
            if (lockRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lockRows.forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.width(104.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onBands, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(DarkyIcons.Bands, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Band plan")
                }
                FilledTonalButton(onClick = onCell, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(DarkyIcons.Tower, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Cell lock")
                }
            }
            if (locked) {
                Button(
                    onClick = onUnlock,
                    enabled = !state.busy && !state.modem.simSelectionRequired,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(DarkyIcons.LockOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock all")
                }
            }
            GamePriorityButton(
                packageNames = gamePackageNames,
                onPackageNamesChange = onGamePackageNamesChange,
                enabled = gamePriorityEnabled,
                onPrioritize = onPrioritizeGames,
                onToggleDropFix = onToggleDropFix,
            )
        }
    }
}

private fun pinnedSummary(state: UiState): String {
    val pinned =
        listOfNotNull(
            "bands".takeIf { state.modem.anyBandLock },
            "cell".takeIf { state.modem.anyCellLock },
            "network".takeIf { state.modem.anyPlmnLock },
        )
    val takesSingularVerb = pinned == listOf("cell") || pinned == listOf("network")
    return when {
        pinned.isEmpty() -> "Nothing is pinned."
        takesSingularVerb -> "The ${pinned[0]} is pinned."
        else -> "The ${andList(pinned)} are pinned."
    }
}

private fun activeLockRows(state: UiState): List<Pair<String, String>> =
    buildList {
        state.modem.groups.filter { it.locked }.forEach { bandGroup ->
            val bands = bandGroup.current!!.sorted().joinToString(", ") { bandGroup.group.bandLabel(it) }
            add(bandGroup.group.label to bands)
        }
        state.modem.plmnLock?.let { add("Network" to it) }
        state.modem.lteLock?.let { add("LTE cell" to it) }
        state.modem.nrLock?.let { add("NR cell" to it) }
        state.modem.legacyCellLock?.let { add("2G/3G cell" to it) }
    }

private fun channelName(rat: Rat) =
    when (rat) {
        Rat.LTE -> "EARFCN"
        Rat.WCDMA -> "UARFCN"
        else -> "ARFCN"
    }

private fun megahertz(khz: Int): String = if (khz % 1000 == 0) "${khz / 1000}" else "%.1f".format(khz / 1000.0)

private fun techSummary(carriers: List<Carrier>): String {
    val lteCount = carriers.count { it.rat == Rat.LTE }
    val technologies =
        listOfNotNull(
            when {
                lteCount > 1 -> "LTE-A"
                lteCount == 1 -> "LTE"
                else -> null
            },
            when {
                carriers.none { it.rat == Rat.NR } -> null
                lteCount > 0 -> "5G NSA"
                else -> "5G SA"
            },
            "3G".takeIf { carriers.any { it.rat == Rat.WCDMA } },
            "2G".takeIf { carriers.any { it.rat == Rat.GSM } },
        )
    val count = if (carriers.size == 1) "1 carrier" else "${carriers.size} carriers"
    return "${technologies.joinToString(" + ")}, $count"
}

private fun totalBandwidth(carriers: List<Carrier>): String? {
    val known = carriers.mapNotNull { it.bandwidthKhz }
    if (known.isEmpty()) return null
    val more = if (known.size < carriers.size) "+" else ""
    return "${megahertz(known.sum())}$more MHz"
}

@Composable
private fun signalColor(
    value: Int,
    steps: List<Int>,
): Color {
    val step = steps.indexOfFirst { value >= it }
    return LocalSignalScale.current[if (step < 0) steps.size else step]
}

private fun dbmSteps(rat: Rat) = if (rat == Rat.LTE || rat == Rat.NR) RSRP_STEPS else PLAIN_DBM_STEPS

@Composable
private fun ratColor(rat: Rat): Color =
    when (rat) {
        Rat.NR -> MaterialTheme.colorScheme.primary
        Rat.LTE -> MaterialTheme.colorScheme.tertiary
        Rat.WCDMA -> MaterialTheme.colorScheme.secondary
        Rat.GSM -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun ConnectionCard(modem: ModemState) {
    val carriers = modem.carriers
    val network = modem.registeredName ?: modem.registeredPlmn?.let(::formatPlmn) ?: "Unknown network"
    val primaryRat = carriers.first().rat
    val signals = modem.signals.filter { it.rsrp !== null || it.rsrq !== null || it.sinr !== null }.sortedBy { it.rat != primaryRat }
    val primarySignal = signals.firstOrNull { it.rat == primaryRat }
    val bandwidth = totalBandwidth(carriers)
    Panel(padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(network, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(techSummary(carriers), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LivePulse(refreshing = modem.refreshing, live = modem.readbackValid)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalMetric(
                label = "RSRP",
                value = primarySignal?.rsrp?.let { "$it dBm" },
                progress = primarySignal?.rsrp?.let { normalized(it, -120f, -60f) },
                color = primarySignal?.rsrp?.let { signalColor(it, RSRP_STEPS) },
                modifier = Modifier.weight(1f),
            )
            SignalMetric(
                label = "RSRQ",
                value = primarySignal?.rsrq?.let { "$it dB" },
                progress = primarySignal?.rsrq?.let { normalized(it, -25f, -3f) },
                color = primarySignal?.rsrq?.let { signalColor(it, RSRQ_STEPS) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalMetric(
                label = "SINR",
                value = primarySignal?.sinr?.let { "$it dB" },
                progress = primarySignal?.sinr?.let { normalized(it, -5f, 35f) },
                color = primarySignal?.sinr?.let { signalColor(it, SINR_STEPS) },
                modifier = Modifier.weight(1f),
            )
            SignalMetric(
                label = "TOTAL BANDWIDTH",
                value = bandwidth,
                progress = null,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        if (carriers.all { it.bandwidthKhz !== null }) BandwidthBar(carriers)
        signals.filter { it.rat != primaryRat }.forEach { signal ->
            SignalRow(signal, showRat = true)
        }
    }
}

@Composable
private fun LivePulse(
    refreshing: Boolean,
    live: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "live pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), repeatMode = RepeatMode.Reverse),
        label = "live pulse alpha",
    )
    val color = if (live) LocalSignalScale.current.first() else MaterialTheme.colorScheme.outline
    val language = LocalAppLanguage.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.size(11.dp).graphicsLayer { alpha = if (refreshing) pulse else if (live) 0.9f else 0.4f },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(color.copy(alpha = 0.32f)))
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Text(
            translateUiText(if (refreshing) "Updating" else if (live) "Live" else "Waiting", language),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun SignalMetric(
    label: String,
    value: String?,
    progress: Float?,
    color: Color?,
    modifier: Modifier = Modifier,
) {
    val metricColor = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val animatedColor by animateColorAsState(metricColor, label = "$label color")
    val animatedProgress by animateFloatAsState(progress ?: 0f, label = "$label gauge")
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            (color ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.28f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                value ?: "—",
                style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFamily = FontFamily.Monospace),
                color = animatedColor,
                maxLines = 1,
            )
            if (progress != null) {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = radius,
                    )
                    val fillWidth = size.width * animatedProgress.coerceIn(0f, 1f)
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            color = animatedColor.copy(alpha = 0.24f),
                            size = Size(fillWidth, size.height),
                            cornerRadius = radius,
                        )
                        drawRoundRect(
                            color = animatedColor,
                            size = Size(fillWidth, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
    }
}

private fun normalized(
    value: Int,
    minimum: Float,
    maximum: Float,
): Float = ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)

@Composable
private fun BandwidthBar(carriers: List<Carrier>) {
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val motion = MaterialTheme.motionScheme
        carriers.forEach { carrier ->
            val width = carrier.bandwidthKhz ?: return@forEach
            key(carrier.rat, carrier.arfcn, carrier.pci) {
                val shownWidth = remember { Animatable(0f) }
                LaunchedEffect(width) { shownWidth.animateTo(width.toFloat(), motion.defaultSpatialSpec()) }
                val color by animateColorAsState(ratColor(carrier.rat).copy(alpha = if (carrier.primary) 1f else 0.55f), label = "carrier")
                Box(
                    Modifier
                        .weight(shownWidth.value.coerceAtLeast(1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: Signal,
    showRat: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showRat) RatBadge(signal.rat, size = 32.dp)
        signal.rsrp?.let {
            SignalMetric("RSRP", "$it dBm", normalized(it, -120f, -60f), signalColor(it, RSRP_STEPS), Modifier.weight(1f))
        }
        signal.rsrq?.let {
            SignalMetric("RSRQ", "$it dB", normalized(it, -25f, -3f), signalColor(it, RSRQ_STEPS), Modifier.weight(1f))
        }
        signal.sinr?.let {
            SignalMetric("SINR", "$it dB", normalized(it, -5f, 35f), signalColor(it, SINR_STEPS), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tag(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SignalPill(
    dbm: Int,
    rat: Rat,
) {
    val color by animateColorAsState(signalColor(dbm, dbmSteps(rat)), label = "signal")
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text("$dbm dBm", style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun RoleTag(
    carrier: Carrier,
    anchoredOnLte: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    when {
        carrier.primary -> Tag("Primary", colors.secondaryContainer, colors.onSecondaryContainer)
        carrier.rat == Rat.NR && anchoredOnLte -> Tag("5G NSA", colors.primaryContainer, colors.onPrimaryContainer)
        else -> Tag("Secondary", colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable
private fun CellTag(cell: Cell) {
    val colors = MaterialTheme.colorScheme
    when {
        cell.note == "NSA SCG" -> Tag("5G NSA", colors.primaryContainer, colors.onPrimaryContainer)
        cell.registered -> Tag("Serving", colors.secondaryContainer, colors.onSecondaryContainer)
    }
}

private fun radioDetails(
    rat: Rat,
    arfcn: Long?,
    pci: Int?,
    bandwidthKhz: Int?,
): String =
    listOfNotNull(
        arfcn?.let { "${channelName(rat)} $it" },
        pci?.let { "PCI $it" },
        bandwidthKhz?.let { "${megahertz(it)} MHz" },
    ).joinToString(" · ")

@Composable
private fun RadioRow(
    shapes: ListItemShapes,
    rat: Rat,
    title: String,
    frequencyName: String?,
    details: String,
    rsrp: Int?,
    tag: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = { RatBadge(rat) }
    val trailing: @Composable () -> Unit = { rsrp?.let { SignalPill(it, rat) } }
    val supporting: (@Composable () -> Unit)? = if (details.isEmpty()) null else ({ Text(details) })
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
            frequencyName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            tag()
        }
    }
    if (onClick !== null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    }
}

@Composable
private fun CarrierItem(
    shapes: ListItemShapes,
    carrier: Carrier,
    anchoredOnLte: Boolean,
) = RadioRow(
    shapes,
    carrier.rat,
    if (carrier.band !== null) carrier.bandLabel else "Unknown band",
    carrier.frequencyName,
    radioDetails(carrier.rat, carrier.arfcn, carrier.pci, carrier.bandwidthKhz),
    carrier.rsrp,
    tag = { RoleTag(carrier, anchoredOnLte) },
)

@Composable
fun CellItem(
    shapes: ListItemShapes,
    cell: Cell,
    onClick: (() -> Unit)? = null,
) = RadioRow(
    shapes,
    cell.rat,
    if (cell.band !== null) cell.bandLabel else "Unknown band",
    cell.frequencyName,
    radioDetails(cell.rat, cell.arfcn, cell.pci.takeIf { it >= 0 }, cell.bandwidthKhz),
    cell.rsrp,
    tag = { CellTag(cell) },
    onClick = onClick,
)