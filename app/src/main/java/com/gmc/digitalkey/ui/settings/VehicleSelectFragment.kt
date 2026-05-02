package com.gmc.digitalkey.ui.settings

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
import com.google.android.material.card.MaterialCardView
import com.gmc.digitalkey.R
import com.gmc.digitalkey.databinding.FragmentVehicleSelectBinding
import com.gmc.digitalkey.model.GmcEvModel
import com.gmc.digitalkey.ui.key.DigitalKeyViewModel
import com.gmc.digitalkey.ui.key.ScannedDevice
import kotlinx.coroutines.launch

class VehicleSelectFragment : Fragment() {

    private var _binding: FragmentVehicleSelectBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DigitalKeyViewModel by viewModels()

    private var selectedDevice: BluetoothDevice? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVehicleSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-select first model so user only needs to pick a device
        binding.modelHummerPickup.isChecked = true

        binding.btnScan.setOnClickListener {
            if (viewModel.isScanning.value) viewModel.stopScan() else viewModel.startScan()
        }

        binding.btnConfirmPair.setOnClickListener {
            val device = selectedDevice ?: return@setOnClickListener
            val model = when (binding.modelGroup.checkedRadioButtonId) {
                binding.modelHummerPickup.id -> GmcEvModel.HUMMER_EV_PICKUP
                binding.modelHummerSuv.id    -> GmcEvModel.HUMMER_EV_SUV
                binding.modelSierraEv.id     -> GmcEvModel.SIERRA_EV_DENALI
                binding.modelTerrainEv.id    -> GmcEvModel.TERRAIN_EV
                else                         -> GmcEvModel.HUMMER_EV_PICKUP
            }
            viewModel.pairDevice(device, model, model.displayName)
            findNavController().popBackStack()
        }

        observeState()
        viewModel.startScan()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScanning.collect { scanning ->
                binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
                binding.btnScan.text = if (scanning) "Stop" else "Scan Again"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanResults.collect { results ->
                val count = results.size
                binding.scanStatusText.text = when {
                    viewModel.isScanning.value && count == 0 -> "Scanning for vehicles…"
                    viewModel.isScanning.value               -> "Scanning… ($count found)"
                    count == 0                               -> "No devices found — try Scan Again"
                    else                                     -> "$count device${if (count > 1) "s" else ""} found"
                }
                renderDeviceList(results)
            }
        }
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun renderDeviceList(devices: List<ScannedDevice>) {
        binding.devicesContainer.removeAllViews()

        if (devices.isEmpty()) {
            binding.noDevicesHint.visibility = View.VISIBLE
            return
        }
        binding.noDevicesHint.visibility = View.GONE

        devices.forEach { scanned ->
            val isSelected = selectedDevice?.address == scanned.device.address

            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dpToPx() }
                setCardBackgroundColor(
                    requireContext().getColor(
                        if (isSelected) R.color.gmc_red_dark else R.color.bg_elevated
                    )
                )
                radius = 8.dpToPx().toFloat()
                strokeWidth = if (isSelected) 2.dpToPx() else 0
                strokeColor = requireContext().getColor(R.color.gmc_red)
            }

            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textBlock = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textBlock.addView(TextView(requireContext()).apply {
                text = scanned.name
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textBlock.addView(TextView(requireContext()).apply {
                text = scanned.device.address
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12f
            })

            inner.addView(textBlock)
            inner.addView(TextView(requireContext()).apply {
                text = "${rssiToBar(scanned.rssi)}\n${scanned.rssi} dBm"
                setTextColor(requireContext().getColor(rssiToColor(scanned.rssi)))
                textSize = 12f
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            })

            card.addView(inner)

            card.setOnClickListener {
                selectedDevice = scanned.device
                // Stop scan immediately so list stops updating
                viewModel.stopScan()
                setPairButtonEnabled(true)
                renderDeviceList(viewModel.scanResults.value)
            }

            binding.devicesContainer.addView(card)
        }
    }

    private fun setPairButtonEnabled(enabled: Boolean) {
        binding.btnConfirmPair.isEnabled = enabled
        binding.btnConfirmPair.alpha = if (enabled) 1f else 0.5f
    }

    private fun rssiToBar(rssi: Int) = when {
        rssi > -60 -> "▂▄▆█"
        rssi > -70 -> "▂▄▆"
        rssi > -80 -> "▂▄"
        else       -> "▂"
    }

    private fun rssiToColor(rssi: Int) = when {
        rssi > -65 -> R.color.status_connected
        rssi > -75 -> R.color.status_warning
        else       -> R.color.status_error
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopScan()
        _binding = null
    }
}
