package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import kotlinx.coroutines.launch

/*
 * AddDeviceWizard — pair a WHOOP strap. Four steps: pick the family, prep the strap, pick it out of a
 * live scan, name it.
 *
 * This fork is WHOOP-only, so the wizard is too. Upstream's version branched across HR straps, FTMS gym
 * machines, Amazfit/Mi Band, Garmin and an Oura factory-reset-and-adopt sub-flow — ~1,500 lines of paths
 * this app has no use for. The WHOOP path itself is UNCHANGED: the same present-scan, the same
 * `discoveredWhoops` feed, and the same `PairedDeviceRow` shape/capabilities it always registered, so
 * pairing behaves exactly as it did before.
 */

private enum class Step { Family, Prep, Pick, Confirm }

private enum class Family(val title: String, val model: WhoopModel, val modelLabel: String) {
    Whoop5MG("WHOOP 5.0 / MG", WhoopModel.WHOOP5_MG, "5.0 MG"),
    Whoop4("WHOOP 4.0", WhoopModel.WHOOP4, "4.0"),
}

@Composable
fun AddDeviceWizard(viewModel: AppViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.Family) }
    var family by remember { mutableStateOf<Family?>(null) }
    var picked by remember { mutableStateOf<WhoopBleClient.DiscoveredWhoop?>(null) }
    var nameDraft by remember { mutableStateOf("") }

    // Belt-and-braces: never leave the present-scan running if the wizard leaves composition.
    DisposableEffect(Unit) { onDispose { viewModel.stopWhoopScan() } }

    fun goBack() {
        when (step) {
            Step.Family -> Unit
            Step.Prep -> { family = null; step = Step.Family }
            Step.Pick -> { viewModel.stopWhoopScan(); step = Step.Prep }
            Step.Confirm -> {
                // Re-enter the pick step and restart the scan so another strap can be chosen.
                family?.let { viewModel.presentWhoopScan(it.model) }
                picked = null
                step = Step.Pick
            }
        }
    }

    /** Register the picked strap and make it active. Same row shape the wizard has always written:
     *  id namespaced by address, brand "WHOOP", the full WHOOP capability set. */
    fun finishAdd() {
        val strap = picked ?: return
        val fam = family ?: return
        viewModel.stopWhoopScan()
        val now = System.currentTimeMillis() / 1000
        val advertised = strap.name?.takeIf { it.isNotBlank() } ?: fam.title
        val device = PairedDeviceRow(
            id = "whoop-${strap.address}",
            brand = "WHOOP",
            model = fam.modelLabel,
            nickname = nameDraft.trim().ifEmpty { advertised },
            peripheralId = strap.address,
            sourceKind = SourceKind.liveBLE.name,
            capabilities = "hr,hrv,spo2,skinTemp,sleep,strainLoad",
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        scope.launch { viewModel.registerDevice(device, makeActive = true) }
        onClose()
    }

    AlertDialog(
        onDismissRequest = { viewModel.stopWhoopScan(); onClose() },
        containerColor = Palette.surfaceRaised,
        title = {
            Row(verticalAlignment = Alignment.Top) {
                if (step != Step.Family) {
                    IconButton(onClick = { goBack() }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = Palette.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when (step) {
                        Step.Family -> "Add your strap"
                        Step.Prep -> family?.title ?: "Prepare"
                        Step.Pick -> "Nearby straps"
                        Step.Confirm -> "Name it"
                    },
                    style = NoopType.title2,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { viewModel.stopWhoopScan(); onClose() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close",
                        tint = Palette.textTertiary, modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        text = {
            // The dialog's text slot doesn't scroll on its own; own it here so no step is ever cut off
            // under large font scaling or on a short display.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    Step.Family -> FamilyStep(onPick = {
                        family = it
                        step = Step.Prep
                    })
                    Step.Prep -> PrepStep(onScan = {
                        family?.let { viewModel.presentWhoopScan(it.model) }
                        step = Step.Pick
                    })
                    Step.Pick -> PickStep(
                        viewModel = viewModel,
                        onSelect = { strap ->
                            viewModel.stopWhoopScan()
                            picked = strap
                            nameDraft = strap.name?.takeIf { it.isNotBlank() } ?: (family?.title ?: "WHOOP")
                            step = Step.Confirm
                        },
                        onRescan = { family?.let { viewModel.presentWhoopScan(it.model) } },
                    )
                    Step.Confirm -> ConfirmStep(
                        name = nameDraft,
                        onName = { nameDraft = it },
                        onAdd = { finishAdd() },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FamilyStep(onPick: (Family) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Family.entries.forEach { fam ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.surfaceInset)
                    .clickable { onPick(fam) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null, tint = Palette.accent)
                Spacer(Modifier.width(12.dp))
                Text(fam.title, style = NoopType.body, color = Palette.textPrimary)
            }
        }
    }
}

@Composable
private fun PrepStep(onScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            "Wear the strap, or put it on its charger.",
            "Close the official WHOOP app — a strap already connected there won't be found.",
            "Keep the strap within arm's reach.",
        ).forEach {
            Text("•  $it", style = NoopType.footnote, color = Palette.textSecondary)
        }
        PrimaryAction("Scan", enabled = true, onClick = onScan)
    }
}

@Composable
private fun PickStep(
    viewModel: AppViewModel,
    onSelect: (WhoopBleClient.DiscoveredWhoop) -> Unit,
    onRescan: () -> Unit,
) {
    val found by viewModel.discoveredWhoops.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (found.isEmpty()) {
            Text("Searching…", style = NoopType.footnote, color = Palette.textTertiary)
        }
        found.sortedByDescending { it.rssi }.forEach { strap ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.surfaceInset)
                    .clickable { onSelect(strap) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        strap.name?.takeIf { it.isNotBlank() } ?: "WHOOP",
                        style = NoopType.body, color = Palette.textPrimary,
                    )
                    Text("Signal ${strap.rssi} dBm", style = NoopType.caption, color = Palette.textTertiary)
                }
            }
        }
        TextButton(onClick = onRescan) {
            Text("Scan again", style = NoopType.footnote, color = Palette.accent)
        }
    }
}

@Composable
private fun ConfirmStep(name: String, onName: (String) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Overline("Name")
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.accent,
                unfocusedBorderColor = Palette.hairline,
                focusedTextColor = Palette.textPrimary,
                unfocusedTextColor = Palette.textPrimary,
                cursorColor = Palette.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Device name" },
        )
        PrimaryAction("Add", enabled = name.trim().isNotEmpty(), onClick = onAdd)
    }
}

/** The wizard's one filled action button. */
@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Palette.accent else Palette.surfaceInset),
    ) {
        Text(
            label,
            style = NoopType.body,
            color = if (enabled) Palette.surfaceBase else Palette.textTertiary,
        )
    }
}
