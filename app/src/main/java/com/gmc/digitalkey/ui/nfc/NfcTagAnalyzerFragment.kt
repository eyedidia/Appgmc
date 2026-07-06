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
import com.gmc.digitalkey.databinding.FragmentNfcAnalyzerBinding

class NfcTagAnalyzerFragment : Fragment() {

    private var _binding: FragmentNfcAnalyzerBinding? = null
    private val binding get() = _binding!!

    private var nfcAdapter: NfcAdapter? = null
    private var lastDump: String = ""

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

        // UID
        val uid = tag.id ?: ByteArray(0)
        sb.appendLine("UID : ${uid.hex()}  (${uid.size * 8}-bit)")
        sb.appendLine("Tech: ${tag.techList.joinToString(", ") { it.substringAfterLast('.') }}")
        sb.appendLine()

        // ── NFC-A (ISO 14443-3A) ──────────────────────────────────────────────
        NfcA.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-A (ISO 14443-3A)")
            sb.appendLine("│  ATQA : ${tech.atqa.hex()}")
            sb.appendLine("│  SAK  : 0x${tech.sak.toString(16).padStart(2, '0').uppercase()}")
            sb.appendLine("│  MaxTx: ${tech.maxTransceiveLength} bytes")
            sb.appendLine()
        }

        // ── NFC-B (ISO 14443-3B) ──────────────────────────────────────────────
        NfcB.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-B (ISO 14443-3B)")
            sb.appendLine("│  ATQB         : ${tech.applicationData.hex()}")
            sb.appendLine("│  Protocol Info: ${tech.protocolInfo.hex()}")
            sb.appendLine("│  MaxTx        : ${tech.maxTransceiveLength} bytes")
            sb.appendLine()
        }

        // ── ISO-DEP (ISO 14443-4 — smart cards, key cards) ───────────────────
        IsoDep.get(tag)?.let { tech ->
            sb.appendLine("┌─ ISO-DEP (ISO 14443-4) ← smart card / key card")
            tech.historicalBytes?.let { sb.appendLine("│  Historical bytes (ATS): ${it.hex()}  \"${it.ascii()}\"") }
            tech.hiLayerResponse?.let  { sb.appendLine("│  HiLayer response       : ${it.hex()}") }
            sb.appendLine("│  ExtendedAPDU: ${tech.isExtendedLengthApduSupported}")
            sb.appendLine()

            // Try talking to the card
            runCatching {
                tech.connect()
                tech.timeout = 3000

                // SELECT GM Digital Key AID: F04756494E47454E4B4579
                val gmAid = byteArrayOf(
                    0xF0.toByte(), 0x47, 0x56, 0x49, 0x4E, 0x47, 0x45, 0x4E, 0x4B, 0x45, 0x79
                )
                val selectGm = apduSelect(gmAid)
                val rGm = tech.transceive(selectGm)
                sb.appendLine("│  SELECT GM AID (F0GVINGENKey):")
                sb.appendLine("│    → ${selectGm.hex()}")
                sb.appendLine("│    ← ${rGm.hex()}  ${swDescription(rGm)}")
                sb.appendLine()

                // SELECT PPSE (payment / Google Pay)
                val ppse = "2PAY.SYS.DDF01".toByteArray()
                val rPpse = tech.transceive(apduSelect(ppse))
                sb.appendLine("│  SELECT PPSE (payment):")
                sb.appendLine("│    ← ${rPpse.hex()}  ${swDescription(rPpse)}")
                sb.appendLine()

                // SELECT NFC Forum Type 4 NDEF AID
                val ndefAid = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
                val rNdef = tech.transceive(apduSelect(ndefAid))
                sb.appendLine("│  SELECT NDEF AID:")
                sb.appendLine("│    ← ${rNdef.hex()}  ${swDescription(rNdef)}")

                tech.close()
            }.onFailure {
                sb.appendLine("│  APDU comm error: ${it.message}")
            }
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
        }

        // ── MIFARE Ultralight ─────────────────────────────────────────────────
        MifareUltralight.get(tag)?.let { tech ->
            sb.appendLine("┌─ MIFARE Ultralight")
            sb.appendLine("│  Type: ${mifareUltralightType(tech.type)}")
            sb.appendLine()
        }

        // ── NFC-F (FeliCa) ────────────────────────────────────────────────────
        NfcF.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-F (FeliCa / JIS 6319-4)")
            sb.appendLine("│  Manufacturer: ${tech.manufacturer.hex()}")
            sb.appendLine("│  System Code : ${tech.systemCode.hex()}")
            sb.appendLine()
        }

        // ── NFC-V (ISO 15693) ─────────────────────────────────────────────────
        NfcV.get(tag)?.use { tech ->
            sb.appendLine("┌─ NFC-V (ISO 15693 / vicinity)")
            sb.appendLine("│  DSF ID         : 0x${tech.dsfId.toUByte().toString(16).uppercase()}")
            sb.appendLine("│  Response Flags : 0x${tech.responseFlags.toUByte().toString(16).uppercase()}")
            sb.appendLine()
        }

        sb.appendLine("╚══ END DUMP ══╝")

        val dump = sb.toString()
        lastDump = dump

        requireActivity().runOnUiThread {
            binding.tvStatus.text = "Tag read OK  UID=${uid.hex()}"
            binding.tvStatus.setTextColor(requireContext().getColor(com.gmc.digitalkey.R.color.status_connected))
            binding.tvLog.text = dump
            binding.tvLog.setTextColor(requireContext().getColor(com.gmc.digitalkey.R.color.text_primary))
            binding.btnCopy.visibility = View.VISIBLE
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
        0 -> "Empty"
        1 -> "Well-known"
        2 -> "MIME type"
        3 -> "Absolute URI"
        4 -> "External"
        5 -> "Unknown"
        6 -> "Unchanged"
        else -> "Reserved"
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
}
