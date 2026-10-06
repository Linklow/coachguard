package io.github.linklow.coachguard.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import io.github.linklow.coachguard.CoachGuard
import io.github.linklow.coachguard.GuardSession
import io.github.linklow.coachguard.SessionOptions
import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.links.PhishingLinkClassifier
import io.github.linklow.coachguard.risk.RiskAssessment

/** A stand-in for a bank's "confirm transfer" screen, guarded for as long as it is open. */
class MainActivity : ComponentActivity() {

    private lateinit var guard: CoachGuard
    private lateinit var session: GuardSession
    private var protectScreen = false
    private val linkClassifier by lazy { PhishingLinkClassifier.create() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        guard = CoachGuard.create(this)
        session = startSession()
        setContent {
            MaterialTheme {
                TransferScreen(
                    onConfirm = ::confirm,
                    onNewSession = ::restartSession,
                    onResetProfile = ::resetProfile,
                    onProtectScreenChange = ::setProtectScreen,
                    onCheckLink = linkClassifier::assess,
                )
            }
        }
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private fun startSession(): GuardSession =
        guard.startSession(this, SessionOptions(protectScreen = protectScreen))

    private fun confirm(transaction: TransactionContext): RiskAssessment = session.assess(transaction)

    /** Simulates leaving and reopening the payment screen, which starts a new behavioral session. */
    private fun restartSession() {
        session.close()
        session = startSession()
    }

    private fun resetProfile() {
        session.close()
        guard.resetBehaviorProfile()
        session = startSession()
    }

    private fun setProtectScreen(enabled: Boolean) {
        protectScreen = enabled
        restartSession()
    }
}
