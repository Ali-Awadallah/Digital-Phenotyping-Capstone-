package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import java.net.URL

class WebsocketVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}

  private lateinit var parameters: JsonObject
  private val clients = mutableSetOf<ServerWebSocket>()

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val configReader = awareConfigRetriever(vertx)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()

        val serverConfig = parameters.getJsonObject("server")

        // Listen for battery updates from event bus
        vertx.eventBus().consumer<JsonObject>("battery.update") { message ->
          val update = message.body()
          broadcast(JsonObject().put("type", "battery_update").put("data", update))
        }

        // Listen for alert updates so dashboard can refresh instantly.
        vertx.eventBus().consumer<JsonObject>("alerts.changed") { message ->
          val update = message.body()
          broadcast(JsonObject().put("type", "alert_update").put("data", update))
        }

        vertx.createHttpServer()
          .webSocketHandler { server ->
            logger.info { "Websocket connected" }
            clients.add(server)

            server.closeHandler {
              logger.info { "Websocket connection closed" }
              clients.remove(server)
            }

            server.exceptionHandler { e ->
              logger.error(e) { "Websocket error" }
              clients.remove(server)
            }

            server.textMessageHandler { message ->
              // Optional: handle incoming messages if needed
              // For now just echo or ignore
              // server.writeTextMessage("Echo: $message")
            }
          }
          .listen(getExternalWebSocketServerPort(serverConfig)) {
            if (it.failed()) {
              logger.error(it.cause()) { "Failed to initialise websocket server." }
            } else {
              logger.info { "AWARE Micro Websocket server: ws://${getExternalWebSocketServerHost(serverConfig)}:${getExternalWebSocketServerPort(serverConfig)}" }
            }
          }
      }
    }
  }

  private fun broadcast(message: JsonObject) {
    val text = message.encode()
    clients.forEach { socket ->
      try {
        socket.writeTextMessage(text)
      } catch (e: Exception) {
        logger.error(e) { "Failed to send websocket message" }
      }
    }
  }

  private fun getExternalWebSocketServerHost(serverConfig: JsonObject): String {
    if (serverConfig.containsKey("external_server_host")) {
      return URL(serverConfig.getString("external_server_host")).host
    }
    return URL(serverConfig.getString("server_host")).host
  }

  private fun getExternalWebSocketServerPort(serverConfig: JsonObject): Int {
    if (serverConfig.containsKey("external_websocket_port")) {
      return serverConfig.getInteger("external_websocket_port")
    }
    return serverConfig.getInteger("websocket_port")
  }
}
