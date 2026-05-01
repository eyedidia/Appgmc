package com.gmc.digitalkey.ble

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gmc.digitalkey.MainActivity
import com.gmc.digitalkey.R
import com.gmc.digitalkey.db.AppDatabase
import kotlinx.coroutines.*

@SuppressLint("MissingPermission")
class PassiveUnlockService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager
    private var wasInRange = false

    companion object {
        const val CHANNEL_ID = "gmc_passive_unlock"
        const val NOTIF_ID = 1001
        private const val RSSI_THRESHOLD_NEAR = -75
        private const val RSSI_THRESHOLD_FAR = -90

        fun start(context: Context) {
            val intent = Intent(context, PassiveUnlockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) =
            context.stopService(Intent(context, PassiveUnlockService::class.java))
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY causes Android to restart the service if killed by OS/battery optimiser
        scope.launch { runProximityLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        bleManager.disconnect()
        super.onDestroy()
    }

    private suspend fun runProximityLoop() {
        val db = AppDatabase.get(applicationContext)
        while (isActive) {
            val vehicles = db.vehicleDao().getAll().filter { it.passiveUnlockEnabled }
            if (vehicles.isEmpty()) { delay(10_000); continue }

            val vehicle = vehicles.first()
            val state = bleManager.connectionState.value

            if (!state.isConnected) {
                val device = runCatching {
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
                    adapter.getRemoteDevice(vehicle.bleAddress)
                }.getOrNull()
                device?.let { bleManager.connect(it, vehicle.id) }
                delay(5_000)
                continue
            }

            bleManager.pollRssi()
            delay(2_000)

            val readyState = bleManager.connectionState.value
            val rssi = if (readyState is BleConnectionState.Ready) readyState.rssi else Int.MIN_VALUE

            if (rssi > RSSI_THRESHOLD_NEAR && !wasInRange) {
                wasInRange = true
                bleManager.sendUnlock()
            } else if (rssi < RSSI_THRESHOLD_FAR && wasInRange) {
                wasInRange = false
                bleManager.sendLock()
            }

            delay(3_000)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Digital Key", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Passive proximity unlock active" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.passive_unlock_active))
            .setSmallIcon(R.drawable.ic_gmc_logo)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }
}
