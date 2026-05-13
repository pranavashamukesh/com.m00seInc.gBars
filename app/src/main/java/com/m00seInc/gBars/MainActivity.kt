package com.m00seInc.gBars

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.m00seInc.gBars.ui.theme.GBarsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Data model for paired logs
 */
data class PokeEntry(@SerializedName("time") val time: String)

/**
 * Define the available operation modes
 */
enum class AppMode { MONITORING, REFRESH }

/**
 * Global state holder
 */
object AppState {
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val diskMutex = Mutex() // Prevents simultaneous read/writes
    val gson = Gson()

    val activeMode = mutableStateOf(AppMode.MONITORING)
    val currentLogs = mutableStateListOf<NetworkLog>()
    val currentNetworkMode = mutableStateOf("Disconnected")
    val isServiceRunning = mutableStateOf(false)
    val isInitialized = mutableStateOf(false)
    val pokeHistory = mutableStateListOf<PokeEntry>()

    private var hasLoadedFromDisk = false

    fun checkPermissions(context: Context): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun saveAppState(context: Context) {
        val appContext = context.applicationContext
        if (!hasLoadedFromDisk) return

        // 1. SNAPSHOT the data immediately on the calling thread
        // This prevents ConcurrentModificationException if the list changes during save
        val historySnapshot = pokeHistory.toList()
        val logsSnapshot = currentLogs.toList()
        val modeSnapshot = activeMode.value.name

        persistenceScope.launch {
            // 2. Wait for any other disk operation to finish
            diskMutex.withLock {
                try {
                    val prefs =
                        appContext.getSharedPreferences("gbars_storage", Context.MODE_PRIVATE)
                    val historyJson = gson.toJson(historySnapshot)
                    val logsJson = gson.toJson(logsSnapshot)

                    prefs.edit().apply {
                        putString("poke_history_json", historyJson)
                        putString("active_mode", modeSnapshot)
                        putString("saved_logs", logsJson)
                        apply()
                    }
                } catch (e: Exception) {
                    Log.e("gBars_State", "Save Failed: ${e.message}")
                }
            }
        }
    }
    fun loadLogsFromPrefs(context: Context) {
        val appContext = context.applicationContext
        runBlocking {
            diskMutex.withLock {
                try {
                    val prefs = appContext.getSharedPreferences("gbars_storage", Context.MODE_PRIVATE)

                    // Load Mode
                    val savedMode = prefs.getString("active_mode", AppMode.MONITORING.name)
                    activeMode.value = try { AppMode.valueOf(savedMode!!) } catch(e: Exception) { AppMode.MONITORING }

                    // Load Poke History
                    val historyJson = prefs.getString("poke_history_json", null)
                    Log.d("gBars_Persistence", "Loading History JSON: $historyJson") // DEBUG LOG

                    historyJson?.let { json ->
                        val type = object : TypeToken<List<PokeEntry>>() {}.type
                        val list: List<PokeEntry>? = gson.fromJson(json, type)
                        pokeHistory.clear()
                        list?.let { pokeHistory.addAll(it) }
                    }

                    // Load Switch Logs
                    val logsJson = prefs.getString("saved_logs", null)
                    logsJson?.let { json ->
                        val type = object : TypeToken<List<NetworkLog>>() {}.type
                        val list: List<NetworkLog>? = gson.fromJson(json, type)
                        currentLogs.clear()
                        list?.let { currentLogs.addAll(it) }
                    }

                    // CRITICAL: Only set this to true AFTER everything is in RAM
                    hasLoadedFromDisk = true
                    Log.d("gBars_Persistence", "Load Complete. Guard Disarmed.")
                } catch (e: Exception) {
                    Log.e("gBars_Persistence", "Load Failed: ${e.message}")
                    // Even on failure, we set this to true so the user can start a fresh history
                    hasLoadedFromDisk = true
                }
            }
        }
    }

