package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SensitiveDataCipher {
  private val logger = KotlinLogging.logger {}
  private const val PREFIX = "enc:v1:"
  private const val ALGO = "AES/GCM/NoPadding"
  private const val NONCE_BYTES = 12
  private const val TAG_BITS = 128

  private val secureRandom = SecureRandom()

  private val keySpec: SecretKeySpec? by lazy {
    val raw = SecretResolver.get("DATA_ENCRYPTION_KEY_B64")
    if (raw.isNullOrBlank()) {
      logger.warn { "DATA_ENCRYPTION_KEY_B64 is not set; sensitive field encryption is disabled." }
      null
    } else {
      try {
        val key = Base64.getDecoder().decode(raw.trim())
        if (key.size != 32) {
          logger.error { "DATA_ENCRYPTION_KEY_B64 must decode to exactly 32 bytes (AES-256)." }
          null
        } else {
          SecretKeySpec(key, "AES")
        }
      } catch (e: Exception) {
        logger.error(e) { "Invalid DATA_ENCRYPTION_KEY_B64; sensitive field encryption is disabled." }
        null
      }
    }
  }

  fun isEnabled(): Boolean = keySpec != null

  fun encrypt(value: String?): String? {
    if (value == null) return null
    if (value.isBlank()) return value
    if (!isEnabled()) return value
    if (value.startsWith(PREFIX)) return value

    val iv = ByteArray(NONCE_BYTES)
    secureRandom.nextBytes(iv)
    val cipher = Cipher.getInstance(ALGO)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val payload = ByteArray(iv.size + encrypted.size)
    System.arraycopy(iv, 0, payload, 0, iv.size)
    System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
    return PREFIX + Base64.getEncoder().encodeToString(payload)
  }

  fun decrypt(value: String?): String? {
    if (value == null) return null
    if (!value.startsWith(PREFIX)) return value
    if (!isEnabled()) return value

    return try {
      val payload = Base64.getDecoder().decode(value.removePrefix(PREFIX))
      if (payload.size <= NONCE_BYTES) return value
      val iv = payload.copyOfRange(0, NONCE_BYTES)
      val encrypted = payload.copyOfRange(NONCE_BYTES, payload.size)
      val cipher = Cipher.getInstance(ALGO)
      cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
      logger.warn(e) { "Failed to decrypt value with current key; returning stored value." }
      value
    }
  }
}
