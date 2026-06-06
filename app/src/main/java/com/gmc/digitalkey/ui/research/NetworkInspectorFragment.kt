package com.gmc.digitalkey.ui.research

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gmc.digitalkey.R
import com.gmc.digitalkey.databinding.FragmentNetworkInspectorBinding
import com.gmc.digitalkey.network.NetworkCaptureServer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NetworkInspectorFragment : Fragment() {

    private var _binding: FragmentNetworkInspectorBinding? = null
    private val binding get() = _binding!!

    private val server = NetworkCaptureServer()
    private var running = false
    private var gmOnly = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkInspectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateProxyAddress()

        binding.btnStartStop.setOnClickListener {
            if (running) stopServer() else startServer()
        }

        binding.chipGmOnly.setOnCheckedChangeListener { _, checked ->
            gmOnly = checked
            // Re-render current entries with filter
            renderEntries(server.entries.value)
        }

        binding.btnClear.setOnClickListener {
            server.clearLog()
        }

        // Long-press to copy entire log to clipboard
        binding.logText.setOnLongClickListener {
            val text = binding.logText.text.toString()
            if (text.isNotBlank()) {
                val clip = ClipData.newPlainText("network_log", text)
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.network_inspector_copied), Toast.LENGTH_SHORT).show()
            }
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            server.entries.collectLatest { entries ->
                renderEntries(entries)
            }
        }
    }

    private fun startServer() {
        server.start()
        running = true
        binding.btnStartStop.text = getString(R.string.network_inspector_stop)
        Toast.makeText(requireContext(), getString(R.string.network_inspector_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        server.stop()
        running = false
        binding.btnStartStop.text = getString(R.string.network_inspector_start)
    }

    private fun renderEntries(entries: List<NetworkCaptureServer.LogEntry>) {
        val filtered = if (gmOnly) entries.filter { it.isGm } else entries
        val gmColor = ContextCompat.getColor(requireContext(), R.color.gmc_red)

        binding.entryCountText.text = if (filtered.isEmpty()) {
            getString(R.string.network_inspector_no_entries)
        } else {
            getString(R.string.network_inspector_entry_count, filtered.size)
        }

        val sb = SpannableStringBuilder()
        filtered.forEach { entry ->
            val line = entry.display + "\n"
            if (entry.isGm) {
                val start = sb.length
                sb.append(line)
                sb.setSpan(ForegroundColorSpan(gmColor), start, sb.length, 0)
            } else {
                sb.append(line)
            }
        }
        binding.logText.text = sb

        // Auto-scroll to bottom
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateProxyAddress() {
        val ip = getWifiIpAddress()
        binding.proxyAddressText.text = if (ip != null) {
            "$ip:${NetworkCaptureServer.PORT}"
        } else {
            "Wi-Fi not connected"
        }
    }

    private fun getWifiIpAddress(): String? {
        return try {
            val wm = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            "%d.%d.%d.%d".format(
                ip and 0xff,
                (ip shr 8) and 0xff,
                (ip shr 16) and 0xff,
                (ip shr 24) and 0xff
            )
        } catch (_: Exception) { null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (running) server.stop()
        _binding = null
    }
}
