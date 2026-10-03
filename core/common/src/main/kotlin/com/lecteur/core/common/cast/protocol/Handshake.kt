package com.lecteur.core.common.cast.protocol

import java.security.MessageDigest
import java.security.SecureRandom

/** The 6-digit code the TV shows so that only someone in the room can drive it. */
object PairingCode {
    const val LENGTH = 6

    fun generate(random: SecureRandom = SecureRandom()): String =
        (0 until LENGTH).joinToString("") { random.nextInt(10).toString() }

    /** Tolerates the spaces a user may type around digits; compares in constant time. */
    fun matches(expected: String, typed: String?): Boolean {
        if (typed == null) return false
        val cleaned = typed.filter(Char::isDigit)
        return MessageDigest.isEqual(expected.toByteArray(), cleaned.toByteArray())
    }
}

object Handshake {

    /** Highest protocol version both sides speak, or null when the ranges do not overlap. */
    fun negotiate(localVersion: Int, localMin: Int, remoteVersion: Int, remoteMin: Int): Int? {
        val agreed = minOf(localVersion, remoteVersion)
        return if (agreed >= localMin && agreed >= remoteMin) agreed else null
    }

    /**
     * The receiver's decision about a controller's [Hello]. [expectedCode] null means pairing is not enforced
     * (never the case on a real TV; it exists for tests and for a future "trusted network" setting).
     */
    fun answer(hello: Hello, deviceName: String, expectedCode: String?): Welcome {
        fun refuse(reason: HelloRefusal) = Welcome(accepted = false, deviceName = deviceName, refusal = reason)

        if (hello.role != PeerRole.CONTROLLER) return refuse(HelloRefusal.WRONG_ROLE)
        val version = negotiate(PROTOCOL_VERSION, MIN_PROTOCOL_VERSION, hello.version, hello.minVersion)
            ?: return refuse(HelloRefusal.INCOMPATIBLE_VERSION)
        if (expectedCode != null && !PairingCode.matches(expectedCode, hello.pairingCode)) {
            return refuse(HelloRefusal.BAD_PAIRING_CODE)
        }
        return Welcome(accepted = true, version = version, deviceName = deviceName)
    }
}
