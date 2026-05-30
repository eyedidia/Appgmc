package com.gmc.digitalkey.ui.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gmc.digitalkey.R
import com.gmc.digitalkey.ble.obd2.GmVcimActivation
import com.gmc.digitalkey.databinding.FragmentDoipActivationBinding
import kotlinx.coroutines.launch

class DoIpActivationFragment : Fragment() {

    private var _binding: FragmentDoipActivationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DoIpActivationViewModel by viewModels()

    private var lastExportableState: DoIpActivationState = DoIpActivationState.Idle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDoipActivationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("vehicleId")?.let { viewModel.loadVehicleId(it) }

        binding.btnDiscover.setOnClickListener { viewModel.startDiscovery() }
        binding.btnScanNetwork.setOnClickListener { viewModel.scanNetwork() }
        binding.btnRunDiagnostic.setOnClickListener { viewModel.runDiagnosticDumpPublic() }
        binding.btnCopyExport.setOnClickListener { copyToClipboard(viewModel.buildExportText(lastExportableState)) }
        binding.btnGoToPairing.setOnClickListener { findNavController().navigate(R.id.vehicleSelectFragment) }
        binding.btnUdsSend.setOnClickListener {
            val addr = binding.addrInput.text?.toString() ?: return@setOnClickListener
            val pdu  = binding.pduInput.text?.toString()  ?: return@setOnClickListener
            viewModel.sendUdsCommand(addr, pdu)
            binding.pduInput.text?.clear()
        }
        binding.btnClearTerminal.setOnClickListener { viewModel.clearTerminalLog() }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { renderState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.terminalLog.collect { renderTerminalLog(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stepLog.collect { renderStepLog(it) }
        }
    }

    private fun renderState(state: DoIpActivationState) {
        setStepActive(1, state is DoIpActivationState.Scanning)
        setStepActive(2, state is DoIpActivationState.VehicleFound || state is DoIpActivationState.Connecting)
        setStepActive(3, state is DoIpActivationState.DiscoveringEcus)
        setStepActive(4, state is DoIpActivationState.ActivatingBle)
        setStepActive(5, state is DoIpActivationState.ActivationSuccess)

        binding.progressBar.visibility = View.GONE
        binding.btnDiscover.isEnabled = true
        binding.btnScanNetwork.isEnabled = true
        binding.btnRunDiagnostic.visibility = View.GONE
        binding.btnGoToPairing.visibility = View.GONE
        binding.btnCopyExport.visibility = View.GONE

        // Show terminal as soon as there's anything to log — including during scan and on error
        val terminalVisible = state !is DoIpActivationState.Idle
        binding.terminalCard.visibility = if (terminalVisible) View.VISIBLE else View.GONE

        when (state) {
            is DoIpActivationState.Idle -> {
                binding.statusText.text =
                    "1. Put vehicle in READY or ACC mode.\n" +
                    "2. Connect phone to vehicle's built-in Wi-Fi hotspot.\n" +
                    "3. Tap Discover Vehicle."
            }
            is DoIpActivationState.Scanning -> {
                binding.statusText.text = "Scanning Wi-Fi network… check the log below for live results."
                binding.progressBar.visibility = View.VISIBLE
                binding.btnDiscover.isEnabled = false
                binding.btnScanNetwork.isEnabled = false
            }
            is DoIpActivationState.VehicleFound -> {
                val vinLine = state.vin?.let { " (VIN: $it)" } ?: ""
                binding.statusText.text = "Vehicle found at ${state.ip}$vinLine\nEstablishing DoIP routing…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnDiscover.isEnabled = false
            }
            is DoIpActivationState.Connecting -> {
                binding.statusText.text = "Opening DoIP routing activation channel…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnDiscover.isEnabled = false
            }
            is DoIpActivationState.DiscoveringEcus -> {
                binding.statusText.text = "Probing VCIM logical addresses via DoIP…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnDiscover.isEnabled = false
            }
            is DoIpActivationState.ActivatingBle -> {
                binding.statusText.text = "Sending BLE enable command to VCIM…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnDiscover.isEnabled = false
            }
            is DoIpActivationState.ActivationSuccess -> {
                val vehicleLine = state.vinInfo?.let { "${it.year} ${it.make} ${it.model}" }
                binding.statusText.text = buildString {
                    if (vehicleLine != null) appendLine("Vehicle: $vehicleLine\n")
                    append("BLE digital key activated via DoIP on VCIM 0x%04X!\n\n".format(state.vcimAddress))
                    append("You can now disconnect from vehicle Wi-Fi and scan for your vehicle via BLE.")
                }
                binding.btnGoToPairing.visibility = View.VISIBLE
                lastExportableState = state
            }
            is DoIpActivationState.NeedsSecurityKey -> {
                val seedHex = state.seed.joinToString(" ") { "%02X".format(it) }
                binding.statusText.text =
                    "VCIM 0x%04X requires SecurityAccess.\n\n".format(state.vcimAddress) +
                    "Seed: $seedHex\n\n" +
                    "GM SecurityAccess key algorithm not yet implemented.\n" +
                    "Copy this seed — it helps derive the key constant.\n\n" +
                    "Tap 'Diagnostic Dump' to capture all readable DIDs."
                binding.btnRunDiagnostic.visibility = View.VISIBLE
                binding.btnCopyExport.text = "Copy Seed"
                binding.btnCopyExport.visibility = View.VISIBLE
                lastExportableState = state
            }
            is DoIpActivationState.DiagnosticMode -> {
                val summary = if (state.didMap.isEmpty()) {
                    "No DIDs readable from VCIM 0x%04X.\n\nVehicle must be in READY mode.".format(state.vcimAddress)
                } else {
                    "VCIM 0x%04X — %d DIDs:\n\n".format(state.vcimAddress, state.didMap.size) +
                    state.didMap.entries.joinToString("\n") { (k, v) -> "$k = $v" }
                }
                binding.statusText.text = summary
                binding.btnRunDiagnostic.visibility = View.VISIBLE
                binding.btnCopyExport.text = "Copy Diagnostic"
                binding.btnCopyExport.visibility = View.VISIBLE
                lastExportableState = state
            }
            is DoIpActivationState.ActivationError -> {
                binding.statusText.text = "Error: ${state.message}"
                binding.btnDiscover.isEnabled = state.recoverable
                if (viewModel.doIpManager.commandLog.isNotEmpty()) {
                    binding.btnCopyExport.text = "Copy Diagnostic Log"
                    binding.btnCopyExport.visibility = View.VISIBLE
                    lastExportableState = state
                }
            }
            is DoIpActivationState.NetworkScanResult -> {
                val summary = buildString {
                    appendLine("Network: ${state.subnetNote}\n")
                    if (state.openHosts.isEmpty()) {
                        appendLine("No open ports found on any host in this subnet.")
                        appendLine("\nThis usually means the vehicle's DoIP gateway is not")
                        appendLine("reachable from the external Wi-Fi hotspot interface.")
                        appendLine("\nOptions:")
                        appendLine("• Try connecting via OBD2 adapter instead")
                        appendLine("• Find the vehicle's Ethernet port and connect directly")
                        appendLine("• Enable Developer Options on the Infotainment screen")
                    } else {
                        appendLine("Found ${state.openHosts.size} host(s) with open ports:\n")
                        state.openHosts.toSortedMap().forEach { (ip, ports) ->
                            val portLabels = mapOf(13400 to "DoIP", 5555 to "ADB", 80 to "HTTP",
                                443 to "HTTPS", 8080 to "HTTP-alt", 22 to "SSH", 23 to "Telnet")
                            append("  $ip  →  ")
                            appendLine(ports.sorted().joinToString("  ") { p ->
                                "$p(${portLabels[p] ?: "?"})"
                            })
                        }
                        val hasDoip = state.openHosts.values.any { 13400 in it }
                        val hasAdb  = state.openHosts.values.any { 5555 in it }
                        if (hasDoip) appendLine("\nDoIP found! Tap 'Discover Vehicle' to connect.")
                        if (hasAdb)  appendLine("\nADB port 5555 open! Run: adb connect ${state.openHosts.entries.first { 5555 in it.value }.key}:5555")
                    }
                }
                binding.statusText.text = summary
                binding.btnCopyExport.text = "Copy Scan Results"
                binding.btnCopyExport.visibility = View.VISIBLE
                lastExportableState = state
            }
        }
    }

    private fun renderTerminalLog(log: List<Pair<String, String>>) {
        binding.terminalOutput.removeAllViews()
        if (log.isEmpty()) return
        log.takeLast(60).forEach { (cmd, resp) ->
            binding.terminalOutput.addView(TextView(requireContext()).apply {
                text = "> $cmd"
                setTextColor(requireContext().getColor(R.color.gmc_red))
                textSize = 12f
                typeface = Typeface.MONOSPACE
            })
            binding.terminalOutput.addView(TextView(requireContext()).apply {
                text = resp.ifEmpty { "(no response)" }
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, 8.dpToPx())
            })
        }
    }

    private fun renderStepLog(steps: List<GmVcimActivation.StepResult>) {
        if (steps.isEmpty()) { binding.stepLogCard.visibility = View.GONE; return }
        binding.stepLogCard.visibility = View.VISIBLE
        binding.stepLogContainer.removeAllViews()
        steps.forEach { step ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 4.dpToPx() }
            }
            row.addView(TextView(requireContext()).apply {
                text = if (step.ok) "✓" else "✗"
                setTextColor(requireContext().getColor(if (step.ok) R.color.status_connected else R.color.status_error))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 6.dpToPx() }
            })
            row.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = step.name
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                })
                if (step.detail.isNotEmpty()) addView(TextView(requireContext()).apply {
                    text = step.detail
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                })
            })
            binding.stepLogContainer.addView(row)
        }
    }

    private fun setStepActive(step: Int, active: Boolean) {
        val v = when (step) {
            1 -> binding.step1Indicator; 2 -> binding.step2Indicator
            3 -> binding.step3Indicator; 4 -> binding.step4Indicator
            5 -> binding.step5Indicator; else -> return
        }
        v.setTextColor(requireContext().getColor(if (active) R.color.gmc_red else R.color.text_hint))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DoIP Diagnostic", text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
