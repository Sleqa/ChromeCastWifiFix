package com.sleqa.wififix.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sleqa.wififix.Diagnostics
import com.sleqa.wififix.EventLog
import com.sleqa.wififix.GuardStatus
import com.sleqa.wififix.Notifications
import com.sleqa.wififix.Prefs
import com.sleqa.wififix.R
import com.sleqa.wififix.core.GuardAction
import com.sleqa.wififix.core.GuardConfig
import com.sleqa.wififix.core.GuardEnvironment
import com.sleqa.wififix.core.GuardEvent
import com.sleqa.wififix.core.GuardStateMachine
import com.sleqa.wififix.core.Kind
import com.sleqa.wififix.core.Tier
import com.sleqa.wififix.strategy.DeviceOwnerStrategy
import com.sleqa.wififix.strategy.DirectStrategy
import com.sleqa.wififix.strategy.EnableStrategy
import com.sleqa.wififix.strategy.StrategyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that watches the Wi-Fi radio and turns it back on.
 *
 * Detection runs on two independent paths:
 *  - a WIFI_STATE_CHANGED receiver registered *at runtime*. A manifest-declared
 *    one would never fire: it is an implicit broadcast, and Android 8+ blocks
 *    those for manifest receivers.
 *  - a poll of WifiManager.getWifiState() as a safety net, in case the broadcast
 *    is missed or the radio goes down without one.
 *
 * All state lives in [GuardStateMachine], which is fed from a single channel and
 * consumed by a single coroutine, so it needs no locking of its own.
 */
class WifiGuardService : Service() {

    private val events = Channel<GuardEvent>(Channel.UNLIMITED)
    private lateinit var scope: CoroutineScope
    private lateinit var machine: GuardStateMachine
    private lateinit var prefs: Prefs
    private lateinit var direct: DirectStrategy
    private lateinit var strategies: List<EnableStrategy>
    private lateinit var wifi: WifiManager

    /**
     * Read by the tick loop, written by the consumer. The machine itself is not
     * thread-safe, so its cadence hint is copied out rather than called across
     * coroutines.
     */
    @Volatile
    private var tickHintMs = 10_000L

