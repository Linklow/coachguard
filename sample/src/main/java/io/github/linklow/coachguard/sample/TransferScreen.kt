package io.github.linklow.coachguard.sample

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.links.LinkAssessment
import io.github.linklow.coachguard.links.LinkRiskLevel
import io.github.linklow.coachguard.risk.RiskAssessment
import io.github.linklow.coachguard.risk.RiskLevel
import io.github.linklow.coachguard.risk.RiskReason
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.ScreenCaptureState
import java.util.Locale

@Composable
fun TransferScreen(
    onConfirm: (TransactionContext) -> RiskAssessment,
    onNewSession: () -> Unit,
    onResetProfile: () -> Unit,
    onProtectScreenChange: (Boolean) -> Unit,
    onCheckLink: (String) -> LinkAssessment,
) {
    var protectScreen by remember { mutableStateOf(false) }
    var isNewPayee by remember { mutableStateOf(true) }
    var isAmountUnusual by remember { mutableStateOf(false) }
    var assessment by remember { mutableStateOf<RiskAssessment?>(null) }
    var link by remember { mutableStateOf("") }
    var linkAssessment by remember { mutableStateOf<LinkAssessment?>(null) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Confirm transfer", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap Confirm while on a phone or WhatsApp call, while sharing your screen, " +
                    "or with a remote-access app installed to see how the risk changes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Behavior: confirm normally, then tap New session; repeat 5 times to build your " +
                    "baseline. Then try leaving the app and pausing before you confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow("New payee", isNewPayee) { isNewPayee = it }
            SwitchRow("Unusual amount", isAmountUnusual) { isAmountUnusual = it }
            SwitchRow("Protect screen (black in recordings and screen sharing)", protectScreen) {
                protectScreen = it
                onProtectScreenChange(it)
                assessment = null
            }
            Button(
                onClick = { assessment = onConfirm(TransactionContext(isNewPayee, isAmountUnusual)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Confirm")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onNewSession()
                        assessment = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("New session")
                }
                TextButton(
                    onClick = {
                        onResetProfile()
                        assessment = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset baseline")
                }
            }
            assessment?.let { AssessmentCard(it) }

            HorizontalDivider()
            Text("Check a payment link", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Link from a QR code or message") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { linkAssessment = onCheckLink(link) },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check link")
            }
            linkAssessment?.let { LinkCard(it) }
        }
    }
}

@Composable
private fun LinkCard(assessment: LinkAssessment) {
    val color = when (assessment.level) {
        LinkRiskLevel.LOW -> RiskLevel.LOW.color()
        LinkRiskLevel.MEDIUM -> RiskLevel.MEDIUM.color()
        LinkRiskLevel.HIGH -> RiskLevel.HIGH.color()
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (assessment.host == null) {
                Text("No host found in this link.")
            } else {
                Text(
                    String.format(Locale.ROOT, "%s · phishing probability %.1f%%", assessment.level, assessment.probability * 100),
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                Text("Host: ${assessment.host}", style = MaterialTheme.typography.bodySmall)
                assessment.reasons.forEach { reason ->
                    Text(
                        String.format(Locale.ROOT, "• %s (%+.2f)", reason.description, reason.contribution),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AssessmentCard(assessment: RiskAssessment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = assessment.level.color().copy(alpha = 0.12f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${assessment.level} risk · ${(assessment.score * 100).toInt()}%",
                color = assessment.level.color(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (assessment.reasons.isEmpty()) {
                Text("No warning signs observed.")
            } else {
                assessment.reasons.forEach { ReasonRow(it) }
            }
            HorizontalDivider()
            assessment.signals.behavior?.let { BehaviorSummary(it) }
            HorizontalDivider()
            SignalsSummary(assessment.signals)
        }
    }
}

@Composable
private fun ReasonRow(reason: RiskReason) {
    Column {
        Text("${reason.code} (+${(reason.weight * 100).toInt()})", fontWeight = FontWeight.SemiBold)
        Text(reason.message, style = MaterialTheme.typography.bodySmall)
        reason.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BehaviorSummary(behavior: BehaviorAnalysis) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Behavior", fontWeight = FontWeight.SemiBold)
        val status = if (behavior.isBaselineReady) {
            String.format(
                Locale.ROOT,
                "Baseline from %d sessions · anomaly score %.2f",
                behavior.baselineSessions,
                behavior.anomalyScore,
            )
        } else {
            "Learning: ${behavior.baselineSessions} sessions so far"
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        val features = behavior.features
        Text(
            String.format(
                Locale.ROOT,
                "This session: %.1f s to confirm, %d touches, %d focus losses, %.1f s away, longest pause %.1f s",
                features.timeToConfirmMillis / 1000.0,
                features.touches,
                features.focusLosses,
                features.timeAwayMillis / 1000.0,
                features.longestPauseMillis / 1000.0,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        behavior.deviations.take(3).forEach { deviation ->
            Text(
                String.format(Locale.ROOT, "%s: %+.1fσ", deviation.feature, deviation.zScore),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SignalsSummary(signals: DeviceSignals) {
    val lines = listOf(
        "Call" to signals.callState.name,
        "Screen capture" to screenCaptureText(signals.screenCapture),
        "Remote-access apps" to signals.remoteAccessApps.ifEmpty { listOf("none") }.joinToString(),
        "Accessibility services" to signals.accessibilityServices.size.toString(),
        "Obscured taps" to "${signals.obscuredTouchCount} (partial: ${signals.partiallyObscuredTouchCount})",
        "ADB" to (signals.adbEnabled?.toString() ?: "unknown"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Signals", fontWeight = FontWeight.SemiBold)
        lines.forEach { (name, value) ->
            Text("$name: $value", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun screenCaptureText(state: ScreenCaptureState): String =
    if (state == ScreenCaptureState.UNKNOWN && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        "UNKNOWN (detection needs Android 15+; use Protect screen)"
    } else {
        state.name
    }

private fun RiskLevel.color(): Color = when (this) {
    RiskLevel.LOW -> Color(0xFF2E7D32)
    RiskLevel.MEDIUM -> Color(0xFFEF6C00)
    RiskLevel.HIGH -> Color(0xFFC62828)
}

@Preview(showBackground = true)
@Composable
private fun TransferScreenPreview() {
    MaterialTheme {
        TransferScreen(
            onConfirm = { error("Preview only") },
            onNewSession = {},
            onResetProfile = {},
            onProtectScreenChange = {},
            onCheckLink = { error("Preview only") },
        )
    }
}
