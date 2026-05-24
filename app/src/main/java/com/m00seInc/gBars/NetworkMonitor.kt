package com.m00seInc.gBars

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.content.Context
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NetworkLog(@SerializedName("dateTime") val dateTime: String,
                      @SerializedName("fromMode") val fromMode: String,
                      @SerializedName("toMode") val toMode: String)

class NetworkMonitor(private val context: Context) {

    private val tag = "gBars_NetworkMonitor"
    private val prefs = context.getSharedPreferences("gbars_storage", Context.MODE_PRIVATE)

    private val _networkMode = MutableStateFlow("Initializing...")
    val networkMode: StateFlow<String> = _networkMode

    private val _networkLogs = MutableStateFlow<List<NetworkLog>>(emptyList())
    val networkLogs: StateFlow<List<NetworkLog>> = _networkLogs

    private var previousMode: String? = null
    private var currentDisplayInfo: TelephonyDisplayInfo? = null
    private var currentServiceState: ServiceState? = null
    private var isInitialRowPending = false

    // FIX: A lock to ensure disk writes happen one at a time chronologically
    private val saveMutex = Mutex()

    init {
        // FIX: If the OS killed the process and the Service wakes up before the
        // Activity, force it to load the logs right now before we take the snapshot.
        if (AppState.currentLogs.isEmpty()) {
            AppState.loadLogsFromPrefs(context)
        }
        _networkLogs.value = AppState.currentLogs.toList()
    }

