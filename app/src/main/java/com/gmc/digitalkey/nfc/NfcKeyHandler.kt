package com.gmc.digitalkey.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.gmc.digitalkey.ble.BleManager
import com.gmc.digitalkey.ui.nfc.NfcTagAnalyzerFragment
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
            runCatching {
                isoDep.connect()
                isoDep.timeout = 2000
                val selectAid = byteArrayOf(
                    0x00, 0xA4.toByte(), 0x04, 0x00, 0x0B,
                    0xF0.toByte(), 0x47, 0x56, 0x49, 0x4E, 0x47, 0x45, 0x4E, 0x4B, 0x45, 0x79
                )
                val response = isoDep.transceive(selectAid)
                if (response.size >= 2 && response[response.size - 2] == 0x90.toByte()) {
                    bleManager.sendUnlock()
                }
                isoDep.close()
            }
        } else {
            val uid = tag.id?.joinToString("") { "%02X".format(it) } ?: return
            if (isEnrolledUid(uid)) {
                bleManager.sendUnlock()
            } else {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Tag not enrolled — open NFC Tag Analyzer to enroll it",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun isEnrolledUid(uid: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val enrolled = prefs.getStringSet(NfcTagAnalyzerFragment.PREF_ENROLLED_TAGS, emptySet()) ?: emptySet()
        return enrolled.isEmpty() || uid in enrolled
    }
}
