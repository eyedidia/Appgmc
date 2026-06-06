package com.gmc.digitalkey.ui.research

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gmc.digitalkey.R
import com.gmc.digitalkey.databinding.FragmentNetworkInspectorBinding
import com.gmc.digitalkey.network.LocalVpnService
import com.gmc.digitalkey.network.NetworkCaptureServer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NetworkInspectorFragment : Fragment() {

    private var _binding: FragmentNetworkInspectorBinding? = null
    private val binding get() = _binding!!

    private var gmOnly = false

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkInspectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatus()

        binding.btnStartStop.setOnClickListener {
            if (LocalVpnService.isRunning) stopVpn() else requestVpn()
        }

        binding.chipGmOnly.setOnCheckedChangeListener { _, checked ->
            gmOnly = checked
            renderEntries(LocalVpnService.captureLog.entries.value)
        }

        binding.btnClear.setOnClickListener {
            LocalVpnService.captureLog.clearLog()
        }

        binding.logText.setOnLongClickListener {
            val text = binding.logText.text.toString()
            if (text.isNotBlank()) {
                val clip = ClipData.newPlainText("vpn_log", text)
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.network_inspector_copied), Toast.LENGTH_SHORT).show()
            }
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            LocalVpnService.captureLog.entries.collectLatest { renderEntries(it) }
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) vpnLauncher.launch(intent)
        else startVpn()
    }

    private fun startVpn() {
        requireContext().startService(
            Intent(requireContext(), LocalVpnService::class.java).setAction(LocalVpnService.ACTION_START)
        )
        updateStatus()
    }

    private fun stopVpn() {
        requireContext().startService(
            Intent(requireContext(), LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP)
        )
        updateStatus()
    }

    private fun updateStatus() {
        val running = LocalVpnService.isRunning
        binding.btnStartStop.text = getString(
            if (running) R.string.network_inspector_stop else R.string.network_inspector_start
        )
        binding.vpnStatusText.text = getString(
            if (running) R.string.network_inspector_vpn_active else R.string.network_inspector_vpn_idle
        )
        binding.vpnStatusText.setTextColor(
            ContextCompat.getColor(requireContext(), if (running) R.color.status_connected else R.color.text_secondary)
        )
    }

    private fun renderEntries(entries: List<NetworkCaptureServer.LogEntry>) {
        val filtered = if (gmOnly) entries.filter { it.isGm } else entries
        val gmColor  = ContextCompat.getColor(requireContext(), R.color.gmc_red)

        binding.entryCountText.text = if (filtered.isEmpty())
            getString(R.string.network_inspector_no_entries)
        else
            getString(R.string.network_inspector_entry_count, filtered.size)

        val sb = SpannableStringBuilder()
        filtered.forEach { entry ->
            val line = entry.display + "\n"
            if (entry.isGm) {
                val start = sb.length; sb.append(line)
                sb.setSpan(ForegroundColorSpan(gmColor), start, sb.length, 0)
            } else {
                sb.append(line)
            }
        }
        binding.logText.text = sb
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
