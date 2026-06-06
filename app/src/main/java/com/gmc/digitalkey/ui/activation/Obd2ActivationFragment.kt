package com.gmc.digitalkey.ui.activation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import com.gmc.digitalkey.ble.obd2.GmVcimActivation
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gmc.digitalkey.R
import com.gmc.digitalkey.databinding.FragmentObd2ActivationBinding
import com.gmc.digitalkey.model.GmcEvModel
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class Obd2ActivationFragment : Fragment() {

    private var _binding: FragmentObd2ActivationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: Obd2ActivationViewModel by viewModels()

    // Track last major state for export
    private var lastExportableState: Obd2ActivationState = Obd2ActivationState.Idle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObd2ActivationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("vehicleId")?.let { viewModel.loadVehicleVin(it) }
        arguments?.getString("modelKey")?.let { key ->
            viewModel.setVehicleModel(GmcEvModel.fromKey(key))
        }

        binding.btnStartScan.setOnClickListener { viewModel.startAdapterScan() }
        binding.btnConnectWifiObd.setOnClickListener {
            // Auto-detect: tries 192.168.0.10:35000 → 192.168.4.1:35000 → etc.
            viewModel.connectWifiAdapter()
        }
        binding.btnShowAllBle.setOnClickListener {
            binding.btnShowAllBle.visibility = View.GONE
            viewModel.showAllBleDevices()
        }
        binding.btnRunDiagnostic.setOnClickListener { viewModel.runDiagnosticDump() }
        binding.btnProceedVinMismatch.setOnClickListener {
            binding.btnProceedVinMismatch.visibility = View.GONE
            viewModel.proceedAfterVinMismatch()
        }
        binding.btnGoToPairing.setOnClickListener {
            findNavController().navigate(R.id.vehicleSelectFragment)
        }
        binding.btnCopyExport.setOnClickListener {
            copyToClipboard(viewModel.buildExportText(lastExportableState))
        }
        binding.btnTryDoip.setOnClickListener {
            findNavController().navigate(R.id.action_obd2_to_doip)
        }
        binding.btnAtSend.setOnClickListener {
            val cmd = binding.atInput.text?.toString() ?: return@setOnClickListener
            viewModel.sendAtCommand(cmd)
            binding.atInput.text?.clear()
        }
        binding.btnClearTerminal.setOnClickListener {
            viewModel.clearTerminalLog()
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { renderState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.foundAdapters.collect { renderAdapterList(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.terminalLog.collect { renderTerminalLog(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stepLog.collect { renderStepLog(it) }
        }
    }

    private fun renderState(state: Obd2ActivationState) {
        // Step indicators
        setStepActive(1, state is Obd2ActivationState.ScanningForAdapter || state is Obd2ActivationState.AdapterList)
        setStepActive(2, state is Obd2ActivationState.Connecting || state is Obd2ActivationState.InitializingAdapter)
        setStepActive(3, state is Obd2ActivationState.ReadingVin)
        setStepActive(4, state is Obd2ActivationState.DiscoveringEcus || state is Obd2ActivationState.ActivatingBle)
        setStepActive(5, state is Obd2ActivationState.ActivationSuccess)

        binding.progressBar.visibility = View.GONE
        binding.btnStartScan.isEnabled = true
        binding.btnRunDiagnostic.visibility = View.GONE
        binding.btnGoToPairing.visibility = View.GONE
        binding.btnCopyExport.visibility = View.GONE
        binding.btnShowAllBle.visibility = View.GONE
        // DoIP button always visible — lets user jump to Wi-Fi path without running BLE scan first
        binding.btnTryDoip.visibility = View.VISIBLE

        // Show AT terminal once we're past initialization
        val terminalVisible = state !is Obd2ActivationState.Idle &&
            state !is Obd2ActivationState.ScanningForAdapter &&
            state !is Obd2ActivationState.AdapterList &&
            state !is Obd2ActivationState.Connecting &&
            state !is Obd2ActivationState.InitializingAdapter &&
            state !is Obd2ActivationState.ActivationError
        binding.terminalCard.visibility = if (terminalVisible) View.VISIBLE else View.GONE

        when (state) {
            is Obd2ActivationState.Idle -> {
                binding.statusText.text =
                    "Plug the ELM327 OBD2 Bluetooth adapter into the vehicle's OBD2 port (under the dashboard, driver's side), then tap Scan."
            }
            is Obd2ActivationState.ScanningForAdapter -> {
                binding.statusText.text = "Scanning for OBD2 adapter via Bluetooth…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.AdapterList -> {
                binding.statusText.text = "${state.devices.size} adapter(s) found. Tap one to connect."
            }
            is Obd2ActivationState.Connecting -> {
                binding.statusText.text = "Connecting to OBD2 adapter…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.InitializingAdapter -> {
                binding.statusText.text = "Initializing adapter (ATZ / ATE0 / ATSP0…)"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.ReadingVin -> {
                binding.statusText.text = "Reading VIN from vehicle ECU…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.DiscoveringEcus -> {
                val proto = if (viewModel.obd2Manager.use29BitCan) "29-bit (ATSP7)" else "11-bit (ATSP0)"
                binding.statusText.text = "Scanning CAN bus for VCIM module [$proto]…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.VinMismatch -> {
                binding.statusText.text =
                    "VIN mismatch!\n\nFrom vehicle OBD: ${state.fromObd}\nStored in app:   ${state.storedVin}\n\nThis might be the wrong vehicle. Proceed anyway?"
                binding.btnProceedVinMismatch.visibility = View.VISIBLE
            }
            is Obd2ActivationState.ActivatingBle -> {
                binding.statusText.text = "Sending BLE enable command to VCIM (trying all candidate DIDs)…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.ActivationSuccess -> {
                val vehicleLine = state.vinInfo?.let { "${it.year} ${it.make} ${it.model}" }
                binding.statusText.text = buildString {
                    if (vehicleLine != null) appendLine("Vehicle: $vehicleLine\n")
                    append("BLE digital key activated on VCIM 0x${state.vcimAddress.toString(16).uppercase()}!\n\n")
                    append("You can now remove the OBD2 adapter and scan for your vehicle.")
                }
                binding.btnStartScan.visibility = View.GONE
                binding.btnGoToPairing.visibility = View.VISIBLE
                lastExportableState = state
            }
            is Obd2ActivationState.NeedsSecurityKey -> {
                val seedHex = state.seed.joinToString(" ") { "%02X".format(it) }
                binding.statusText.text =
                    "VCIM 0x${state.vcimAddress.toString(16).uppercase()} requires a security key.\n\n" +
                    "Seed received: $seedHex\n\n" +
                    "The GM VCIM seed-key algorithm is not yet implemented.\n" +
                    "Copy and share this seed — it helps derive the key constant.\n\n" +
                    "Meanwhile, tap 'Diagnostic Dump' to capture all readable DIDs."
                binding.btnRunDiagnostic.visibility = View.VISIBLE
                binding.btnCopyExport.text = "Copy Seed"
                binding.btnCopyExport.visibility = View.VISIBLE
                lastExportableState = state
            }
            is Obd2ActivationState.UnsupportedModel -> {
                binding.statusText.text =
                    "VCIM did not recognize any of the BLE enable DIDs.\nTap 'Diagnostic Dump' to discover which DIDs this module exposes.\n\nIf K73 isn't on the CAN bus, try DoIP via the vehicle Wi-Fi hotspot."
                binding.btnRunDiagnostic.visibility = View.VISIBLE
                binding.btnTryDoip.visibility = View.VISIBLE
            }
            is Obd2ActivationState.DiagnosticMode -> {
                val vcimStr = "0x${state.vcimAddress.toString(16).uppercase()}"
                val summary = if (state.didMap.isEmpty()) {
                    "No DIDs readable from VCIM $vcimStr.\n\n" +
                    "• Ensure vehicle ignition is ON\n" +
                    "• Try entering 'ATDP' in the terminal below to confirm protocol\n" +
                    "• Try 'ATMA' to monitor CAN traffic"
                } else {
                    "VCIM $vcimStr — ${state.didMap.size} DIDs readable:\n\n" +
                    state.didMap.entries.joinToString("\n") { (k, v) -> "$k = $v" }
                }
                binding.statusText.text = summary
                binding.btnRunDiagnostic.visibility = View.VISIBLE
                binding.btnCopyExport.text = "Copy Diagnostic"
                binding.btnCopyExport.visibility = View.VISIBLE
                lastExportableState = state
            }
            is Obd2ActivationState.ActivationError -> {
                binding.statusText.text = "Error: ${state.message}"
                binding.btnStartScan.isEnabled = state.recoverable
                if (state.message.startsWith("No OBD2 adapter found")) {
                    binding.btnShowAllBle.visibility = View.VISIBLE
                }
                if (state.message.contains("VCIM", ignoreCase = true) ||
                    state.message.contains("READY mode", ignoreCase = true)) {
                    binding.btnTryDoip.visibility = View.VISIBLE
                }
                if (viewModel.obd2Manager.commandLog.isNotEmpty()) {
                    binding.btnCopyExport.text = "Copy Diagnostic Log"
                    binding.btnCopyExport.visibility = View.VISIBLE
                    lastExportableState = state
                }
            }
            is Obd2ActivationState.AtTerminalResult -> { /* handled by terminal log */ }
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderAdapterList(adapters: List<BluetoothDevice>) {
        binding.adaptersContainer.removeAllViews()
        adapters.forEach { device ->
            val name = runCatching { device.name }.getOrNull() ?: device.address
            val isClassic = device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL

            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dpToPx() }
                setCardBackgroundColor(requireContext().getColor(R.color.bg_elevated))
                radius = 8.dpToPx().toFloat()
            }
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            inner.addView(TextView(requireContext()).apply {
                text = name
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            inner.addView(TextView(requireContext()).apply {
                text = if (isClassic) "${device.address}  •  Paired (Classic BT)" else device.address
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12f
            })
            card.addView(inner)
            card.setOnClickListener { viewModel.connectAndActivate(device) }
            binding.adaptersContainer.addView(card)
        }
    }

    private fun renderTerminalLog(log: List<Pair<String, String>>) {
        binding.terminalOutput.removeAllViews()
        if (log.isEmpty()) {
            binding.terminalCard.visibility = View.GONE
            return
        }
        binding.terminalCard.visibility = View.VISIBLE
        log.takeLast(20).forEach { (cmd, resp) ->
            binding.terminalOutput.addView(TextView(requireContext()).apply {
                text = "> $cmd"
                setTextColor(requireContext().getColor(R.color.gmc_red))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            binding.terminalOutput.addView(TextView(requireContext()).apply {
                text = resp.ifEmpty { "(no response)" }
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, 0, 8.dpToPx())
            })
        }
    }

    private fun renderStepLog(steps: List<GmVcimActivation.StepResult>) {
        if (steps.isEmpty()) {
            binding.stepLogCard.visibility = View.GONE
            return
        }
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
                setTextColor(requireContext().getColor(
                    if (step.ok) R.color.status_connected else R.color.status_error))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 6.dpToPx() }
            })
            row.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = step.name
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                })
                if (step.detail.isNotEmpty()) addView(TextView(requireContext()).apply {
                    text = step.detail
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            })
            binding.stepLogContainer.addView(row)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD2 Diagnostic", text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setStepActive(step: Int, active: Boolean) {
        val v = when (step) {
            1 -> binding.step1Indicator; 2 -> binding.step2Indicator
            3 -> binding.step3Indicator; 4 -> binding.step4Indicator
            5 -> binding.step5Indicator; else -> return
        }
        v.setTextColor(requireContext().getColor(if (active) R.color.gmc_red else R.color.text_hint))
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
