package com.pulsedrop.app.security

import android.util.Base64
import com.pulsedrop.app.model.PairedDevice
import java.security.SecureRandom

/**
 * Short-lived tokens that prove a request came from a device holding the pairwise key.
 *
 * The pairing id itself is public — both public keys travel in the clear during pairing — so
 * it authenticates nothing on its own. These proofs do: each is a small GCM-sealed message
 * bound to one action (an upload of an exact length, a relay post or pull), time-boxed, and
 * single-use via a nonce registry so a captured token cannot be replayed inside its window.
 */
class ProofTokens(private val repo: PairRepository) {

    private val usedProofs = LinkedHashMap<String, Long>()

    /**
     * Proves the sender holds the pairwise key, so a paired app can push a file to a phone
     * that has "Require PIN" turned on without ever learning the PIN. The request length is
     * part of the message so one valid proof cannot be reused to push a different payload.
     */
    fun uploadProof(pid: String, contentLength: Long): String? {
        val pairing = repo.findPairing(pid) ?: return null
        return try {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val payload = "$PROOF_VERSION|$pid|$contentLength|${System.currentTimeMillis()}|${Base64.encodeToString(nonce, Base64.NO_WRAP)}"
            val (iv, ct) = Crypto.encryptText(repo.secretKey(pairing), payload)
            "$iv.$ct"
        } catch (e: Exception) {
            null
        }
    }

    /** Validates a proof against the pairwise key and binds it to this exact upload. */
    fun verifyUploadProof(pid: String, contentLength: Long, proof: String): Boolean {
        val pairing = repo.findPairing(pid) ?: return false
        return try {
            val parts = proof.split('.')
            if (parts.size != 2) return false
            val plain = Crypto.decryptText(repo.secretKey(pairing), parts[0], parts[1])
            val fields = plain.split('|')
            if (fields.size != 5 || fields[0] != PROOF_VERSION) return false
            if (fields[1] != pid || fields[2] != contentLength.toString()) return false
            val issuedAt = fields[3].toLongOrNull() ?: return false
            val age = System.currentTimeMillis() - issuedAt
            if (age > PROOF_TTL_MS || age < -PROOF_TTL_MS) return false
            if (consumeNonce(parts[0] + '|' + fields[4])) return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Proves to somebody else's server that we hold one of its pairings, so a host will carry
     * our mail without becoming a party to it. The claimed device id travels *inside* the
     * ciphertext, so the host learns who is speaking from something only that device could
     * have produced — the `from` on an envelope is never taken on trust. `pull` is signed
     * separately from `post` so a stolen post token cannot empty a mailbox.
     */
    private fun relayProof(pid: String, role: String): String? {
        val pairing = repo.findPairing(pid) ?: return null
        return try {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val payload = "$RELAY_VERSION|$pid|$role|${repo.myDeviceId()}|${System.currentTimeMillis()}|" +
                Base64.encodeToString(nonce, Base64.NO_WRAP)
            val (iv, ct) = Crypto.encryptText(repo.secretKey(pairing), payload)
            "$iv.$ct"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Signs a relay request with the key shared with [hostDeviceId] — the host whose server we
     * are talking to. Returns its pairing id and the token, or null when we are not paired with
     * that host, since an unpaired device has nothing to say to its mailbox.
     */
    fun relayAuth(hostDeviceId: String, role: String): Pair<String, String>? {
        val pairing = repo.findPairingByDeviceId(hostDeviceId) ?: return null
        val proof = relayProof(pairing.pid, role) ?: return null
        return pairing.pid to proof
    }

    /**
     * Server side: validates a relay token and returns the device id it was signed for, or null.
     * This is the only place the claimed sender identity is believed.
     */
    fun verifyRelayAuth(pid: String, role: String, proof: String): String? {
        val pairing = repo.findPairing(pid) ?: return null
        return try {
            val parts = proof.split('.')
            if (parts.size != 2) return null
            val plain = Crypto.decryptText(repo.secretKey(pairing), parts[0], parts[1])
            val fields = plain.split('|')
            // version|pid|role|deviceId|issuedAt|nonce
            if (fields.size != 6 || fields[0] != RELAY_VERSION) return null
            if (fields[1] != pid || fields[2] != role) return null
            if (fields[3] != repo.deviceIdOf(pairing)) return null
            val issuedAt = fields[4].toLongOrNull() ?: return null
            val age = System.currentTimeMillis() - issuedAt
            if (age > PROOF_TTL_MS || age < -PROOF_TTL_MS) return null
            if (consumeNonce(parts[0] + "|r|" + fields[5])) return null
            fields[3]
        } catch (e: Exception) {
            null
        }
    }

    /** True when the nonce had already been seen, i.e. this is a replay. */
    private fun consumeNonce(nonceKey: String): Boolean = synchronized(usedProofs) {
        val now = System.currentTimeMillis()
        usedProofs.entries.filter { it.value <= now }.map { it.key }.forEach { usedProofs.remove(it) }
        while (usedProofs.size > 64) usedProofs.remove(usedProofs.keys.first())
        if (usedProofs.containsKey(nonceKey)) true else {
            usedProofs[nonceKey] = now + PROOF_TTL_MS
            false
        }
    }

    private val random = SecureRandom()

    companion object {
        private const val PROOF_VERSION = "pulsedrop-upload-v1"
        private const val RELAY_VERSION = "pulsedrop-relay-v1"
        private const val PROOF_TTL_MS = 120_000L
    }
}
