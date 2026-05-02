package com.gmc.digitalkey.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private var nameFilter = ""
    private var gmcOnlyFilter = false
    private var nearOnlyFilter = false   // rssi > -70
    private var closeOnlyFilter = false  // rssi > -60

    // OUI prefixes registered to GM and their primary BLE module Tier-1 suppliers
    private val gmcOuiPrefixes = setOf(
        "B4:DE:31",  // General Motors LLC
        "04:E6:76",  // Continental Automotive Technologies
        "AC:23:3F",  // Aptiv Solutions (ex-Delphi)
        "04:52:C7",  // Aptiv Services
        "74:F0:7D",  // Continental Automotive
        "20:CD:39",  // Harman International
        "C4:6E:1F",  // Harman/Becker Automotive
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVehicleSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        binding.filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                nameFilter = s?.toString()?.trim() ?: ""
                renderDeviceList(viewModel.scanResults.value)
            }
        })

        binding.chipGmcOnly.setOnCheckedChangeListener { _, checked ->
            gmcOnlyFilter = checked
            renderDeviceList(viewModel.scanResults.value)
        }

        binding.chipNearOnly.setOnCheckedChangeListener { _, checked ->
            nearOnlyFilter = checked
            if (checked) { closeOnlyFilter = false; binding.chipCloseOnly.isChecked = false }
            renderDeviceList(viewModel.scanResults.value)
        }

        binding.chipCloseOnly.setOnCheckedChangeListener { _, checked ->
            closeOnlyFilter = checked
            if (checked) { nearOnlyFilter = false; binding.chipNearOnly.isChecked = false }
            renderDeviceList(viewModel.scanResults.value)
        }

        observeState()
        viewModel.startScan()
    }

    private fun applyFilters(devices: List<ScannedDevice>): List<ScannedDevice> = devices.filter { s ->
        if (nameFilter.isNotEmpty() && !s.name.contains(nameFilter, ignoreCase = true)) return@filter false
        if (gmcOnlyFilter && !isGmcDevice(s.device.address)) return@filter false
        if (closeOnlyFilter && s.rssi <= -60) return@filter false
        if (nearOnlyFilter && s.rssi <= -70) return@filter false
        true
    }

    private fun isGmcDevice(address: String) =
        gmcOuiPrefixes.any { address.uppercase().startsWith(it) }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScanning.collect { scanning ->
                binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
                binding.btnScan.text = if (scanning) "Stop" else "Scan Again"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanResults.collect { results ->
                val shown = applyFilters(results).size
                val total = results.size
                binding.scanStatusText.text = when {
                    viewModel.isScanning.value && total == 0 -> "Scanning for vehicles…"
                    viewModel.isScanning.value -> "Scanning… ($total found, $shown shown)"
                    total == 0 -> "No devices found — try Scan Again"
                    else -> "$shown of $total device${if (total > 1) "s" else ""} shown"
                }
                renderDeviceList(results)
            }
        }
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun renderDeviceList(devices: List<ScannedDevice>) {
        val filtered = applyFilters(devices)
        binding.devicesContainer.removeAllViews()

        if (filtered.isEmpty()) {
            binding.noDevicesHint.text = if (devices.isEmpty())
                "No devices found. Make sure your vehicle's Bluetooth is on."
            else
                "No devices match the current filters."
            binding.noDevicesHint.visibility = View.VISIBLE
            return
        }
        binding.noDevicesHint.visibility = View.GONE

        filtered.forEach { scanned ->
            val isSelected = selectedDevice?.address == scanned.device.address
            val isGmc = isGmcDevice(scanned.device.address)

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
                text = if (isGmc) "★ ${scanned.name}" else scanned.name
                setTextColor(requireContext().getColor(
                    if (isGmc) R.color.gmc_red else R.color.text_primary
                ))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textBlock.addView(TextView(requireContext()).apply {
                text = scanned.device.address
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12f
            })
            if (isGmc) {
                textBlock.addView(TextView(requireContext()).apply {
                    text = "GM vehicle detected"
                    setTextColor(requireContext().getColor(R.color.gmc_red))
                    textSize = 11f
                })
            }

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
