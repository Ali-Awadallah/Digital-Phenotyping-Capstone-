package com.awareframework.micro

import com.mitchellbosecke.pebble.PebbleEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.RoutingContext


class MainVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}
  private val adminApiPrefixes = listOf("/participants", "/zones", "/alerts", "/signature-alerts", "/users", "/admin")
  private val allRoles = setOf("admin", "analyst", "viewer", "doctor", "ingest")
  private val adminWriteRoles = setOf("admin", "analyst")
  private val adminReadRoles = setOf("admin", "analyst", "viewer", "doctor")
  private val ingestRoles = setOf("admin", "ingest")

  private lateinit var parameters: JsonObject
  private lateinit var httpServer: HttpServer

  override fun start(startPromise: Promise<Void>) {

    logger.info { "AWARE Micro initializing..." }

    val serverOptions = HttpServerOptions().setMaxWebSocketMessageSize(1024 * 1024 * 20).setMaxChunkSize(1024 * 1024 * 50).setMaxInitialLineLength(1024 * 1024 * 50).setMaxHeaderSize(1024 * 1024 * 50);
    val pebbleEngine = PebbleTemplateEngine.create(vertx, PebbleEngine.Builder().cacheActive(false).build())
    val eventBus = vertx.eventBus()

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024 * 50));

    // 🔸 Add this CORS block:
    router.route().handler(
      CorsHandler.create("*")
        .allowedMethod(HttpMethod.GET)
        .allowedMethod(HttpMethod.POST)
        .allowedMethod(HttpMethod.OPTIONS)
        .allowedHeader("Content-Type")
        .allowedHeader("Authorization")
        .allowedHeader("X-API-Key")
    )

    router.route(HttpMethod.GET, "/testing").handler { ctx ->
      ctx.response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
        .end(JsonObject(mapOf("ok" to true)).encode())
    }


    router.route("/cache/*").handler(StaticHandler.create("cache"))

    // Serve static dashboard files
    router.route("/static/*").handler(StaticHandler.create("static").setCachingEnabled(false))
    router.route().handler {
      logger.info { "Processing ${it.request().method()} ${it.request().path()}" }
      it.next()
    }

    val configReader = awareConfigRetriever(vertx)
    configReader.getConfig { config ->

      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")
        val study = parameters.getJsonObject("study")

        // HttpServerOptions.host is the host to listen on. So using |server_host|, not |external_server_host| here.
        // See also: https://vertx.io/docs/4.3.3/apidocs/io/vertx/core/net/NetServerOptions.html#DEFAULT_HOST
        serverOptions.host = System.getenv("SERVER_HOST") ?: serverConfig.getString("server_host")

        // ---- API SUBROUTER (put BEFORE any "/:studyNumber/:studyKey" routes) ----------------------------------------------------------------------------------------------
        val api = Router.router(vertx)
        installApiAuth(api, serverConfig)

        // GET /api/testing  -> { "ok": true }
        api.get("/testing").handler { ctx ->
          ctx.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end(JsonObject().put("ok", true).encode())
        }

        // POST /api/auth/login
        api.post("/auth/login").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val username = body.getString("username", "").trim()
            val password = body.getString("password", "")
            if (username.isEmpty() || password.isEmpty()) {
              respondError(ctx, 400, "username and password are required")
              return@handler
            }

            val payload = JsonObject()
              .put("username", username)
              .put("password", password)
              .put("client_ip", ctx.request().remoteAddress()?.hostAddress())
              .put("user_agent", ctx.request().getHeader("User-Agent"))
            vertx.eventBus().request<JsonObject>("authLogin", payload) { ar ->
              if (ar.succeeded()) {
                respondJson(ctx, 200, ar.result().body())
              } else {
                val failureCode = if (ar.cause().message?.contains("Invalid credentials", ignoreCase = true) == true) {
                  401
                } else {
                  500
                }
                respondError(ctx, failureCode, ar.cause().message)
              }
            }
          } catch (e: Exception) {
            respondError(ctx, 400, e.message)
          }
        }

        // GET /api/auth/me
        api.get("/auth/me").handler { ctx ->
          val user = ctx.get<JsonObject>("auth_user")
          if (user == null) {
            respondError(ctx, 401, "Unauthorized")
          } else {
            respondJson(ctx, 200, JsonObject().put("ok", true).put("user", user))
          }
        }

        // POST /api/auth/logout
        api.post("/auth/logout").handler { ctx ->
          val token = extractBearerToken(ctx)
          if (token.isNullOrBlank()) {
            respondOk(ctx)
            return@handler
          }
          requestJsonObject(ctx, "authLogout", JsonObject().put("token", token))
        }

        // GET /api/users
        api.get("/users").handler { ctx ->
          vertx.eventBus().request<JsonArray>("listAppUsers", JsonObject()) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              respondError(ctx, 500, ar.cause().message)
            }
          }
        }

        // POST /api/users (create/update)
        api.post("/users").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val actor = ctx.get<JsonObject>("auth_user")?.getString("username", "admin") ?: "admin"
            body.put("actor", actor)
            requestJsonObject(ctx, "upsertAppUser", body)
          } catch (e: Exception) {
            respondError(ctx, 400, e.message)
          }
        }

        // GET /api/admin/security-status
        api.get("/admin/security-status").handler { ctx ->
          requestJsonObject(ctx, "getSecurityStatus", JsonObject())
        }

        // GET /api/admin/sessions?limit=200
        api.get("/admin/sessions").handler { ctx ->
          val limit = ctx.queryParam("limit").firstOrNull()?.toIntOrNull() ?: 200
          vertx.eventBus().request<JsonArray>("listAuthSessions", JsonObject().put("limit", limit)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              respondError(ctx, 500, ar.cause().message)
            }
          }
        }

        // POST /api/admin/sessions/:id/revoke
        api.post("/admin/sessions/:id/revoke").handler { ctx ->
          val sessionId = ctx.pathParam("id").toLongOrNull()
          if (sessionId == null) {
            respondError(ctx, 400, "Invalid session id")
            return@handler
          }
          requestJsonObject(ctx, "revokeAuthSessionById", JsonObject().put("id", sessionId))
        }

        // GET /api/admin/audit?limit=200
        api.get("/admin/audit").handler { ctx ->
          val limit = ctx.queryParam("limit").firstOrNull()?.toIntOrNull() ?: 200
          vertx.eventBus().request<JsonArray>("getSecurityAuditEvents", JsonObject().put("limit", limit)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              respondError(ctx, 500, ar.cause().message)
            }
          }
        }

        // GET /api/admin/login-lockouts?limit=100
        api.get("/admin/login-lockouts").handler { ctx ->
          val limit = ctx.queryParam("limit").firstOrNull()?.toIntOrNull() ?: 100
          vertx.eventBus().request<JsonArray>("getLoginLockouts", JsonObject().put("limit", limit)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              respondError(ctx, 500, ar.cause().message)
            }
          }
        }

        // POST /api/events  { device_id, ts, value }  -> insert via event bus
        api.post("/events").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val deviceId = body.getString("device_id") ?: "unknown"
            val ts = body.getLong("ts") ?: System.currentTimeMillis()
            val value = body.getString("value") ?: ""

            val record = JsonObject().put("timestamp", ts).put("value", value)
            val dataArray = JsonArray().add(record)

            vertx.eventBus().publish(
              "insertData",
              JsonObject()
                .put("table", "events")              // adjust table name if needed
                .put("device_id", deviceId)
                .put("data", dataArray.encode())     // legacy format: JSON array as string
            )

            ctx.response()
              .setStatusCode(201)
              .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
              .end(JsonObject().put("ok", true).encode())
          } catch (e: Exception) {
            logger.error(e) { "POST /api/events failed" }
            ctx.response().setStatusCode(400).end(JsonObject().put("error", e.message).encode())
          }
        }

        // GET /api/events?device_id=...&start=0&end=<ms>
        api.get("/events").handler { ctx ->
          val deviceId = ctx.queryParam("device_id").firstOrNull() ?: "unknown"
          val start = ctx.queryParam("start").firstOrNull()?.toDoubleOrNull() ?: 0.0
          val end = ctx.queryParam("end").firstOrNull()?.toDoubleOrNull() ?: System.currentTimeMillis().toDouble()

          val requestData = JsonObject()
            .put("table", "events")
            .put("device_id", deviceId)
            .put("start", start)
            .put("end", end)

          vertx.eventBus().request<JsonArray>("getData", requestData) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              logger.error(ar.cause()) { "GET /api/events failed" }
              ctx.response().setStatusCode(500).end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // POST /api/battery  -> receive real battery reading from app, store in DB
        api.post("/battery").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)

            val deviceId = body.getString("device_id") ?: "unknown"
            val percentage = body.getDouble("percentage")
            val chargingStatus = body.getString("charging_status", "unknown")
            val ts = body.getLong("ts")

            logger.info { "Battery reading from $deviceId: $percentage% ($chargingStatus) at $ts" }

            // Store battery reading in database
            val batteryData = JsonObject()
              .put("device_id", deviceId)
              .put("percentage", percentage)
              .put("charging_status", chargingStatus)
              .put("timestamp", ts)

            requestAndReplyOk(ctx, "insertBatteryReading", batteryData, "Failed to store battery reading")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/battery" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/screen  -> receive real screen state from app, store in DB
        api.post("/screen").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)

            val deviceId = body.getString("device_id") ?: "unknown"
            val state = body.getString("state") ?: "UNKNOWN" // "ON" or "OFF" (approx)
            val ts = body.getLong("ts")

            logger.info { "Screen state from $deviceId: $state at $ts" }

            // Store screen event in database
            val screenData = JsonObject()
              .put("device_id", deviceId)
              .put("state", state)
              .put("timestamp", ts)

            requestAndReplyOk(ctx, "insertScreenEvent", screenData, "Failed to store screen event")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/screen" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/notification  -> receive notification from app, store in DB
        api.post("/notification").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)

            val deviceId = body.getString("device_id") ?: "unknown"
            val appName = body.getString("app_name", "")
            val title = body.getString("title", "")
            val content = body.getString("content", "")
            val category = body.getString("category", "")
            val kind = body.getString("kind", "posted")
            val ts = body.getLong("ts")
            val dismissedAt = body.getLong("dismissed_at", 0L)

            logger.info { "Notification from $deviceId: $appName - $title ($kind) at $ts" }

            // Store notification in database
            val notificationData = JsonObject()
              .put("device_id", deviceId)
              .put("app_name", appName)
              .put("title", title)
              .put("content", content)
              .put("category", category)
              .put("kind", kind)
              .put("timestamp", ts)
              .put("dismissed_at", dismissedAt)

            requestAndReplyOk(ctx, "insertNotification", notificationData, "Failed to store notification")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/notification" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/gyroscope
        api.post("/gyroscope").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val deviceId = body.getString("device_id") ?: "unknown"
            val ts = body.getLong("ts")
            val x = body.getDouble("x")
            val y = body.getDouble("y")
            val z = body.getDouble("z")

            logger.info { "Gyro from $deviceId: x=$x y=$y z=$z" }

            val gyroData = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", ts)
              .put("x", x)
              .put("y", y)
              .put("z", z)

            requestAndReplyOk(ctx, "insertGyroscope", gyroData, "Failed to store gyroscope")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/gyroscope" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/accelerometer
        api.post("/accelerometer").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val deviceId = body.getString("device_id") ?: "unknown"
            val ts = body.getLong("ts")
            val x = body.getDouble("x")
            val y = body.getDouble("y")
            val z = body.getDouble("z")

            logger.info { "Accel from $deviceId: x=$x y=$y z=$z" }

            val accelData = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", ts)
              .put("x", x)
              .put("y", y)
              .put("z", z)

            requestAndReplyOk(ctx, "insertAccelerometer", accelData, "Failed to store accelerometer")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/accelerometer" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/pedometer
        api.post("/pedometer").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val deviceId = body.getString("device_id") ?: "unknown"
            val ts = body.getLong("ts")
            val steps = body.getLong("steps")

            val record = JsonObject()
              .put("timestamp", ts)
              .put("steps", steps)

            val dataArray = JsonArray().add(record)

            vertx.eventBus().publish(
              "insertData",
              JsonObject()
                .put("table", "pedometer")
                .put("device_id", deviceId)
                .put("data", dataArray.encode())
            )

            logger.info { "Pedometer from $deviceId: steps=$steps at $ts" }

            ctx.response()
              .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
              .end(JsonObject().put("ok", true).encode())
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/pedometer" }
            ctx.response().setStatusCode(400)
              .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
              .end(JsonObject().put("error", e.message).encode())
          }
        }

        // POST /api/location - Store location and check geofence
        api.post("/location").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val deviceId = body.getString("device_id") ?: "unknown"
            val participantId = extractParticipantId(body)
            val ts = readLongValue(body, "timestamp", "ts") ?: System.currentTimeMillis()
            val latitude = readDoubleValue(body, "latitude", "lat")
              ?: throw IllegalArgumentException("latitude is required")
            val longitude = readDoubleValue(body, "longitude", "lon", "lng")
              ?: throw IllegalArgumentException("longitude is required")
            val accuracy = readDoubleValue(body, "accuracy", "horizontal_accuracy") ?: 0.0
            val altitude = readDoubleValue(body, "altitude") ?: 0.0

            logger.info { "Location from $deviceId: lat=$latitude lon=$longitude acc=$accuracy" }

            val locationData = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", ts)
              .put("latitude", latitude)
              .put("longitude", longitude)
              .put("altitude", altitude)
              .put("accuracy", accuracy)

            ensureParticipantLink(deviceId, "phone", participantId)
            requestAndReplyOk(ctx, "insertLocation", locationData, "Failed to store location") {
              checkGeofence(deviceId, latitude, longitude)
            }
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/location" }
            respondError(ctx, 400, e.message)
          }
        }

        // ---- WEARABLE DATA API ENDPOINTS ----

        // POST /api/wearable/heart-rate
        api.post("/wearable/heart-rate").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", body.getLong("timestamp"))
              .put("bpm", body.getInteger("bpm"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableHeartRate", data, "Failed to store wearable heart rate")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/heart-rate" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/steps
        api.post("/wearable/steps").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("start_time", body.getLong("start_time"))
              .put("end_time", body.getLong("end_time"))
              .put("count", body.getInteger("count"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableSteps", data, "Failed to store wearable steps")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/steps" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/sleep
        api.post("/wearable/sleep").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("start_time", body.getLong("start_time"))
              .put("end_time", body.getLong("end_time"))
              .put("title", body.getString("title", "Sleep"))
              .put("notes", body.getString("notes", ""))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableSleep", data, "Failed to store wearable sleep")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/sleep" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/blood-pressure
        api.post("/wearable/blood-pressure").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", body.getLong("timestamp"))
              .put("systolic", body.getDouble("systolic"))
              .put("diastolic", body.getDouble("diastolic"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableBloodPressure", data, "Failed to store wearable blood pressure")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/blood-pressure" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/weight
        api.post("/wearable/weight").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", body.getLong("timestamp"))
              .put("weight_kg", body.getDouble("weight_kg"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableWeight", data, "Failed to store wearable weight")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/weight" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/oxygen
        api.post("/wearable/oxygen").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", body.getLong("timestamp"))
              .put("percentage", body.getDouble("percentage"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableOxygen", data, "Failed to store wearable oxygen")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/oxygen" }
            respondError(ctx, 400, e.message)
          }
        }

        // POST /api/wearable/respiratory
        api.post("/wearable/respiratory").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            val participantId = extractParticipantId(body)
            val deviceId = body.getString("device_id") ?: "unknown"
            val data = JsonObject()
              .put("device_id", deviceId)
              .put("timestamp", body.getLong("timestamp"))
              .put("rate", body.getDouble("rate"))
            ensureParticipantLink(deviceId, "watch", participantId)
            requestAndReplyOk(ctx, "insertWearableRespiratory", data, "Failed to store wearable respiratory")
          } catch (e: Exception) {
            logger.error(e) { "Error handling /api/wearable/respiratory" }
            respondError(ctx, 400, e.message)
          }
        }

        // ---- GEOFENCE ALERT SYSTEM API ENDPOINTS ----

        // GET /api/participants - List all participants
        api.get("/participants").handler { ctx ->
          eventBus.request<JsonArray>("getParticipants", JsonObject()) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // GET /api/participants/:deviceId - Get participant by device ID
        api.get("/participants/:deviceId").handler { ctx ->
          val deviceId = ctx.pathParam("deviceId")
          eventBus.request<JsonObject?>("getParticipantByDevice", JsonObject().put("device_id", deviceId)) { ar ->
            if (ar.succeeded()) {
              val result = ar.result().body()
              if (result != null) {
                ctx.response()
                  .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                  .end(result.encode())
              } else {
                ctx.response().setStatusCode(404)
                  .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                  .end(JsonObject().put("error", "Participant not found").encode())
              }
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // POST /api/participants - Create/update participant
        api.post("/participants").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            requestJsonObject(ctx, "upsertParticipant", body, successStatusCode = 201)
          } catch (e: Exception) {
            respondError(ctx, 400, e.message)
          }
        }

        // PUT /api/participants/:participantId - Update participant
        api.put("/participants/:participantId").handler { ctx ->
          try {
            val participantId = ctx.pathParam("participantId")
            val body = requireJsonBody(ctx).put("participant_id", participantId)
            requestJsonObject(ctx, "upsertParticipant", body)
          } catch (e: Exception) {
            respondError(ctx, 400, e.message)
          }
        }

        // GET /api/zones - List all red zones
        api.get("/zones").handler { ctx ->
          val participantId = ctx.queryParam("participant_id").firstOrNull()
          eventBus.request<JsonArray>("getRedZones", JsonObject().put("participant_id", participantId)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // POST /api/zones - Create red zone
        api.post("/zones").handler { ctx ->
          try {
            val body = requireJsonBody(ctx)
            requestJsonObject(ctx, "insertRedZone", body, successStatusCode = 201)
          } catch (e: Exception) {
            respondError(ctx, 400, e.message)
          }
        }

        // DELETE /api/zones/:zoneId - Delete red zone
        api.delete("/zones/:zoneId").handler { ctx ->
          val zoneId = ctx.pathParam("zoneId")
          eventBus.request<JsonObject>("deleteRedZone", JsonObject().put("zone_id", zoneId)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // GET /api/alerts - List geofence alerts
        api.get("/alerts").handler { ctx ->
          val activeOnly = ctx.queryParam("active").firstOrNull()?.toBoolean() ?: false
          eventBus.request<JsonArray>("getGeofenceAlerts", JsonObject().put("active_only", activeOnly)) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // POST /api/alerts/:alertId/acknowledge - Acknowledge alert
        api.post("/alerts/:alertId/acknowledge").handler { ctx ->
          val alertId = ctx.pathParam("alertId")
          val acknowledgedBy = ctx.get<JsonObject>("auth_user")?.getString("username")
            ?: ctx.queryParam("by").firstOrNull()
            ?: "admin"
          eventBus.request<JsonObject>("acknowledgeAlert", JsonObject()
            .put("alert_id", alertId)
            .put("acknowledged_by", acknowledgedBy)
          ) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }




        // ---- SIGNATURE ALERT ENDPOINTS ----

        // GET /api/signature-alerts?active=true|false&limit=10000|all
        api.get("/signature-alerts").handler { ctx ->
          val activeOnly = ctx.queryParam("active").firstOrNull()?.toBoolean() ?: false
          val limitParam = ctx.queryParam("limit").firstOrNull()
          val requestBody = JsonObject().put("active_only", activeOnly)
          if (!limitParam.isNullOrBlank()) {
            requestBody.put("limit", limitParam)
          }

          vertx.eventBus().request<JsonArray>(
            "getSignatureAlerts",
            requestBody
          ) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(ar.result().body().encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // POST /api/signature-alerts/:id/acknowledge?by=admin
        api.post("/signature-alerts/:id/acknowledge").handler { ctx ->
          val id = ctx.pathParam("id").toLong()
          val by = ctx.get<JsonObject>("auth_user")?.getString("username")
            ?: ctx.queryParam("by").firstOrNull()
            ?: "admin"

          vertx.eventBus().request<JsonObject>(
            "acknowledgeSignatureAlert",
            JsonObject().put("id", id).put("acknowledged_by", by)
          ) { ar ->
            if (ar.succeeded()) {
              ctx.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("ok", true).encode())
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // GET /api/participants/:deviceId/location - Get latest location for participant
        api.get("/participants/:deviceId/location").handler { ctx ->
          val deviceId = ctx.pathParam("deviceId")
          eventBus.request<JsonObject?>("getLatestLocation", JsonObject().put("device_id", deviceId)) { ar ->
            if (ar.succeeded()) {
              val result = ar.result().body()
              if (result != null) {
                ctx.response()
                  .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                  .end(result.encode())
              } else {
                ctx.response().setStatusCode(404)
                  .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                  .end(JsonObject().put("error", "No location found").encode())
              }
            } else {
              ctx.response().setStatusCode(500)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(JsonObject().put("error", ar.cause().message).encode())
            }
          }
        }

        // Mount the subrouter at /api
        router.mountSubRouter("/api", api)
        // ---- end API SUBROUTER ----                      ----------------------------------------------------------------------------------------------


        /**
         * Generate QRCode to join the study using Google's Chart API
         */
        router.route(HttpMethod.GET, "/:studyNumber/:studyKey").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            vertx.fileSystem().delete("./cache/qrcode.png") {
              if (it.succeeded()) logger.info { "Cleared old qrcode..." }
            }
            vertx.fileSystem().open(
              "./cache/qrcode.png",
              OpenOptions().setTruncateExisting(true).setCreate(true).setWrite(true)
            ) { write ->
              if (write.succeeded()) {
                val asyncQrcode = write.result()
                val webClientOptions = WebClientOptions()
                  .setKeepAlive(true)
                  .setPipelining(true)
                  .setFollowRedirects(true)
                  .setSsl(true)
                  .setTrustAll(true)

                val client = WebClient.create(vertx, webClientOptions)
                val serverURL =
                  "${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}/index.php/${study.getInteger(
                    "study_number"
                  )}/${study.getString("study_key")}"

                logger.info { "URL encoded for the QRCode is: $serverURL" }

                client.get(
                  443, "qrcode.tec-it.com",
                  "/API/QRCode?size=small&data=$serverURL"
                )
                  .`as`(BodyCodec.pipe(asyncQrcode, true))
                  .send { request ->
                    if (request.succeeded()) {
                      pebbleEngine.render(JsonObject().put("studyURL", serverURL), "templates/qrcode.peb") { pebble ->
                        if (pebble.succeeded()) {
                          route.response().statusCode = 200
                          route.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
                        }
                      }
                    } else {
                      logger.error(request.cause()) { "QRCode creation failed." }
                    }
                  }
              }
            }
          }
        }

        /**
         * This route is called:
         * - when joining the study, returns the JSON with all the settings from the study. Can be called from apps using Aware.joinStudy(URL) or client's QRCode scanner
         * - when checking study status with the study_check=1.
         */
        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            if (route.request().getFormAttribute("study_check") == "1") {
              val status = JsonObject()
              status.put("status", study.getBoolean("study_active"))
              status.put(
                "config",
                "[]"
              ) //NOTE: if we send the configuration, it will keep reapplying the settings on legacy clients. Sending empty JsonArray (i.e., no changes)
              route.response().end(JsonArray().add(status).encode())
              route.next()
            } else {
              logger.info { "Study configuration: ${getStudyConfig().encodePrettily()}" }
              route.response().end(getStudyConfig().encode())
            }
          } else {
            route.response().statusCode = 401
            route.response().end()
          }
        }

        /**
         * Legacy: this will be hit by legacy client to retrieve the study information. It retuns JsonObject with (defined also in aware-config.json on AWARE Micro):
        {
        "study_key" : "studyKey",
        "study_number" : 1,
        "study_name" : "AWARE Micro demo study",
        "study_description" : "This is a demo study to test AWARE Micro",
        "researcher_first" : "First Name",
        "researcher_last" : "Last Name",
        "researcher_contact" : "your@email.com"
        }
         */
        router.route(HttpMethod.GET, "/index.php/webservice/client_get_study_info/:studyKey").handler { route ->
          if (route.request().getParam("studyKey") == study.getString("study_key")) {
            route.response().end(study.encode())
          } else {
            route.response().statusCode = 401
            route.response().end()
          }
        }

        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey/:table/:operation").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            when (route.request().getParam("operation")) {
              "create_table" -> {
                //Commented the following line as we merged with insert. Only here so that legacy client thinks all is ok
                //eventBus.publish("createTable", route.request().getParam("table"))
                route.response().statusCode = 200
                route.response().end()
              }
              "insert" -> {
                eventBus.publish(
                  "insertData",
                  JsonObject()
                    .put("table", route.request().getParam("table"))
                    .put("device_id", route.request().getFormAttribute("device_id"))
                    .put("data", route.request().getFormAttribute("data"))
                )
                route.response().statusCode = 200
                route.response().end()
              }
              "update" -> {
                eventBus.publish(
                  "updateData",
                  JsonObject()
                    .put("table", route.request().getParam("table"))
                    .put("device_id", route.request().getFormAttribute("device_id"))
                    .put("data", route.request().getFormAttribute("data"))
                )
                route.response().statusCode = 200
                route.response().end()
              }
              "delete" -> {
                eventBus.publish(
                  "deleteData",
                  JsonObject()
                    .put("table", route.request().getParam("table"))
                    .put("device_id", route.request().getFormAttribute("device_id"))
                    .put("data", route.request().getFormAttribute("data"))
                )
                route.response().statusCode = 200
                route.response().end()
              }
              "query" -> {
                val requestData = JsonObject()
                  .put("table", route.request().getParam("table"))
                  .put("device_id", route.request().getFormAttribute("device_id"))
                  .put("start", route.request().getFormAttribute("start").toDouble())
                  .put("end", route.request().getFormAttribute("end").toDouble())

                eventBus.request<JsonArray>("getData", requestData) { response ->
                  if (response.succeeded()) {
                    route.response().statusCode = 200
                    route.response().end(response.result().body().encode())
                  } else {
                    route.response().statusCode = 401
                    route.response().end()
                  }
                }
              }
              else -> {
                route.response().statusCode = 401
                route.response().end()
              }
            }
          } else {
            route.response().statusCode = 401
            route.response().end()
          }
        }

        /**
         * Default route, landing page of the server - redirect to clinical dashboard
         */
        router.route(HttpMethod.GET, "/").handler { route ->
          route.response()
            .setStatusCode(302)
            .putHeader("Location", "/static/dashboard/index.html")
            .end()
        }

        //Use SSL
        if (serverConfig.getString("path_fullchain_pem").isNotEmpty() && serverConfig.getString("path_key_pem").isNotEmpty()) {
          serverOptions.pemTrustOptions = PemTrustOptions().addCertPath(serverConfig.getString("path_fullchain_pem"))
          serverOptions.pemKeyCertOptions = PemKeyCertOptions()
            .setCertPath(serverConfig.getString("path_fullchain_pem"))
            .setKeyPath(serverConfig.getString("path_key_pem"))
          serverOptions.isSsl = true
        }

        httpServer = vertx.createHttpServer(serverOptions)
          .requestHandler(router)
          .listen(serverConfig.getInteger("server_port")) { server ->
            if (server.succeeded()) {
              when (serverConfig.getString("database_engine")) {
                "mysql" -> {
                  vertx.deployVerticle("com.awareframework.micro.MySQLVerticle")
                }
                "postgres" -> {
                  vertx.deployVerticle("com.awareframework.micro.PostgresVerticle")
                }
                else -> {
                  logger.info { "Not storing data into a database engine: mysql, postgres" }
                }
              }

              vertx.deployVerticle("com.awareframework.micro.WebsocketVerticle")

              logger.info { "AWARE Micro API at ${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}" }
              logger.info { "Serving study config: ${getStudyConfig()}" }
              startPromise.complete()
            } else {
              logger.error(server.cause()) { "AWARE Micro initialisation failed!" }
              startPromise.fail(server.cause())
            }
          }

        configReader.listen { change ->
          val newConfig = change.newConfiguration
          httpServer.close()

          val newServerConfig = newConfig.getJsonObject("server")
          val newServerOptions = HttpServerOptions()

          if (newServerConfig.getString("path_fullchain_pem").isNotEmpty() && newServerConfig.getString("path_key_pem").isNotEmpty()) {
            newServerOptions.pemTrustOptions =
              PemTrustOptions().addCertPath(newServerConfig.getString("path_fullchain_pem"))

            newServerOptions.pemKeyCertOptions = PemKeyCertOptions()
              .setCertPath(newServerConfig.getString("path_fullchain_pem"))
              .setKeyPath(newServerConfig.getString("path_key_pem"))
            newServerOptions.isSsl = true
          }

          httpServer = vertx.createHttpServer(newServerOptions)
            .requestHandler(router)
            .listen(newServerConfig.getInteger("server_port")) { server ->
              if (server.succeeded()) {
                when (newServerConfig.getString("database_engine")) {
                  "mysql" -> {
                    vertx.undeploy("com.awareframework.micro.MySQLVerticle")
                    vertx.deployVerticle("com.awareframework.micro.MySQLVerticle")
                  }
                  "postgres" -> {
                    vertx.undeploy("com.awareframework.micro.PostgresVerticle")
                    vertx.deployVerticle("com.awareframework.micro.PostgresVerticle")
                  }
                  else -> {
                    logger.info { "Not storing data into a database engine: mysql, postgres" }
                  }
                }

                vertx.undeploy("com.awareframework.micro.WebsocketVerticle")
                vertx.deployVerticle("com.awareframework.micro.WebsocketVerticle")

                logger.info { "AWARE Micro API at ${getExternalServerHost(newServerConfig)}:${getExternalServerPort(newServerConfig)}" }

              } else {
                logger.error(server.cause()) { "AWARE Micro initialisation failed!" }
              }
            }
        }

      } else { //this is a fresh instance, no server created yet.

        val configFile = JsonObject()

        //infrastructure info
        val server = JsonObject()
        server.put("database_engine", "mysql") //[mysql, postgres]
        server.put("database_host", "localhost")
        server.put("database_name", "studyDatabase")
        server.put("database_user", "databaseUser")
        server.put("database_pwd", "databasePassword")
        server.put("database_port", 3306)
        server.put("server_host", "http://localhost")
        server.put("server_port", 8080)
        server.put("websocket_port", 8081)
        server.put("path_fullchain_pem", "")
        server.put("path_key_pem", "")
        configFile.put("server", server)

        //study info
        val study = JsonObject()
        study.put("study_key", "4lph4num3ric")
        study.put("study_number", 1)
        study.put("study_name", "AWARE Micro demo study")
        study.put("study_active", true)
        study.put("study_start", System.currentTimeMillis())
        study.put("study_description", "This is a demo study to test AWARE Micro")
        study.put("researcher_first", "First Name")
        study.put("researcher_last", "Last Name")
        study.put("researcher_contact", "your@email.com")
        configFile.put("study", study)

//        //AWARE framework settings from both sensors and plugins
//        val sensors =
//          getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml")
//
//        configFile.put("sensors", sensors)
//
//        val pluginsList = HashMap<String, String>()
//        pluginsList["com.aware.plugin.ambient_noise"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.ambient_noise/master/com.aware.plugin.ambient_noise/src/main/res/xml/preferences_ambient_noise.xml"
//        pluginsList["com.aware.plugin.contacts_list"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.contacts_list/master/com.aware.plugin.contacts_list/src/main/res/xml/preferences_contacts_list.xml"
//        pluginsList["com.aware.plugin.device_usage"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.device_usage/master/com.aware.plugin.device_usage/src/main/res/xml/preferences_device_usage.xml"
//        pluginsList["com.aware.plugin.esm.scheduler"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.esm.scheduler/master/com.aware.plugin.esm.scheduler/src/main/res/xml/preferences_esm_scheduler.xml"
//        pluginsList["com.aware.plugin.fitbit"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.fitbit/master/com.aware.plugin.fitbit/src/main/res/xml/preferences_fitbit.xml"
//        pluginsList["com.aware.plugin.google.activity_recognition"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.activity_recognition/master/com.aware.plugin.google.activity_recognition/src/main/res/xml/preferences_activity_recog.xml"
//        pluginsList["com.aware.plugin.google.auth"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.auth/master/com.aware.plugin.google.auth/src/main/res/xml/preferences_google_auth.xml"
//        pluginsList["com.aware.plugin.google.fused_location"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.fused_location/master/com.aware.plugin.google.fused_location/src/main/res/xml/preferences_fused_location.xml"
//        pluginsList["com.aware.plugin.openweather"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.openweather/master/com.aware.plugin.openweather/src/main/res/xml/preferences_openweather.xml"
//        pluginsList["com.aware.plugin.sensortag"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sensortag/master/com.aware.plugin.sensortag/src/main/res/xml/preferences_sensortag.xml"
//        pluginsList["com.aware.plugin.sentimental"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sentimental/master/com.aware.plugin.sentimental/src/main/res/xml/preferences_sentimental.xml"
//        pluginsList["com.aware.plugin.studentlife.audio_final"] =
//          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.studentlife.audio_final/master/com.aware.plugin.studentlife.audio/src/main/res/xml/preferences_conversations.xml"
//
//        val plugins = getPlugins(pluginsList)
//        configFile.put("plugins", plugins)
//
//        vertx.fileSystem().writeFile("./aware-config.json", Buffer.buffer(configFile.encodePrettily())) { result ->
//          if (result.succeeded()) {
//            logger.info { "You can now configure your server by editing the aware-config.json that was automatically created. You can now stop this instance (press Ctrl+C)" }
//          } else {
//            logger.error(result.cause()) { "Failed to create aware-config.json." }
//          }
//        }
      }
    }
  }

  /**
   * Check valid study key and number
   */
  fun validRoute(studyInfo: JsonObject, studyNumber: Int, studyKey: String): Boolean {
    return studyNumber == studyInfo.getInteger("study_number") && studyKey == studyInfo.getString("study_key")
  }

  fun getStudyConfig(): JsonArray {
    val serverConfig = parameters.getJsonObject("server")
    //println("Server config: ${serverConfig.encodePrettily()}")

    val study = parameters.getJsonObject("study")
    //println("Study info: ${study.encodePrettily()}")

    val sensors = JsonArray()
    val plugins = JsonArray()

    val awareSensors = parameters.getJsonArray("sensors")
    for (i in 0 until awareSensors.size()) {
      val awareSensor = awareSensors.getJsonObject(i)
      val sensorSettings = awareSensor.getJsonArray("settings")
      for (j in 0 until sensorSettings.size()) {
        val setting = sensorSettings.getJsonObject(j)

        val awareSetting = JsonObject()
        awareSetting.put("setting", setting.getString("setting"))

        when (setting.getString("setting")) {
          "status_webservice" -> awareSetting.put("value", "true")
          "webservice_server" -> awareSetting.put(
            "value",
            "${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}/index.php/${study.getInteger(
              "study_number"
            )}/${study.getString("study_key")}"
          )
          else -> awareSetting.put("value", setting.getString("defaultValue"))
        }
        sensors.add(awareSetting)
      }
    }

    var awareSetting = JsonObject()
    awareSetting.put("setting", "study_id")
    awareSetting.put("value", study.getString("study_key"))
    sensors.add(awareSetting)

    awareSetting = JsonObject()
    awareSetting.put("setting", "study_start")
    awareSetting.put("value", study.getDouble("study_start"))
    sensors.add(awareSetting)

    val awarePlugins = parameters.getJsonArray("plugins")
    for (i in 0 until awarePlugins.size()) {
      val awarePlugin = awarePlugins.getJsonObject(i)
      val pluginSettings = awarePlugin.getJsonArray("settings")

      val pluginOutput = JsonObject()
      pluginOutput.put("plugin", awarePlugin.getString("package_name"))

      val pluginSettingsOutput = JsonArray()
      for (j in 0 until pluginSettings.size()) {
        val setting = pluginSettings.getJsonObject(j)
        val settingOutput = JsonObject()
        settingOutput.put("setting", setting.getString("setting"))
        settingOutput.put("value", setting.getString("defaultValue"))
        pluginSettingsOutput.add(settingOutput)
      }
      pluginOutput.put("settings", pluginSettingsOutput)

      plugins.add(pluginOutput)
    }

    val schedulers = parameters.getJsonArray("schedulers")

    val output = JsonArray()
    output.add(JsonObject().put("sensors", sensors).put("plugins", plugins))
    if (schedulers != null) {
      output.getJsonObject(0).put("schedulers", schedulers)
    }
    return output
  }
//
//  /**
//   * This parses the aware-client xml file to retrieve all possible settings for a study
//   */
//  fun getSensors(xmlUrl: String): JsonArray {
//    val sensors = JsonArray()
//    val awarePreferences = URL(xmlUrl).openStream()
//
//    val docFactory = DocumentBuilderFactory.newInstance()
//    val docBuilder = docFactory.newDocumentBuilder()
//    val doc = docBuilder.parse(awarePreferences)
//    val docRoot = doc.getElementsByTagName("PreferenceScreen")
//
//    for (i in 1..docRoot.length) {
//      val child = docRoot.item(i)
//      if (child != null) {
//
//        val sensor = JsonObject()
//        if (child.attributes.getNamedItem("android:key") != null)
//          sensor.put("sensor", child.attributes.getNamedItem("android:key").nodeValue)
//        if (child.attributes.getNamedItem("android:title") != null)
//          sensor.put("title", child.attributes.getNamedItem("android:title").nodeValue)
//        if (child.attributes.getNamedItem("android:icon") != null)
//          sensor.put("icon", getSensorIcon(child.attributes.getNamedItem("android:icon").nodeValue))
//        if (child.attributes.getNamedItem("android:summary") != null)
//          sensor.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)
//
//        val settings = JsonArray()
//        val subChildren = child.childNodes
//        for (j in 0..subChildren.length) {
//          val subChild = subChildren.item(j)
//          if (subChild != null && subChild.nodeName.contains("Preference")) {
//            val setting = JsonObject()
//            if (subChild.attributes.getNamedItem("android:key") != null)
//              setting.put("setting", subChild.attributes.getNamedItem("android:key").nodeValue)
//            if (subChild.attributes.getNamedItem("android:title") != null)
//              setting.put("title", subChild.attributes.getNamedItem("android:title").nodeValue)
//            if (subChild.attributes.getNamedItem("android:defaultValue") != null)
//              setting.put("defaultValue", subChild.attributes.getNamedItem("android:defaultValue").nodeValue)
//            if (subChild.attributes.getNamedItem("android:summary") != null && subChild.attributes.getNamedItem("android:summary").nodeValue != "%s")
//              setting.put("summary", subChild.attributes.getNamedItem("android:summary").nodeValue)
//
//            if (setting.containsKey("defaultValue"))
//              settings.add(setting)
//          }
//        }
//        sensor.put("settings", settings)
//        sensors.add(sensor)
//      }
//    }
//    return sensors
//  }
//
//  /**
//   * This retrieves asynchronously the icons for each sensor from the client source code
//   */
//  private fun getSensorIcon(drawableId: String): String {
//    val icon = drawableId.substring(drawableId.indexOf('/') + 1)
//    val downloadUrl = "/denzilferreira/aware-client/raw/master/aware-core/src/main/res/drawable/*.png"
//
//    vertx.fileSystem().mkdir("./cache") { cacheFolder ->
//      if (cacheFolder.succeeded()) {
//        logger.info { "Created cache folder" }
//      }
//    }
//
//    vertx.fileSystem().exists("./cache/$icon.png") { iconResult ->
//      if (!iconResult.result()) {
//        vertx.fileSystem().open("./cache/$icon.png", OpenOptions().setCreate(true).setWrite(true)) { writeFile ->
//          if (writeFile.succeeded()) {
//
//            logger.info { "Downloading $icon.png" }
//
//            val asyncFile = writeFile.result()
//            val webClientOptions = WebClientOptions()
//              .setKeepAlive(true)
//              .setPipelining(true)
//              .setFollowRedirects(true)
//              .setSsl(true)
//              .setTrustAll(true)
//
//            val client = WebClient.create(vertx, webClientOptions)
//            client.get(443, "github.com", downloadUrl.replace("*", icon))
//              .`as`(BodyCodec.pipe(asyncFile, true))
//              .send { request ->
//                if (request.succeeded()) {
//                  val iconFile = request.result()
//                  logger.info { "Cached $icon.png: ${iconFile.statusCode() == 200}" }
//                }
//              }
//          } else {
//            logger.error(writeFile.cause()) { "Unable to create file." }
//          }
//        }
//      }
//    }
//
//    return "$icon.png"
//  }
//
//  /**
//   * This parses a list of plugins' xml to retrieve plugins' settings
//   */
//  private fun getPlugins(xmlUrls: HashMap<String, String>): JsonArray {
//    val plugins = JsonArray()
//
//    for (pluginUrl in xmlUrls) {
//      val pluginPreferences = URL(pluginUrl.value).openStream()
//
//      val docFactory = DocumentBuilderFactory.newInstance()
//      val docBuilder = docFactory.newDocumentBuilder()
//      val doc = docBuilder.parse(pluginPreferences)
//      val docRoot = doc.getElementsByTagName("PreferenceScreen")
//
//      for (i in 0..docRoot.length) {
//        val child = docRoot.item(i)
//        if (child != null) {
//
//          val plugin = JsonObject()
//          plugin.put("package_name", pluginUrl.key)
//
//          if (child.attributes.getNamedItem("android:key") != null)
//            plugin.put("plugin", child.attributes.getNamedItem("android:key").nodeValue)
//          if (child.attributes.getNamedItem("android:icon") != null)
//            plugin.put("icon", child.attributes.getNamedItem("android:icon").nodeValue)
//          if (child.attributes.getNamedItem("android:summary") != null)
//            plugin.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)
//
//          val settings = JsonArray()
//          val subChildren = child.childNodes
//          for (j in 0..subChildren.length) {
//            val subChild = subChildren.item(j)
//            if (subChild != null && subChild.nodeName.contains("Preference")) {
//              val setting = JsonObject()
//              if (subChild.attributes.getNamedItem("android:key") != null)
//                setting.put("setting", subChild.attributes.getNamedItem("android:key").nodeValue)
//              if (subChild.attributes.getNamedItem("android:title") != null)
//                setting.put("title", subChild.attributes.getNamedItem("android:title").nodeValue)
//              if (subChild.attributes.getNamedItem("android:defaultValue") != null)
//                setting.put("defaultValue", subChild.attributes.getNamedItem("android:defaultValue").nodeValue)
//              if (subChild.attributes.getNamedItem("android:summary") != null && subChild.attributes.getNamedItem("android:summary").nodeValue != "%s")
//                setting.put("summary", subChild.attributes.getNamedItem("android:summary").nodeValue)
//
//              if (setting.containsKey("defaultValue"))
//                settings.add(setting)
//            }
//          }
//          plugin.put("settings", settings)
//          plugins.add(plugin)
//        }
//      }
//    }
//    return plugins
//  }

  private fun getExternalServerHost(serverConfig: JsonObject): String {
    if (serverConfig.containsKey("external_server_host")) {
      return serverConfig.getString("external_server_host")
    }
    return serverConfig.getString("server_host")
  }

  private fun getExternalServerPort(serverConfig: JsonObject): Int {
    if (serverConfig.containsKey("external_server_port")) {
      return serverConfig.getInteger("external_server_port")
    }
    return serverConfig.getInteger("server_port")
  }

  private fun installApiAuth(api: Router, serverConfig: JsonObject) {
    val ingestKey = SecretResolver.get("API_KEY_INGEST", serverConfig.getString("api_key_ingest"))
    val adminKey = SecretResolver.get("API_KEY_ADMIN", serverConfig.getString("api_key_admin"))
    val keysConfigured = ingestKey != null || adminKey != null
    if (!keysConfigured) {
      logger.warn { "API key auth is disabled (API_KEY_INGEST/API_KEY_ADMIN not set). Session auth is still enabled for admin APIs." }
    }

    api.route().handler { ctx ->
      if (ctx.request().method() == HttpMethod.OPTIONS) {
        ctx.next()
        return@handler
      }

      val path = ctx.request().path().removePrefix("/api")
      if (path == "/auth/login") {
        ctx.next()
        return@handler
      }

      val method = ctx.request().method()
      val bearerToken = extractBearerToken(ctx)
      val explicitApiKey = extractApiKey(ctx)
      val providedApiKey = explicitApiKey ?: bearerToken
      val keyMatchesAdmin = adminKey != null && providedApiKey == adminKey
      val keyMatchesIngest = ingestKey != null && providedApiKey == ingestKey

      val requiredRoles = requiredRolesForPath(path, method)
      val isAdminPath = isAdminApiPath(path) || path == "/auth/me" || path == "/auth/logout"

      if (isAdminPath) {
        if (keyMatchesAdmin) {
          ctx.put(
            "auth_user",
            JsonObject()
              .put("username", "api_key_admin")
              .put("role", "admin")
              .put("auth_mode", "api_key")
          )
          ctx.next()
          return@handler
        }

        if (bearerToken.isNullOrBlank()) {
          respondError(ctx, 401, "Unauthorized")
          return@handler
        }

        authorizeWithSessionToken(ctx, bearerToken, requiredRoles)
        return@handler
      }

      if (keyMatchesIngest || keyMatchesAdmin) {
        val role = if (keyMatchesAdmin) "admin" else "ingest"
        ctx.put(
          "auth_user",
          JsonObject()
            .put("username", if (keyMatchesAdmin) "api_key_admin" else "api_key_ingest")
            .put("role", role)
            .put("auth_mode", "api_key")
        )
        ctx.next()
        return@handler
      }

      if (!keysConfigured) {
        if (bearerToken.isNullOrBlank()) {
          ctx.next()
        } else {
          authorizeWithSessionToken(ctx, bearerToken, ingestRoles)
        }
        return@handler
      }

      if (!bearerToken.isNullOrBlank()) {
        authorizeWithSessionToken(ctx, bearerToken, ingestRoles)
      } else {
        respondError(ctx, 401, "Unauthorized")
      }
    }
  }

  private fun isAdminApiPath(path: String): Boolean {
    return adminApiPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }
  }

  private fun requiredRolesForPath(path: String, method: HttpMethod): Set<String> {
    if (path == "/auth/me" || path == "/auth/logout") return allRoles
    if (path == "/users" || path.startsWith("/users/")) return setOf("admin")
    if (path == "/admin" || path.startsWith("/admin/")) return setOf("admin")
    if (path == "/participants" || path.startsWith("/participants/")) {
      return if (method == HttpMethod.GET) setOf("admin", "analyst", "viewer", "doctor") else adminWriteRoles
    }
    if (isAdminApiPath(path)) {
      return if (method == HttpMethod.GET) adminReadRoles else adminWriteRoles
    }
    return ingestRoles
  }

  private fun authorizeWithSessionToken(
    ctx: RoutingContext,
    token: String,
    allowedRoles: Set<String>
  ) {
    vertx.eventBus().request<JsonObject>("authValidateToken", JsonObject().put("token", token)) { ar ->
      if (ar.failed()) {
        respondError(ctx, 401, "Unauthorized")
        return@request
      }

      val body = ar.result().body()
      if (!body.getBoolean("ok", false)) {
        respondError(ctx, 401, "Unauthorized")
        return@request
      }

      val user = body.getJsonObject("user") ?: JsonObject()
      val role = user.getString("role", "").trim().lowercase()
      if (!allowedRoles.contains(role)) {
        respondError(ctx, 403, "Forbidden")
        return@request
      }

      ctx.put("auth_user", user.put("auth_mode", "session"))
      ctx.next()
    }
  }

  private fun extractBearerToken(ctx: RoutingContext): String? {
    val authHeader = ctx.request().getHeader("Authorization")
    if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ", ignoreCase = true)) {
      return authHeader.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() }
    }
    return null
  }

  private fun extractApiKey(ctx: RoutingContext): String? {
    val xApiKey = ctx.request().getHeader("X-API-Key")?.trim()
    if (!xApiKey.isNullOrEmpty()) return xApiKey

    val queryKey = ctx.queryParam("api_key").firstOrNull()?.trim()
    if (!queryKey.isNullOrEmpty()) return queryKey

    // Optional fallback for clients that cannot easily set headers/query params.
    val body = try {
      ctx.body().asJsonObject()
    } catch (_: Exception) {
      null
    }
    if (body != null) {
      val bodyKey = body.getString("api_key")?.trim()
      if (!bodyKey.isNullOrEmpty()) return bodyKey
    }

    return null
  }

  private fun respondJson(ctx: RoutingContext, statusCode: Int, payload: JsonObject) {
    ctx.response()
      .setStatusCode(statusCode)
      .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
      .end(payload.encode())
  }

  private fun respondOk(ctx: RoutingContext, statusCode: Int = 200) {
    respondJson(ctx, statusCode, JsonObject().put("ok", true))
  }

  private fun respondError(ctx: RoutingContext, statusCode: Int, message: String?) {
    respondJson(ctx, statusCode, JsonObject().put("error", message ?: "Unknown error"))
  }

  private fun requireJsonBody(ctx: RoutingContext): JsonObject {
    return ctx.body().asJsonObject()
  }

  private fun extractParticipantId(body: JsonObject): String? {
    val raw = body.getString("participant_id") ?: body.getString("participantId")
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return trimmed
  }

  private fun readLongValue(body: JsonObject, vararg keys: String): Long? {
    for (key in keys) {
      val value = body.getValue(key) ?: continue
      when (value) {
        is Number -> return value.toLong()
        is String -> value.toLongOrNull()?.let { return it }
      }
    }
    return null
  }

  private fun readDoubleValue(body: JsonObject, vararg keys: String): Double? {
    for (key in keys) {
      val value = body.getValue(key) ?: continue
      when (value) {
        is Number -> return value.toDouble()
        is String -> value.toDoubleOrNull()?.let { return it }
      }
    }
    return null
  }

  private fun ensureParticipantLink(
    deviceId: String?,
    deviceType: String,
    participantId: String? = null
  ) {
    val safeDeviceId = deviceId?.trim()
    if (safeDeviceId.isNullOrEmpty() || safeDeviceId == "unknown") return

    val payload = JsonObject()
      .put("device_id", safeDeviceId)
      .put("device_type", deviceType)
      .put("name", "Device $safeDeviceId")
      .put("is_auto_link", true)
    if (!participantId.isNullOrBlank()) {
      payload.put("participant_id", participantId.trim())
    }

    vertx.eventBus().request<JsonObject>("upsertParticipant", payload) { ar ->
      if (ar.failed()) {
        logger.debug(ar.cause()) { "Auto participant link failed for $safeDeviceId ($deviceType)" }
      }
    }
  }

  private fun requestAndReplyOk(
    ctx: RoutingContext,
    address: String,
    data: JsonObject,
    failureLog: String,
    successStatusCode: Int = 200,
    onSuccess: (() -> Unit)? = null
  ) {
    vertx.eventBus().request<JsonObject>(address, data) { ar ->
      if (ar.succeeded()) {
        onSuccess?.invoke()
      } else {
        logger.error(ar.cause()) { failureLog }
      }
      respondOk(ctx, successStatusCode)
    }
  }

  private fun requestJsonObject(
    ctx: RoutingContext,
    address: String,
    data: JsonObject,
    successStatusCode: Int = 200
  ) {
    vertx.eventBus().request<JsonObject>(address, data) { ar ->
      if (ar.succeeded()) {
        respondJson(ctx, successStatusCode, ar.result().body())
      } else {
        respondError(ctx, 500, ar.cause().message)
      }
    }
  }

  // ---- GEOFENCE DETECTION LOGIC ----

  /**
   * Calculate distance between two coordinates using Haversine formula
   * @return distance in meters
   */
  private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return R * c
  }

  /**
   * Check if device location is within any red zone and create alerts
   */
  private fun checkGeofence(deviceId: String, latitude: Double, longitude: Double) {
    val eventBus = vertx.eventBus()

    // First, get the participant for this device
    eventBus.request<JsonObject?>("getParticipantByDevice", JsonObject().put("device_id", deviceId)) { participantResult ->
      if (participantResult.succeeded()) {
        val participant = participantResult.result().body()
        if (participant == null) {
          // Auto-create participant for new devices
          val newParticipant = JsonObject()
            .put("device_id", deviceId)
            .put("name", "Device $deviceId")
            .put("is_auto_link", true)
          eventBus.publish("upsertParticipant", newParticipant)
          logger.info { "Auto-created participant for device: $deviceId" }
          return@request
        }

        val participantId = participant.getString("participant_id")
        val defaultRadius = participant.getInteger("red_zone_radius") ?: 300

        // Get red zones for this participant (including global zones)
        eventBus.request<JsonArray>("getRedZones", JsonObject().put("participant_id", participantId)) { zonesResult ->
          if (zonesResult.succeeded()) {
            val zones = zonesResult.result().body()
            
            for (i in 0 until zones.size()) {
              val zone = zones.getJsonObject(i)
              val zoneId = zone.getString("zone_id")
              val zoneName = zone.getString("name")
              val zoneLat = zone.getDouble("latitude")
              val zoneLon = zone.getDouble("longitude")
              val zoneRadius = zone.getInteger("radius") ?: defaultRadius

              val distance = haversineDistance(latitude, longitude, zoneLat, zoneLon)

              if (distance <= zoneRadius) {
                // Check for recent alert to prevent duplicates (within 30 minutes)
                eventBus.request<JsonObject>("checkRecentAlert", JsonObject()
                  .put("participant_id", participantId)
                  .put("zone_id", zoneId)
                  .put("window_minutes", 30)
                ) { recentResult ->
                  if (recentResult.succeeded()) {
                    val exists = recentResult.result().body().getBoolean("exists") ?: false
                    
                    if (!exists) {
                      // Create new geofence alert
                      val alertData = JsonObject()
                        .put("participant_id", participantId)
                        .put("zone_id", zoneId)
                        .put("zone_name", zoneName)
                        .put("latitude", latitude)
                        .put("longitude", longitude)
                        .put("distance", distance)

                      eventBus.request<JsonObject>("insertGeofenceAlert", alertData)
                      logger.warn { "GEOFENCE ALERT: Participant $participantId entered zone '$zoneName' (distance: ${String.format("%.1f", distance)}m)" }
                    }
                  }
                }
              }
            }
          } else {
            logger.error(zonesResult.cause()) { "Failed to get red zones for participant $participantId" }
          }
        }
      } else {
        logger.debug { "Device $deviceId has no associated participant yet" }
      }
    }
  }
}
