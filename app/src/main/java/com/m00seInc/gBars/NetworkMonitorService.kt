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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.telephony.TelephonyManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.cancelChildren

class NetworkMonitorService : Service() {

    private val tag = "gBars_Service"
    private val channelId = "gBars_Channel"
    private val notificationId = 101

    private lateinit var networkMonitor: NetworkMonitor
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var lastKnownMode = "Detecting..."

    // Refinement: Track lock state to gate the heartbeat
    private var isDeviceUnlocked = true

    private var updateJob: Job? = null

    private var lastStartId: Int = -1

    private var isReceiverRegistered = false
    private var pendingPokeJob: Job? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var isCellularDataActive = false

    private var lastPokeTime = 0L
    private val REFRESH_COOLDOWN_MS = 3_600_000L // 1 Hour
    //private val REFRESH_COOLDOWN_MS = 60_000L // 30 sec for testing
    private var heartbeatJob: Job? = null

    private fun manageHeartbeat(start: Boolean) {
        heartbeatJob?.cancel() // Kill any existing ghost loop immediately
        heartbeatJob = null

        if (start) {
            heartbeatJob = serviceScope.launch {
                while (true) {
                    delay(5000)
                    // ZOMBIE CHECK: Terminate this coroutine loop if a newer service has started
                    if (AppState.activeServiceHash != this@NetworkMonitorService.hashCode()) {
                        Log.w(tag, "Zombie heartbeat loop detected (${this@NetworkMonitorService.hashCode()}). Terminating.")
                        heartbeatJob?.cancel()
                        return@launch
                    }
                    if (isDeviceUnlocked) {
                        networkMonitor.checkInitialState()
                        updateNotification(lastKnownMode)
                        Log.d(tag, "Heartbeat Loop: Active.")
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (AppState.activeServiceHash != this@NetworkMonitorService.hashCode()) return
            isCellularDataActive = true
            Log.d("gBars_Network", "Cellular Data Available")
        }

        override fun onLost(network: Network) {
            if (AppState.activeServiceHash != this@NetworkMonitorService.hashCode()) return
            isCellularDataActive = false
            Log.d("gBars_Network", "Cellular Data Lost/Disabled")
        }
    }

    private val lockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // ZOMBIE CHECK: If I am an old instance, kill this receiver and exit
            if (AppState.activeServiceHash != this@NetworkMonitorService.hashCode()) {
                Log.w(tag, "Zombie receiver detected (${this@NetworkMonitorService.hashCode()}). Evicting.")
                try { context?.unregisterReceiver(this) } catch (e: Exception) {}
                return
            }
            pendingPokeJob?.cancel()
            //val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(tag, "Phone Locked")
                    pendingPokeJob?.cancel()
                    isDeviceUnlocked = false
                    if (AppState.activeMode.value == AppMode.MONITORING) {
                        // REFINEMENT: If the Mac server link is alive, don't stop the telephony engine
                        if (AppState.isServerRunning.value) {
                            Log.i(tag, "Screen Off: Server link is ACTIVE. Keeping live telephony monitor loop spinning.")
                        } else {
                            Log.i(tag, "Screen Off: Server link is INACTIVE. Detaching monitor engine to preserve battery.")
                            networkMonitor.stopMonitoring()
                            stopUpdateLoop()
                        }
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.d(tag, "Phone Unlocked")
                    isDeviceUnlocked = true
                    val currentTime = System.currentTimeMillis()

                    // 1. Handle MONITORING Mode separately
                    if (AppState.activeMode.value == AppMode.MONITORING) {
                        // REFINEMENT: Skip re-registering listeners if they were kept alive during lock
                        if (AppState.isServerRunning.value) {
                            Log.d(tag, "Screen On: Monitor loop was already kept alive by the server link. Skipping redundant registration.")
                        } else {
                            Log.i(tag, "Screen On: Server link was down. Re-attaching fresh monitor loops cleanly.")
                            networkMonitor.startMonitoring(Dispatchers.Main.asExecutor(), isNewSession = false)
                            startUpdateLoop()
                        }
                    }
                    // 2. Handle REFRESH Mode separately
                    else {
                        if ((currentTime - lastPokeTime > REFRESH_COOLDOWN_MS)) {
                            if (pendingPokeJob?.isCompleted ?: true) {
                                pendingPokeJob = serviceScope.launch {
                                    delay(2000)
                                    if (!isDeviceUnlocked) return@launch
                                    if (isCellularDataActive) {
                                        networkMonitor.pokeHardwareOnly()
                                        lastPokeTime = System.currentTimeMillis()
                                        AppState.recordPoke(applicationContext,java.text.SimpleDateFormat(
                                            "dd/MM/yyyy HH:mm:ss",
                                            java.util.Locale.getDefault()
                                        ).format(java.util.Date()))
                                        Log.d(tag, "MODEM poked @ $lastPokeTime")
                                    }
                                    else Log.d(tag, "cellular data disabled @ $lastPokeTime")
                                }
                            }
                        } else {
                            // SILENCE: Cooldown is active, do absolutely nothing.
                            Log.d(tag, "Refresh: Cooldown active. Next poke in ${ (REFRESH_COOLDOWN_MS - (currentTime - lastPokeTime)) / 60_000 }m")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        Log.d("gBars_RaceCheck", "[Main Thread] onCreate entry - Current activeMode in RAM: ${AppState.activeMode.value}")
        super.onCreate()
        // Register this instance as the single source of truth
        AppState.activeServiceHash = this.hashCode()
        Log.i(tag, "Service Instance Created: ${this.hashCode()}. Claimed active status.")
        // FIX: Promote to Foreground immediately in onCreate to avoid
        // background-start restrictions if the screen locks during transition.
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 1. KILL any zombie network listener from a previous instance
        AppState.persistentNetworkCallback?.let { oldCallback ->
            try { connectivityManager.unregisterNetworkCallback(oldCallback) } catch(e: Exception) {}
        }
        val notification = buildNotification(lastKnownMode)
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            AppState.persistentNetworkCallback = networkCallback // Store in bridge
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, notification)
            }
            Log.d(tag, "FGS secured in onCreate")
        } catch (e: Exception) {
            Log.e(tag, "FGS immediate promotion failed: ${e.message}")
        }
        AppState.isServiceRunning.value = true
        networkMonitor = NetworkMonitor(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (!isReceiverRegistered) {
            registerReceiver(lockStateReceiver, filter)
            isReceiverRegistered = true
        }
        if (AppState.activeMode.value == AppMode.MONITORING) {
            // 1. Core update flow
            startUpdateLoop()
            manageHeartbeat(true)
        }
    }

    private fun startUpdateLoop() {
        updateJob?.cancel() // Ensure no duplicate loops
        updateJob = serviceScope.launch {
            networkMonitor.networkMode.collectLatest { mode ->
                lastKnownMode = mode
                updateNotification(mode)
                if (mode == "Disconnected") {
                    Log.w(tag, "Network Lost. Starting 3s termination timer...")
                    delay(3000)

                    // FIX: Only stop if this is still the active session and user hasn't toggled it off
                    if (lastKnownMode == "Disconnected" && AppState.isServiceRunning.value) {
                        Log.e(tag, "Network not recovered. Self-terminating session $lastStartId.")
                        stopSelf(lastStartId)
                    }
                }
            }
        }
    }

    private fun stopUpdateLoop() {
        updateJob?.cancel()
        updateJob = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("gBars_RaceCheck", "[Main Thread] onStartCommand entry - Current activeMode in RAM: ${AppState.activeMode.value}")
        lastStartId = startId

        if (AppState.activeMode.value == AppMode.REFRESH) {
            manageHeartbeat(false) // KILL the tick
            networkMonitor.stopMonitoring()
            lastPokeTime = System.currentTimeMillis()
            Log.d(tag, "Refresh Mode: Defensive stopMonitoring() called to prevent listener leak.")
        } else if (AppState.activeMode.value == AppMode.MONITORING) {
            // Register persistent listeners for active tracking
            networkMonitor.startMonitoring(Dispatchers.Main.asExecutor(), isNewSession = true)
            manageHeartbeat(true) // START the tick
        }

        return START_STICKY
    }

    private fun updateNotification(mode: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, buildNotification(mode))
    }

    private fun buildNotification(mode: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Tells the OS to bring the existing app to the front instead of creating a new one
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isRefreshMode = AppState.activeMode.value == AppMode.REFRESH
        return NotificationCompat.Builder(this, channelId)
            .setContentText(if (isRefreshMode) "REFRESH - v1.4.7.2 \\ STABLE" else "MONITOR - v1.4.7.2 \\ STABLE")
            .setSmallIcon(getIconForMode(mode))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            // FIX: Force the notification to appear immediately on Android 12+
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun getIconForMode(mode: String): Int {
        return when {
            mode.contains("5G SA") -> R.drawable.ic_5g_sa
            mode.contains("5G NSA (Active)") -> R.drawable.ic_5g_active
            mode.contains("5G Idle") -> R.drawable.ic_4g
            mode.contains("4G") -> R.drawable.ic_4g
            mode.contains("3G") -> R.drawable.ic_3g
            mode.contains("2G") -> R.drawable.ic_2g
            else -> android.R.drawable.ic_menu_compass
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "gBars Network Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 1. FORCE removal of the notification tray entry immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // FIX: Wrap in try-catch to prevent a crash if the Android OS
        // already forcefully detached the receiver during a swipe-away kill.
        try {
            // 1. Stop the loop so the CPU stops 'ticking'
            stopUpdateLoop()

            // 2. Kill the 'Screen Unlock' task if it's currently waiting
            pendingPokeJob?.cancel()

            // 3. Clear the bridge specifically
            AppState.persistentNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                AppState.persistentNetworkCallback = null
            }

            // 3. UNREGISTER the receiver to prevent 'Ghost' duplicates
            if (isReceiverRegistered) {
                unregisterReceiver(lockStateReceiver)
                //unregisterNetworkCallback(networkCallback)
                isReceiverRegistered = false
            }
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Receiver already unregistered by system: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering receiver: ${e.message}")
        }

        networkMonitor.stopMonitoring()
        serviceJob.cancelChildren()
        serviceJob.cancel()
        AppState.isServiceRunning.value = false
        AppState.currentNetworkMode.value = "Disconnected"
        super.onDestroy()
    }
}