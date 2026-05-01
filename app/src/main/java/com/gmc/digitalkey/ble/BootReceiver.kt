package com.gmc.digitalkey.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gmc.digitalkey.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        CoroutineScope(Dispatchers.IO).launch {
            val hasPassiveUnlock = AppDatabase.get(context)
                .vehicleDao().getAll()
                .any { it.passiveUnlockEnabled }
            if (hasPassiveUnlock) {
                PassiveUnlockService.start(context)
            }
        }
    }
}