    private val telephonyCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        @RequiresApi(Build.VERSION_CODES.S)
        object : TelephonyCallback(),
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener
            {
            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                updateNetworkType(displayInfo, currentServiceState)
            }
            override fun onServiceStateChanged(serviceState: ServiceState) {
                updateNetworkType(currentDisplayInfo, serviceState)
            }
        }
    } else null

    private fun updateNetworkType(displayInfo: TelephonyDisplayInfo?, serviceState: ServiceState?) {
        if (displayInfo != null) currentDisplayInfo = displayInfo
        if (serviceState != null) currentServiceState = serviceState

        val dInfo = currentDisplayInfo ?: return
        val is5GActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkActive5G(dInfo, currentServiceState)
        } else false

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            determineModeApi30(dInfo, is5GActive)
        } else {
            "LTE/Legacy (API < 30)"
        }

        if (_networkMode.value != result) {
            handleLogUpdate(result)
            previousMode = result
            _networkMode.value = result
            AppState.currentNetworkMode.value = result
        }
    }
    /**
     * SILENT POKE: Hits the hardware binder without triggering
     * any UI updates, StateFlow emissions, or log writes.
     */
    @SuppressLint("MissingPermission")
    fun pokeHardwareOnly() {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // 1. The Universal Nudge (API 1+)
            // Accessing the serviceState property triggers the Binder IPC
            // call that refreshes the modem's internal state machine.
            val state = telephonyManager.serviceState

            // 2. The Data State Nudge (API 1+)
            // This is another lightweight check that doesn't trigger
            // any complex UI logic but hits the telephony stack hard enough.
            val dataState = telephonyManager.dataState

            Log.d(tag, "Modem Nudge: Universal Binder hit successful.$state.$dataState")
        } catch (e: SecurityException) {
            // We catch this to prevent crashes during restricted background states
            Log.w(tag, "Modem Nudge: OS blocked access (SecurityException).")
        } catch (e: Exception) {
            Log.e(tag, "Modem Nudge: Failed: ${e.message}")
        }
    }

    private fun handleLogUpdate(newMode: String) {
        val currentList = _networkLogs.value.toMutableList()
        if (isInitialRowPending && currentList.isNotEmpty()) {
            val updatedLog = currentList[0].copy(toMode = newMode)
            currentList[0] = updatedLog
            isInitialRowPending = false
            syncAndSave(currentList)
        } else if (_networkMode.value != "Initializing..." && _networkMode.value != "Disconnected") {
            addLog(_networkMode.value, newMode)
        }
    }

    fun addLog(from: String, to: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(Date())
        val newLog = NetworkLog(timeStr, from, to)
        val currentList = _networkLogs.value.toMutableList()
        currentList.add(0, newLog)
        if (currentList.size > 10) currentList.removeAt(currentList.size - 1)
        syncAndSave(currentList)
    }

    /**
     * FIX: Threading Bottleneck + Gson Instantiation + Synchronous Disk Write
     * Updates UI state immediately on Main thread, offloads I/O to background.
     */
    /**
     * FIX: Threading Bottleneck + Gson Instantiation + Synchronous Disk Write + Mutex Lock
     * Updates UI state immediately on Main thread, offloads I/O to background chronologically.
     */
    private fun syncAndSave(newList: List<NetworkLog>) {
        // 1. Immediate UI update (Main Thread)
        _networkLogs.value = newList
        AppState.currentLogs.clear()
        AppState.currentLogs.addAll(newList)

        // 2. Offload heavy serialization and storage to IO Thread sequentially
        CoroutineScope(Dispatchers.IO).launch {
            // FIX: The Mutex ensures parallel coroutines wait their turn
            saveMutex.withLock {
                val serialized = AppState.gson.toJson(newList)
                prefs.edit().putString("saved_logs", serialized).commit()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun determineModeApi30(dInfo: TelephonyDisplayInfo, is5GActive: Boolean): String {
        if (currentServiceState?.state != ServiceState.STATE_IN_SERVICE) return "Disconnected"
        return when (dInfo.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            TelephonyManager.NETWORK_TYPE_LTE -> {
                when (dInfo.overrideNetworkType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> if (is5GActive) "5G NSA (Active)" else "4G (5G Idle)"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G (CA)"
                    else -> "4G"
                }
            }
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "Other"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkActive5G(dInfo: TelephonyDisplayInfo, serviceState: ServiceState?): Boolean {
        if (serviceState == null) return false
        val isNsaIconShowing = dInfo.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                dInfo.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
        return isNsaIconShowing && serviceState.cellBandwidths.any { it > 20000 }
    }

    fun startMonitoring(executor: Executor, isNewSession: Boolean = false) {
        Log.w(TAG, "start Monitoring")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

                // 1. REACH ACROSS THE BRIDGE: If a listener from an OLD service instance
                // is still alive in the System Server, kill it now.
                (AppState.persistentTelephonyCallback as? TelephonyCallback)?.let { oldCallback ->
                    try {
                        telephonyManager.unregisterTelephonyCallback(oldCallback)
                        Log.d("gBars_Monitor", "Identity Bridge: Zombie listener cleared.")
                    } catch (e: Exception) { /* Ignore unregister errors */ }
                }

                telephonyManager.registerTelephonyCallback(executor, telephonyCallback)
                AppState.persistentTelephonyCallback = telephonyCallback

                checkInitialState()
                if (isNewSession) createInitialPendingRow()
            } catch (e: Exception) {
                Log.e(tag, "MONITOR -> Failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    public fun checkInitialState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val serviceState = telephonyManager.serviceState
            updateNetworkType(null, serviceState)
        }
    }

    private fun createInitialPendingRow() {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = sdf.format(Date())

        // FIX: Capture the current mode immediately instead of using an empty placeholder.
        // Since checkInitialState() is called first, _networkMode.value is already live.
        val currentMode = _networkMode.value
        val hasValidMode = currentMode != "Initializing..." && currentMode != "Disconnected"

        val initialLog = NetworkLog(
            dateTime = currentTime,
            fromMode = "",
            toMode = if (hasValidMode) currentMode else ""
        )

        val currentList = _networkLogs.value.toMutableList()
        currentList.add(0, initialLog)
        if (currentList.size > 10) currentList.removeAt(currentList.size - 1)

        // Only remain "pending" if we are still waiting for a real network connection
        isInitialRowPending = !hasValidMode

        syncAndSave(currentList)
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d("gBars_Monitor", "TelephonyCallback unregistered and nullified.")
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // FIX 5: Secondary Safeguard — Unregister the local callback reference directly
            telephonyCallback?.let { localCallback ->
                try {
                    telephonyManager.unregisterTelephonyCallback(localCallback)
                    Log.d("gBars_Monitor", "Local Reference: Hardware listener detached successfully.")
                } catch (_: Exception) {}
            }

            // 3. ALWAYS unregister the identity stored in the bridge,
            // not just the local one.
            (AppState.persistentTelephonyCallback as? TelephonyCallback)?.let { activeCallback ->
                try {
                    telephonyManager.unregisterTelephonyCallback(activeCallback)
                    AppState.persistentTelephonyCallback = null // Clear the bridge
                    Log.d("gBars_Monitor", "Identity Bridge: Modem detached successfully.")
                } catch (e: Exception) {
                    Log.e("gBars_Monitor", "Identity Bridge: Detach failed: ${e.message}")
                }
            }?: Log.d("gBars_Monitor", "Identity Bridge: Nothing to unregister.")
        }
    }
}