    fun recordPoke(context: Context, time: String) {
        if (pokeHistory.size >= 15) pokeHistory.removeAt(pokeHistory.size - 1)
        pokeHistory.add(0, PokeEntry(time))
        saveAppState(context)
    }
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (AppState.checkPermissions(this)) AppState.isInitialized.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppState.loadLogsFromPrefs(this)
        AppState.isInitialized.value = AppState.checkPermissions(this)

        setContent {
            GBarsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!AppState.isInitialized.value) {
                        InitializationPage {
                            val perms = mutableListOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            requestPermissionLauncher.launch(perms.toTypedArray())
                        }
                    } else {
                        MainDashboard()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AppState.checkPermissions(this)) AppState.isInitialized.value = true
    }

    fun toggleMonitoring() {
        // Double-check permissions at the exact moment of the button press
        if (AppState.checkPermissions(this)) {
            if (AppState.isServiceRunning.value) stopMonitoringService()
            else startMonitoringService()
        } else {
            // If permissions were revoked or haven't propagated, send user back to init
            AppState.isInitialized.value = false
        }
    }

    fun startMonitoringService() {
        val intent = Intent(this, NetworkMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun stopMonitoringService() {
        stopService(Intent(this, NetworkMonitorService::class.java))
    }
}

@Composable
fun ModeSelector() {
    val isRunning by AppState.isServiceRunning
    val mode by AppState.activeMode
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).alpha(if (isRunning) 0.5f else 1.0f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "REFRESH MODE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text = "Pokes modem on screen unlock", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
        }
        Switch(
            checked = mode == AppMode.REFRESH,
            onCheckedChange = { isRefresh ->
                if (!isRunning) {
                    AppState.activeMode.value = if (isRefresh) AppMode.REFRESH else AppMode.MONITORING
                    AppState.saveAppState(context)
                }
            },
            enabled = !isRunning
        )
    }
}

