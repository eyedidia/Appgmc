package com.gmc.digitalkey.ble.companion

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format encoder/decoder for UKEY2 and CDP messages.
 * Implements only what is needed: varint (wire type 0) and length-delimited (wire type 2).
 */
internal object ProtoUtil {

    private const val WIRE_VARINT = 0
    private const val WIRE_LEN_DELIM = 2

    // ── Encoding ──────────────────────────────────────────────────────────────

    fun encodeVarint(value: Long): ByteArray {
        val buf = ByteArray(10)
        var v = value
        var pos = 0
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            buf[pos++] = b.toByte()
        } while (v != 0L)
        return buf.copyOf(pos)
    }

    fun encodeVarint(value: Int) = encodeVarint(value.toLong())

    /** Encode a varint field: tag + value. */
    fun varintField(fieldNum: Int, value: Int): ByteArray =
        encodeVarint(((fieldNum shl 3) or WIRE_VARINT).toLong()) + encodeVarint(value)

    /** Encode a bool field (bool = varint 0/1). */
    fun boolField(fieldNum: Int, value: Boolean) = varintField(fieldNum, if (value) 1 else 0)

    /** Encode a bytes/string/embedded-message field: tag + varint length + raw bytes. */
    fun bytesField(fieldNum: Int, data: ByteArray): ByteArray {
        val tag = encodeVarint(((fieldNum shl 3) or WIRE_LEN_DELIM).toLong())
        return tag + encodeVarint(data.size) + data
    }

    fun stringField(fieldNum: Int, s: String) = bytesField(fieldNum, s.toByteArray(Charsets.UTF_8))

    // ── Decoding ──────────────────────────────────────────────────────────────

    /**
     * Parse a serialized proto into a map of field number → list of raw payloads.
     * Varint fields are returned as little-endian encoded bytes (use [varintValue] to read them).
     * Length-delimited fields are returned as their raw byte content.
     */
    fun parseFields(bytes: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var i = 0
        while (i < bytes.size) {
            val (tagVal, after) = readVarint(bytes, i)
            i = after
            val wireType = (tagVal and 0x7L).toInt()
            val fieldNum = (tagVal ushr 3).toInt()
            when (wireType) {
                WIRE_VARINT -> {
                    val (v, next) = readVarint(bytes, i)
                    i = next
                    result.getOrPut(fieldNum) { mutableListOf() }.add(encodeVarint(v))
                }
                WIRE_LEN_DELIM -> {
                    val (len, next) = readVarint(bytes, i)
                    i = next
                    val data = bytes.copyOfRange(i, i + len.toInt())
                    i += len.toInt()
                    result.getOrPut(fieldNum) { mutableListOf() }.add(data)
                }
                else -> break
            }
        }
        return result
    }

    fun getBytes(fields: Map<Int, List<ByteArray>>, fieldNum: Int): ByteArray? =
        fields[fieldNum]?.firstOrNull()

    fun getRepeatedBytes(fields: Map<Int, List<ByteArray>>, fieldNum: Int): List<ByteArray> =
        fields[fieldNum] ?: emptyList()

    fun varintValue(raw: ByteArray): Long = readVarint(raw, 0).first

    fun getInt(fields: Map<Int, List<ByteArray>>, fieldNum: Int): Int? {
        val raw = fields[fieldNum]?.firstOrNull() ?: return null
        return readVarint(raw, 0).first.toInt()
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < bytes.size) {
            val b = bytes[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        return result to i
    }

    // ── CDP BLE framing: 4-byte big-endian length prefix ──────────────────────

    /** Prepend 4-byte big-endian length to a serialized proto. */
    fun wrapLength(proto: ByteArray): ByteArray {
        val n = proto.size
        return byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(),
                            (n shr 8).toByte(), n.toByte()) + proto
    }

    /** Strip 4-byte length prefix and return proto bytes, or null if packet too short. */
    fun unwrapLength(packet: ByteArray): ByteArray? {
        if (packet.size < 4) return null
        val n = ((packet[0].toInt() and 0xFF) shl 24) or
                ((packet[1].toInt() and 0xFF) shl 16) or
                ((packet[2].toInt() and 0xFF) shl 8) or
                 (packet[3].toInt() and 0xFF)
        if (n < 0 || packet.size < 4 + n) return null
        return packet.copyOfRange(4, 4 + n)
    }

    // ── Helper: ByteArrayOutputStream concatenation ───────────────────────────

    fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }
}
