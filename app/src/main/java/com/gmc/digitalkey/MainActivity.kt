package com.gmc.digitalkey

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gmc.digitalkey.databinding.ActivityMainBinding
import com.gmc.digitalkey.nfc.NfcKeyHandler
import com.gmc.digitalkey.ble.BleManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcKeyHandler: NfcKeyHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        nfcKeyHandler = NfcKeyHandler(this, BleManager(this))
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
}
