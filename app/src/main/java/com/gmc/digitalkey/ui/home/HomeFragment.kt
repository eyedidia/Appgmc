package com.gmc.digitalkey.ui.home

import android.content.Intent
import androidx.navigation.fragment.findNavController
import coil.load
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gmc.digitalkey.R
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.databinding.FragmentHomeBinding
import com.gmc.digitalkey.model.EngineState
import com.gmc.digitalkey.model.LockState
import com.gmc.digitalkey.model.PairedVehicle
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        observeState()
    }

    private fun setupButtons() {
        binding.btnLock.setOnClickListener { viewModel.lock() }
        binding.btnUnlock.setOnClickListener {
            viewModel.unlock()
            val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.unlock_flash)
            binding.vehicleImage.startAnimation(anim)
        }
        binding.btnRemoteStart.setOnClickListener { viewModel.remoteStart() }
        binding.btnHorn.setOnClickListener { viewModel.hornLights() }
        binding.errorActionBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.vehicleSelector.setOnClickListener {
            showVehiclePicker()
        }
        binding.btnSetupGuide.setOnClickListener {
            findNavController().navigate(R.id.setupGuideFragment)
        }
        binding.btnGoPairDirect.setOnClickListener {
            findNavController().navigate(R.id.vehicleSelectFragment)
        }
    }

    private fun showVehiclePicker() {
        val vehicles = viewModel.pairedVehicles.value
        if (vehicles.size <= 1) return
        val activeId = viewModel.activeVehicle.value?.id
        val names = vehicles.map { v ->
            val vin = if (v.vin.length == 17) " · ${v.vin.takeLast(6)}" else ""
            "${v.displayName}$vin"
        }.toTypedArray()
        val checkedIndex = vehicles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Vehicle")
            .setSingleChoiceItems(names, checkedIndex) { dialog, which ->
                viewModel.selectVehicle(vehicles[which].id)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pairedVehicles.collect { vehicles ->
                binding.vehicleSwitchHint.visibility =
                    if (vehicles.size > 1) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeVehicle.collect { vehicle ->
                if (vehicle == null) {
                    binding.noVehicleText.visibility = View.VISIBLE
                    binding.vehicleSelector.visibility = View.GONE
                    binding.actionButtons.visibility = View.GONE
                } else {
                    binding.noVehicleText.visibility = View.GONE
                    binding.vehicleSelector.visibility = View.VISIBLE
                    binding.actionButtons.visibility = View.VISIBLE
                    binding.vehicleName.text = vehicle.displayName
                    if (vehicle.imageUrl.isNotEmpty()) {
                        binding.vehicleImage.load(vehicle.imageUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_car_hummer)
                            error(R.drawable.ic_car_hummer)
                        }
                    } else {
                        binding.vehicleImage.setImageResource(R.drawable.ic_car_hummer)
                    }
                    if (!viewModel.connectionState.value.isConnected) {
                        viewModel.connectToVehicle(vehicle)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateBleStatus(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.vehicleState.collect { state ->
                state ?: return@collect
                // Lock state
                binding.lockStateText.text = when (state.lockState) {
                    LockState.LOCKED -> getString(R.string.vehicle_locked)
                    LockState.UNLOCKED -> getString(R.string.vehicle_unlocked)
                    LockState.UNKNOWN -> getString(R.string.vehicle_status_unknown)
                }
                // Engine state
                binding.engineStateText.text = when (state.engineState) {
                    EngineState.OFF -> getString(R.string.engine_off)
                    EngineState.STARTING -> getString(R.string.engine_starting)
                    EngineState.RUNNING -> getString(R.string.engine_running)
                    EngineState.STOPPING -> getString(R.string.engine_off)
                }
                // SOC
                val soc = state.chargingState.socPercent
                if (soc >= 0) {
                    binding.socText.visibility = View.VISIBLE
                    binding.socText.text = getString(R.string.soc_percent, soc)
                } else {
                    binding.socText.visibility = View.GONE
                }
            }
        }
    }

    private fun updateBleStatus(state: BleConnectionState) {
        val (text, color) = when (state) {
            is BleConnectionState.BluetoothOff -> Pair(getString(R.string.ble_off), R.color.status_error)
            is BleConnectionState.Scanning -> Pair(getString(R.string.ble_scanning), R.color.status_warning)
            is BleConnectionState.Connecting -> Pair(getString(R.string.ble_connecting), R.color.status_warning)
            is BleConnectionState.Authenticating -> Pair(getString(R.string.ble_authenticating), R.color.status_warning)
            is BleConnectionState.Connected -> Pair(getString(R.string.ble_connected), R.color.status_connected)
            is BleConnectionState.Ready -> {
                val rssiDesc = when {
                    state.rssi > -60 -> "Signal Strong"
                    state.rssi > -75 -> "Signal Good"
                    else -> "Signal Weak"
                }
                Pair("${getString(R.string.ble_ready)} • $rssiDesc", R.color.status_connected)
            }
            is BleConnectionState.Error -> Pair(state.message, R.color.status_error)
            else -> Pair(getString(R.string.ble_disconnected), R.color.text_secondary)
        }

        binding.bleStatusText.text = text
        binding.bleStatusText.setTextColor(requireContext().getColor(color))
        binding.bleIcon.setColorFilter(requireContext().getColor(color))

        // Error banner
        when (state) {
            is BleConnectionState.BluetoothOff -> {
                binding.errorBanner.visibility = View.VISIBLE
                binding.errorText.text = getString(R.string.ble_off)
                binding.errorActionBtn.text = getString(R.string.ble_off_action)
                binding.errorActionBtn.visibility = View.VISIBLE
            }
            is BleConnectionState.Error -> {
                binding.errorBanner.visibility = View.VISIBLE
                binding.errorText.text = state.message
                binding.errorActionBtn.visibility = View.GONE
            }
            else -> binding.errorBanner.visibility = View.GONE
        }

        // Disable buttons when not ready
        val enabled = state.isReady
        binding.btnLock.isEnabled = enabled
        binding.btnUnlock.isEnabled = enabled
        binding.btnRemoteStart.isEnabled = enabled
        binding.btnHorn.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
