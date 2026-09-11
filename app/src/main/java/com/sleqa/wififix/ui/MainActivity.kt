package com.sleqa.wififix.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.sleqa.wififix.Diagnostics
import com.sleqa.wififix.EventLog
import com.sleqa.wififix.GuardStatus
import com.sleqa.wififix.Prefs
import com.sleqa.wififix.R
import com.sleqa.wififix.core.Kind
import com.sleqa.wififix.core.WifiState
import com.sleqa.wififix.service.WifiGuardService
import com.sleqa.wififix.strategy.DirectStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One D-pad-navigable status screen. Framework views only -- six focusable
 * widgets do not justify Compose or leanback, and plain Buttons already handle
 * remote-control focus correctly.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var direct: DirectStrategy
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_500)
        }
    }

    private val timeFormat = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        direct = DirectStrategy(this)

        if (prefs.guardEnabled) WifiGuardService.start(this)

        findViewById<Button>(R.id.btn_toggle).setOnClickListener { toggleGuard() }
        findViewById<Button>(R.id.btn_probe).setOnClickListener { probeCapability() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { confirmSelfTest() }
        findViewById<Button>(R.id.btn_snooze).setOnClickListener { chooseSnooze() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            EventLog.clear(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun toggleGuard() {
        prefs.guardEnabled = !prefs.guardEnabled
        if (prefs.guardEnabled) {
            WifiGuardService.start(this)
            WifiGuardService.send(this, WifiGuardService.ACTION_RESUME)
        } else {
            stopService(android.content.Intent(this, WifiGuardService::class.java))
        }
        render()
    }

    /**
     * The zero-risk capability probe: calls setWifiEnabled(true) while Wi-Fi is
     * already on, which changes nothing but reveals whether the framework lets
     * this app make the call at all.
     */
    private fun probeCapability() {
        val state = direct.currentState()
        if (state != WifiState.ENABLED) {
            toast(getString(R.string.probe_needs_wifi_on))
            return
        }
        val result = direct.enable()
        val verdict = if (result.accepted) {
            // Deliberately hedged: since Android 13 a `true` can also mean the
            // framework posted a confirmation dialog instead of doing anything.
            getString(R.string.probe_accepted)
        } else {
            getString(R.string.probe_rejected)
        }
        prefs.lastProbe = "${timeFormat.format(Date())}: ${result.detail} - $verdict"
        EventLog.append(this, Kind.PROBE, null, "${result.detail} - $verdict")
        AlertDialog.Builder(this)
            .setTitle(R.string.probe_title)
            .setMessage("${result.detail}\n\n$verdict")
            .setPositiveButton(android.R.string.ok, null)
            .show()
        render()
    }

    private fun confirmSelfTest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.test_title)
            .setMessage(R.string.test_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.test_confirm) { _, _ ->
                WifiGuardService.start(this)
                WifiGuardService.send(this, WifiGuardService.ACTION_SELF_TEST)
            }
            .show()
    }

    private fun chooseSnooze() {
        val labels = resources.getStringArray(R.array.snooze_labels)
        AlertDialog.Builder(this)
            .setTitle(R.string.snooze_title)
            .setItems(labels) { _, which ->
                val now = System.currentTimeMillis()
                val until = when (which) {
                    0 -> 0L
                    1 -> now + 30 * 60_000L
                    2 -> now + 2 * 60 * 60_000L
                    else -> Prefs.UNTIL_REBOOT
                }
                if (until == 0L) {
                    WifiGuardService.send(this, WifiGuardService.ACTION_RESUME)
                } else {
                    WifiGuardService.send(this, WifiGuardService.ACTION_SNOOZE) {
                        putExtra(WifiGuardService.EXTRA_UNTIL, until)
                    }
                }
                render()
            }
            .show()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val diag = Diagnostics.collect(this)
        val snapshot = GuardStatus.snapshot
        val stats = EventLog.stats(this)

        findViewById<TextView>(R.id.txt_wifi).text = getString(
            R.string.line_wifi,
            WifiState.name(direct.currentState()),
        )

        findViewById<TextView>(R.id.txt_guard).text = when {
            !prefs.guardEnabled -> getString(R.string.guard_off)
            snapshot == null -> getString(R.string.guard_starting)
            else -> getString(
                R.string.line_guard,
                snapshot.phase.name,
                snapshot.status,
                snapshot.currentTier?.label ?: getString(R.string.tier_none),
            )
        }

        findViewById<TextView>(R.id.txt_snooze).text =
            getString(R.string.line_snooze, prefs.describeSnooze(now))

        findViewById<TextView>(R.id.txt_stats).text = getString(
            R.string.line_stats,
            stats.outages,
            stats.repairs,
            stats.giveUps,
            if (stats.lastOutageAt == 0L) "never" else timeFormat.format(Date(stats.lastOutageAt)),
        )

        findViewById<TextView>(R.id.txt_diag).text = buildString {
            appendLine(getString(R.string.line_android, diag.androidRelease))
            appendLine(getString(R.string.line_targetsdk, diag.targetSdk))
            appendLine(getString(R.string.line_appop, diag.changeWifiStateOp))
            appendLine(getString(R.string.line_airplane, diag.airplaneMode.toString()))
            appendLine(getString(R.string.line_standby, diag.lowPowerStandby))
            appendLine(getString(R.string.line_owner, diag.deviceOwner.toString()))
            val probe = prefs.lastProbe
            if (probe.isNotEmpty()) appendLine(getString(R.string.line_probe, probe))
            if (!diag.looksHealthy) appendLine(getString(R.string.diag_warning))
        }.trim()

        findViewById<TextView>(R.id.txt_events).text =
            EventLog.recent(this, 20).joinToString("\n") { e ->
                "${timeFormat.format(Date(e.at))}  ${e.kind}${e.tier?.let { " [$it]" } ?: ""}  ${e.detail}"
            }.ifEmpty { getString(R.string.no_events) }

        findViewById<Button>(R.id.btn_toggle).setText(
            if (prefs.guardEnabled) R.string.btn_stop else R.string.btn_start
        )
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }
}
