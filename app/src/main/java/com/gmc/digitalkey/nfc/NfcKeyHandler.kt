package com.gmc.digitalkey.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.gmc.digitalkey.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NfcKeyHandler(
    private val activity: Activity,
    private val bleManager: BleManager
) {
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val scope = CoroutineScope(Dispatchers.IO)

    val isNfcSupported get() = nfcAdapter != null
    val isNfcEnabled get() = nfcAdapter?.isEnabled == true

    fun enableForegroundDispatch() {
        nfcAdapter?.enableForegroundDispatch(
            activity,
            android.app.PendingIntent.getActivity(
                activity, 0,
                Intent(activity, activity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_MUTABLE
            ),
            null,
            null
        )
    }

    fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(activity)
    }

    fun handleIntent(intent: Intent): Boolean {
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return false
        scope.launch { processTag(tag) }
        return true
    }

    private fun processTag(tag: Tag) {
        val isoDep = runCatching { IsoDep.get(tag) }.getOrNull()
        if (isoDep != null) {
            // ISO-DEP tag on car door handle — read vehicle AID and trigger BLE unlock
            runCatching {
                isoDep.connect()
                isoDep.timeout = 2000
                // SELECT AID command
                val selectAid = byteArrayOf(
                    0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                    0x0B.toByte(),
                    0xF0.toByte(), 0x47.toByte(), 0x56.toByte(), 0x49.toByte(),
                    0x4E.toByte(), 0x47.toByte(), 0x45.toByte(), 0x4E.toByte(),
                    0x4B.toByte(), 0x45.toByte(), 0x79.toByte()
                )
                val response = isoDep.transceive(selectAid)
                if (response.size >= 2 && response[response.size - 2] == 0x90.toByte()) {
                    // Car NFC reader responded — trigger BLE unlock
                    bleManager.sendUnlock()
                }
                isoDep.close()
            }
        } else {
            // Plain NFC tag — treat as unlock trigger if BLE is ready
            bleManager.sendUnlock()
        }
    }
}
