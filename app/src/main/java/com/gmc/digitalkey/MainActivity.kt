package com.gmc.digitalkey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gmc.digitalkey.databinding.ActivityMainBinding
import com.gmc.digitalkey.nfc.NfcKeyHandler
import com.gmc.digitalkey.ble.BleManager
import android.widget.TextView
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcKeyHandler: NfcKeyHandler
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        // Set up DrawerLayout and NavigationView
        drawerLayout = binding.drawerLayout
        navView = binding.navView

        // Stamp version into drawer header
        navView.getHeaderView(0)
            .findViewById<TextView>(R.id.navHeaderVersion)
            ?.text = "v${BuildConfig.VERSION_NAME}  #${BuildConfig.VERSION_CODE}  ·  GM Digital Key Tool"

        // Hamburger button opens the drawer
        binding.btnHamburger.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Handle drawer item selections
        navView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.drawer_obd2 -> {
                    val args = bundleOf("vehicleId" to null as String?)
                    navController.navigate(R.id.action_global_obd2, args)
                    true
                }
                R.id.drawer_doip -> {
                    val args = bundleOf("vehicleId" to null as String?)
                    navController.navigate(R.id.action_global_doip, args)
                    true
                }
                R.id.drawer_vehicle_select -> {
                    navController.navigate(R.id.action_global_vehicle_select)
                    true
                }
                R.id.drawer_digital_key -> {
                    navController.navigate(R.id.action_global_digital_key)
                    true
                }
                R.id.drawer_unlock -> {
                    Toast.makeText(this, getString(R.string.drawer_connect_first), Toast.LENGTH_SHORT).show()
                    navController.navigate(R.id.action_global_home)
                    true
                }
                R.id.drawer_lock -> {
                    Toast.makeText(this, getString(R.string.drawer_connect_first), Toast.LENGTH_SHORT).show()
                    navController.navigate(R.id.action_global_home)
                    true
                }
                R.id.drawer_start_engine -> {
                    // Disabled item — no-op
                    true
                }
                R.id.drawer_charge -> {
                    navController.navigate(R.id.action_global_charge)
                    true
                }
                R.id.drawer_settings -> {
                    navController.navigate(R.id.action_global_settings)
                    true
                }
                else -> false
            }
        }

        nfcKeyHandler = NfcKeyHandler(this, BleManager(this))

        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLE_PERMS)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcKeyHandler.enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcKeyHandler.disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            nfcKeyHandler.handleIntent(intent)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_BLE_PERMS = 100
    }
}
