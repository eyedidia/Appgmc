package com.gmc.digitalkey.ui.charge

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.gmc.digitalkey.R
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.databinding.FragmentChargeBinding
import com.gmc.digitalkey.model.PlugState
import kotlinx.coroutines.launch

class ChargeFragment : Fragment() {

    private var _binding: FragmentChargeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChargeViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChargeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        observeState()
    }

    private fun setupControls() {
        // Charge limit seekbar (range 20–100, mapped to 0–80)
        binding.chargeLimitSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val limit = progress + 20
                binding.chargeLimitLabel.text = "$limit%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val limit = (sb?.progress ?: 70) + 20
                viewModel.setChargeLimit(limit)
            }
        })

        // Schedule switch
        binding.scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            binding.scheduleTimePickerRow.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.setSchedule(viewModel.scheduleHour.value, viewModel.scheduleMinute.value, checked)
        }

        // Time picker button
        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    binding.btnPickTime.text = "%02d:%02d".format(hour, minute)
                    viewModel.setSchedule(hour, minute, binding.scheduleSwitch.isChecked)
                    binding.scheduleTimeLabel.text = getString(R.string.charge_schedule_set, hour, minute)
                },
                viewModel.scheduleHour.value,
                viewModel.scheduleMinute.value,
                true
            ).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chargingState.collect { charging ->
                // SOC display
                if (charging.socPercent >= 0) {
                    binding.socPercentText.text = getString(R.string.soc_percent, charging.socPercent)
                    binding.staleBadge.visibility = View.GONE
                } else {
                    binding.socPercentText.text = "—"
                }

                // Range
                if (charging.estimatedRangeKm >= 0) {
                    binding.rangeText.text = getString(R.string.range_km, charging.estimatedRangeKm)
                } else {
                    binding.rangeText.text = "—"
                }

                // Plug state chip
                val (chipText, chipColor) = when (charging.plugState) {
                    PlugState.CHARGING -> Pair(getString(R.string.plug_charging), R.color.status_charging)
                    PlugState.PLUGGED -> Pair(getString(R.string.plug_plugged), R.color.status_connected)
                    PlugState.UNPLUGGED -> Pair(getString(R.string.plug_unplugged), R.color.text_secondary)
                    PlugState.UNKNOWN -> Pair("—", R.color.text_hint)
                }
                binding.plugStatusChip.text = chipText
                binding.plugStatusChip.setTextColor(requireContext().getColor(chipColor))

                // Charge rate
                if (charging.chargeRateKw > 0f) {
                    binding.chargeRateText.visibility = View.VISIBLE
                    binding.chargeRateText.text = getString(R.string.charge_rate_kw, charging.chargeRateKw)
                } else {
                    binding.chargeRateText.visibility = View.GONE
                }

                // Low battery warning
                binding.lowBatteryWarning.visibility =
                    if (charging.isLowBattery) View.VISIBLE else View.GONE

                // Full charge
                if (charging.isFull) {
                    binding.socPercentText.setTextColor(requireContext().getColor(R.color.status_connected))
                } else if (charging.isLowBattery) {
                    binding.socPercentText.setTextColor(requireContext().getColor(R.color.status_error))
                } else {
                    binding.socPercentText.setTextColor(requireContext().getColor(R.color.text_primary))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                val notConnected = !state.isConnected
                binding.noBleWarning.visibility = if (notConnected) View.VISIBLE else View.GONE
            }
        }

        // Show stale data from DB when BLE is off
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lastKnownSoc.collect { (soc, rangeKm) ->
                val state = viewModel.chargingState.value
                if (state.isStale && soc >= 0) {
                    binding.socPercentText.text = getString(R.string.soc_percent, soc)
                    if (rangeKm >= 0) binding.rangeText.text = getString(R.string.range_km, rangeKm)
                    binding.staleBadge.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
