package com.gmc.digitalkey.ui.key

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gmc.digitalkey.R
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.ble.RawAdvert
import android.graphics.Typeface
import com.gmc.digitalkey.databinding.FragmentDigitalKeyBinding
import com.gmc.digitalkey.db.VehicleEntity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class DigitalKeyFragment : Fragment() {

    private var _binding: FragmentDigitalKeyBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DigitalKeyViewModel by viewModels()

    private lateinit var qrLauncher: ActivityResultLauncher<ScanOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qrLauncher = registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { viewModel.onQrScanned(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDigitalKeyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPairVehicle.setOnClickListener {
            findNavController().navigate(R.id.action_key_to_vehicle_select)
        }

        binding.btnScanQr.setOnClickListener {
            qrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Point at vehicle infotainment QR code")
                    .setBeepEnabled(false)
                    .setBarcodeImageEnabled(false)
            )
        }

        binding.btnRawBleScan.setOnClickListener {
            if (viewModel.isRawScanning.value) {
                viewModel.stopRawScan()
            } else {
                viewModel.startRawScan()
            }
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state -> updateKeyStatus(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pairedVehicles.collect { vehicles ->
                updatePairedVehiclesList(vehicles)
                binding.noVehiclesText.visibility = if (vehicles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRawScanning.collect { scanning ->
                binding.btnRawBleScan.text = getString(
                    if (scanning) R.string.raw_ble_scanning else R.string.raw_ble_scan
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qrResult.collect { hints ->
                if (hints == null) return@collect
                binding.tvQrResult.text = hints.summary()
                binding.tvQrResult.visibility = View.VISIBLE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rawDevices.collect { devices -> updateRawDevicesList(devices) }
        }
    }

    private fun updateKeyStatus(state: BleConnectionState) {
        val pulseAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.ble_pulse)

        when (state) {
            is BleConnectionState.Ready -> {
                binding.keyStatusLabel.text = getString(R.string.digital_key_active)
                binding.keyStatusLabel.setTextColor(requireContext().getColor(R.color.status_connected))
                binding.keyStatusSub.text = getString(R.string.ble_ready)
                binding.bleIconCenter.setColorFilter(requireContext().getColor(R.color.status_connected))
                binding.bleRingInner.startAnimation(pulseAnim)
                binding.bleRingOuter.startAnimation(pulseAnim)
            }
            is BleConnectionState.Scanning -> {
                binding.keyStatusLabel.text = getString(R.string.digital_key_inactive)
                binding.keyStatusLabel.setTextColor(requireContext().getColor(R.color.text_primary))
                binding.keyStatusSub.text = getString(R.string.ble_scanning)
                binding.bleIconCenter.setColorFilter(requireContext().getColor(R.color.status_warning))
                binding.bleRingInner.startAnimation(pulseAnim)
                binding.bleRingOuter.clearAnimation()
            }
            is BleConnectionState.Connecting, is BleConnectionState.Authenticating -> {
                binding.keyStatusSub.text = getString(R.string.ble_connecting)
                binding.bleIconCenter.setColorFilter(requireContext().getColor(R.color.status_warning))
            }
            is BleConnectionState.GattDump -> {
                binding.keyStatusSub.text = "GATT dump: ${state.services.size} services"
                binding.bleIconCenter.setColorFilter(requireContext().getColor(R.color.status_connected))
                showGattDumpDialog(state)
            }
            is BleConnectionState.BluetoothOff -> {
                binding.keyStatusLabel.text = getString(R.string.digital_key_inactive)
                binding.keyStatusSub.text = getString(R.string.ble_off)
                binding.bleIconCenter.setColorFilter(requireContext().getColor(R.color.status_error))
                binding.bleRingInner.clearAnimation()
                binding.bleRingOuter.clearAnimation()
            }
            else -> {
                binding.keyStatusLabel.text = getString(R.string.digital_key_inactive)
                binding.keyStatusSub.text = getString(R.string.ble_disconnected)
                binding.bleIconCenter.clearColorFilter()
                binding.bleRingInner.clearAnimation()
                binding.bleRingOuter.clearAnimation()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateRawDevicesList(devices: List<RawAdvert>) {
        binding.rawDevicesContainer.removeAllViews()
        if (devices.isEmpty()) {
            binding.tvRawDevicesLabel.visibility = View.GONE
            return
        }
        binding.tvRawDevicesLabel.visibility = View.VISIBLE

        // GM vehicles first, then by RSSI
        val sorted = devices.sortedWith(compareByDescending<RawAdvert> { it.isGm }.thenByDescending { it.rssi })

        sorted.forEach { advert ->
            val gmTag = if (advert.isGm) " ★ GM VEHICLE" else ""
            val color = when {
                advert.isGm                      -> requireContext().getColor(R.color.status_warning)
                advert.serviceUuids.isNotEmpty() -> requireContext().getColor(R.color.status_connected)
                else                             -> requireContext().getColor(R.color.text_secondary)
            }
            val tv = android.widget.TextView(requireContext()).apply {
                text = buildString {
                    append("${advert.rssi} dBm  ${advert.address}$gmTag")
                    if (advert.name != null) append("  \"${advert.name}\"")
                    appendLine()
                    if (advert.serviceUuids.isNotEmpty()) appendLine("  UUIDs: ${advert.serviceUuidsDisplay()}")
                    if (advert.manufacturerData.isNotEmpty()) appendLine("  Mfr: ${advert.manufacturerDisplay()}")
                    if (advert.isGm) append("  ↑ TAP to GATT dump")
                }
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(color)
                setPadding(0, 8, 0, 8)
                isClickable = true
                isFocusable = true
                // Tap any device → GATT dump (most useful during pairing window)
                setOnClickListener { viewModel.dumpVehicleGatt(advert.device) }
            }
            val divider = View(requireContext()).apply {
                setBackgroundColor(requireContext().getColor(R.color.bg_surface))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            }
            binding.rawDevicesContainer.addView(tv)
            binding.rawDevicesContainer.addView(divider)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePairedVehiclesList(vehicles: List<VehicleEntity>) {
        binding.pairedVehiclesContainer.removeAllViews()
        vehicles.forEach { vehicle ->
            val cardView = layoutInflater.inflate(
                android.R.layout.simple_list_item_2,
                binding.pairedVehiclesContainer,
                false
            )
            cardView.findViewById<android.widget.TextView>(android.R.id.text1)?.apply {
                text = vehicle.displayName
                setTextColor(requireContext().getColor(R.color.text_primary))
            }
            cardView.findViewById<android.widget.TextView>(android.R.id.text2)?.apply {
                text = if (vehicle.vin.isNotEmpty()) "${vehicle.bleAddress} • ${vehicle.vin}" else vehicle.bleAddress
                setTextColor(requireContext().getColor(R.color.text_secondary))
            }
            cardView.setOnLongClickListener {
                showUnpairDialog(vehicle)
                true
            }
            binding.pairedVehiclesContainer.addView(cardView)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showGattDumpDialog(dump: BleConnectionState.GattDump) {
        val sb = StringBuilder()
        sb.appendLine("Device: ${dump.device.address}")
        sb.appendLine("Services: ${dump.services.size}")
        sb.appendLine()
        dump.services.forEach { svc ->
            sb.appendLine("SERVICE")
            sb.appendLine(svc.uuid)
            svc.characteristics.forEach { c ->
                sb.appendLine("  CHAR ${c.uuid}")
                sb.appendLine("    [${c.properties}]")
                if (c.value.isNotEmpty()) sb.appendLine("    = ${c.value}")
            }
            sb.appendLine()
        }
        val text = sb.toString().trimEnd()

        val tv = TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(requireContext().getColor(R.color.text_primary))
            setPadding(32, 16, 32, 16)
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("GATT Dump")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("GATT Dump", text))
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showUnpairDialog(vehicle: VehicleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.unpair_confirm))
            .setMessage(vehicle.displayName)
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                viewModel.unpairVehicle(vehicle.id)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
