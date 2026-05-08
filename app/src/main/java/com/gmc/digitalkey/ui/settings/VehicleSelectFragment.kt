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
import com.gmc.digitalkey.R
import com.google.android.material.card.MaterialCardView
import com.gmc.digitalkey.databinding.FragmentVehicleSelectBinding
import com.gmc.digitalkey.model.GmcEvModel
import com.gmc.digitalkey.ui.key.DigitalKeyViewModel
import com.gmc.digitalkey.ui.key.ScannedDevice
import coil.load
import coil.transform.RoundedCornersTransformation
import com.gmc.digitalkey.vin.VinDecoder
import com.gmc.digitalkey.vin.VinInfo
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VehicleSelectFragment : Fragment() {

    private var _binding: FragmentVehicleSelectBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DigitalKeyViewModel by viewModels()

    private var selectedDevice: BluetoothDevice? = null
    private var enteredVin = ""
    private var decodedVinInfo: VinInfo? = null
    private var decodeJob: Job? = null
    private var nameFilter = ""
    private var gmcOnlyFilter = false
    private var nearOnlyFilter = false   // rssi > -70
    private var closeOnlyFilter = false  // rssi > -60

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        // Door jamb QR may contain just the VIN or a longer string — extract 17-char VIN
        val vin = Regex("[A-HJ-NPR-Z0-9]{17}").find(raw.uppercase())?.value ?: return@registerForActivityResult
        applyVin(vin)
    }

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

        binding.btnShowGuide.setOnClickListener {
            findNavController().navigate(R.id.setupGuideFragment)
        }

        binding.btnObd2Activate.setOnClickListener {
            findNavController().navigate(R.id.action_vehicle_select_to_obd2)
        }

        // VIN entry
        binding.vinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.uppercase()?.trim() ?: ""
                applyVin(raw)
            }
        })

        binding.btnScanQr.setOnClickListener {
            val opts = ScanOptions().apply {
                setPrompt("Scan the QR code on your vehicle's door jamb")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(opts)
        }

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
                binding.modelSilveradoEv.id  -> GmcEvModel.SILVERADO_EV
                binding.modelBlazerEv.id     -> GmcEvModel.BLAZER_EV
                binding.modelEquinoxEv.id    -> GmcEvModel.EQUINOX_EV
                binding.modelLyriq.id        -> GmcEvModel.LYRIQ
                binding.modelOptiq.id        -> GmcEvModel.OPTIQ
                binding.modelVistiq.id       -> GmcEvModel.VISTIQ
                binding.modelEscaladeIq.id   -> GmcEvModel.ESCALADE_IQ
                else                         -> GmcEvModel.HUMMER_EV_PICKUP
            }
            viewModel.pairDevice(device, model, model.displayName, enteredVin,
                decodedVinInfo?.imageUrl ?: "")
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

    private fun applyVin(raw: String) {
        val isValid = raw.length == 17 && raw.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
        enteredVin = if (isValid) raw else ""
        if (binding.vinInput.text?.toString()?.uppercase() != raw) {
            binding.vinInput.setText(raw)
            binding.vinInput.setSelection(raw.length)
        }
        binding.vinStatus.text = when {
            raw.isEmpty() -> "Scan the QR code on your door jamb, or enter VIN manually to identify your vehicle"
            isValid -> "VIN confirmed — fetching vehicle info…"
            else -> "VIN must be 17 characters (A–Z excluding I/O/Q, digits)"
        }
        binding.vinStatus.setTextColor(
            requireContext().getColor(when {
                isValid -> R.color.status_connected
                raw.isEmpty() -> R.color.text_hint
                else -> R.color.status_error
            })
        )
        if (isValid) decodeVinAsync(raw) else showDecodedCard(null)
        renderDeviceList(viewModel.scanResults.value)
    }

    private fun decodeVinAsync(vin: String) {
        decodeJob?.cancel()
        binding.decodeProgress.visibility = View.VISIBLE
        binding.decodedCard.visibility = View.VISIBLE
        binding.decodedTitle.text = "Looking up VIN…"
        binding.decodedDetail.text = ""
        binding.decodedBadge.visibility = View.GONE
        decodeJob = viewLifecycleOwner.lifecycleScope.launch {
            val info = VinDecoder.decode(vin)
            if (_binding == null) return@launch
            binding.decodeProgress.visibility = View.GONE
            showDecodedCard(info)
        }
    }

    private fun showDecodedCard(info: VinInfo?) {
        decodedVinInfo = info
        if (info == null) {
            binding.decodedCard.visibility = View.GONE
            return
        }
        binding.decodedCard.visibility = View.VISIBLE
        binding.decodeProgress.visibility = View.GONE
        binding.decodedTitle.text = "${info.year} ${info.make.lowercase().replaceFirstChar { it.uppercase() }} ${info.model.lowercase().replaceFirstChar { it.uppercase() }}"
        binding.decodedDetail.text = buildString {
            if (info.bodyClass.isNotEmpty()) append(info.bodyClass)
            if (info.isElectric) append(" · Electric")
        }
        binding.decodedBadge.visibility = View.VISIBLE

        // Load Wikipedia image or fall back to local drawable
        if (info.imageUrl.isNotEmpty()) {
            binding.decodedVehicleImage.load(info.imageUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(8f))
                placeholder(R.drawable.ic_car_hummer)
                error(R.drawable.ic_car_hummer)
            }
        }

        // Auto-select the matching model radio button
        val modelRadioId = when {
            info.model.contains("HUMMER") && info.bodyClass.contains("pickup", ignoreCase = true) -> binding.modelHummerPickup.id
            info.model.contains("HUMMER")    -> binding.modelHummerSuv.id
            info.model.contains("SIERRA")    -> binding.modelSierraEv.id
            info.model.contains("TERRAIN")   -> binding.modelTerrainEv.id
            info.model.contains("SILVERADO") -> binding.modelSilveradoEv.id
            info.model.contains("BLAZER")    -> binding.modelBlazerEv.id
            info.model.contains("EQUINOX")   -> binding.modelEquinoxEv.id
            info.model.contains("LYRIQ")     -> binding.modelLyriq.id
            info.model.contains("OPTIQ")     -> binding.modelOptiq.id
            info.model.contains("VISTIQ")    -> binding.modelVistiq.id
            info.model.contains("ESCALADE")  -> binding.modelEscaladeIq.id
            else -> null
        }
        if (modelRadioId != null) binding.modelGroup.check(modelRadioId)

        binding.vinStatus.text = "✓ ${info.year} ${info.make} ${info.model} — model auto-selected"
    }

    private fun applyFilters(devices: List<ScannedDevice>): List<ScannedDevice> = devices.filter { s ->
        // If a valid VIN is entered, show only devices whose name contains the last 6 VIN chars
        if (enteredVin.length == 17 && !s.name.contains(enteredVin.takeLast(6), ignoreCase = true)) return@filter false
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
            val isVinMatch = enteredVin.length == 17 &&
                scanned.name.contains(enteredVin.takeLast(6), ignoreCase = true)

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
                val prefix = when { isVinMatch -> "✓ "; isGmc -> "★ "; else -> "" }
                text = "$prefix${scanned.name}"
                setTextColor(requireContext().getColor(when {
                    isVinMatch -> R.color.status_connected
                    isGmc -> R.color.gmc_red
                    else -> R.color.text_primary
                }))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textBlock.addView(TextView(requireContext()).apply {
                text = scanned.device.address
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12f
            })
            if (isVinMatch) {
                textBlock.addView(TextView(requireContext()).apply {
                    text = "Your vehicle (VIN match)"
                    setTextColor(requireContext().getColor(R.color.status_connected))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                })
            } else if (isGmc) {
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
