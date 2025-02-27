@file:Suppress("unused")

package one.mixin.bot.util

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.math.FieldElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import one.mixin.bot.extension.base64Decode
import one.mixin.bot.extension.base64Encode
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.whispersystems.curve25519.Curve25519
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and
import kotlin.experimental.or

fun generateRSAKeyPair(keyLength: Int = 2048): KeyPair {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(keyLength)
    return kpg.genKeyPair()
}

fun generateEd25519KeyPair(): KeyPair {
    return net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair()
}

fun calculateAgreement(publicKey: ByteArray, privateKey: ByteArray): ByteArray {
    return Curve25519.getInstance(Curve25519.BEST).calculateAgreement(publicKey, privateKey)
}

fun privateKeyToCurve25519(edSeed: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-512")
    val h = md.digest(edSeed).sliceArray(IntRange(0, 31))
    h[0] = h[0] and 248.toByte()
    h[31] = h[31] and 127
    h[31] = h[31] or 64
    return h
}

internal val ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

fun getEdDSAPrivateKeyFromString(base64: String): EdDSAPrivateKey {
    val privateSpec = EdDSAPrivateKeySpec(base64.base64Decode().copyOfRange(0, 32), ed25519)
    return EdDSAPrivateKey(privateSpec)
}

fun decryASEKey(src: String, privateKey: EdDSAPrivateKey): String? {
    return Base64.getEncoder().encodeToString(
        calculateAgreement(
            Base64.getUrlDecoder().decode(src),
            privateKeyToCurve25519(privateKey.seed)
        )
    )
}

private val secureRandom: SecureRandom = SecureRandom()
private const val GCM_IV_LENGTH = 12

fun generateAesKey(): ByteArray {
    val key = ByteArray(16)
    secureRandom.nextBytes(key)
    return key
}

fun publicKeyToCurve25519(publicKey: EdDSAPublicKey): ByteArray {
    val p = publicKey.abyte.map { it.toInt().toByte() }.toByteArray()
    val public = EdDSAPublicKey(EdDSAPublicKeySpec(p, ed25519))
    val groupElement = public.a
    val x = edwardsToMontgomeryX(groupElement.y)
    return x.toByteArray()
}
private fun edwardsToMontgomeryX(y: FieldElement): FieldElement {
    val field = ed25519.curve.field
    var oneMinusY = field.ONE
    oneMinusY = oneMinusY.subtract(y)
    oneMinusY = oneMinusY.invert()

    var outX = field.ONE
    outX = outX.add(y)

    return oneMinusY.multiply(outX)
}

fun aesGcmEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
    val iv = ByteArray(GCM_IV_LENGTH)
    secureRandom.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val parameterSpec = GCMParameterSpec(128, iv) // 128 bit auth tag length
    val secretKey = SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
    val result = cipher.doFinal(plain)
    return iv.plus(result)
}

fun aesGcmDecrypt(cipherMessage: ByteArray, key: ByteArray): ByteArray {
    val secretKey = SecretKeySpec(key, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val gcmIv = GCMParameterSpec(128, cipherMessage, 0, GCM_IV_LENGTH)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmIv)
    return cipher.doFinal(cipherMessage, GCM_IV_LENGTH, cipherMessage.size - GCM_IV_LENGTH)
}

fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
    val keySpec = SecretKeySpec(key, "AES")
    val iv = ByteArray(16)
    secureRandom.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
    val result = cipher.doFinal(plain)
    return iv.plus(result)
}

fun aesDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val keySpec = SecretKeySpec(key, "AES")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
    return cipher.doFinal(ciphertext)
}

fun rsaDecrypt(privateKey: PrivateKey, iv: String, pinToken: String): String {
    val deCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    deCipher.init(
        Cipher.DECRYPT_MODE, privateKey,
        OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
            PSource.PSpecified(iv.toByteArray())
        )
    )

    return (deCipher.doFinal(pinToken.base64Decode())).base64Encode()
}

fun getRSAPrivateKeyFromString(privateKeyPEM: String): PrivateKey {
    Security.addProvider(BouncyCastleProvider())
    val striped = stripRsaPrivateKeyHeaders(privateKeyPEM)
    val keySpec = PKCS8EncodedKeySpec(striped.base64Decode())
    val kf = KeyFactory.getInstance("RSA")
    return kf.generatePrivate(keySpec)
}

private fun stripRsaPrivateKeyHeaders(privatePem: String): String {
    val strippedKey = StringBuilder()
    val lines = privatePem.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    lines.filter { line ->
        !line.contains("BEGIN RSA PRIVATE KEY") &&
            !line.contains("END RSA PRIVATE KEY") && line.trim { it <= ' ' }.isNotEmpty()
    }
        .forEach { line -> strippedKey.append(line.trim { it <= ' ' }) }
    return strippedKey.toString().trim { it <= ' ' }
}