    private var lastPolledState = -1
    private var status = "Starting up"

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(
                WifiManager.EXTRA_WIFI_STATE,
                WifiManager.WIFI_STATE_UNKNOWN,
            )
            lastPolledState = state
            events.trySend(GuardEvent.WifiChanged(state, now()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Must be the first thing we do: startForegroundService gives us five
        // seconds to post this or the system kills us.
        Notifications.createChannels(this)
        startForeground(Notifications.ONGOING_ID, Notifications.ongoing(this, status))

        prefs = Prefs(this)
        wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        direct = DirectStrategy(this)
        strategies = listOf(direct, DeviceOwnerStrategy(this, direct))
        machine = GuardStateMachine(GuardConfig(), environment())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        ContextCompat.registerReceiver(
            this,
            wifiReceiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        EventLog.append(this, Kind.SERVICE_START, null, "watchdog started")
        GuardStatus.serviceRunning = true

        consumeEvents()
        pollLoop()

        // Restore any snooze that outlived the process.
        prefs.snoozeUntil.takeIf { it > 0 }?.let {
            events.trySend(GuardEvent.Snooze(it, now()))
        }

        WatchdogAlarm.armPeriodic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> {
                val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
                prefs.snoozeUntil = until
                events.trySend(GuardEvent.Snooze(until, now()))
            }

            ACTION_RESUME -> {
                prefs.snoozeUntil = 0
                events.trySend(GuardEvent.Resume(now()))
            }

            ACTION_SELF_TEST -> startSelfTest()

            ACTION_FORCE_ENABLE -> forceEnable("dead-man's switch")
        }
        // START_STICKY so the system brings us back after a low-memory kill.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wifiReceiver) }
        if (::scope.isInitialized) scope.cancel()
        GuardStatus.cleared()
        // Belt and braces: if something stopped us, have the alarm restart us.
        WatchdogAlarm.armPeriodic(this, delayMs = 60_000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun environment() = object : GuardEnvironment {
        override fun isAirplaneModeOn() = Diagnostics.isAirplaneModeOn(this@WifiGuardService)
        override fun isTierAvailable(tier: Tier) =
            strategies.firstOrNull { it.tier == tier }?.isAvailable() == true
    }

    private fun consumeEvents() = scope.launch {
        for (event in events) {
            if (!prefs.guardEnabled && event !is GuardEvent.Resume) continue
            val actions = runCatching { machine.onEvent(event) }.getOrElse { t ->
                EventLog.append(
                    this@WifiGuardService, Kind.BLOCKED, null,
                    "state machine threw: ${t.javaClass.simpleName}: ${t.message}",
                )
                emptyList()
            }
            actions.forEach { execute(it) }
            tickHintMs = machine.nextTickHintMs()
            GuardStatus.publish(machine.snapshot())
        }
    }

    private fun execute(action: GuardAction) {
        when (action) {
            is GuardAction.Record -> {
                EventLog.append(this, action.kind, action.tier?.label, action.detail)
                if (action.kind == Kind.GAVE_UP || action.kind == Kind.AUTO_SNOOZE) {
                    Notifications.postAlert(this, getString(R.string.app_name), action.detail)
                }
            }

            is GuardAction.Status -> {
                status = action.text
                getSystemService(NotificationManager::class.java)
                    ?.notify(Notifications.ONGOING_ID, Notifications.ongoing(this, status))
            }

            is GuardAction.Attempt -> runAttempt(action.tier)
        }
    }

    /** Runs off the consumer thread and reports back as an event. */
    private fun runAttempt(tier: Tier) = scope.launch(Dispatchers.IO) {
        val strategy = strategies.firstOrNull { it.tier == tier }
        val result = if (strategy == null) {
            StrategyResult(false, "no strategy for ${tier.label}")
        } else {
            runCatching { strategy.enable() }.getOrElse {
                StrategyResult(false, "threw ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        events.trySend(GuardEvent.AttemptOutcome(tier, result.accepted, result.detail, now()))
    }

    private fun pollLoop() = scope.launch {
        while (isActive) {
            val state = runCatching { wifi.wifiState }.getOrDefault(WifiManager.WIFI_STATE_UNKNOWN)
            if (state != lastPolledState) {
                lastPolledState = state
                events.trySend(GuardEvent.WifiChanged(state, now()))
            }
            events.trySend(GuardEvent.Tick(now()))
            delay(tickHintMs)
        }
    }

    /**
     * Turns Wi-Fi off on purpose to prove the repair works end to end. Arms an
     * independent alarm first: if the state machine itself is broken, that alarm
     * still forces the radio back on and the device does not end up stranded
     * with no network and no way in.
     */
    private fun startSelfTest() {
        WatchdogAlarm.armDeadMansSwitch(this, delayMs = 60_000)
        events.trySend(GuardEvent.SelfTestArmed(now()))
        scope.launch(Dispatchers.IO) {
            val result = direct.disable()
            EventLog.append(this@WifiGuardService, Kind.SELF_TEST, null, "disabled Wi-Fi: ${result.detail}")
        }
    }

    private fun forceEnable(why: String) = scope.launch(Dispatchers.IO) {
        strategies.filter { it.isAvailable() }.forEach { s ->
            val r = runCatching { s.enable() }.getOrElse { StrategyResult(false, it.message ?: "threw") }
            EventLog.append(this@WifiGuardService, Kind.ATTEMPT, s.tier.label, "$why: ${r.detail}")
            if (r.accepted) return@launch
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val ACTION_SNOOZE = "com.sleqa.wififix.SNOOZE"
        const val ACTION_RESUME = "com.sleqa.wififix.RESUME"
        const val ACTION_SELF_TEST = "com.sleqa.wififix.SELF_TEST"
        const val ACTION_FORCE_ENABLE = "com.sleqa.wififix.FORCE_ENABLE"
        const val EXTRA_UNTIL = "until"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WifiGuardService::class.java))
        }

        fun send(ctx: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(ctx, WifiGuardService::class.java).setAction(action).apply(configure)
            ContextCompat.startForegroundService(ctx, intent)
        }
    }
}
