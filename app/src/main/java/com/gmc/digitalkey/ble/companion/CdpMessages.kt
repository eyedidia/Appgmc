package com.gmc.digitalkey.ble.companion

/**
 * CDP (Connected Device Platform) message builders.
 *
 * All messages are wrapped in a DeviceMessage proto before being sent over the D2D channel.
 * UKEY2 handshake messages use OperationType.ENCRYPTION_HANDSHAKE (4).
 * Feature messages (e.g. lock/unlock) use OperationType.CLIENT_MESSAGE (1).
 *
 * Proto schemas (companion library / aae.companion namespace):
 *
 *   DeviceMessage {
 *     bytes payload = 1;
 *     bool is_message_visible_to_receivers = 2;
 *     OperationType operation_type = 3;  // CLIENT_MESSAGE=1, ENCRYPTION_HANDSHAKE=4
 *     int32 recipient = 4;
 *     bool is_compressed = 5;
 *   }
 *
 *   TrustedDeviceMessage {
 *     int32 version = 1;
 *     MessageType type = 2;  // ESCROW_TOKEN=1, UNLOCK_CREDENTIALS=2, STATE=3, ACK=4
 *     bytes payload = 3;
 *   }
 *
 *   PhoneCredentials { bytes handle = 1; bytes reconnect_challenge = 2; }
 *   UnlockCredentials { PhoneCredentials phone_credentials = 1; bytes encrypted_token = 2; }
 */
internal object CdpMessages {

    // DeviceMessage.OperationType values
    const val OP_CLIENT_MESSAGE        = 1
    const val OP_ENCRYPTION_HANDSHAKE  = 4

    // TrustedDeviceMessage.MessageType values
    const val TD_ESCROW_TOKEN       = 1
    const val TD_UNLOCK_CREDENTIALS = 2
    const val TD_STATE              = 3
    const val TD_ACK                = 4
    const val TD_UNLOCK_NOTIFICATION = 5

    // TrustedDevice feature recipient index (matches companion library default)
    const val TRUSTED_DEVICE_RECIPIENT = 2

    // ── DeviceMessage ─────────────────────────────────────────────────────────

    /** Wrap raw UKEY2 handshake bytes in a DeviceMessage (ENCRYPTION_HANDSHAKE type). */
    fun wrapHandshake(ukey2Bytes: ByteArray): ByteArray =
        ProtoUtil.concat(
            ProtoUtil.bytesField(1, ukey2Bytes),
            ProtoUtil.boolField(2, true),
            ProtoUtil.varintField(3, OP_ENCRYPTION_HANDSHAKE)
        )

    /** Wrap encrypted feature payload in a DeviceMessage (CLIENT_MESSAGE type). */
    fun wrapClientMessage(encryptedPayload: ByteArray, recipient: Int = TRUSTED_DEVICE_RECIPIENT): ByteArray =
        ProtoUtil.concat(
            ProtoUtil.bytesField(1, encryptedPayload),
            ProtoUtil.boolField(2, false),
            ProtoUtil.varintField(3, OP_CLIENT_MESSAGE),
            ProtoUtil.varintField(4, recipient)
        )

    /** Unwrap a DeviceMessage: returns (operationType, payload) or null if malformed. */
    fun unwrap(bytes: ByteArray): Pair<Int, ByteArray>? {
        val fields = ProtoUtil.parseFields(bytes)
        val payload = ProtoUtil.getBytes(fields, 1) ?: return null
        val opType  = ProtoUtil.getInt(fields, 3) ?: 0
        return opType to payload
    }

    // ── TrustedDeviceMessage ──────────────────────────────────────────────────

    /** Build a TrustedDeviceMessage proto. */
    fun buildTrustedDeviceMessage(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ProtoUtil.concat(
            ProtoUtil.varintField(1, 1),       // version = 1
            ProtoUtil.varintField(2, type),
            ProtoUtil.bytesField(3, payload)
        )

    /** Parse a TrustedDeviceMessage: returns (type, payload) or null. */
    fun parseTrustedDeviceMessage(bytes: ByteArray): Pair<Int, ByteArray>? {
        val fields = ProtoUtil.parseFields(bytes)
        val type    = ProtoUtil.getInt(fields, 2) ?: return null
        val payload = ProtoUtil.getBytes(fields, 3) ?: ByteArray(0)
        return type to payload
    }

    // ── UnlockCredentials ─────────────────────────────────────────────────────

    /**
     * Build an UnlockCredentials payload to send inside a TrustedDeviceMessage(UNLOCK_CREDENTIALS).
     *
     * @param handle       8-byte handle stored from initial pairing (credentials.handle column)
     * @param encryptedToken AES-encrypted token from initial pairing (credentials.token column)
     */
    fun buildUnlockCredentials(handle: ByteArray, encryptedToken: ByteArray): ByteArray {
        // PhoneCredentials { handle(1) }
        val phoneCreds = ProtoUtil.bytesField(1, handle)
        // UnlockCredentials { phone_credentials(1), encrypted_token(2) }
        return ProtoUtil.concat(
            ProtoUtil.bytesField(1, phoneCreds),
            ProtoUtil.bytesField(2, encryptedToken)
        )
    }

    /**
     * Build the full TrustedDevice unlock message (not yet encrypted).
     * Encrypt this with D2dSecureChannel.encrypt() before wrapping in wrapClientMessage().
     */
    fun buildUnlockMessage(handle: ByteArray, encryptedToken: ByteArray): ByteArray =
        buildTrustedDeviceMessage(TD_UNLOCK_CREDENTIALS, buildUnlockCredentials(handle, encryptedToken))

    /**
     * Parse an escrow token response from the vehicle.
     * The vehicle sends TrustedDeviceMessage(ESCROW_TOKEN, <token bytes>) during initial pairing.
     */
    fun parseEscrowToken(tdPayload: ByteArray): ByteArray? {
        val (type, payload) = parseTrustedDeviceMessage(tdPayload) ?: return null
        if (type != TD_ESCROW_TOKEN) return null
        return if (payload.isNotEmpty()) payload else null
    }
}
