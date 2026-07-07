package com.gmc.digitalkey.ui.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.load
import com.gmc.digitalkey.R
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.databinding.FragmentHomeBinding
import com.gmc.digitalkey.model.LockState
import com.gmc.digitalkey.model.PairedVehicle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        binding.errorActionBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.vehicleHeroCard.setOnClickListener {
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
                val hasMultiple = vehicles.size > 1
                binding.vehicleSwitchHint.visibility = if (hasMultiple) View.VISIBLE else View.GONE
                binding.vehiclesSectionLabel.visibility = if (hasMultiple) View.VISIBLE else View.GONE
                binding.vehiclesScroll.visibility = if (hasMultiple) View.VISIBLE else View.GONE
                if (hasMultiple) renderVehicleCards(vehicles)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeVehicle.collect { vehicle ->
                if (vehicle == null) {
                    binding.noVehicleText.visibility = View.VISIBLE
                    binding.vehicleHeroCard.visibility = View.GONE
                    binding.actionButtons.visibility = View.GONE
                } else {
                    binding.noVehicleText.visibility = View.GONE
                    binding.vehicleHeroCard.visibility = View.VISIBLE
                    binding.actionButtons.visibility = View.VISIBLE
                    binding.vehicleName.text = vehicle.displayName
                    if (vehicle.vin.length >= 6) {
                        binding.vehicleVinBadge.visibility = View.VISIBLE
                        binding.vehicleVinBadge.text = "· ${vehicle.vin.takeLast(6)}"
                    } else {
                        binding.vehicleVinBadge.visibility = View.GONE
                    }
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
                binding.lockStateText.text = when (state.lockState) {
                    LockState.LOCKED -> getString(R.string.vehicle_locked)
                    LockState.UNLOCKED -> getString(R.string.vehicle_unlocked)
                    LockState.UNKNOWN -> getString(R.string.vehicle_status_unknown)
                }
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

    private fun renderVehicleCards(vehicles: List<PairedVehicle>) {
        binding.vehiclesContainer.removeAllViews()
        val activeId = viewModel.activeVehicle.value?.id
        val inflater = LayoutInflater.from(requireContext())
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        val strokePx = (2 * resources.displayMetrics.density).toInt()

        vehicles.forEach { vehicle ->
            val card = inflater.inflate(R.layout.item_vehicle_card, binding.vehiclesContainer, false) as MaterialCardView
            if (vehicle.id == activeId) {
                card.strokeWidth = strokePx
                card.strokeColor = requireContext().getColor(R.color.gmc_red)
            }
            val params = card.layoutParams as LinearLayout.LayoutParams
            params.marginEnd = marginPx
            card.layoutParams = params

            card.findViewById<TextView>(R.id.mini_vehicle_name).text = vehicle.displayName
            val vinView = card.findViewById<TextView>(R.id.mini_vehicle_vin)
            if (vehicle.vin.length >= 6) {
                vinView.visibility = View.VISIBLE
                vinView.text = vehicle.vin.takeLast(6)
            }
            val imageView = card.findViewById<ImageView>(R.id.mini_vehicle_image)
            if (vehicle.imageUrl.isNotEmpty()) {
                imageView.load(vehicle.imageUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_car_hummer)
                    error(R.drawable.ic_car_hummer)
                }
            }
            card.setOnClickListener { viewModel.selectVehicle(vehicle.id) }
            binding.vehiclesContainer.addView(card)
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

        val enabled = state.isReady
        binding.btnLock.isEnabled = enabled
        binding.btnLock.alpha = if (enabled) 1f else 0.4f
        binding.btnUnlock.isEnabled = enabled
        binding.btnUnlock.alpha = if (enabled) 1f else 0.4f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
