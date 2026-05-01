package com.gmc.digitalkey.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gmc.digitalkey.databinding.FragmentVehicleSelectBinding
import com.gmc.digitalkey.model.GmcEvModel
import com.gmc.digitalkey.ui.key.DigitalKeyViewModel
import kotlinx.coroutines.launch

class VehicleSelectFragment : Fragment() {

    private var _binding: FragmentVehicleSelectBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DigitalKeyViewModel by viewModels({ requireParentFragment() })
    private var scannedDevice: BluetoothDevice? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVehicleSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show scanned device if available
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanResults.collect { devices ->
                val device = devices.firstOrNull()
                if (device != null) {
                    scannedDevice = device
                    binding.scannedDeviceCard.visibility = View.VISIBLE
                    binding.scannedDeviceName.text = device.name ?: "GMC Vehicle"
                    binding.scannedDeviceAddress.text = device.address
                }
            }
        }

        // Start scan immediately
        viewModel.startScan()

        binding.btnConfirmPair.setOnClickListener {
            val device = scannedDevice ?: return@setOnClickListener
            val model = when (binding.modelGroup.checkedRadioButtonId) {
                binding.modelHummerPickup.id -> GmcEvModel.HUMMER_EV_PICKUP
                binding.modelHummerSuv.id -> GmcEvModel.HUMMER_EV_SUV
                binding.modelSierraEv.id -> GmcEvModel.SIERRA_EV_DENALI
                binding.modelTerrainEv.id -> GmcEvModel.TERRAIN_EV
                else -> GmcEvModel.HUMMER_EV_PICKUP
            }
            val name = model.displayName
            viewModel.pairDevice(device, model, name)
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopScan()
        _binding = null
    }
}
