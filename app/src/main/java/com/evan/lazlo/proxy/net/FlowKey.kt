package com.evan.lazlo.proxy.net

/**
 * Identifies one TCP (or UDP) flow by its 4-tuple, from the originating
 * app's point of view: [sourceAddress]/[sourcePort] is the app's own
 * TUN-assigned address and ephemeral port, [destinationAddress]/
 * [destinationPort] is the real remote host it's trying to reach.
 *
 * IPv4 addresses are packed into an [Int] rather than kept as
 * [ByteArray] specifically so this can be a plain data class: arrays
 * don't get structural equals()/hashCode() from Kotlin's data class
 * codegen, which would silently break using this as a HashMap key.
 */
data class FlowKey(
    val sourceAddress: Int,
    val sourcePort: Int,
    val destinationAddress: Int,
    val destinationPort: Int,
) {
    companion object {
        fun of(source: IpV4Packet, sourcePort: Int, destinationPort: Int) = FlowKey(
            sourceAddress = source.sourceAddress.toIpInt(),
            sourcePort = sourcePort,
            destinationAddress = source.destinationAddress.toIpInt(),
            destinationPort = destinationPort,
        )
    }
}

fun ByteArray.toIpInt(): Int =
    ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

fun Int.toIpBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    this.toByte(),
)
