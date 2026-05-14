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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isCellularDataActive = true
            Log.d("gBars_Network", "Cellular Data Available")
        }

        override fun onLost(network: Network) {
            isCellularDataActive = false
            Log.d("gBars_Network", "Cellular Data Lost/Disabled")
        }
    }

    private val lockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingPokeJob?.cancel()
            //val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pendingPokeJob?.cancel()
                    isDeviceUnlocked = false
                    if (AppState.activeMode.value == AppMode.MONITORING) {
                        networkMonitor.stopMonitoring()
                        stopUpdateLoop()
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    isDeviceUnlocked = true
                    val currentTime = System.currentTimeMillis()

                    // 1. Handle MONITORING Mode separately
                    if (AppState.activeMode.value == AppMode.MONITORING) {
                        networkMonitor.startMonitoring(Dispatchers.Main.asExecutor(), isNewSession = false)
                        startUpdateLoop()
                        Log.d(tag, "Phone Unlocked: Resuming monitoring.")
                    }
                    // 2. Handle REFRESH Mode separately
                    else {
                        if ((currentTime - lastPokeTime > REFRESH_COOLDOWN_MS)|| lastPokeTime == 0L) {
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
                                    }
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
        super.onCreate()
        // FIX: Promote to Foreground immediately in onCreate to avoid
        // background-start restrictions if the screen locks during transition.
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val notification = buildNotification(lastKnownMode)
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
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

            // 2. Refinement: Gated 5-Second Heartbeat
            serviceScope.launch {
                while (true) {
                    delay(5000)
                    // Only refresh if the user is present/unlocked
                    if (isDeviceUnlocked) {
                        networkMonitor.checkInitialState()
                        updateNotification(lastKnownMode)
                        Log.d(tag, "Notification Heartbeat: Refreshed.")
                    }
                }
            }
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
        lastStartId = startId

        if (AppState.activeMode.value == AppMode.REFRESH) {
            // You'll need to make telephonyCallback accessible or call a cleanup method
            lastPokeTime = 0L
            networkMonitor.stopMonitoring()
            networkMonitor.pokeHardwareOnly()
            Log.d(tag, "Refresh Mode: Defensive stopMonitoring() called to prevent listener leak.")
        } else if (AppState.activeMode.value == AppMode.MONITORING) {
            // Register persistent listeners for active tracking
            networkMonitor.startMonitoring(Dispatchers.Main.asExecutor(), isNewSession = true)
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
            .setContentText(if (isRefreshMode) "REFRESH - v1.4.4 \\ STABLE" else "MONITOR - v1.4.4 \\ STABLE")
            .setSmallIcon(getIconForMode(mode))
            .setOngoing(!isRefreshMode)
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

            // 2. CRITICAL CLEANUP: Unregister the listener to prevent Scenario 3 drain
            connectivityManager.unregisterNetworkCallback(networkCallback)

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