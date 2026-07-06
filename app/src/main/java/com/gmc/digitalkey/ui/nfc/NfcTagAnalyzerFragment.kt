package com.gmc.digitalkey.ui.nfc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.gmc.digitalkey.databinding.FragmentNfcAnalyzerBinding

class NfcTagAnalyzerFragment : Fragment() {

    private var _binding: FragmentNfcAnalyzerBinding? = null
    private val binding get() = _binding!!

    private var nfcAdapter: NfcAdapter? = null
    private var lastDump: String = ""
    private var lastUidHex: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNfcAnalyzerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        if (nfcAdapter == null) {
            binding.tvStatus.text = "NFC not supported on this device"
            binding.tvLog.text = "—"
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            binding.tvStatus.text = "NFC is OFF — enable it in Settings"
        }

        binding.btnCopy.setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("NFC Dump", lastDump))
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }

        binding.btnEnroll.setOnClickListener {
            if (lastUidHex.isEmpty()) return@setOnClickListener
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val enrolled = prefs.getStringSet(PREF_ENROLLED_TAGS, mutableSetOf())!!.toMutableSet()
            if (enrolled.add(lastUidHex)) {
                prefs.edit().putStringSet(PREF_ENROLLED_TAGS, enrolled).apply()
                Toast.makeText(requireContext(), "Tag enrolled — tap it to unlock via BLE", Toast.LENGTH_LONG).show()
                binding.btnEnroll.text = "Enrolled ✓"
                binding.btnEnroll.isEnabled = false
            } else {
                Toast.makeText(requireContext(), "Already enrolled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        adapter.enableReaderMode(
            requireActivity(),
            { tag -> analyzeTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        binding.tvStatus.text = "Ready — tap tag to phone now"
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(requireActivity())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Tag analysis ──────────────────────────────────────────────────────────

    private fun analyzeTag(tag: Tag) {
        val sb = StringBuilder()
        sb.appendLine("╔══ NFC TAG DUMP ══╗")
        sb.appendLine()

        val uid = tag.id ?: ByteArray(0)
        val uidHex = uid.joinToString("") { "%02X".format(it) }
        sb.appendLine("UID : ${uid.hex()}  (${uid.size * 8}-bit)")
        sb.appendLine("Tech: ${tag.techList.joinToString(", ") { it.substringAfterLast('.') }}")
        sb.appendLine()

        // ── NFC-A ─────────────────────────────────────────────────────────────
        NfcA.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-A (ISO 14443-3A)")
            sb.appendLine("│  ATQA : ${tech.atqa.hex()}")
            sb.appendLine("│  SAK  : 0x${tech.sak.toString(16).padStart(2, '0').uppercase()}")
            sb.appendLine("│  MaxTx: ${tech.maxTransceiveLength} bytes")
            sb.appendLine()
        }

        // ── NFC-B ─────────────────────────────────────────────────────────────
        NfcB.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-B (ISO 14443-3B)")
            sb.appendLine("│  ATQB         : ${tech.applicationData.hex()}")
            sb.appendLine("│  Protocol Info: ${tech.protocolInfo.hex()}")
            sb.appendLine("│  MaxTx        : ${tech.maxTransceiveLength} bytes")
            sb.appendLine()
        }

        // ── ISO-DEP ───────────────────────────────────────────────────────────
        IsoDep.get(tag)?.let { tech ->
            sb.appendLine("┌─ ISO-DEP (ISO 14443-4) ← smart card / key card")
            tech.historicalBytes?.let { sb.appendLine("│  Historical bytes (ATS): ${it.hex()}  \"${it.ascii()}\"") }
            tech.hiLayerResponse?.let  { sb.appendLine("│  HiLayer response       : ${it.hex()}") }
            sb.appendLine("│  ExtendedAPDU: ${tech.isExtendedLengthApduSupported}")
            sb.appendLine()

            runCatching {
                tech.connect()
                tech.timeout = 3000

                val gmAid = byteArrayOf(
                    0xF0.toByte(), 0x47, 0x56, 0x49, 0x4E, 0x47, 0x45, 0x4E, 0x4B, 0x45, 0x79
                )
                val rGm = tech.transceive(apduSelect(gmAid))
                sb.appendLine("│  SELECT GM AID:")
                sb.appendLine("│    ← ${rGm.hex()}  ${swDescription(rGm)}")

                val ppse = "2PAY.SYS.DDF01".toByteArray()
                val rPpse = tech.transceive(apduSelect(ppse))
                sb.appendLine("│  SELECT PPSE:")
                sb.appendLine("│    ← ${rPpse.hex()}  ${swDescription(rPpse)}")

                val ndefAid = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
                val rNdef = tech.transceive(apduSelect(ndefAid))
                sb.appendLine("│  SELECT NDEF AID:")
                sb.appendLine("│    ← ${rNdef.hex()}  ${swDescription(rNdef)}")

                tech.close()
            }.onFailure { sb.appendLine("│  APDU error: ${it.message}") }
            sb.appendLine()
        }

        // ── NDEF ──────────────────────────────────────────────────────────────
        Ndef.get(tag)?.use { tech ->
            sb.appendLine("┌─ NDEF")
            sb.appendLine("│  Type    : ${tech.type}")
            sb.appendLine("│  MaxSize : ${tech.maxSize} bytes")
            sb.appendLine("│  Writable: ${tech.isWritable}")
            runCatching {
                tech.connect()
                val msg = tech.ndefMessage
                if (msg == null || msg.records.isEmpty()) {
                    sb.appendLine("│  Records : (empty)")
                } else {
                    msg.records.forEachIndexed { i, rec ->
                        sb.appendLine("│  Record $i:")
                        sb.appendLine("│    TNF    : ${tnfName(rec.tnf.toInt())}")
                        sb.appendLine("│    Type   : ${rec.type.hex()}  \"${rec.type.ascii()}\"")
                        sb.appendLine("│    Payload: ${rec.payload.hex()}")
                        val txt = rec.payload.ascii()
                        if (txt.length > 2) sb.appendLine("│    ASCII  : $txt")
                    }
                }
            }.onFailure { sb.appendLine("│  Read error: ${it.message}") }
            sb.appendLine()
        }

        // ── MIFARE Classic ────────────────────────────────────────────────────
        MifareClassic.get(tag)?.let { tech ->
            sb.appendLine("┌─ MIFARE Classic")
            sb.appendLine("│  Type   : ${mifareClassicType(tech.type)}")
            sb.appendLine("│  Size   : ${tech.size} bytes")
            sb.appendLine("│  Sectors: ${tech.sectorCount}  Blocks: ${tech.blockCount}")
            sb.appendLine()

            runCatching {
                tech.connect()
                tech.timeout = 5000
                val probeSectors = minOf(tech.sectorCount, 4)
                for (sector in 0 until probeSectors) {
                    var authed = false
                    var usedKey: ByteArray? = null
                    var keyType = ""
                    for (key in COMMON_KEYS) {
                        if (tech.authenticateSectorWithKeyA(sector, key)) {
                            authed = true; usedKey = key; keyType = "A"; break
                        }
                        if (tech.authenticateSectorWithKeyB(sector, key)) {
                            authed = true; usedKey = key; keyType = "B"; break
                        }
                    }
                    if (authed && usedKey != null) {
                        sb.appendLine("│  Sector $sector  [Key$keyType: ${usedKey!!.hex()}]")
                        val first = tech.sectorToBlock(sector)
                        val count = tech.getBlockCountInSector(sector)
                        for (b in first until first + count) {
                            runCatching {
                                val data = tech.readBlock(b)
                                sb.appendLine("│    Block $b: ${data.hex()}")
                            }.onFailure { sb.appendLine("│    Block $b: read error") }
                        }
                    } else {
                        sb.appendLine("│  Sector $sector: ❌ auth failed (custom key)")
                    }
                }
                if (tech.sectorCount > probeSectors) {
                    sb.appendLine("│  (sectors $probeSectors–${tech.sectorCount - 1}: not probed)")
                }
                tech.close()
            }.onFailure { sb.appendLine("│  Read error: ${it.message}") }
            sb.appendLine()
        }

        // ── MIFARE Ultralight ─────────────────────────────────────────────────
        MifareUltralight.get(tag)?.let { tech ->
            sb.appendLine("┌─ MIFARE Ultralight")
            sb.appendLine("│  Type: ${mifareUltralightType(tech.type)}")
            sb.appendLine()
        }

        // ── NFC-F ─────────────────────────────────────────────────────────────
        NfcF.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-F (FeliCa / JIS 6319-4)")
            sb.appendLine("│  Manufacturer: ${tech.manufacturer.hex()}")
            sb.appendLine("│  System Code : ${tech.systemCode.hex()}")
            sb.appendLine()
        }

        // ── NFC-V ─────────────────────────────────────────────────────────────
        NfcV.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-V (ISO 15693 / vicinity)")
            sb.appendLine("│  DSF ID         : 0x${tech.dsfId.toUByte().toString(16).uppercase()}")
            sb.appendLine("│  Response Flags : 0x${tech.responseFlags.toUByte().toString(16).uppercase()}")
            sb.appendLine()
        }

        sb.appendLine("╚══ END DUMP ══╝")

        val dump = sb.toString()
        lastDump = dump
        lastUidHex = uidHex

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val enrolled = prefs.getStringSet(PREF_ENROLLED_TAGS, emptySet()) ?: emptySet()
        val alreadyEnrolled = uidHex in enrolled

        requireActivity().runOnUiThread {
            binding.tvStatus.text = "Tag read OK  UID=${uid.hex()}"
            binding.tvStatus.setTextColor(requireContext().getColor(com.gmc.digitalkey.R.color.status_connected))
            binding.tvLog.text = dump
            binding.tvLog.setTextColor(requireContext().getColor(com.gmc.digitalkey.R.color.text_primary))
            binding.btnCopy.visibility = View.VISIBLE
            binding.btnEnroll.visibility = View.VISIBLE
            if (alreadyEnrolled) {
                binding.btnEnroll.text = "Enrolled ✓"
                binding.btnEnroll.isEnabled = false
            } else {
                binding.btnEnroll.text = "Enroll"
                binding.btnEnroll.isEnabled = true
            }
            binding.scrollLog.post { binding.scrollLog.scrollTo(0, 0) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.ascii() = map { b ->
        val c = b.toInt() and 0xFF
        if (c in 32..126) c.toChar() else '.'
    }.joinToString("")

    private fun apduSelect(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid

    private fun swDescription(resp: ByteArray): String {
        if (resp.size < 2) return "(short)"
        val sw = ((resp[resp.size - 2].toInt() and 0xFF) shl 8) or (resp[resp.size - 1].toInt() and 0xFF)
        return when (sw) {
            0x9000 -> "✓ OK"
            0x6A82 -> "✗ File not found"
            0x6D00 -> "✗ Instruction not supported"
            0x6E00 -> "✗ Class not supported"
            0x6700 -> "✗ Wrong length"
            0x6900 -> "✗ Command not allowed"
            0x6300 -> "✗ No info given"
            else   -> "(SW=${"%04X".format(sw)})"
        }
    }

    private fun tnfName(tnf: Int) = when (tnf) {
        0 -> "Empty"; 1 -> "Well-known"; 2 -> "MIME type"
        3 -> "Absolute URI"; 4 -> "External"; 5 -> "Unknown"
        6 -> "Unchanged"; else -> "Reserved"
    }

    private fun mifareClassicType(t: Int) = when (t) {
        MifareClassic.TYPE_CLASSIC -> "Classic"
        MifareClassic.TYPE_PLUS    -> "Plus"
        MifareClassic.TYPE_PRO     -> "Pro"
        else                       -> "Unknown"
    }

    private fun mifareUltralightType(t: Int) = when (t) {
        MifareUltralight.TYPE_ULTRALIGHT   -> "Ultralight"
        MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"
        else                               -> "Unknown"
    }

    private inline fun <T : TagTechnology> T.use(block: (T) -> Unit) {
        runCatching { block(this) }
    }

    companion object {
        const val PREF_ENROLLED_TAGS = "enrolled_nfc_tags"

        private val COMMON_KEYS = arrayOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
            byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()),
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        )
    }
}
