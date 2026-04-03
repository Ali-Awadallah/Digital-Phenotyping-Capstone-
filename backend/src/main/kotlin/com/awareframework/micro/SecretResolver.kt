package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Paths

object SecretResolver {
  private val logger = KotlinLogging.logger {}

  fun get(name: String, fallback: String? = null): String? {
    val fileEnv = System.getenv("${name}_FILE")?.trim()?.takeIf { it.isNotEmpty() }
    if (fileEnv != null) {
      try {
        val value = Files.readString(Paths.get(fileEnv)).trim()
        if (value.isNotEmpty()) return value
      } catch (e: Exception) {
        logger.warn(e) { "Failed to read secret file for $name from $fileEnv" }
      }
    }

    val direct = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    if (direct != null) return direct

    return fallback?.trim()?.takeIf { it.isNotEmpty() }
  }
}

