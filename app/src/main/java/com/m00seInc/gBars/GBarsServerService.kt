package com.m00seInc.gBars

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class GBarsServerService : Service() {

    private val tag = "gBars_ServerService"
    private val serverPort = 9876
    private val notificationId = 102
    private val channelId = "gBars_Server_Channel"

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var serverSocket: ServerSocket? = null
    private var connectionWorkerJob: Job? = null

    // REFINEMENT: Heartbeat job container for the 5-second refresh cycle
    private var heartbeatJob: Job? = null

    private var isReceiverRegistered = false

    //private var localTestingApp= false

    // SYSTEM ROUTING HOOK: Listens dynamically to hardware Hotspot toggles
    private val hotspotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                // WifiManager.EXTRA_WIFI_AP_STATE evaluates to "wifi_state"
                var state = intent.getIntExtra("wifi_state", 11)
                Log.d(
                    tag,
                    "Hotspot Interceptor: Caught system AP state change alert -> Code: $state"
                )

                when (state) {
                    13 -> { // WifiManager.WIFI_AP_STATE_ENABLED
                        Log.i(
                            tag,
                            "Hotspot State: [ACTIVE]. Spinning up server sockets & tracking loops."
                        )
                        startSocketEngine()
                        startHeartbeatLoop()
                    }

                    10, 11, 12, 14 -> { // Disabling, Disabled, Enabling, Failed
                        Log.i(
                            tag,
                            "Hotspot State: [INACTIVE / CHANGING]. Placing server on standby hold."
                        )
                        stopSocketEngineOnly()
                        stopHeartbeatLoopOnly()
                        // FIX 2: Explicitly kill the NetworkMonitorService so its modem callbacks aren't left stranded when the hotspot drops
                        stopService(Intent(applicationContext, NetworkMonitorService::class.java))
                        // FIXED: Post standby state message immediately to the status tray
                        val notificationManager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(
                            notificationId,
                            buildServerNotification("STANDBY")
                        )
                    }
                }
            }
        }
    }

    private fun evaluateStartupHotspotState() {
        Log.d(tag, "Startup Scan: Querying system provider Global space parameters...")
        try {
            // Fallback default to 11 (WIFI_AP_STATE_DISABLED) if the key isn't populated yet
            val apState =
                android.provider.Settings.Global.getInt(contentResolver, "wifi_ap_state", 11)
            Log.d(tag, "Startup Scan: Retrieved global AP state token -> $apState")

            // Catching both standard AOSP (13) and vendor-specific variations (1)
            val isHotspotActive = (apState == 13 || apState == 1)

            if (isHotspotActive) {
                Log.i(
                    tag,
                    "Startup Validation: Hotspot verified [ACTIVE]. Initializing socket engines."
                )
                startSocketEngine()
                startHeartbeatLoop()
            } else {
                Log.i(
                    tag,
                    "Startup Validation: Hotspot verified [INACTIVE]. Shifting to standby constraint."
                )
                buildServerNotification("STANDBY")
            }
        } catch (e: Exception) {
            Log.e(tag, "Startup Scan Failure: Global state reading error: ${e.message}")
            // Fail-safe fallback to standalone message state
            buildServerNotification("STANDBY")
        }
    }

    private fun stopHeartbeatLoopOnly() {
        Log.d(tag, "Heartbeat Engine: Halting background update timers.")
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun stopSocketEngineOnly() {
        Log.d(tag, "Socket Engine: Directing teardown of network pipelines.")
        connectionWorkerJob?.cancel()
        connectionWorkerJob = null
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(tag, "Socket Engine: Sockets safely disconnected.")
        } catch (e: Exception) {
            Log.e(tag, "Socket Engine: Exception thrown during pipeline close down: ${e.message}")
        }
    }


    override fun onCreate() {
        Log.i(tag, "[Lifecycle] onCreate triggered. Initializing Standalone Server Context.")
        super.onCreate()
        createNotificationChannel()

        // FIXED: Enforce explicit validation parameters for Android 14+ runtime environments
        // Secure immediate foreground status
        val initialNotification = buildServerNotification("STANDBY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, initialNotification)
        }

        AppState.isServerRunning.value = true
        // Register the hotspot receiver interface
        // Note: This broadcast is "sticky", so Android will fire onReceive immediately with the current state!
        try {
            val filter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
            registerReceiver(hotspotStateReceiver, filter)
            isReceiverRegistered = true
            Log.d(tag, "Hotspot Interceptor: Broadcast listener attached successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Hotspot Interceptor: Failed to attach receiver: ${e.message}")
        }

        // FIXED: Proactively check live kernel interfaces to see if hotspot is already active
        evaluateStartupHotspotState()

        /*if(localTestingApp){
            Log.i(tag, "APP IN DEBUG MODE. Spinning up server sockets & tracking loops.")
            startSocketEngine()
            startHeartbeatLoop()
        }UNCOMMENT ONLY FOR TESTING*/
    }

    // REFINEMENT: Continuous 5-second visual heartbeat and thread persistence loop
    private fun startHeartbeatLoop() {
        Log.d(tag, "Heartbeat Engine: Launching 5-second notification refresh loop.")
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var pulseToggle = false
            val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            while (isActive) {
                delay(5000)
                pulseToggle = !pulseToggle

                val currentTime = timeFormatter.format(Date())
                // Alternating pulse glyph to show live processing visual ticks
                val statusMessage = "ACTIVE"

                Log.d(tag, "Heartbeat Engine: Ticking. Refreshing server notification bar text.")

                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(notificationId, buildServerNotification(statusMessage))
            }
        }
    }

    private fun startSocketEngine() {
        Log.d(tag, "Socket Engine: Launching connection pool coroutine structure.")
        connectionWorkerJob?.cancel()
        connectionWorkerJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Log.d(tag, "Socket Engine: Attempting ServerSocket bind on port $serverPort...")
                    // FIX 3: Introduce an idle timeout. If no client talks to the port for 5 mins, throw a socket exception to save battery
                    serverSocket = ServerSocket(serverPort).apply {
                        reuseAddress = true
                        soTimeout = 5 * 60 * 1000
                    }
                    Log.i(tag, "Socket Engine: Successfully bound socket port listener to port $serverPort")
                    while (isActive) {
                        val socket = serverSocket?.accept() ?: break
                        // FIX 4: Use structured scoping linked directly to the service lifecycle to clean up active socket streams cleanly
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                handleMacTransaction(socket)
                            } finally {
                                try {
                                    socket.close()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(
                        tag,
                        "Socket Engine Timeout: No active Mac connections detected. Self-terminating engine to drop battery baseline."
                    )
                    withContext(Dispatchers.Main) { stopSelf() }
                } catch (e: java.net.SocketException) {
                    // FIXED: Catch intentional shutdowns quietly without throwing a full error log dump
                    if (!isActive || !AppState.isServerRunning.value) {
                        Log.i(
                            tag,
                            "Socket Engine: Server socket un-bound and closed cleanly during service shutdown."
                        )
                    } else {
                        Log.e(tag, "Socket Engine Unexpected Network Exception: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(
                        tag,
                        "Socket Engine Generic Error: Loop interface encountered an error: ${e.message}"
                    )
                    delay(2000)
                } finally {
                    try {
                        serverSocket?.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun handleMacTransaction(socket: Socket) {
        val clientIp = socket.remoteSocketAddress
        Log.d(tag, "Pipeline [$clientIp]: Initializing reader/writer streams.")
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: ""
            Log.i(tag, "Pipeline [$clientIp]: Received HTTP Request Line -> \"$firstLine\"")

            if (firstLine.isEmpty()) {
                Log.w(
                    tag,
                    "Pipeline [$clientIp]: Inbound transmission line empty. Aborting pipeline process."
                )
                return
            }

            // Consume all incoming HTTP request header metadata lines to bypass TCP RST packet drop boundaries
            var drainedHeadersCount = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                drainedHeadersCount++
            }
            Log.d(
                tag,
                "Pipeline [$clientIp]: Successfully drained $drainedHeadersCount unread header data rows."
            )

            // Check URL endpoints and issue intents matching what UI actions perform
            when {
                firstLine.contains("POST /start-monitor") -> {
                    Log.i(
                        tag,
                        "Pipeline [$clientIp]: Routing endpoint matched: [/start-monitor]. Flipping systems."
                    )
                    dispatchTelemetryCommand(enable = true, mode = AppMode.MONITORING)
                }

                firstLine.contains("POST /start-refresh") -> {
                    Log.i(
                        tag,
                        "Pipeline [$clientIp]: Routing endpoint matched: [/start-refresh]. Flipping systems."
                    )
                    dispatchTelemetryCommand(enable = true, mode = AppMode.REFRESH)
                }

                firstLine.contains("POST /stop-engine") -> {
                    Log.i(
                        tag,
                        "Pipeline [$clientIp]: Routing endpoint matched: [/stop-engine]. Stopping monitoring service."
                    )
                    dispatchTelemetryCommand(enable = false, mode = AppState.activeMode.value)
                }

                else -> {
                    Log.d(
                        tag,
                        "Pipeline [$clientIp]: Non-mutating state query endpoint handled (/status request generic scan)."
                    )
                }
            }

            // Extract the data variables directly out of AppState indicators
            val serviceActive = AppState.isServiceRunning.value
            val currentMode = AppState.activeMode.value.name
            val currentCell = AppState.currentNetworkMode.value
            Log.d(
                tag,
                "Pipeline [$clientIp]: Mapping current status values: running=$serviceActive, mode=$currentMode, network=$currentCell"
            )

            val jsonPayload =
                "{\"serverActive\":true,\"serviceRunning\":$serviceActive,\"activeMode\":\"$currentMode\",\"networkMode\":\"$currentCell\"}"
            Log.i(tag, "Pipeline [$clientIp]: Formulated JSON Response Payload -> $jsonPayload")

            val httpHeadersBlock = """
                HTTP/1.1 200 OK
                Content-Type: application/json
                Access-Control-Allow-Origin: *
                Content-Length: ${jsonPayload.length}
                Connection: close

                $jsonPayload
            """.trimIndent()

            val outputStream = socket.getOutputStream()
            outputStream.write(httpHeadersBlock.toByteArray(StandardCharsets.UTF_8))
            outputStream.flush()
            Log.i(
                tag,
                "Pipeline [$clientIp]: Server bytes successfully written and flushed out to network pipeline."
            )
        } catch (e: Exception) {
            Log.e(
                tag,
                "Pipeline Error [$clientIp]: Fatal error processing transaction stream: ${e.message}"
            )
            Log.e(tag, "Pipeline Error [$clientIp] StackTrace: ${Log.getStackTraceString(e)}")
        } finally {
            try {
                socket.close()
                Log.d(
                    tag,
                    "Pipeline [$clientIp]: Socket handles cleaned and dropped successfully in finally segment."
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchTelemetryCommand(enable: Boolean, mode: AppMode) {
        Log.d(
            tag,
            "Command Dispatched: Processing request update. Target active status configuration -> enable=$enable, mode=${mode.name}"
        )
        // FIX 1: Mutate RAM variables IMMEDIATELY on the calling thread to defeat Doze/Main-thread lag
        Log.d(
            tag,
            "Command Dispatched [Main Thread]: Mutating AppState properties directly matching target adjustments."
        )
        AppState.activeMode.value = mode
        AppState.saveAppState(applicationContext)

        serviceScope.launch(Dispatchers.Main) {
            val serviceIntent = Intent(applicationContext, NetworkMonitorService::class.java)
            if (enable) {
                Log.i(
                    tag,
                    "Command Dispatched [Main Thread]: Firing start service intent targets to activate tracking systems."
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.i(
                    tag,
                    "Command Dispatched [Main Thread]: Firing stopService intent down to tear down monitoring containers."
                )
                stopService(serviceIntent)
            }
        }
    }

    private fun buildServerNotification(status: String): Notification {
        // 1. Configure an explicit intent pointing back to your dashboard UI
        val intent = Intent(this, MainActivity::class.java).apply {
            // Prevents spinning up redundant duplicate activity instances if the app is already open
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 2. Wrap it inside a PendingIntent wrapper
        // Android 12+ / 15 strictly mandates declaring explicit IMMUTABLE or MUTABLE flags
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            //.setContentTitle("gBars Remote Server Link")
            .setContentText("MAC LINK " + status + " - v1.4.7.5 \\ STABLE")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent) // FIXED: Binds the tap intent action
            .setOngoing(true)
            .setSilent(true) // Keeps the 5-second updates silent so it doesn't vibrate or chime
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(
                tag,
                "Lifecycle: Registering standalone low-importance notification channel context blocks."
            )
            val channel = NotificationChannel(
                channelId,
                "gBars Remote Server Interface",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            tag,
            "[Lifecycle] onStartCommand processed. Service pinned to continuous runtime loops."
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(tag, "[Lifecycle] onDestroy triggered. Tearing down standalone server contexts.")
        AppState.isServerRunning.value = false
        AppState.saveAppState(this)

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(hotspotStateReceiver)
                Log.d(tag, "Hotspot Interceptor: Broadcast receiver unregistered cleanly.")
            } catch (_: Exception) {
            }
        }

        serviceJob.cancelChildren()
        serviceJob.cancel()
        try {
            serverSocket?.close()
            Log.d(
                tag,
                "[Lifecycle] Server socket handles dropped cleanly during core lifecycle release loops."
            )
        } catch (e: Exception) {
            Log.e(tag, "[Lifecycle] Error closing ServerSocket during shutdown: ${e.message}")
        }
        super.onDestroy()
    }
}