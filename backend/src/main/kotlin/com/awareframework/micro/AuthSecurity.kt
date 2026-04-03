package com.awareframework.micro

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AuthSecurity {
  private const val HASH_ALGO = "PBKDF2WithHmacSHA256"
  private const val DEFAULT_ITERATIONS = 210_000
  private const val KEY_LENGTH_BITS = 256
  private const val SALT_BYTES = 16
  private const val TOKEN_BYTES = 32
  private const val HASH_PREFIX = "pbkdf2"

  private val secureRandom = SecureRandom()

  fun hashPassword(password: String): String {
    val salt = ByteArray(SALT_BYTES)
    secureRandom.nextBytes(salt)
    val hash = pbkdf2(password.toCharArray(), salt, DEFAULT_ITERATIONS, KEY_LENGTH_BITS)
    val saltB64 = Base64.getEncoder().encodeToString(salt)
    val hashB64 = Base64.getEncoder().encodeToString(hash)
    return "$HASH_PREFIX$$DEFAULT_ITERATIONS$$saltB64$$hashB64"
  }

  fun verifyPassword(password: String, storedHash: String?): Boolean {
    if (storedHash.isNullOrBlank()) return false

    val parts = storedHash.split("$")
    if (parts.size != 4 || parts[0] != HASH_PREFIX) return false

    val iterations = parts[1].toIntOrNull() ?: return false
    val salt = decodeBase64(parts[2]) ?: return false
    val expected = decodeBase64(parts[3]) ?: return false
    val actual = pbkdf2(password.toCharArray(), salt, iterations, expected.size * 8)
    return MessageDigest.isEqual(expected, actual)
  }

  fun generateSessionToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  fun hashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(token.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
    val spec = PBEKeySpec(password, salt, iterations, bits)
    val skf = SecretKeyFactory.getInstance(HASH_ALGO)
    return skf.generateSecret(spec).encoded
  }

  private fun decodeBase64(value: String): ByteArray? {
    return try {
      Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