@Composable
fun MainDashboard() {
    val configuration = LocalConfiguration.current
    val currentMode by AppState.currentNetworkMode
    val isRunning by AppState.isServiceRunning
    val context = LocalContext.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // LANDSCAPE: Side-by-Side Layout
        Row(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left Side: Controls
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.Start
            ) {
                AppBranding()
                Spacer(modifier = Modifier.height(16.dp))
                ModeSelector()
                Spacer(modifier = Modifier.height(12.dp))
                MonitorButton(
                    isActive = isRunning,
                    networkMode = currentMode,
                    onToggle = { (context as? MainActivity)?.toggleMonitoring() },
                    modifier = Modifier.weight(1f) // Button fills vertical space
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Right Side: History
            Column(
                modifier = Modifier
                    .weight(1.8f) // Give more room to the table
                    .fillMaxHeight()
            ) {
                Text(
                    text = if (AppState.activeMode.value == AppMode.REFRESH) "POKE HISTORY" else "SWITCH HISTORY",
                    modifier = Modifier.padding(start = 12.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                LogTable(modifier = Modifier.weight(1f))
            }
        }
    } else {
        // PORTRAIT: Vertical Stack (Original)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                AppBranding()
            }
            Spacer(modifier = Modifier.height(24.dp))
            ModeSelector()
            Spacer(modifier = Modifier.height(16.dp))
            MonitorButton(
                isActive = isRunning,
                networkMode = currentMode,
                onToggle = { (context as? MainActivity)?.toggleMonitoring() },
                modifier = Modifier.height(120.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = if (AppState.activeMode.value == AppMode.REFRESH) "POKE HISTORY" else "SWITCH HISTORY",
                modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            LogTable(modifier = Modifier.fillMaxHeight().padding(bottom = 8.dp))
        }
    }
}

@Composable
fun AppBranding() {
    Column(modifier = Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.Start) {
        Text("GBARS", fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 5.sp)
        Text("V1.4.3 \\ STABLE", fontSize = 7.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun LogTable(modifier: Modifier = Modifier) {
    val mode by AppState.activeMode
    // OPTIMIZATION: Only recalculate the display list when the history actually changes.
    // This prevents the 'handleResized abandoned' buffer noise in your logs.
    val displayItems by remember(mode) {
        derivedStateOf {
            if (mode == AppMode.REFRESH) AppState.pokeHistory.toList()
            else AppState.currentLogs.toList()
        }
    }
    // MOVE headCol here so the entire function can see it
    val headCol = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Row
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                if (mode == AppMode.REFRESH) {
                    Text("Screen unlock", Modifier.weight(1f), fontSize = 9.sp, color = headCol)
                } else {
                    Text("TIME", Modifier.weight(0.25f), fontWeight = FontWeight.Bold, fontSize = 9.sp, color = headCol)
                    Text("FROM", Modifier.weight(0.375f), fontWeight = FontWeight.Bold, fontSize = 9.sp, color = headCol)
                    Text("TO", Modifier.weight(0.375f), fontWeight = FontWeight.Bold, fontSize = 9.sp, color = headCol)
                }
            }

            // Data Rows
            repeat(10) { index ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (mode == AppMode.REFRESH) {
                        val entry = displayItems.getOrNull(index) as? PokeEntry
                        Text(
                            text = entry?.time ?: "--:--:--",
                            modifier = Modifier.weight(0.8f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (entry != null) MaterialTheme.colorScheme.primary else headCol.copy(alpha = 0.3f)
                        )
                    } else {
                        val log = AppState.currentLogs.getOrNull(index)
                        Text(log?.dateTime ?: "--:--:--", Modifier.weight(0.25f), fontSize = 10.sp)
                        Text(log?.fromMode ?: "...", Modifier.weight(0.375f), fontSize = 11.sp)
                        Text(log?.toMode ?: "...", Modifier.weight(0.375f), fontSize = 11.sp)
                    }
                }
                if (index < 9) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
fun MonitorButton(isActive: Boolean, networkMode: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val mode by AppState.activeMode
    Button(
        onClick = onToggle, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isActive) {
                Text(if (mode == AppMode.REFRESH) "REFRESH" else "MONITOR", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(if (mode == AppMode.REFRESH) "REFRESHING" else "MONITORING", fontSize = 9.sp)
                Spacer(Modifier.height(4.dp))
                Text(if (mode == AppMode.REFRESH) "POKING MODEM" else networkMode, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("TAP TO STOP", modifier = Modifier.alpha(0.5f), fontSize = 8.sp)
            }
        }
    }
}

@Composable
fun InitializationPage(onGrantClick: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // LANDSCAPE: Side-by-Side Layout
        Row(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Side: Branding & Info
            Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
                Text(
                    text = "SYSTEM INITIALIZATION",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The application requires access to monitor low-level modem states and signal metrics.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    lineHeight = 14.sp
                )
            }

            // Right Side: Permissions & Button
            Column(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    val perms = listOf(
                        "PHONE STATE" to "Detect radio power states.",
                        "LOCATION" to "Cell tower access.",
                        "NOTIFICATIONS" to "Background watchdog service."
                    )

                    perms.forEach { (title, desc) ->
                        Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Column {
                                Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(desc, fontSize = 8.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }

                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("GRANT ACCESS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    } else {
        // PORTRAIT: Original Vertical Stack
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "SYSTEM INITIALIZATION",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The application requires the following access to monitor low-level modem states and signal metrics:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(32.dp))

            val perms = listOf(
                "PHONE STATE" to "Detect network type (5G/4G/3G) and radio power states.",
                "LOCATION" to "Required by Android OS for low-level cell tower access.",
                "NOTIFICATIONS" to "Maintains the background 'Modem Watchdog' service."
            )

            perms.forEach { (title, desc) ->
                Column(modifier = Modifier.padding(bottom = 20.dp)) {
                    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(desc, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onGrantClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text("GRANT ACCESS", fontWeight = FontWeight.Bold)
            }
        }
    }
}