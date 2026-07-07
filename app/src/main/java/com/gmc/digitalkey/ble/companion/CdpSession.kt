package com.gmc.digitalkey.ble.companion

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * CDP (Connected Device Platform) session state machine.
 *
 * Sits between BleManager (BLE transport) and the application layer.
 * The caller feeds incoming BLE notification bytes via [onBytesReceived] and
 * sends bytes via the [send] callback supplied at construction.
 *
 * State machine:
 *   IDLE → SENT_CLIENT_INIT → AWAITING_VISUAL_CONFIRM? → SECURE → (lock/unlock)
 *
 * Usage:
 *   val session = CdpSession(vehicleId, send = { bytes -> gatt.writeChar(bytes) }, listener)
 *   session.start()            // sends CLIENT_INIT
 *   session.onBytesReceived(b) // call from GATT onCharacteristicChanged
 *   session.sendUnlock()       // when SECURE
 */
internal class CdpSession(
    private val vehicleId: String,
    private val send: (ByteArray) -> Unit,
    private val listener: Listener,
) {
    private val TAG = "CdpSession"

    enum class State {
        IDLE,
        SENT_CLIENT_INIT,
        SENT_CLIENT_FINISH,
        AWAITING_VISUAL_CONFIRM,
        SECURE,
        ERROR,
    }

    interface Listener {
        /** Handshake complete and encrypted channel ready. If [needsVisualConfirm] is true,
         *  the user must confirm the PIN shown on the vehicle infotainment.
         *  [pinHex] is the 6-hex-char PIN; [authBytes] is the full UKEY2 auth string. */
        fun onSecureChannelEstablished(
            session: CdpSession,
            needsVisualConfirm: Boolean,
            pinHex: String = "",
            authBytes: ByteArray = ByteArray(0),
        )

        /** Vehicle sent an escrow token during initial pairing — store in DB. */
        fun onEscrowToken(vehicleId: String, token: ByteArray, handle: ByteArray)

        /** Lock/unlock acknowledgement received from vehicle. */
        fun onCommandAck(vehicleId: String, success: Boolean)

        /** Unrecoverable session error. */
        fun onError(vehicleId: String, reason: String)

        /** Association complete (vehicle confirmed pairing). */
        fun onAssociationSuccess(vehicleId: String)
    }

    var state: State = State.IDLE
        private set

    private var handshake: Ukey2Handshake? = null
    private var secureChannel: D2dSecureChannel? = null

    // Reassembly buffer: CDP BLE messages are prefixed with 4-byte big-endian length
    private val receiveBuffer = ByteArrayOutputStream()
    private var expectedLength = -1

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Begin the UKEY2 handshake — sends CLIENT_INIT over BLE. */
    fun start() {
        check(state == State.IDLE) { "session already started" }
        val hs = Ukey2Handshake()
        handshake = hs

        val clientInitBytes = hs.buildClientInit()
        // Wrap in DeviceMessage(ENCRYPTION_HANDSHAKE) then prepend 4-byte length
        val wrapped = ProtoUtil.wrapLength(CdpMessages.wrapHandshake(clientInitBytes))
        Log.d(TAG, "[$vehicleId] → CLIENT_INIT (${wrapped.size}B total)")
        send(wrapped)
        state = State.SENT_CLIENT_INIT
    }

    /**
     * Feed incoming BLE notification bytes into the reassembly buffer.
     * Reassembles fragmented BLE packets and dispatches complete CDP messages.
     */
    fun onBytesReceived(bytes: ByteArray) {
        receiveBuffer.write(bytes)
        processBuffer()
    }

    /** Send lock request (only valid in SECURE state). */
    fun sendLock() = sendTrustedDeviceMessage(CdpMessages.buildLockMessage())

    /** Send unlock credentials (only valid in SECURE state). */
    fun sendUnlock(handle: ByteArray, encryptedToken: ByteArray) =
        sendTrustedDeviceMessage(CdpMessages.buildUnlockMessage(handle, encryptedToken))

    /** Reset session for reuse (e.g. reconnect). */
    fun reset() {
        state = State.IDLE
        handshake = null
        secureChannel = null
        receiveBuffer.reset()
        expectedLength = -1
    }

    /**
     * Caller should invoke after user confirms PIN on vehicle screen (or after OBD2 approval).
     *
     * Sends TD_ACK to the vehicle over the secure channel — this is the phone-side signal that
     * the pairing is approved.  The vehicle should respond with TD_ESCROW_TOKEN (initial pairing)
     * or TD_STATE (reconnect) to complete the association.
     */
    fun onVisualConfirmComplete() {
        if (state == State.AWAITING_VISUAL_CONFIRM) {
            // Notify vehicle that the phone has confirmed the pairing PIN
            sendTrustedDeviceMessage(CdpMessages.buildTrustedDeviceMessage(CdpMessages.TD_ACK))
            state = State.SECURE
            listener.onSecureChannelEstablished(this, needsVisualConfirm = false, pinHex = "", authBytes = ByteArray(0))
        }
    }

    // ── Internal message dispatch ──────────────────────────────────────────────

    private fun processBuffer() {
        while (true) {
            val buf = receiveBuffer.toByteArray()

            if (expectedLength < 0) {
                if (buf.size < 4) return
                expectedLength = ((buf[0].toInt() and 0xFF) shl 24) or
                                 ((buf[1].toInt() and 0xFF) shl 16) or
                                 ((buf[2].toInt() and 0xFF) shl 8) or
                                  (buf[3].toInt() and 0xFF)
                if (expectedLength <= 0 || expectedLength > 65_536) {
                    Log.w(TAG, "[$vehicleId] Bad length prefix $expectedLength — resetting buffer")
                    receiveBuffer.reset(); expectedLength = -1; return
                }
            }

            if (buf.size < 4 + expectedLength) return

            val messageBytes = buf.copyOfRange(4, 4 + expectedLength)
            val remaining    = buf.copyOfRange(4 + expectedLength, buf.size)
            receiveBuffer.reset()
            receiveBuffer.write(remaining)
            expectedLength = -1

            dispatchMessage(messageBytes)
        }
    }

    private fun dispatchMessage(bytes: ByteArray) {
        val (opType, payload) = CdpMessages.unwrap(bytes) ?: run {
            Log.w(TAG, "[$vehicleId] Malformed DeviceMessage"); return
        }
        Log.d(TAG, "[$vehicleId] ← DeviceMessage opType=$opType payload=${payload.size}B state=$state")

        when (opType) {
            CdpMessages.OP_ENCRYPTION_HANDSHAKE -> handleHandshakeMessage(payload)
            CdpMessages.OP_CLIENT_MESSAGE        -> handleClientMessage(payload)
            else -> Log.w(TAG, "[$vehicleId] Unknown operation type $opType")
        }
    }

    // ── Handshake handling ────────────────────────────────────────────────────

    private fun handleHandshakeMessage(ukey2Bytes: ByteArray) {
        val hs = handshake ?: run { Log.w(TAG, "No handshake active"); return }

        when (state) {
            State.SENT_CLIENT_INIT -> {
                // Expect SERVER_INIT from vehicle
                try {
                    hs.processServerInit(ukey2Bytes)
                } catch (e: Exception) {
                    failSession("SERVER_INIT parse error: ${e.message}")
                    return
                }

                val clientFinishBytes = hs.buildClientFinish()
                val wrapped = ProtoUtil.wrapLength(CdpMessages.wrapHandshake(clientFinishBytes))
                Log.d(TAG, "[$vehicleId] → CLIENT_FINISH (${wrapped.size}B)")
                send(wrapped)
                state = State.SENT_CLIENT_FINISH

                // Derive D2D secure channel
                val nextSecret = runCatching { hs.nextProtocolSecret() }.getOrElse {
                    failSession("Key derivation failed: ${it.message}"); return
                }
                secureChannel = D2dSecureChannel(nextSecret)

                // Auth string for visual confirmation (first 6 bytes shown as PIN)
                val authStr = hs.authString()
                val pinHex = authStr.take(6).joinToString("") { "%02X".format(it) }
                Log.i(TAG, "[$vehicleId] Auth string (PIN): $pinHex")

                // For initial pairing, visual confirmation is required.
                // For reconnect with stored credentials, the vehicle may skip this.
                state = State.AWAITING_VISUAL_CONFIRM
                listener.onSecureChannelEstablished(this, needsVisualConfirm = true, pinHex = pinHex, authBytes = authStr)
            }

            else -> Log.w(TAG, "[$vehicleId] Unexpected handshake message in state $state")
        }
    }

    // ── Feature message handling ──────────────────────────────────────────────

    private fun handleClientMessage(encryptedPayload: ByteArray) {
        val ch = secureChannel ?: run {
            Log.w(TAG, "[$vehicleId] Received CLIENT_MESSAGE before secure channel"); return
        }
        val plaintext = ch.decrypt(encryptedPayload) ?: run {
            Log.w(TAG, "[$vehicleId] Decryption failed"); return
        }

        val (tdType, tdPayload) = CdpMessages.parseTrustedDeviceMessage(plaintext) ?: run {
            Log.w(TAG, "[$vehicleId] Unknown feature message: ${plaintext.take(8).joinToString(" ") { "%02X".format(it) }}")
            return
        }
        Log.d(TAG, "[$vehicleId] TrustedDevice type=$tdType payload=${tdPayload.size}B")

        when (tdType) {
            CdpMessages.TD_ESCROW_TOKEN -> {
                // Vehicle is sending an escrow token during initial association
                // The 'handle' is typically the first 8 bytes, token is the rest
                val handle = if (tdPayload.size >= 8) tdPayload.copyOfRange(0, 8) else tdPayload
                val token  = tdPayload
                Log.i(TAG, "[$vehicleId] Escrow token received (${token.size}B), handle (${handle.size}B)")
                listener.onEscrowToken(vehicleId, token, handle)
                // Send ACK
                sendTrustedDeviceMessage(CdpMessages.buildTrustedDeviceMessage(CdpMessages.TD_ACK))
            }

            CdpMessages.TD_ACK -> {
                Log.i(TAG, "[$vehicleId] ACK received — association complete")
                state = State.SECURE
                listener.onAssociationSuccess(vehicleId)
            }

            CdpMessages.TD_STATE -> {
                Log.d(TAG, "[$vehicleId] STATE message: ${tdPayload.joinToString(" ") { "%02X".format(it) }}")
                // State update from vehicle (lock/unlock status) — promote to SECURE
                if (state == State.AWAITING_VISUAL_CONFIRM || state == State.SENT_CLIENT_FINISH) {
                    state = State.SECURE
                    listener.onSecureChannelEstablished(this, needsVisualConfirm = false, pinHex = "", authBytes = ByteArray(0))
                }
            }

            CdpMessages.TD_UNLOCK_NOTIFICATION -> {
                val success = tdPayload.firstOrNull()?.toInt() == 0
                Log.i(TAG, "[$vehicleId] Unlock notification success=$success")
                listener.onCommandAck(vehicleId, success)
            }

            else -> Log.d(TAG, "[$vehicleId] Unknown TrustedDevice type $tdType")
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────────

    private fun sendTrustedDeviceMessage(plaintext: ByteArray) {
        val ch = secureChannel ?: run {
            Log.w(TAG, "[$vehicleId] sendTrustedDeviceMessage called before secure channel"); return
        }
        val encrypted = ch.encrypt(plaintext)
        val deviceMessage = CdpMessages.wrapClientMessage(encrypted)
        val framed = ProtoUtil.wrapLength(deviceMessage)
        Log.d(TAG, "[$vehicleId] → TrustedDevice msg (${framed.size}B)")
        send(framed)
    }

    private fun failSession(reason: String) {
        Log.e(TAG, "[$vehicleId] Session error: $reason")
        state = State.ERROR
        listener.onError(vehicleId, reason)
    }
}
