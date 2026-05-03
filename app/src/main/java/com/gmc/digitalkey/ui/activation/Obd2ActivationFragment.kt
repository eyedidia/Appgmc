package com.gmc.digitalkey.ui.activation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gmc.digitalkey.R
import com.gmc.digitalkey.databinding.FragmentObd2ActivationBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class Obd2ActivationFragment : Fragment() {

    private var _binding: FragmentObd2ActivationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: Obd2ActivationViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObd2ActivationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("vehicleId")?.let { viewModel.loadVehicleVin(it) }

        binding.btnStartScan.setOnClickListener { viewModel.startAdapterScan() }
        binding.btnRunDiagnostic.setOnClickListener { viewModel.runDiagnosticDump() }
        binding.btnProceedVinMismatch.setOnClickListener {
            binding.btnProceedVinMismatch.visibility = View.GONE
            viewModel.proceedAfterVinMismatch()
        }
        binding.btnGoToPairing.setOnClickListener {
            findNavController().navigate(R.id.vehicleSelectFragment)
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
    }

    private fun renderState(state: Obd2ActivationState) {
        setStepActive(1, state is Obd2ActivationState.ScanningForAdapter || state is Obd2ActivationState.AdapterList)
        setStepActive(2, state is Obd2ActivationState.Connecting || state is Obd2ActivationState.InitializingAdapter)
        setStepActive(3, state is Obd2ActivationState.ReadingVin)
        setStepActive(4, state is Obd2ActivationState.ActivatingBle)
        setStepActive(5, state is Obd2ActivationState.ActivationSuccess)

        binding.progressBar.visibility = View.GONE
        binding.btnStartScan.isEnabled = true
        binding.btnRunDiagnostic.visibility = View.GONE
        binding.btnGoToPairing.visibility = View.GONE

        when (state) {
            is Obd2ActivationState.Idle -> {
                binding.statusText.text = "Plug your ELM327 OBD2 adapter into the vehicle's OBD2 port (under the dashboard), then tap Scan."
                binding.btnStartScan.visibility = View.VISIBLE
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
                binding.statusText.text = "Connecting to adapter…"
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
            is Obd2ActivationState.VinMismatch -> {
                binding.statusText.text =
                    "VIN mismatch!\n\nFrom vehicle OBD: ${state.fromObd}\nStored in app: ${state.storedVin}\n\nThis might be the wrong vehicle. Proceed anyway?"
                binding.btnProceedVinMismatch.visibility = View.VISIBLE
            }
            is Obd2ActivationState.ActivatingBle -> {
                binding.statusText.text = "Sending BLE activation command to VCIM module…"
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStartScan.isEnabled = false
            }
            is Obd2ActivationState.ActivationSuccess -> {
                binding.statusText.text =
                    "BLE digital key activated!\n\nYou can now remove the OBD2 adapter and scan for your vehicle."
                binding.btnStartScan.visibility = View.GONE
                binding.btnGoToPairing.visibility = View.VISIBLE
            }
            is Obd2ActivationState.NeedsSecurityKey -> {
                binding.statusText.text =
                    "The VCIM requires a security unlock key to write this configuration.\n\n" +
                    "The GM seed-key algorithm for this module is not yet implemented.\n\n" +
                    "Tap 'Diagnostic Dump' to read all available VCIM DIDs — compare the output " +
                    "between an activated and unactivated vehicle to identify the correct DID and value."
                binding.btnRunDiagnostic.visibility = View.VISIBLE
            }
            is Obd2ActivationState.UnsupportedModel -> {
                binding.statusText.text =
                    "The VCIM on this vehicle did not recognize the BLE enable DID.\n\n" +
                    "Tap 'Diagnostic Dump' to discover which DIDs this VCIM exposes."
                binding.btnRunDiagnostic.visibility = View.VISIBLE
            }
            is Obd2ActivationState.DiagnosticMode -> {
                val summary = if (state.didMap.isEmpty()) {
                    "No DIDs readable from VCIM at this address.\n\nCheck Logcat (tag: GmVcimActivation) for details.\nMake sure vehicle ignition is ON."
                } else {
                    "Readable DIDs from VCIM:\n\n" + state.didMap.entries.joinToString("\n") { (k, v) -> "$k = $v" }
                }
                binding.statusText.text = summary
            }
            is Obd2ActivationState.ActivationError -> {
                binding.statusText.text = "Error: ${state.message}"
                binding.btnStartScan.isEnabled = state.recoverable
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderAdapterList(adapters: List<BluetoothDevice>) {
        binding.adaptersContainer.removeAllViews()
        adapters.forEach { device ->
            val name = runCatching { device.name }.getOrNull() ?: device.address

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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            inner.addView(TextView(requireContext()).apply {
                text = name
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            inner.addView(TextView(requireContext()).apply {
                text = device.address
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12f
            })

            card.addView(inner)
            card.setOnClickListener { viewModel.connectAndActivate(device) }
            binding.adaptersContainer.addView(card)
        }
    }

    private fun setStepActive(step: Int, active: Boolean) {
        val v = when (step) {
            1 -> binding.step1Indicator
            2 -> binding.step2Indicator
            3 -> binding.step3Indicator
            4 -> binding.step4Indicator
            5 -> binding.step5Indicator
            else -> return
        }
        v.setTextColor(requireContext().getColor(if (active) R.color.gmc_red else R.color.text_hint))
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
