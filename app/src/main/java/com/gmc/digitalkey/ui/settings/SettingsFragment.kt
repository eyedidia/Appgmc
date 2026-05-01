package com.gmc.digitalkey.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.gmc.digitalkey.ble.PassiveUnlockService
import com.gmc.digitalkey.databinding.FragmentSettingsBinding
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.model.GmcEvModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val db = AppDatabase.get(requireContext())
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            db.vehicleDao().observeAll().collect { vehicles ->
                val vehicle = vehicles.firstOrNull()
                if (vehicle != null) {
                    binding.vehicleNameValue.text = vehicle.displayName
                    binding.vehicleModelValue.text = GmcEvModel.fromKey(vehicle.modelKey).displayName
                    binding.vehicleVinValue.text = "${vehicle.vinPrefix}***"
                    binding.passiveUnlockSwitch.isChecked = vehicle.passiveUnlockEnabled
                } else {
                    binding.vehicleNameValue.text = "—"
                    binding.vehicleModelValue.text = "—"
                    binding.vehicleVinValue.text = "—"
                }
            }
        }

        // NFC mode
        val savedNfcMode = prefs.getString("nfc_mode", "hce")
        binding.nfcModeGroup.check(
            if (savedNfcMode == "hce") binding.nfcHceRadio.id else binding.nfcTagRadio.id
        )
        binding.nfcModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.nfcHceRadio.id) "hce" else "tag"
            prefs.edit().putString("nfc_mode", mode).apply()
        }

        // Passive unlock toggle
        binding.passiveUnlockSwitch.setOnCheckedChangeListener { _, enabled ->
            viewLifecycleOwner.lifecycleScope.launch {
                val vehicle = db.vehicleDao().getAll().firstOrNull() ?: return@launch
                db.vehicleDao().setPassiveUnlock(vehicle.id, enabled)
                prefs.edit().putString("active_vehicle_id", vehicle.id).apply()
                if (enabled) {
                    requestBatteryOptimizationExemption()
                    PassiveUnlockService.start(requireContext())
                } else {
                    PassiveUnlockService.stop(requireContext())
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = requireContext().getSystemService(PowerManager::class.java) ?: return
        val pkg = requireContext().packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$pkg")
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
