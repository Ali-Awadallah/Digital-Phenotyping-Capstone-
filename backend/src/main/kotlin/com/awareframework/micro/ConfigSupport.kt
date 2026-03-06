package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

private const val CONFIG_PATH_ENV = "AWARE_CONFIG_PATH"

fun awareConfigPath(): String {
  val configured = System.getenv(CONFIG_PATH_ENV)?.trim()
  if (!configured.isNullOrEmpty()) return configured

  val defaultPath = Path.of("aware-config.json")
  if (Files.isRegularFile(defaultPath)) return defaultPath.toString()

  val dockerPath = Path.of("aware-config-docker.json")
  if (Files.isRegularFile(dockerPath)) return dockerPath.toString()

  return defaultPath.toString()
}

fun awareConfigRetriever(vertx: Vertx, scanPeriodMs: Long = 5000): ConfigRetriever {
  val configStore = ConfigStoreOptions()
    .setType("file")
    .setFormat("json")
    .setConfig(JsonObject().put("path", awareConfigPath()))

  val options = ConfigRetrieverOptions()
    .addStore(configStore)
    .setScanPeriod(scanPeriodMs)

  return ConfigRetriever.create(vertx, options)
}
