@file:JvmName("BIP32")

package org.kethereum.bip32

import org.kethereum.bip32.model.CHAINCODE_SIZE
import org.kethereum.bip32.model.ExtendedKey
import org.kethereum.bip32.model.Seed
import org.kethereum.bip44.BIP44
import org.kethereum.bip44.BIP44Element
import org.kethereum.crypto.CURVE
import org.kethereum.crypto.getCompressedPublicKey
import org.kethereum.crypto.model.ECKeyPair
import org.kethereum.crypto.model.PRIVATE_KEY_SIZE
import org.kethereum.crypto.model.PrivateKey
import org.kethereum.crypto.toECKeyPair
import org.kethereum.crypto.toPublicKey
import org.kethereum.extensions.toBytesPadded
import org.kethereum.hashes.ripemd160
import org.kethereum.hashes.sha256
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidKeyException
import java.security.KeyException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun Seed.toKey(pathString: String) = BIP44(pathString).path
        .fold(toExtendedKey()) { current, bip44Element ->
            current.generateChildKey(bip44Element)
        }


/**
 * Gets an [Int] representation of public key hash
 * @return an Int built from the first 4 bytes of the result of hash160 over the compressed public key
 */
private fun ECKeyPair.computeFingerPrint(): Int {
    val pubKeyHash = getCompressedPublicKey()
            .sha256()
            .ripemd160()
    var fingerprint = 0
    for (i in 0..3) {
        fingerprint = fingerprint shl 8
        fingerprint = fingerprint or (pubKeyHash[i].toInt() and 0xff)
    }
    return fingerprint
}

fun ExtendedKey.generateChildKey(element: BIP44Element): ExtendedKey {
    try {
        if (element.hardened && keyPair.privateKey.key == BigInteger.ZERO) {
            throw IllegalArgumentException("need private key for private generation using hardened paths")
        }
        val mac = Mac.getInstance("HmacSHA512")
        val key = SecretKeySpec(chainCode, "HmacSHA512")
        mac.init(key)

        val extended: ByteArray
        val pub = keyPair.getCompressedPublicKey()
        if (element.hardened) {
            val privateKeyPaddedBytes = keyPair.privateKey.key.toBytesPadded(PRIVATE_KEY_SIZE)

            extended = ByteBuffer
                    .allocate(privateKeyPaddedBytes.size + 5)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0)
                    .put(privateKeyPaddedBytes)
                    .putInt(element.numberWithHardeningFlag)
                    .array()
        } else {
            //non-hardened
            extended = ByteBuffer
                    .allocate(pub.size + 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(pub)
                    .putInt(element.numberWithHardeningFlag)
                    .array()
        }
        val lr = mac.doFinal(extended)
        val l = Arrays.copyOfRange(lr, 0, PRIVATE_KEY_SIZE)
        val r = Arrays.copyOfRange(lr, PRIVATE_KEY_SIZE, PRIVATE_KEY_SIZE + CHAINCODE_SIZE)

        val m = BigInteger(1, l)
        if (m >= CURVE.n) {
            throw KeyException("Child key derivation resulted in a key with higher modulus. Suggest deriving the next increment.")
        }

        return if (keyPair.privateKey.key != BigInteger.ZERO) {
            val k = m.add(keyPair.privateKey.key).mod(CURVE.n)
            if (k == BigInteger.ZERO) {
                throw KeyException("Child key derivation resulted in zeros. Suggest deriving the next increment.")
            }
            ExtendedKey(PrivateKey(k).toECKeyPair(), r, (depth + 1).toByte(), keyPair.computeFingerPrint(), element.numberWithHardeningFlag)
        } else {
            val q = CURVE.g.multiply(m).add(CURVE.curve.decodePoint(pub)).normalize()
            if (q.isInfinity) {
                throw KeyException("Child key derivation resulted in zeros. Suggest deriving the next increment.")
            }
            val point = CURVE.curve.createPoint(q.xCoord.toBigInteger(), q.yCoord.toBigInteger())

            ExtendedKey(ECKeyPair(PrivateKey(BigInteger.ZERO), point.toPublicKey()), r, (depth + 1).toByte(), keyPair.computeFingerPrint(), element.numberWithHardeningFlag)
        }
    } catch (e: NoSuchAlgorithmException) {
        throw KeyException(e)
    } catch (e: NoSuchProviderException) {
        throw KeyException(e)
    } catch (e: InvalidKeyException) {
        throw KeyException(e)
    }

}

