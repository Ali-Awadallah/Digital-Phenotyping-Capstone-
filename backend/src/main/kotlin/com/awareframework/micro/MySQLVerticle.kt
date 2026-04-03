package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.mysqlclient.SslMode
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.LocalDateTime
import java.util.stream.Collectors
import java.util.stream.StreamSupport
import kotlin.math.min

class MySQLVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: MySQLPool

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val eventBus = vertx.eventBus()

    val configReader = awareConfigRetriever(vertx)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        // https://vertx.io/docs/4.3.3/apidocs/io/vertx/mysqlclient/MySQLConnectOptions.html
        val connectOptions = MySQLConnectOptions()
          .setHost(SecretResolver.get("DATABASE_HOST", serverConfig.getString("database_host")))
          .setPort(SecretResolver.get("DATABASE_PORT")?.toIntOrNull() ?: serverConfig.getInteger("database_port"))
          .setDatabase(SecretResolver.get("DATABASE_NAME", serverConfig.getString("database_name")))
          .setUser(SecretResolver.get("DATABASE_USER", serverConfig.getString("database_user")))
          .setPassword(SecretResolver.get("DATABASE_PASSWORD", serverConfig.getString("database_pwd")))
        setDatabaseSslMode(serverConfig, connectOptions)

        val poolOptions = PoolOptions().setMaxSize(5)

        // Create the client pool
        sqlClient = MySQLPool.pool(vertx, connectOptions, poolOptions)

        eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
          val postData = receivedMessage.body()
          insertData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("updateData") { receivedMessage ->
          val postData = receivedMessage.body()
          updateData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("deleteData") { receivedMessage ->
          val postData = receivedMessage.body()
          deleteData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("getData") { receivedMessage ->
          val postData = receivedMessage.body()
          getData(
            device_id = postData.getString("device_id"),
            table = postData.getString("table"),
            start = postData.getDouble("start"),
            end = postData.getDouble("end")
          // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-handlers_changes-in-common-components
          ).onComplete { response ->
            receivedMessage.reply(response.result())
          }
        }

        // ---- GEOFENCE ALERT SYSTEM TABLES AND HANDLERS ----
        
        // Create geofence and alert system tables on startup
        createParticipantsTable()
        createParticipantDevicesTable()
        createRedZonesTable()
        createGeofenceAlertsTable()
        createSignatureAlertsTable()
        createSignatureAlertsArchiveTable()
        createSignatureAlertsLegacyTable()
        createAwareDbGeofenceAlertsTable()

        // ---- SENSOR DATA TABLES ----
        
        // Create sensor data tables on startup
        createAccelerometerTable()
        createGyroscopeTable()
        createLocationTable()
        createBatteryReadingsTable()
        createScreenEventsTable()
        createNotificationsTable()


        // ---- WEARABLE DATA TABLES ----
        createWearableHeartRateTable()
        createWearableStepsTable()
        createWearableSleepTable()
        createWearableBloodPressureTable()
        createWearableWeightTable()
        createWearableOxygenTable()
        createWearableRespiratoryTable()

        // ---- ANALYTICS / ENGINE TABLES ----
        createAnomalyHoursTable()
        createWatchDayProfilesTable()
        createEngineStateTable()
        createHourlyFeaturesTable()
        createWearableDailyFeaturesTable()
        createSecurityAuditLogTable()
        createAuthUsersTable().onSuccess {
          ensureBootstrapUsers()
        }
        createAuthSessionsTable()
        createAuthLoginAttemptsTable()

        // Get all participants
        eventBus.consumer<JsonObject>("getParticipants") { receivedMessage ->
          getAllParticipants().onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get participants")
            }
          }
        }

        // Get participant by device_id
        eventBus.consumer<JsonObject>("getParticipantByDevice") { receivedMessage ->
          val deviceId = receivedMessage.body().getString("device_id")
          getParticipantByDeviceId(deviceId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get participant")
            }
          }
        }

        // Insert/update participant
        eventBus.consumer<JsonObject>("upsertParticipant") { receivedMessage ->
          val data = receivedMessage.body()
          upsertParticipant(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true).put("participant_id", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to upsert participant")
            }
          }
        }

        // Get red zones for participant (includes global zones)
        eventBus.consumer<JsonObject>("getRedZones") { receivedMessage ->
          val participantId = receivedMessage.body().getString("participant_id")
          getRedZonesForParticipant(participantId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get red zones")
            }
          }
        }

        // Insert red zone
        eventBus.consumer<JsonObject>("insertRedZone") { receivedMessage ->
          val data = receivedMessage.body()
          insertRedZone(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true).put("zone_id", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert red zone")
            }
          }
        }

        // Delete red zone
        eventBus.consumer<JsonObject>("deleteRedZone") { receivedMessage ->
          val zoneId = receivedMessage.body().getString("zone_id")
          deleteRedZone(zoneId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to delete red zone")
            }
          }
        }

        // Get geofence alerts
        eventBus.consumer<JsonObject>("getGeofenceAlerts") { receivedMessage ->
          val activeOnly = receivedMessage.body().getBoolean("active_only", false)
          getGeofenceAlerts(activeOnly).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get alerts")
            }
          }
        }

        // Insert geofence alert
        eventBus.consumer<JsonObject>("insertGeofenceAlert") { receivedMessage ->
          val data = receivedMessage.body()
          insertGeofenceAlert(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true).put("alert_id", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert alert")
            }
          }
        }

        // Acknowledge alert
        eventBus.consumer<JsonObject>("acknowledgeAlert") { receivedMessage ->
          val alertId = receivedMessage.body().getString("alert_id")
          val acknowledgedBy = receivedMessage.body().getString("acknowledged_by", "admin")
          acknowledgeAlert(alertId, acknowledgedBy).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to acknowledge alert")
            }
          }
        }



        // Check for recent alert (duplicate prevention)
        eventBus.consumer<JsonObject>("checkRecentAlert") { receivedMessage ->
          val participantId = receivedMessage.body().getString("participant_id")
          val zoneId = receivedMessage.body().getString("zone_id")
          val windowMinutes = receivedMessage.body().getInteger("window_minutes", 30)
          checkRecentAlert(participantId, zoneId, windowMinutes).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("exists", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to check recent alert")
            }
          }
        }

        // Get latest location for participant
        eventBus.consumer<JsonObject>("getLatestLocation") { receivedMessage ->
          val deviceId = receivedMessage.body().getString("device_id")
          getLatestLocation(deviceId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get location")
            }
          }
        }

        // ---- SIGNATURE ALERT HANDLERS ----

        // Get signature alerts
        eventBus.consumer<JsonObject>("getSignatureAlerts") { receivedMessage ->
          val activeOnly = receivedMessage.body().getBoolean("active_only", false)
          val limitRaw = receivedMessage.body().getValue("limit")
          val limit = when (limitRaw) {
            null -> 10000
            is Number -> limitRaw.toInt()
            is String -> if (limitRaw.equals("all", ignoreCase = true)) null else limitRaw.toIntOrNull() ?: 10000
            else -> 10000
          }
          getSignatureAlerts(activeOnly, limit).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get signature alerts")
            }
          }
        }

        // Acknowledge signature alert
        eventBus.consumer<JsonObject>("acknowledgeSignatureAlert") { receivedMessage ->
          val id = receivedMessage.body().getLong("id")
          val acknowledgedBy = receivedMessage.body().getString("acknowledged_by", "admin")
          acknowledgeSignatureAlert(id, acknowledgedBy).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to acknowledge signature alert")
            }
          }
        }

        // ---- AUTH / RBAC HANDLERS ----
        eventBus.consumer<JsonObject>("authLogin") { receivedMessage ->
          val body = receivedMessage.body()
          val username = body.getString("username", "")
          val password = body.getString("password", "")
          val clientIp = body.getString("client_ip")
          val userAgent = body.getString("user_agent")
          loginUser(username, password, clientIp, userAgent).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(401, response.cause().message ?: "Invalid credentials")
            }
          }
        }

        eventBus.consumer<JsonObject>("authValidateToken") { receivedMessage ->
          val token = receivedMessage.body().getString("token", "")
          validateSessionToken(token).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(401, response.cause().message ?: "Unauthorized")
            }
          }
        }

        eventBus.consumer<JsonObject>("authLogout") { receivedMessage ->
          val token = receivedMessage.body().getString("token", "")
          revokeSessionToken(token).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to logout")
            }
          }
        }

        eventBus.consumer<JsonObject>("listAppUsers") { receivedMessage ->
          listAppUsers().onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to list users")
            }
          }
        }

        eventBus.consumer<JsonObject>("upsertAppUser") { receivedMessage ->
          val body = receivedMessage.body()
          val actor = body.getString("actor", "admin")
          upsertAppUser(body, actor).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to upsert user")
            }
          }
        }

        eventBus.consumer<JsonObject>("listAuthSessions") { receivedMessage ->
          val limit = receivedMessage.body().getInteger("limit", 200)
          listActiveAuthSessions(limit).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to list active sessions")
            }
          }
        }

        eventBus.consumer<JsonObject>("revokeAuthSessionById") { receivedMessage ->
          val sessionId = receivedMessage.body().getLong("id")
          revokeSessionById(sessionId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", response.result()))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to revoke session")
            }
          }
        }

        eventBus.consumer<JsonObject>("getSecurityAuditEvents") { receivedMessage ->
          val limit = receivedMessage.body().getInteger("limit", 200)
          getSecurityAuditEvents(limit).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to list audit events")
            }
          }
        }

        eventBus.consumer<JsonObject>("getSecurityStatus") { receivedMessage ->
          getSecurityStatus().onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get security status")
            }
          }
        }

        eventBus.consumer<JsonObject>("getLoginLockouts") { receivedMessage ->
          val limit = receivedMessage.body().getInteger("limit", 100)
          getLoginLockouts(limit).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get login lockouts")
            }
          }
        }

        // ---- SENSOR DATA EVENT HANDLERS ----

        // Insert battery reading
        eventBus.consumer<JsonObject>("insertBatteryReading") { receivedMessage ->
          val data = receivedMessage.body()
          insertBatteryReading(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert battery reading")
            }
          }
        }

        // Insert screen event
        eventBus.consumer<JsonObject>("insertScreenEvent") { receivedMessage ->
          val data = receivedMessage.body()
          insertScreenEvent(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert screen event")
            }
          }
        }

        // Insert notification
        eventBus.consumer<JsonObject>("insertNotification") { receivedMessage ->
          val data = receivedMessage.body()
          insertNotification(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert notification")
            }
          }
        }

        // Insert accelerometer reading
        eventBus.consumer<JsonObject>("insertAccelerometer") { receivedMessage ->
          val data = receivedMessage.body()
          insertAccelerometer(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert accelerometer")
            }
          }
        }

        // Insert gyroscope reading
        eventBus.consumer<JsonObject>("insertGyroscope") { receivedMessage ->
          val data = receivedMessage.body()
          insertGyroscope(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert gyroscope")
            }
          }
        }

        // Insert location reading
        eventBus.consumer<JsonObject>("insertLocation") { receivedMessage ->
          val data = receivedMessage.body()
          insertLocation(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert location")
            }
          }
        }

        // Get latest battery for device
        eventBus.consumer<JsonObject>("getLatestBattery") { receivedMessage ->
          val deviceId = receivedMessage.body().getString("device_id")
          getLatestBattery(deviceId).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get battery")
            }
          }
        }

        // ---- WEARABLE DATA EVENT HANDLERS ----

        eventBus.consumer<JsonObject>("insertWearableHeartRate") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableHeartRate(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable heart rate")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableSteps") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableSteps(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable steps")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableSleep") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableSleep(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable sleep")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableBloodPressure") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableBloodPressure(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable blood pressure")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableWeight") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableWeight(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable weight")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableOxygen") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableOxygen(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable oxygen")
            }
          }
        }

        eventBus.consumer<JsonObject>("insertWearableRespiratory") { receivedMessage ->
          val data = receivedMessage.body()
          insertWearableRespiratory(data).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to insert wearable respiratory")
            }
          }
        }
      }
    }
  }

  //Fetch data from the database and return results as JsonArray
  fun getData(device_id: String, table: String, start: Double, end: Double): Future<JsonArray> {

    val dataPromise: Promise<JsonArray> = Promise.promise()
    val safeTable = try {
      requireSafeTableName(table)
    } catch (e: Exception) {
      return Future.failedFuture(e)
    }

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = "SELECT * FROM `$safeTable` WHERE device_id = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp ASC"
        connection
          .preparedQuery(query)
          .execute(Tuple.of(device_id, start, end))
          .onFailure { e ->
            logger.error(e) { "Failed to retrieve data." }
            connection.close()
            dataPromise.fail(e.message)
          }
          .onSuccess { rows ->
            logger.info { "$device_id : retrieved ${rows.size()} records from $safeTable" }
            connection.close()
            dataPromise.complete(JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row ->
                val doc = row.toJson()
                if (safeTable == "notifications") {
                  decryptFields(doc, "title", "content")
                } else {
                  doc
                }
              }
              .collect(Collectors.toList())))
          }
      }
    }

    return dataPromise.future()
  }

  fun updateData(device_id: String, table: String, data: JsonArray) {
    val safeTable = try {
      requireSafeTableName(table)
    } catch (e: Exception) {
      logger.warn { "Rejected unsafe table name for updateData: $table" }
      return
    }
    if (data.isEmpty()) return
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = "UPDATE `$safeTable` SET data = CAST(? AS JSON) WHERE device_id = ? AND timestamp = ?"
        val batch = ArrayList<Tuple>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          val ts = entry.getDouble("timestamp")
          batch.add(Tuple.of(entry.encode(), device_id, ts))
        }
        connection.preparedQuery(query)
          .executeBatch(batch)
          .onFailure { e ->
            logger.error(e) { "Failed to process update." }
            connection.close()
          }
          .onSuccess {
            logger.info { "$device_id updated $safeTable: ${data.size()} records" }
            connection.close()
          }
      } else {
        logger.error(connectionResult.cause()) { "Failed to establish connection." }
      }
    }
  }

  fun deleteData(device_id: String, table: String, data: JsonArray) {
    val safeTable = try {
      requireSafeTableName(table)
    } catch (e: Exception) {
      logger.warn { "Rejected unsafe table name for deleteData: $table" }
      return
    }
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val timestamps = mutableListOf<Double>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          timestamps.add(entry.getDouble("timestamp"))
        }
        if (timestamps.isEmpty()) {
          connection.close()
          return@getConnection
        }

        val placeholders = (1..timestamps.size).joinToString(",") { "?" }
        val query = "DELETE FROM `$safeTable` WHERE device_id = ? AND timestamp in ($placeholders)"
        val tuple = Tuple.tuple().addValue(device_id)
        timestamps.forEach { tuple.addValue(it) }
        connection.preparedQuery(query)
          .execute(tuple)
          .onFailure { e ->
            logger.error(e) { "Failed to process delete batch." }
            connection.close()
          }
          .onSuccess { _ ->
            logger.info { "$device_id deleted from $safeTable: ${data.size()} records" }
            connection.close()
          }
      } else {
        logger.error(connectionResult.cause()) { "Failed to establish connection." }
      }
    }
  }

  /**
   * Create a database table if it doesn't exist
   */
  fun createTable(table: String): Future<Boolean> {
    val safeTable = try {
      requireSafeTableName(table)
    } catch (e: Exception) {
      return Future.failedFuture(e)
    }
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connect = connectionResult.result()
        val queryCreateTable = "CREATE TABLE IF NOT EXISTS `$safeTable` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL, INDEX `timestamp_device` (`timestamp`, `device_id`))"
        connect.query(queryCreateTable)
          .execute()
          .onFailure { e ->
            logger.error(e) { "Failed in: $queryCreateTable" }
            promise.fail(e.message)
            connect.close()
          }
          .onSuccess { _ ->
            logger.debug { "Created table \"$table\" successfully: $queryCreateTable" }
            promise.complete(true)
            connect.close()
          }
      } else {
        logger.error(connectionResult.cause()) { "Failed to connect to database for creating a table." }
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  /**
   * Insert batch of data into database table
   */
  fun insertData(table: String, device_id: String, data: JsonArray) {
    if (data.isEmpty()) {
      return
    }
    val safeTable = try {
      requireSafeTableName(table)
    } catch (e: Exception) {
      logger.warn { "Rejected unsafe table name for insertData: $table" }
      return
    }

    createTable(safeTable)
      .onSuccess { _ ->
        sqlClient.getConnection { connectionResult ->
          if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val rows = data.size()
            val batch = ArrayList<Tuple>()
            for (i in 0 until data.size()) {
              val entry = data.getJsonObject(i)
              batch.add(Tuple.of(device_id, entry.getDouble("timestamp"), entry.encode()))
            }
            val query = "INSERT INTO `$safeTable` (`device_id`,`timestamp`,`data`) VALUES (?, ?, CAST(? AS JSON))"
            connection.preparedQuery(query)
              .executeBatch(batch)
              .onFailure { e ->
                logger.error(e) { "Failed to process batch." }
                connection.close()
              }
              .onSuccess { _ ->
                logger.info { "$device_id inserted to $safeTable: $rows records" }
                connection.close()
              }
          }
        }
      }
      .onFailure { e ->
        logger.error(e) { "Failed to create table." }
      }
  }

  // ---- GEOFENCE SYSTEM TABLE CREATION ----

  fun createParticipantsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `participants` (
            `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            `participant_id` VARCHAR(128) NOT NULL,
            `name` VARCHAR(100) NOT NULL,
            `red_zone_radius` INT DEFAULT 300,
            `status` ENUM('active', 'inactive') DEFAULT 'active',
            `risk_level` ENUM('low', 'moderate', 'high') DEFAULT 'low',
            `device_id` VARCHAR(128) NULL,
            `device_type` VARCHAR(32) NULL DEFAULT 'unknown',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY `ux_participant_id` (`participant_id`),
            INDEX `idx_device` (`device_id`),
            INDEX `idx_status` (`status`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            connection
              .query("SHOW COLUMNS FROM participants LIKE 'id'")
              .execute()
              .onSuccess { idRows ->
                val ensureIdFuture = if (idRows.size() == 0) {
                  connection.query(
                    """
                    ALTER TABLE participants
                      DROP PRIMARY KEY,
                      ADD COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST
                    """.trimIndent()
                  ).execute()
                } else {
                  Future.succeededFuture()
                }

                ensureIdFuture
                  .compose {
                    connection.query("SHOW COLUMNS FROM participants LIKE 'device_id'").execute()
                  }
                  .compose { deviceRows ->
                    if (deviceRows.size() == 0) {
                      connection.query("ALTER TABLE participants ADD COLUMN device_id VARCHAR(128) NULL AFTER risk_level").execute()
                    } else {
                      Future.succeededFuture()
                    }
                  }
                  .compose {
                    connection.query("SHOW COLUMNS FROM participants LIKE 'device_type'").execute()
                  }
                  .compose { deviceTypeRows ->
                    if (deviceTypeRows.size() == 0) {
                      connection.query("ALTER TABLE participants ADD COLUMN device_type VARCHAR(32) NOT NULL DEFAULT 'unknown' AFTER device_id").execute()
                    } else {
                      Future.succeededFuture()
                    }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE participants MODIFY COLUMN participant_id VARCHAR(128) NOT NULL")
                      .execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE participants MODIFY COLUMN device_id VARCHAR(128) NULL")
                      .execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE participants MODIFY COLUMN device_type VARCHAR(32) NULL DEFAULT 'unknown'")
                      .execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query(
                      """
                      DELETE p1
                      FROM participants p1
                      INNER JOIN participants p2
                        ON p1.participant_id = p2.participant_id
                       AND p1.id < p2.id
                      """.trimIndent()
                    ).execute().recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query("CREATE UNIQUE INDEX ux_participant_id ON participants(participant_id)").execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query("CREATE INDEX idx_device ON participants(device_id)").execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query("CREATE INDEX idx_status ON participants(status)").execute()
                      .recover { Future.succeededFuture() }
                  }
                  .onSuccess {
                    logger.info { "Created/migrated participants table" }
                    promise.complete(true)
                    connection.close()
                  }
                  .onFailure { e ->
                    logger.error(e) { "Failed to migrate participants table" }
                    promise.fail(e.message)
                    connection.close()
                  }
              }
              .onFailure { e ->
                logger.error(e) { "Failed to inspect participants table" }
                promise.fail(e.message)
                connection.close()
              }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create participants table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createParticipantDevicesTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `participant_devices` (
            `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            `participant_id` VARCHAR(128) NOT NULL,
            `device_id` VARCHAR(128) NOT NULL,
            `device_type` VARCHAR(32) NOT NULL DEFAULT 'unknown',
            `is_primary` BOOLEAN NOT NULL DEFAULT TRUE,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY `uq_participant_device` (`participant_id`, `device_id`),
            UNIQUE KEY `uq_participant_devices_device_id` (`device_id`),
            INDEX `idx_participant_devices_participant` (`participant_id`),
            INDEX `idx_participant_devices_type` (`device_type`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            connection
              .query("ALTER TABLE participant_devices MODIFY COLUMN participant_id VARCHAR(128) NOT NULL")
              .execute()
              .recover { Future.succeededFuture() }
              .compose {
                connection.query("ALTER TABLE participant_devices MODIFY COLUMN device_id VARCHAR(128) NOT NULL").execute()
                  .recover { Future.succeededFuture() }
              }
              .compose {
                connection.query("ALTER TABLE participant_devices MODIFY COLUMN device_type VARCHAR(32) NOT NULL DEFAULT 'unknown'").execute()
                  .recover { Future.succeededFuture() }
              }
              .compose {
                connection.query(
                  """
                  INSERT INTO participant_devices (
                    participant_id, device_id, device_type, is_primary, created_at, updated_at
                  )
                  SELECT
                    participant_id,
                    device_id,
                    LOWER(COALESCE(NULLIF(device_type, ''), 'unknown')) AS device_type,
                    TRUE,
                    COALESCE(created_at, NOW()),
                    COALESCE(updated_at, NOW())
                  FROM participants
                  WHERE device_id IS NOT NULL AND TRIM(device_id) <> ''
                  ON DUPLICATE KEY UPDATE
                    participant_id = VALUES(participant_id),
                    device_type = IF(VALUES(device_type) = 'unknown', participant_devices.device_type, VALUES(device_type)),
                    updated_at = NOW()
                  """.trimIndent()
                ).execute().recover { Future.succeededFuture() }
              }
              .onSuccess {
                logger.info { "Created/migrated participant_devices table" }
                promise.complete(true)
                connection.close()
              }
              .onFailure { e ->
                logger.error(e) { "Failed to migrate participant_devices table" }
                promise.fail(e.message)
                connection.close()
              }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create participant_devices table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createRedZonesTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `red_zones` (
            `zone_id` VARCHAR(36) PRIMARY KEY,
            `participant_id` VARCHAR(36) NULL,
            `name` VARCHAR(100) NOT NULL,
            `latitude` DOUBLE NOT NULL,
            `longitude` DOUBLE NOT NULL,
            `radius` INT DEFAULT 300,
            `zone_type` ENUM('bar', 'dealer', 'relapse', 'custom') DEFAULT 'custom',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_participant` (`participant_id`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created red_zones table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create red_zones table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createGeofenceAlertsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `geofence_alerts` (
            `alert_id` VARCHAR(36) PRIMARY KEY,
            `participant_id` VARCHAR(36) NOT NULL,
            `zone_id` VARCHAR(36) NOT NULL,
            `zone_name` VARCHAR(100),
            `latitude` DOUBLE NOT NULL,
            `longitude` DOUBLE NOT NULL,
            `distance` DOUBLE NOT NULL,
            `triggered_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `acknowledged` BOOLEAN DEFAULT FALSE,
            `acknowledged_by` VARCHAR(100) NULL,
            `acknowledged_at` TIMESTAMP NULL,
            INDEX `idx_participant` (`participant_id`),
            INDEX `idx_acknowledged` (`acknowledged`),
            INDEX `idx_triggered` (`triggered_at`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created geofence_alerts table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create geofence_alerts table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  /**
   * Create signature_alerts table for storing signature-based anomaly alerts
   */
  fun createSignatureAlertsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `signature_alerts` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `participant_id` VARCHAR(128) NOT NULL,
            `device_id` VARCHAR(128) NULL,
            `source_type` VARCHAR(16) NULL,
            `hour_start` DATETIME NOT NULL,
            `alert_code` VARCHAR(32) NOT NULL,
            `alert_name` VARCHAR(255) NULL,
            `severity` VARCHAR(16) NULL,
            `score` DOUBLE NULL,
            `baseline_ref` VARCHAR(64) NULL,
            `top_features_json` JSON NULL,
            `explanation` TEXT NULL,
            `status` VARCHAR(32) DEFAULT 'active',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `acknowledged_at` TIMESTAMP NULL,
            `acknowledged_by` VARCHAR(100) NULL,
            UNIQUE KEY `uniq_alert` (`participant_id`, `hour_start`, `alert_code`),
            INDEX `idx_sa_participant` (`participant_id`),
            INDEX `idx_sa_device` (`device_id`),
            INDEX `idx_sa_source_type` (`source_type`),
            INDEX `idx_sa_created` (`created_at`),
            INDEX `idx_sa_status` (`status`),
            INDEX `idx_sa_ack` (`acknowledged_at`)
          )
        """.trimIndent()

        connection.query(query).execute()
          .onSuccess {
            connection
              .query("SHOW COLUMNS FROM signature_alerts")
              .execute()
              .compose { rows ->
                val columns = rows.mapNotNull { row -> row.getString("Field") }.toSet()

                val ensureAcknowledgedAt = if (columns.contains("acknowledged_at")) {
                  connection.query("SELECT 1").execute()
                } else {
                  connection.query("ALTER TABLE signature_alerts ADD COLUMN acknowledged_at TIMESTAMP NULL").execute()
                }

                ensureAcknowledgedAt
                  .compose {
                    if (columns.contains("acknowledged_by")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN acknowledged_by VARCHAR(100) NULL").execute()
                    }
                  }
                  .compose {
                    if (columns.contains("status")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN status VARCHAR(32) DEFAULT 'active'").execute()
                    }
                  }
                  .compose {
                    if (columns.contains("hour_start_iso")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN hour_start_iso VARCHAR(32) NULL").execute()
                    }
                  }
                  .compose {
                    if (columns.contains("hour_start_ts")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN hour_start_ts BIGINT NULL").execute()
                    }
                  }
                  .compose {
                    if (columns.contains("device_id")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN device_id VARCHAR(128) NULL AFTER participant_id").execute()
                    }
                  }
                  .compose {
                    if (columns.contains("source_type")) {
                      connection.query("SELECT 1").execute()
                    } else {
                      connection.query("ALTER TABLE signature_alerts ADD COLUMN source_type VARCHAR(16) NULL AFTER device_id").execute()
                    }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE signature_alerts MODIFY COLUMN participant_id VARCHAR(128) NOT NULL")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE signature_alerts MODIFY COLUMN device_id VARCHAR(128) NULL")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("ALTER TABLE signature_alerts MODIFY COLUMN source_type VARCHAR(16) NULL")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query(
                        """
                        UPDATE signature_alerts
                        SET source_type = CASE
                          WHEN UPPER(COALESCE(alert_code, '')) LIKE 'W%' THEN 'watch'
                          ELSE 'phone'
                        END
                        WHERE source_type IS NULL OR TRIM(source_type) = ''
                        """.trimIndent()
                      )
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_participant ON signature_alerts(participant_id)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_device ON signature_alerts(device_id)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_source_type ON signature_alerts(source_type)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_created ON signature_alerts(created_at)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_status ON signature_alerts(status)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
                  .compose {
                    connection
                      .query("CREATE INDEX idx_sa_ack ON signature_alerts(acknowledged_at)")
                      .execute()
                      .recover { connection.query("SELECT 1").execute() }
                  }
              }
              .onSuccess {
                logger.info { "Created/migrated signature_alerts table" }
                promise.complete(true)
                connection.close()
              }
              .onFailure { e ->
                logger.error(e) { "Failed to migrate signature_alerts table" }
                promise.fail(e.message)
                connection.close()
              }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create signature_alerts table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  private fun createTableWithQuery(tableName: String, query: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created $tableName table (if missing)" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create $tableName table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createSignatureAlertsArchiveTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `signature_alerts_archive` (
        `id` BIGINT NOT NULL AUTO_INCREMENT,
        `participant_id` VARCHAR(36) NOT NULL,
        `hour_start` DATETIME NOT NULL,
        `alert_code` VARCHAR(32) NOT NULL,
        `alert_name` VARCHAR(255) DEFAULT NULL,
        `severity` VARCHAR(16) DEFAULT NULL,
        `score` DOUBLE DEFAULT NULL,
        `baseline_ref` VARCHAR(64) DEFAULT NULL,
        `top_features_json` JSON DEFAULT NULL,
        `explanation` TEXT,
        `status` VARCHAR(32) DEFAULT 'active',
        `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
        `acknowledged_at` TIMESTAMP NULL DEFAULT NULL,
        `acknowledged_by` VARCHAR(100) DEFAULT NULL,
        `hour_start_iso` VARCHAR(32) DEFAULT NULL,
        `hour_start_ts` BIGINT DEFAULT NULL,
        PRIMARY KEY (`id`),
        UNIQUE KEY `uniq_alert` (`participant_id`, `hour_start`, `alert_code`),
        KEY `idx_sa_participant` (`participant_id`),
        KEY `idx_sa_created` (`created_at`),
        KEY `idx_sa_status` (`status`),
        KEY `idx_sa_ack` (`acknowledged_at`)
      )
    """.trimIndent()
    return createTableWithQuery("signature_alerts_archive", query)
  }

  fun createSignatureAlertsLegacyTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `signature_alerts_legacy` (
        `id` BIGINT NOT NULL AUTO_INCREMENT,
        `participant_id` VARCHAR(36) NOT NULL,
        `hour_start` DATETIME NOT NULL,
        `alert_code` VARCHAR(32) NOT NULL,
        `alert_name` VARCHAR(255) DEFAULT NULL,
        `severity` VARCHAR(16) DEFAULT NULL,
        `score` DOUBLE DEFAULT NULL,
        `baseline_ref` VARCHAR(64) DEFAULT NULL,
        `top_features_json` JSON DEFAULT NULL,
        `explanation` TEXT,
        `status` VARCHAR(32) DEFAULT 'active',
        `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
        `acknowledged_at` TIMESTAMP NULL DEFAULT NULL,
        `acknowledged_by` VARCHAR(100) DEFAULT NULL,
        `hour_start_iso` VARCHAR(32) DEFAULT NULL,
        `hour_start_ts` BIGINT DEFAULT NULL,
        PRIMARY KEY (`id`),
        UNIQUE KEY `uniq_alert` (`participant_id`, `hour_start`, `alert_code`),
        KEY `idx_sa_participant` (`participant_id`),
        KEY `idx_sa_created` (`created_at`),
        KEY `idx_sa_status` (`status`),
        KEY `idx_sa_ack` (`acknowledged_at`)
      )
    """.trimIndent()
    return createTableWithQuery("signature_alerts_legacy", query)
  }

  fun createAwareDbGeofenceAlertsTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `aware_db_geofence_alerts` (
        `Column1` VARCHAR(50) DEFAULT NULL
      )
    """.trimIndent()
    return createTableWithQuery("aware_db_geofence_alerts", query)
  }

  fun createAnomalyHoursTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `anomaly_hours` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `device_id` VARCHAR(128) NOT NULL,
        `hour_start_ts` BIGINT NOT NULL,
        `hour_start_utc` VARCHAR(32) NOT NULL,
        `anomaly_type` VARCHAR(32) NOT NULL,
        `created_at` TEXT NOT NULL,
        INDEX `idx_device` (`device_id`),
        INDEX `idx_hour_start_ts` (`hour_start_ts`),
        INDEX `idx_anom_dev_hour` (`device_id`, `hour_start_ts`)
      )
    """.trimIndent()
    return createTableWithQuery("anomaly_hours", query)
  }

  fun createWatchDayProfilesTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `watch_day_profiles` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `device_id` VARCHAR(128) NOT NULL,
        `day_start` BIGINT NOT NULL,
        `profile` VARCHAR(32) NOT NULL,
        `created_at` TEXT NOT NULL,
        INDEX `idx_device` (`device_id`),
        INDEX `idx_day_start` (`day_start`)
      )
    """.trimIndent()
    return createTableWithQuery("watch_day_profiles", query)
  }

  fun createEngineStateTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `engine_state` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `participant_id` VARCHAR(128) NOT NULL,
        `engine_name` VARCHAR(128) NOT NULL,
        `last_processed_hour_start` DATETIME NULL,
        `last_processed_day_start` DATETIME NULL,
        `last_trained_hour_start` DATETIME NULL,
        `threshold` DOUBLE NULL,
        `updated_at` DATETIME NOT NULL,
        UNIQUE KEY `uq_engine_state` (`participant_id`, `engine_name`)
      )
    """.trimIndent()
    return createTableWithQuery("engine_state", query)
  }

  fun createHourlyFeaturesTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `hourly_features` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `participant_id` VARCHAR(128) NOT NULL,
        `hour_start` DATETIME NOT NULL,
        `accel_n` INT NULL,
        `gyro_n` INT NULL,
        `gps_n` INT NULL,
        `screen_event_n` INT NULL,
        `acc_mag_mean` DOUBLE NULL,
        `acc_mag_std` DOUBLE NULL,
        `acc_jerk_mean` DOUBLE NULL,
        `acc_mag_p95` DOUBLE NULL,
        `acc_inactive_ratio` DOUBLE NULL,
        `gyro_mag_mean` DOUBLE NULL,
        `gyro_mag_std` DOUBLE NULL,
        `gyro_mag_p95` DOUBLE NULL,
        `gps_points` INT NULL,
        `gps_mean_accuracy` DOUBLE NULL,
        `gps_distance_m` DOUBLE NULL,
        `gps_stationary_ratio` DOUBLE NULL,
        `screen_on_events` INT NULL,
        `screen_off_events` INT NULL,
        `screen_sessions` INT NULL,
        `screen_on_seconds` DOUBLE NULL,
        `screen_avg_session_seconds` DOUBLE NULL,
        UNIQUE KEY `uq_hourly_features` (`participant_id`, `hour_start`),
        KEY `idx_hourly_features_hour_start` (`hour_start`)
      )
    """.trimIndent()
    return createTableWithQuery("hourly_features", query)
  }

  fun createWearableDailyFeaturesTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `wearable_daily_features` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `participant_id` VARCHAR(128) NOT NULL,
        `day_start` DATETIME NOT NULL,
        `steps_total` DOUBLE NULL,
        `sleep_minutes` DOUBLE NULL,
        `sleep_episode_n` INT NULL,
        `sleep_start_hour` DOUBLE NULL,
        `sleep_end_hour` DOUBLE NULL,
        `sleep_midpoint_hour` DOUBLE NULL,
        `day_hr_mean` DOUBLE NULL,
        `night_hr_mean` DOUBLE NULL,
        `resting_hr_p10` DOUBLE NULL,
        `rmssd_night` DOUBLE NULL,
        `sdnn_night` DOUBLE NULL,
        UNIQUE KEY `uq_wearable_daily_features` (`participant_id`, `day_start`),
        KEY `idx_wearable_daily_day_start` (`day_start`)
      )
    """.trimIndent()
    return createTableWithQuery("wearable_daily_features", query)
  }

  fun createSecurityAuditLogTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `security_audit_log` (
        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
        `event_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        `actor` VARCHAR(128) NULL,
        `action` VARCHAR(64) NOT NULL,
        `target_type` VARCHAR(64) NOT NULL,
        `target_id` VARCHAR(128) NULL,
        `details` JSON NULL,
        INDEX `idx_event_at` (`event_at`),
        INDEX `idx_action` (`action`),
        INDEX `idx_actor` (`actor`),
        INDEX `idx_target_type` (`target_type`)
      )
    """.trimIndent()
    return createTableWithQuery("security_audit_log", query)
  }

  fun createAuthUsersTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `app_users` (
        `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        `username` VARCHAR(64) NOT NULL,
        `password_hash` VARCHAR(255) NOT NULL,
        `role` VARCHAR(32) NOT NULL DEFAULT 'viewer',
        `full_name` VARCHAR(128) NULL,
        `email` VARCHAR(255) NULL,
        `status` VARCHAR(16) NOT NULL DEFAULT 'active',
        `last_login_at` DATETIME NULL,
        `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY `uq_app_users_username` (`username`),
        INDEX `idx_app_users_role` (`role`),
        INDEX `idx_app_users_status` (`status`)
      )
    """.trimIndent()
    return createTableWithQuery("app_users", query)
  }

  fun createAuthSessionsTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `auth_sessions` (
        `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        `user_id` BIGINT UNSIGNED NOT NULL,
        `token_hash` CHAR(64) NOT NULL,
        `expires_at` DATETIME NOT NULL,
        `client_ip` VARCHAR(64) NULL,
        `user_agent` VARCHAR(255) NULL,
        `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        `last_seen_at` DATETIME NULL,
        `revoked_at` DATETIME NULL,
        UNIQUE KEY `uq_auth_sessions_token_hash` (`token_hash`),
        INDEX `idx_auth_sessions_user_id` (`user_id`),
        INDEX `idx_auth_sessions_expires` (`expires_at`),
        INDEX `idx_auth_sessions_revoked` (`revoked_at`)
      )
    """.trimIndent()
    return createTableWithQuery("auth_sessions", query)
  }

  fun createAuthLoginAttemptsTable(): Future<Boolean> {
    val query = """
      CREATE TABLE IF NOT EXISTS `auth_login_attempts` (
        `username` VARCHAR(64) PRIMARY KEY,
        `failed_count` INT NOT NULL DEFAULT 0,
        `first_failed_at` DATETIME NULL,
        `last_failed_at` DATETIME NULL,
        `locked_until` DATETIME NULL,
        `last_ip` VARCHAR(64) NULL,
        `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        INDEX `idx_auth_login_attempts_locked_until` (`locked_until`),
        INDEX `idx_auth_login_attempts_last_failed_at` (`last_failed_at`)
      )
    """.trimIndent()
    return createTableWithQuery("auth_login_attempts", query)
  }

  private fun ensureBootstrapUsers() {
    ensureBootstrapUser(
      username = (SecretResolver.get("APP_ADMIN_USERNAME", "admin") ?: "admin").trim(),
      password = (SecretResolver.get("APP_ADMIN_PASSWORD", "capstone") ?: "capstone").trim(),
      role = "admin",
      fullName = "Administrator",
      defaultPassword = "capstone",
      passwordEnvName = "APP_ADMIN_PASSWORD"
    )

    ensureBootstrapUser(
      username = (SecretResolver.get("APP_DOCTOR_USERNAME", "doctor") ?: "doctor").trim(),
      password = (SecretResolver.get("APP_DOCTOR_PASSWORD", "Doctor@12345") ?: "Doctor@12345").trim(),
      role = "doctor",
      fullName = "Doctor User",
      defaultPassword = "Doctor@12345",
      passwordEnvName = "APP_DOCTOR_PASSWORD"
    )
  }

  private fun ensureBootstrapUser(
    username: String,
    password: String,
    role: String,
    fullName: String,
    defaultPassword: String,
    passwordEnvName: String
  ) {
    if (username.isEmpty() || password.isEmpty()) return

    val passwordHash = AuthSecurity.hashPassword(password)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        logger.error(connectionResult.cause()) { "Failed to ensure bootstrap user '$username' (db connection)." }
        return@getConnection
      }

      val connection = connectionResult.result()
      connection.preparedQuery(
        """
          INSERT INTO app_users (username, password_hash, role, full_name, status, created_at, updated_at)
          VALUES (?, ?, ?, ?, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
          ON DUPLICATE KEY UPDATE username = username
        """.trimIndent()
      ).execute(Tuple.of(username, passwordHash, role, fullName))
        .onSuccess {
          if (password == defaultPassword) {
            logger.warn { "Default password is set for '$username'. Change $passwordEnvName for production use." }
          }
          connection.close()
        }
        .onFailure { e ->
          logger.error(e) { "Failed to ensure bootstrap user '$username'." }
          connection.close()
        }
    }
  }

  // ---- SENSOR DATA TABLE CREATION ----

  fun createBatteryReadingsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `battery_readings` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `percentage` DOUBLE NOT NULL,
            `charging_status` VARCHAR(20) DEFAULT 'unknown',
            `timestamp` BIGINT NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created battery_readings table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create battery_readings table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createScreenEventsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `screen_events` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `utc_time` DATETIME NULL,
            `event` VARCHAR(50) NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created screen_events table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create screen_events table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createNotificationsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `notifications` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `app_name` VARCHAR(256),
            `title` TEXT,
            `content` TEXT,
            `category` VARCHAR(50),
            `kind` VARCHAR(20) DEFAULT 'posted',
            `timestamp` BIGINT NOT NULL,
            `dismissed_at` BIGINT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`),
            INDEX `idx_app` (`app_name`),
            INDEX `idx_kind` (`kind`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created notifications table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create notifications table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }



  // ---- SENSOR DATA INSERT FUNCTIONS ----

  fun insertBatteryReading(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val percentage = data.getDouble("percentage")
    val chargingStatus = data.getString("charging_status", "unknown")
    val timestamp = data.getLong("timestamp")
    
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO battery_readings (device_id, percentage, charging_status, timestamp)
          VALUES (?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, percentage, chargingStatus, timestamp))
          .onSuccess {
            logger.info { "Inserted battery reading for $deviceId: $percentage% ($chargingStatus)" }
            
            // Publish update to event bus for real-time dashboard updates
            val updateData = JsonObject()
              .put("device_id", deviceId)
              .put("percentage", percentage)
              .put("charging_status", chargingStatus)
              .put("timestamp", timestamp)
            vertx.eventBus().publish("battery.update", updateData)
            
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert battery reading" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertScreenEvent(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val rawState = data.getString("state") ?: data.getString("event") ?: "UNKNOWN"
    val event = when (rawState.uppercase()) {
      "ON" -> "Screen turned on"
      "OFF" -> "Screen turned off"
      else -> rawState
    }
    val timestamp = data.getLong("timestamp")
    val utcTime = java.time.Instant.ofEpochMilli(timestamp)
      .atZone(java.time.ZoneOffset.UTC)
      .toLocalDateTime()
      .toString()
    
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO `screen_events` (`device_id`, `timestamp`, `utc_time`, `event`)
          VALUES (?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, utcTime, event))
          .onSuccess {
            logger.info { "Inserted screen event for $deviceId: $event" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert screen event" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- DEDICATED SENSOR TABLE CREATION ----

  fun createAccelerometerTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `accelerometer` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `utc_time` DATETIME NULL,
            `x` DOUBLE NULL,
            `x_enc` TEXT NULL,
            `y` DOUBLE NULL,
            `y_enc` TEXT NULL,
            `z` DOUBLE NULL,
            `z_enc` TEXT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            connection
              .query("SHOW COLUMNS FROM accelerometer LIKE 'x_enc'")
              .execute()
              .compose { rows ->
                if (rows.size() == 0) {
                  connection.query("ALTER TABLE accelerometer ADD COLUMN x_enc TEXT NULL AFTER x").execute()
                } else {
                  connection.query("SELECT 1").execute()
                }
              }
              .compose {
                connection.query("SHOW COLUMNS FROM accelerometer LIKE 'y_enc'").execute()
              }
              .compose { rows ->
                if (rows.size() == 0) {
                  connection.query("ALTER TABLE accelerometer ADD COLUMN y_enc TEXT NULL AFTER y").execute()
                } else {
                  connection.query("SELECT 1").execute()
                }
              }
              .compose {
                connection.query("SHOW COLUMNS FROM accelerometer LIKE 'z_enc'").execute()
              }
              .compose { rows ->
                if (rows.size() == 0) {
                  connection.query("ALTER TABLE accelerometer ADD COLUMN z_enc TEXT NULL AFTER z").execute()
                } else {
                  connection.query("SELECT 1").execute()
                }
              }
              .onSuccess {
                logger.info { "Created/migrated accelerometer table" }
                promise.complete(true)
                connection.close()
              }
              .onFailure { e ->
                logger.error(e) { "Failed to migrate accelerometer table" }
                promise.fail(e.message)
                connection.close()
              }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create accelerometer table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createGyroscopeTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `gyroscope` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `utc_time` DATETIME NULL,
            `x` DOUBLE NULL,
            `y` DOUBLE NULL,
            `z` DOUBLE NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created gyroscope table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create gyroscope table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createLocationTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `location` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `utc_time` DATETIME NULL,
            `latitude` DOUBLE NULL,
            `longitude` DOUBLE NULL,
            `altitude` DOUBLE NULL,
            `accuracy` DOUBLE NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            connection.query("SHOW COLUMNS FROM location LIKE 'coordinates'").execute()
              .onSuccess { coordinateRows ->
                val normalizeCoordinates = if (coordinateRows.size() > 0) {
                  connection.query("ALTER TABLE location MODIFY COLUMN coordinates JSON NULL").execute()
                } else {
                  connection.query("ALTER TABLE location ADD COLUMN coordinates JSON NULL AFTER accuracy").execute()
                }

                normalizeCoordinates
                  .compose {
                    connection.query("SHOW COLUMNS FROM location LIKE 'created_at'").execute()
                  }
                  .compose { createdAtRows ->
                    if (createdAtRows.size() > 0) {
                      connection.query("ALTER TABLE location MODIFY COLUMN created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP").execute()
                    } else {
                      Future.succeededFuture()
                    }
                  }
                  .onSuccess {
                    logger.info { "Created/migrated location table" }
                    promise.complete(true)
                    connection.close()
                  }
                  .onFailure { e ->
                    logger.error(e) { "Failed to migrate location table" }
                    promise.fail(e.message)
                    connection.close()
                  }
              }
              .onFailure { e ->
                logger.error(e) { "Failed to inspect location table" }
                promise.fail(e.message)
                connection.close()
              }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create location table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- DEDICATED SENSOR INSERT METHODS ----

  fun insertAccelerometer(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val x = data.getDouble("x")
    val y = data.getDouble("y")
    val z = data.getDouble("z")
    val xEnc = encryptSensitiveText(x.toString()) ?: ""
    val yEnc = encryptSensitiveText(y.toString()) ?: ""
    val zEnc = encryptSensitiveText(z.toString()) ?: ""
    val utcTime = java.time.Instant.ofEpochMilli(timestamp)
      .atZone(java.time.ZoneOffset.UTC)
      .toLocalDateTime()
      .toString()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO `accelerometer` (`device_id`, `timestamp`, `utc_time`, `x`, `x_enc`, `y`, `y_enc`, `z`, `z_enc`)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(
          Tuple.of(deviceId, timestamp, utcTime, x, xEnc, y, yEnc, z, zEnc)
        )
          .onSuccess {
            logger.info { "Inserted accelerometer for $deviceId: x=$x y=$y z=$z" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert accelerometer" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertGyroscope(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val x = data.getDouble("x")
    val y = data.getDouble("y")
    val z = data.getDouble("z")
    val utcTime = java.time.Instant.ofEpochMilli(timestamp)
      .atZone(java.time.ZoneOffset.UTC)
      .toLocalDateTime()
      .toString()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO `gyroscope` (`device_id`, `timestamp`, `utc_time`, `x`, `y`, `z`)
          VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, utcTime, x, y, z))
          .onSuccess {
            logger.info { "Inserted gyroscope for $deviceId: x=$x y=$y z=$z" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert gyroscope" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertLocation(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = getLongValue(data, "timestamp", "ts") ?: System.currentTimeMillis()
    val latitude = getDoubleValue(data, "latitude", "lat")
      ?: return Future.failedFuture("latitude is required")
    val longitude = getDoubleValue(data, "longitude", "lon", "lng")
      ?: return Future.failedFuture("longitude is required")
    val altitude = getDoubleValue(data, "altitude") ?: 0.0
    val accuracy = getDoubleValue(data, "accuracy", "horizontal_accuracy") ?: 0.0
    val utcTime = java.time.Instant.ofEpochMilli(timestamp)
      .atZone(java.time.ZoneOffset.UTC)
      .toLocalDateTime()
      .toString()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO `location` (`device_id`, `timestamp`, `utc_time`, `latitude`, `longitude`, `altitude`, `accuracy`, `created_at`)
          VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(
          Tuple.of(deviceId, timestamp, utcTime, latitude, longitude, altitude, accuracy)
        )
          .onSuccess {
            logger.info { "Inserted location for $deviceId: lat=$latitude lon=$longitude" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert location" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  private fun getLongValue(data: JsonObject, vararg keys: String): Long? {
    for (key in keys) {
      val value = data.getValue(key) ?: continue
      when (value) {
        is Number -> return value.toLong()
        is String -> value.toLongOrNull()?.let { return it }
      }
    }
    return null
  }

  private fun getDoubleValue(data: JsonObject, vararg keys: String): Double? {
    for (key in keys) {
      val value = data.getValue(key) ?: continue
      when (value) {
        is Number -> return value.toDouble()
        is String -> value.toDoubleOrNull()?.let { return it }
      }
    }
    return null
  }

  private fun encryptSensitiveText(value: String?): String? = SensitiveDataCipher.encrypt(value)

  private fun decryptSensitiveText(value: String?): String? = SensitiveDataCipher.decrypt(value)

  private fun decryptFields(row: JsonObject, vararg fields: String): JsonObject {
    fields.forEach { field ->
      val current = row.getString(field)
      if (current != null) {
        row.put(field, decryptSensitiveText(current))
      }
    }
    return row
  }

  private fun requireSafeTableName(table: String): String {
    if (!table.matches(Regex("^[A-Za-z0-9_]+$"))) {
      throw IllegalArgumentException("Unsafe table name: $table")
    }
    return table
  }

  private fun safeAuditDetails(details: JsonObject?): String? {
    if (details == null || details.isEmpty) return null
    return details.encode()
  }

  private fun insertSecurityAuditEvent(
    actor: String?,
    action: String,
    targetType: String,
    targetId: String?,
    details: JsonObject? = null
  ) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        logger.warn(connectionResult.cause()) { "Failed to open DB connection for security audit log" }
        return@getConnection
      }

      val connection = connectionResult.result()
      val query = """
        INSERT INTO security_audit_log (actor, action, target_type, target_id, details)
        VALUES (?, ?, ?, ?, CAST(? AS JSON))
      """.trimIndent()
      val detailsJson = safeAuditDetails(details) ?: "null"
      connection.preparedQuery(query)
        .execute(Tuple.of(actor, action, targetType, targetId, detailsJson))
        .onFailure { e -> logger.warn(e) { "Failed to insert security audit event: $action/$targetType" } }
        .onComplete { connection.close() }
    }
  }

  fun insertNotification(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val appName = data.getString("app_name", "")
    val title = encryptSensitiveText(data.getString("title", "")) ?: ""
    val content = encryptSensitiveText(data.getString("content", "")) ?: ""
    val category = data.getString("category", "")
    val kind = data.getString("kind", "posted")
    val timestamp = data.getLong("timestamp")
    val dismissedAt = data.getLong("dismissed_at", 0L)
    
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = if (dismissedAt > 0) {
          """
          INSERT INTO notifications (device_id, app_name, title, content, category, kind, timestamp, dismissed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """.trimIndent()
        } else {
          """
          INSERT INTO notifications (device_id, app_name, title, content, category, kind, timestamp, dismissed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
          """.trimIndent()
        }
        val params = if (dismissedAt > 0) {
          Tuple.of(deviceId, appName, title, content, category, kind, timestamp, dismissedAt)
        } else {
          Tuple.of(deviceId, appName, title, content, category, kind, timestamp)
        }
        connection.preparedQuery(query).execute(params)
          .onSuccess {
            logger.info { "Inserted notification for $deviceId from $appName ($kind)" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert notification" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun getLatestBattery(deviceId: String): Future<JsonObject> {
    val promise = Promise.promise<JsonObject>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = "SELECT * FROM battery_readings WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1"
        connection.preparedQuery(query).execute(Tuple.of(deviceId))
          .onSuccess { rows ->
            if (rows.size() > 0) {
              promise.complete(rows.first().toJson())
            } else {
              promise.complete(JsonObject())
            }
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get latest battery for $deviceId" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- PARTICIPANT OPERATIONS ----

  fun getAllParticipants(): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          SELECT
            p.participant_id,
            p.name,
            p.red_zone_radius,
            p.status,
            p.risk_level,
            p.created_at,
            p.updated_at,
            COALESCE(pd.phone_device_id, CASE WHEN LOWER(COALESCE(p.device_type, '')) = 'phone' THEN p.device_id END) AS phone_device_id,
            COALESCE(pd.watch_device_id, CASE WHEN LOWER(COALESCE(p.device_type, '')) = 'watch' THEN p.device_id END) AS watch_device_id,
            COALESCE(pd.primary_device_id, p.device_id) AS device_id,
            COALESCE(
              pd.device_count,
              CASE WHEN p.device_id IS NULL OR TRIM(p.device_id) = '' THEN 0 ELSE 1 END
            ) AS device_count,
            pd.devices,
            b.percentage,
            b.charging_status,
            COALESCE(
              pd.source_type,
              CASE
                WHEN LOWER(COALESCE(p.device_type, '')) IN ('phone', 'watch') THEN LOWER(p.device_type)
                WHEN p.device_id IS NOT NULL AND TRIM(p.device_id) <> '' THEN 'phone'
                ELSE 'unknown'
              END
            ) AS source_type
          FROM participants p
          LEFT JOIN (
            SELECT
              participant_id,
              MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN device_id END) AS phone_device_id,
              MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN device_id END) AS watch_device_id,
              MIN(device_id) AS primary_device_id,
              COUNT(*) AS device_count,
              JSON_ARRAYAGG(
                JSON_OBJECT(
                  'device_id', device_id,
                  'device_type', LOWER(COALESCE(device_type, 'unknown'))
                )
              ) AS devices,
              CASE
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN 1 ELSE 0 END) > 0
                 AND SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN 1 ELSE 0 END) > 0
                  THEN 'both'
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN 1 ELSE 0 END) > 0
                  THEN 'phone'
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN 1 ELSE 0 END) > 0
                  THEN 'watch'
                ELSE 'unknown'
              END AS source_type
            FROM participant_devices
            GROUP BY participant_id
          ) pd ON p.participant_id = pd.participant_id
          LEFT JOIN (
            SELECT br.device_id, br.percentage, br.charging_status
            FROM battery_readings br
            INNER JOIN (
              SELECT device_id, MAX(id) AS max_id
              FROM battery_readings
              GROUP BY device_id
            ) latest_battery ON latest_battery.max_id = br.id
          ) b ON b.device_id = COALESCE(pd.phone_device_id, pd.primary_device_id, p.device_id)
          ORDER BY p.name ASC, p.participant_id ASC
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess { rows ->
            val result = JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row ->
                val json = row.toJson()

                val devicesValue = json.getValue("devices")
                val devices = when (devicesValue) {
                  is JsonArray -> devicesValue
                  is String -> try {
                    JsonArray(devicesValue)
                  } catch (_: Exception) {
                    JsonArray()
                  }
                  else -> JsonArray()
                }

                if (devices.size() == 0) {
                  val legacyDeviceId = json.getString("device_id")
                  if (!legacyDeviceId.isNullOrBlank()) {
                    val legacyType = json.getString("source_type", "unknown").lowercase()
                    devices.add(
                      JsonObject()
                        .put("device_id", legacyDeviceId)
                        .put("device_type", legacyType)
                    )
                  }
                }

                val phoneDeviceId = json.getString("phone_device_id")
                  ?: devices.find { (it as? JsonObject)?.getString("device_type") == "phone" }
                    ?.let { (it as JsonObject).getString("device_id") }
                val watchDeviceId = json.getString("watch_device_id")
                  ?: devices.find { (it as? JsonObject)?.getString("device_type") == "watch" }
                    ?.let { (it as JsonObject).getString("device_id") }
                val primaryDeviceId = phoneDeviceId
                  ?: watchDeviceId
                  ?: json.getString("device_id")
                  ?: devices.find { it is JsonObject }?.let { (it as JsonObject).getString("device_id") }

                val sourceTypeRaw = json.getString("source_type", "unknown").lowercase()
                val sourceType = when (sourceTypeRaw) {
                  "phone", "watch", "both" -> sourceTypeRaw
                  else -> when {
                    !phoneDeviceId.isNullOrBlank() && !watchDeviceId.isNullOrBlank() -> "both"
                    !phoneDeviceId.isNullOrBlank() -> "phone"
                    !watchDeviceId.isNullOrBlank() -> "watch"
                    else -> "unknown"
                  }
                }

                val deviceCount = (json.getValue("device_count") as? Number)?.toInt() ?: devices.size()

                json.put("phone_device_id", phoneDeviceId)
                json.put("watch_device_id", watchDeviceId)
                json.put("device_id", primaryDeviceId)
                json.put("source_type", sourceType)
                json.put("device_count", deviceCount)
                json.put("devices", devices)

                decryptFields(json, "name")
              }
              .collect(Collectors.toList()))
            promise.complete(result)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get participants" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun getParticipantByDeviceId(deviceId: String): Future<JsonObject?> {
    val promise = Promise.promise<JsonObject?>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          SELECT
            p.participant_id,
            p.name,
            p.red_zone_radius,
            p.status,
            p.risk_level,
            p.created_at,
            p.updated_at,
            pd.device_id,
            LOWER(COALESCE(pd.device_type, 'unknown')) AS device_type,
            summary.phone_device_id,
            summary.watch_device_id,
            summary.source_type,
            summary.device_count
          FROM participant_devices pd
          INNER JOIN participants p ON p.participant_id = pd.participant_id
          LEFT JOIN (
            SELECT
              participant_id,
              MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN device_id END) AS phone_device_id,
              MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN device_id END) AS watch_device_id,
              COUNT(*) AS device_count,
              CASE
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN 1 ELSE 0 END) > 0
                 AND SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN 1 ELSE 0 END) > 0
                  THEN 'both'
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN 1 ELSE 0 END) > 0
                  THEN 'phone'
                WHEN SUM(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN 1 ELSE 0 END) > 0
                  THEN 'watch'
                ELSE 'unknown'
              END AS source_type
            FROM participant_devices
            GROUP BY participant_id
          ) summary ON summary.participant_id = p.participant_id
          WHERE pd.device_id = ?
          LIMIT 1
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId))
          .onSuccess { rows ->
            if (rows.size() > 0) {
              val resultRow = rows.first().toJson()
              val result = resultRow
                .put("source_type", resultRow.getString("source_type", "unknown").lowercase())
                .let { decryptFields(it, "name") }
              promise.complete(result)
              connection.close()
            } else {
              connection.preparedQuery(
                """
                SELECT participant_id, name, red_zone_radius, status, risk_level, created_at, updated_at, device_id,
                       LOWER(COALESCE(device_type, 'unknown')) AS device_type
                FROM participants
                WHERE device_id = ?
                LIMIT 1
                """.trimIndent()
              ).execute(Tuple.of(deviceId))
                .onSuccess { fallbackRows ->
                  val result = fallbackRows.firstOrNull()?.toJson()?.let { row ->
                    row.put("source_type", row.getString("device_type", "unknown"))
                    row.put("phone_device_id", if (row.getString("device_type") == "phone") row.getString("device_id") else null)
                    row.put("watch_device_id", if (row.getString("device_type") == "watch") row.getString("device_id") else null)
                    row.put("device_count", if (!row.getString("device_id").isNullOrBlank()) 1 else 0)
                    decryptFields(row, "name")
                  }
                  promise.complete(result)
                  connection.close()
                }
                .onFailure { e ->
                  logger.error(e) { "Failed fallback participant lookup by device_id" }
                  promise.fail(e.message)
                  connection.close()
                }
            }
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get participant by device_id" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun upsertParticipant(data: JsonObject): Future<String> {
    val promise = Promise.promise<String>()
    val providedParticipantId = data.getString("participant_id")?.trim()?.takeIf { it.isNotEmpty() }
    val deviceId = data.getString("device_id")?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedDeviceType = data.getString("device_type", "unknown").trim().lowercase()
    val deviceType = when (normalizedDeviceType) {
      "phone", "watch" -> normalizedDeviceType
      else -> "unknown"
    }
    val plainName = data.getString("name", "Unknown")
    val name = encryptSensitiveText(plainName) ?: plainName
    val redZoneRadius = data.getInteger("red_zone_radius", 300)
    val status = data.getString("status", "active")
    val riskLevel = data.getString("risk_level", "low")
    val isAutoLink = data.getBoolean("is_auto_link", false)

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val resolveParticipantFuture = when {
          !providedParticipantId.isNullOrBlank() -> Future.succeededFuture(providedParticipantId)
          !deviceId.isNullOrBlank() -> connection.preparedQuery(
            "SELECT participant_id FROM participant_devices WHERE device_id = ? LIMIT 1"
          ).execute(Tuple.of(deviceId)).map { rows ->
            rows.firstOrNull()?.getString("participant_id") ?: "participant_${System.currentTimeMillis()}"
          }
          else -> Future.succeededFuture("participant_${System.currentTimeMillis()}")
        }

        resolveParticipantFuture
          .compose { resolvedParticipantId ->
            val query = """
              INSERT INTO participants (
                participant_id, name, red_zone_radius, status, risk_level, device_id, device_type, created_at, updated_at
              )
              VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
              ON DUPLICATE KEY UPDATE
                name = IF(VALUES(name) = 'Unknown', participants.name, VALUES(name)),
                red_zone_radius = VALUES(red_zone_radius),
                status = VALUES(status),
                risk_level = VALUES(risk_level),
                device_id = COALESCE(participants.device_id, VALUES(device_id)),
                device_type = IF(VALUES(device_type) = 'unknown', participants.device_type, VALUES(device_type)),
                updated_at = NOW()
            """.trimIndent()
            connection.preparedQuery(query)
              .execute(Tuple.of(resolvedParticipantId, name, redZoneRadius, status, riskLevel, deviceId, deviceType))
              .map(resolvedParticipantId)
          }
          .compose { resolvedParticipantId ->
            if (deviceId.isNullOrBlank()) {
              Future.succeededFuture(resolvedParticipantId)
            } else {
              val linkQuery = """
                INSERT INTO participant_devices (participant_id, device_id, device_type, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, TRUE, NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                  participant_id = VALUES(participant_id),
                  device_type = IF(VALUES(device_type) = 'unknown', participant_devices.device_type, VALUES(device_type)),
                  updated_at = NOW()
              """.trimIndent()
              connection.preparedQuery(linkQuery)
                .execute(Tuple.of(resolvedParticipantId, deviceId, deviceType))
                .map(resolvedParticipantId)
            }
          }
          .onSuccess { resolvedParticipantId ->
            logger.info { "Upserted participant: $resolvedParticipantId (device=$deviceId type=$deviceType)" }
            if (!isAutoLink) {
              insertSecurityAuditEvent(
                actor = "admin",
                action = "participant_upsert",
                targetType = "participant",
                targetId = resolvedParticipantId,
                details = JsonObject()
                  .put("device_id", deviceId)
                  .put("device_type", deviceType)
              )
            }
            promise.complete(resolvedParticipantId)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to upsert participant" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- RED ZONE OPERATIONS ----

  fun getRedZonesForParticipant(participantId: String?): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = if (participantId != null) {
          "SELECT * FROM red_zones WHERE participant_id = ? OR participant_id IS NULL ORDER BY name ASC"
        } else {
          "SELECT * FROM red_zones ORDER BY name ASC"
        }
        val exec = if (participantId != null) {
          connection.preparedQuery(query).execute(Tuple.of(participantId))
        } else {
          connection.query(query).execute()
        }
        exec
          .onSuccess { rows ->
            val result = JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> decryptFields(row.toJson(), "name") }
              .collect(Collectors.toList()))
            promise.complete(result)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get red zones" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertRedZone(data: JsonObject): Future<String> {
    val promise = Promise.promise<String>()
    val zoneId = data.getString("zone_id") ?: java.util.UUID.randomUUID().toString()
    val participantId = data.getString("participant_id")
    val name = encryptSensitiveText(data.getString("name", "Unnamed Zone")) ?: "Unnamed Zone"
    val latitude = data.getDouble("latitude")
    val longitude = data.getDouble("longitude")
    val radius = data.getInteger("radius", 300)
    val zoneType = data.getString("zone_type", "custom")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO red_zones (zone_id, participant_id, name, latitude, longitude, radius, zone_type)
          VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(
          Tuple.of(zoneId, participantId, name, latitude, longitude, radius, zoneType)
        )
          .onSuccess {
            logger.info { "Inserted red zone: $zoneId" }
            insertSecurityAuditEvent(
              actor = "admin",
              action = "red_zone_insert",
              targetType = "red_zone",
              targetId = zoneId,
              details = JsonObject().put("participant_id", participantId).put("zone_type", zoneType)
            )
            promise.complete(zoneId)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert red zone" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun deleteRedZone(zoneId: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        connection.preparedQuery("DELETE FROM red_zones WHERE zone_id = ?").execute(Tuple.of(zoneId))
          .onSuccess {
            logger.info { "Deleted red zone: $zoneId" }
            insertSecurityAuditEvent(
              actor = "admin",
              action = "red_zone_delete",
              targetType = "red_zone",
              targetId = zoneId
            )
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to delete red zone" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- GEOFENCE ALERT OPERATIONS ----

  fun getGeofenceAlerts(activeOnly: Boolean): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val whereClause = if (activeOnly) "WHERE acknowledged = FALSE" else ""
        connection.query("""
          SELECT a.*, p.name as participant_name, p.device_id
          FROM geofence_alerts a
          LEFT JOIN participants p ON a.participant_id = p.participant_id
          $whereClause
          ORDER BY triggered_at DESC
          LIMIT 100
        """.trimIndent()).execute()
          .onSuccess { rows ->
            val result = JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> decryptFields(row.toJson(), "participant_name") }
              .collect(Collectors.toList()))
            promise.complete(result)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get geofence alerts" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertGeofenceAlert(data: JsonObject): Future<String> {
    val promise = Promise.promise<String>()
    val alertId = data.getString("alert_id") ?: java.util.UUID.randomUUID().toString()
    val participantId = data.getString("participant_id")
    val zoneId = data.getString("zone_id")
    val zoneName = data.getString("zone_name", "")
    val latitude = data.getDouble("latitude")
    val longitude = data.getDouble("longitude")
    val distance = data.getDouble("distance")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO geofence_alerts (alert_id, participant_id, zone_id, zone_name, latitude, longitude, distance)
          VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.preparedQuery(query).execute(
          Tuple.of(alertId, participantId, zoneId, zoneName, latitude, longitude, distance)
        )
          .onSuccess {
            logger.info { "Inserted geofence alert: $alertId for participant $participantId" }
            vertx.eventBus().publish(
              "alerts.changed",
              JsonObject()
                .put("action", "insert")
                .put("alert_type", "geofence")
                .put("alert_id", alertId)
                .put("participant_id", participantId)
            )
            promise.complete(alertId)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert geofence alert" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun acknowledgeAlert(alertId: String, acknowledgedBy: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          UPDATE geofence_alerts
          SET acknowledged = TRUE, acknowledged_by = ?, acknowledged_at = NOW()
          WHERE alert_id = ?
        """.trimIndent()

        connection.preparedQuery(query).execute(Tuple.of(acknowledgedBy, alertId))
          .onSuccess {
            logger.info { "Acknowledged alert: $alertId by $acknowledgedBy" }
            insertSecurityAuditEvent(
              actor = acknowledgedBy,
              action = "geofence_acknowledge",
              targetType = "geofence_alert",
              targetId = alertId
            )
            vertx.eventBus().publish(
              "alerts.changed",
              JsonObject()
                .put("action", "acknowledge")
                .put("alert_type", "geofence")
                .put("alert_id", alertId)
                .put("acknowledged_by", acknowledgedBy)
            )
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to acknowledge alert" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }



  fun checkRecentAlert(participantId: String, zoneId: String, windowMinutes: Int): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val safeWindowMinutes = windowMinutes.coerceIn(1, 24 * 60)
        val query = """
          SELECT COUNT(*) as count FROM geofence_alerts
          WHERE participant_id = ?
            AND zone_id = ?
            AND triggered_at > DATE_SUB(NOW(), INTERVAL $safeWindowMinutes MINUTE)
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(participantId, zoneId))
          .onSuccess { rows ->
            val count = rows.firstOrNull()?.getInteger("count") ?: 0
            promise.complete(count > 0)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to check recent alert" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun getLatestLocation(deviceId: String): Future<JsonObject?> {
    val promise = Promise.promise<JsonObject?>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        connection.preparedQuery(
          "SELECT id, device_id, timestamp, utc_time, latitude, longitude, altitude, accuracy, created_at FROM location WHERE device_id = ? ORDER BY timestamp DESC LIMIT 1"
        ).execute(Tuple.of(deviceId))
          .onSuccess { rows ->
            val result = rows.firstOrNull()?.toJson()
            promise.complete(result)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get latest location" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- WEARABLE DATA TABLE CREATION ----

  fun createWearableHeartRateTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_heart_rate` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `bpm` INT NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_heart_rate table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_heart_rate table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableStepsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_steps` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `start_time` BIGINT NOT NULL,
            `end_time` BIGINT NOT NULL,
            `count` INT NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_start_time` (`start_time`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_steps table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_steps table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableSleepTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_sleep` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `start_time` BIGINT NOT NULL,
            `end_time` BIGINT NOT NULL,
            `title` VARCHAR(256) DEFAULT 'Sleep',
            `notes` TEXT,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_start_time` (`start_time`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_sleep table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_sleep table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableBloodPressureTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_blood_pressure` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `systolic` DOUBLE NOT NULL,
            `diastolic` DOUBLE NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_blood_pressure table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_blood_pressure table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableWeightTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_weight` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `weight_kg` DOUBLE NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_weight table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_weight table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableOxygenTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_oxygen` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `percentage` DOUBLE NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_oxygen table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_oxygen table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun createWearableRespiratoryTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          CREATE TABLE IF NOT EXISTS `wearable_respiratory` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `rate` DOUBLE NOT NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created wearable_respiratory table" }
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to create wearable_respiratory table" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- WEARABLE DATA INSERT FUNCTIONS ----

  fun insertWearableHeartRate(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val bpm = data.getInteger("bpm")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_heart_rate (device_id, timestamp, bpm, created_at)
          VALUES (?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, bpm))
          .onSuccess {
            logger.info { "Inserted wearable heart rate for $deviceId: $bpm bpm" }
            vertx.eventBus().publish("wearable.heartrate.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable heart rate" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableSteps(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val startTime = data.getLong("start_time")
    val endTime = data.getLong("end_time")
    val count = data.getInteger("count")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_steps (device_id, start_time, end_time, count, created_at)
          VALUES (?, ?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, startTime, endTime, count))
          .onSuccess {
            logger.info { "Inserted wearable steps for $deviceId: $count steps" }
            vertx.eventBus().publish("wearable.steps.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable steps" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableSleep(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val startTime = data.getLong("start_time")
    val endTime = data.getLong("end_time")
    val title = data.getString("title", "Sleep")
    val notes = data.getString("notes", "")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_sleep (device_id, start_time, end_time, title, notes, created_at)
          VALUES (?, ?, ?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, startTime, endTime, title, notes))
          .onSuccess {
            logger.info { "Inserted wearable sleep for $deviceId: $title" }
            vertx.eventBus().publish("wearable.sleep.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable sleep" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableBloodPressure(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val systolic = data.getDouble("systolic")
    val diastolic = data.getDouble("diastolic")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_blood_pressure (device_id, timestamp, systolic, diastolic, created_at)
          VALUES (?, ?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, systolic, diastolic))
          .onSuccess {
            logger.info { "Inserted wearable blood pressure for $deviceId: $systolic/$diastolic" }
            vertx.eventBus().publish("wearable.bloodpressure.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable blood pressure" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableWeight(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val weightKg = data.getDouble("weight_kg")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_weight (device_id, timestamp, weight_kg, created_at)
          VALUES (?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, weightKg))
          .onSuccess {
            logger.info { "Inserted wearable weight for $deviceId: $weightKg kg" }
            vertx.eventBus().publish("wearable.weight.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable weight" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableOxygen(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val percentage = data.getDouble("percentage")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_oxygen (device_id, timestamp, percentage, created_at)
          VALUES (?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, percentage))
          .onSuccess {
            logger.info { "Inserted wearable oxygen for $deviceId: $percentage%" }
            vertx.eventBus().publish("wearable.oxygen.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable oxygen" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  fun insertWearableRespiratory(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val timestamp = data.getLong("timestamp")
    val rate = data.getDouble("rate")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_respiratory (device_id, timestamp, rate, created_at)
          VALUES (?, ?, ?, NOW())
        """.trimIndent()
        connection.preparedQuery(query).execute(Tuple.of(deviceId, timestamp, rate))
          .onSuccess {
            logger.info { "Inserted wearable respiratory for $deviceId: $rate breaths/min" }
            vertx.eventBus().publish("wearable.respiratory.update", data)
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to insert wearable respiratory" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- SIGNATURE ALERT OPERATIONS ----

  /**
   * Get signature alerts, optionally only active (unacknowledged) ones
   */
  fun getSignatureAlerts(activeOnly: Boolean, limit: Int?): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val whereClause = if (activeOnly) "WHERE acknowledged_at IS NULL" else ""
        val limitClause = if (limit == null) {
          ""
        } else {
          val safeLimit = if (limit <= 0) 200 else minOf(limit, 10000)
          "\nLIMIT $safeLimit"
        }

        val query = """
          SELECT
            id, participant_id, device_id, source_type, hour_start, alert_code, alert_name,
            severity, score, baseline_ref, top_features_json, explanation,
            status, created_at, acknowledged_at, acknowledged_by
          FROM signature_alerts
          $whereClause
          ORDER BY created_at DESC
          $limitClause
        """.trimIndent()

        connection.query(query).execute()
          .onSuccess { rows ->
            val result = JsonArray(
              StreamSupport.stream(rows.spliterator(), false)
                .map { row -> row.toJson() }
                .collect(Collectors.toList())
            )
            promise.complete(result)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to get signature alerts" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  /**
   * Acknowledge a signature alert
   */
  fun acknowledgeSignatureAlert(id: Long, acknowledgedBy: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          UPDATE signature_alerts
          SET acknowledged_at = NOW(),
              acknowledged_by = ?,
              status = 'acknowledged'
          WHERE id = ? AND acknowledged_at IS NULL
        """.trimIndent()

        connection.preparedQuery(query).execute(Tuple.of(acknowledgedBy, id))
          .onSuccess {
            logger.info { "Acknowledged signature alert: $id by $acknowledgedBy" }
            insertSecurityAuditEvent(
              actor = acknowledgedBy,
              action = "signature_alert_acknowledge",
              targetType = "signature_alert",
              targetId = id.toString()
            )
            vertx.eventBus().publish(
              "alerts.changed",
              JsonObject()
                .put("action", "acknowledge")
                .put("alert_type", "signature")
                .put("id", id)
                .put("acknowledged_by", acknowledgedBy)
            )
            promise.complete(true)
            connection.close()
          }
          .onFailure { e ->
            logger.error(e) { "Failed to acknowledge signature alert" }
            promise.fail(e.message)
            connection.close()
          }
      } else {
        promise.fail(connectionResult.cause().message)
      }
    }
    return promise.future()
  }

  // ---- AUTH / RBAC OPERATIONS ----

  private fun normalizeRole(value: String?): String {
    return when ((value ?: "").trim().lowercase()) {
      "admin", "analyst", "viewer", "doctor", "ingest" -> value!!.trim().lowercase()
      else -> "viewer"
    }
  }

  private fun normalizeStatus(value: String?): String {
    return when ((value ?: "").trim().lowercase()) {
      "active", "inactive" -> value!!.trim().lowercase()
      else -> "active"
    }
  }

  private fun sessionTtlHours(): Int {
    val raw = SecretResolver.get("AUTH_SESSION_TTL_HOURS")?.trim()
    val parsed = raw?.toIntOrNull()
    return if (parsed == null) 12 else parsed.coerceIn(1, 24 * 14)
  }

  private fun authAttemptWindowMinutes(): Int {
    val raw = SecretResolver.get("AUTH_ATTEMPT_WINDOW_MINUTES")?.trim()
    val parsed = raw?.toIntOrNull()
    return if (parsed == null) 15 else parsed.coerceIn(1, 180)
  }

  private fun authMaxFailedAttempts(): Int {
    val raw = SecretResolver.get("AUTH_MAX_FAILED_ATTEMPTS")?.trim()
    val parsed = raw?.toIntOrNull()
    return if (parsed == null) 5 else parsed.coerceIn(3, 20)
  }

  private fun authLockoutMinutes(): Int {
    val raw = SecretResolver.get("AUTH_LOCKOUT_MINUTES")?.trim()
    val parsed = raw?.toIntOrNull()
    return if (parsed == null) 15 else parsed.coerceIn(1, 240)
  }

  private fun passwordMinLength(): Int {
    val raw = SecretResolver.get("PASSWORD_MIN_LENGTH")?.trim()
    val parsed = raw?.toIntOrNull()
    return if (parsed == null) 12 else parsed.coerceIn(8, 128)
  }

  private fun requireUppercase(): Boolean = (SecretResolver.get("PASSWORD_REQUIRE_UPPERCASE", "true") ?: "true").toBoolean()
  private fun requireLowercase(): Boolean = (SecretResolver.get("PASSWORD_REQUIRE_LOWERCASE", "true") ?: "true").toBoolean()
  private fun requireDigit(): Boolean = (SecretResolver.get("PASSWORD_REQUIRE_DIGIT", "true") ?: "true").toBoolean()
  private fun requireSpecial(): Boolean = (SecretResolver.get("PASSWORD_REQUIRE_SPECIAL", "true") ?: "true").toBoolean()

  private fun validatePasswordPolicy(password: String): String? {
    if (password.length < passwordMinLength()) {
      return "Password must be at least ${passwordMinLength()} characters."
    }
    if (requireUppercase() && !password.any { it.isUpperCase() }) {
      return "Password must include at least one uppercase letter."
    }
    if (requireLowercase() && !password.any { it.isLowerCase() }) {
      return "Password must include at least one lowercase letter."
    }
    if (requireDigit() && !password.any { it.isDigit() }) {
      return "Password must include at least one digit."
    }
    if (requireSpecial() && !password.any { !it.isLetterOrDigit() }) {
      return "Password must include at least one special character."
    }
    return null
  }

  private fun getLoginLockState(connection: SqlClient, username: String): Future<JsonObject> {
    return connection.preparedQuery(
      """
        SELECT
          username,
          failed_count,
          CASE
            WHEN locked_until IS NOT NULL AND locked_until > UTC_TIMESTAMP()
              THEN TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), locked_until)
            ELSE 0
          END AS lock_seconds
        FROM auth_login_attempts
        WHERE username = ?
        LIMIT 1
      """.trimIndent()
    ).execute(Tuple.of(username))
      .map { rows ->
        val row = rows.firstOrNull()
        if (row == null) {
          JsonObject().put("locked", false).put("lock_seconds", 0).put("failed_count", 0)
        } else {
          val lockSeconds = (row.getInteger("lock_seconds") ?: 0).coerceAtLeast(0)
          JsonObject()
            .put("locked", lockSeconds > 0)
            .put("lock_seconds", lockSeconds)
            .put("failed_count", row.getInteger("failed_count") ?: 0)
        }
      }
  }

  private fun clearFailedLogin(connection: SqlClient, username: String): Future<Void> {
    return connection.preparedQuery(
      """
        UPDATE auth_login_attempts
        SET failed_count = 0,
            first_failed_at = NULL,
            last_failed_at = UTC_TIMESTAMP(),
            locked_until = NULL,
            updated_at = UTC_TIMESTAMP()
        WHERE username = ?
      """.trimIndent()
    ).execute(Tuple.of(username)).mapEmpty()
  }

  private fun recordFailedLogin(connection: SqlClient, username: String, clientIp: String?): Future<JsonObject> {
    val windowMinutes = authAttemptWindowMinutes()
    val maxAttempts = authMaxFailedAttempts()
    val lockoutMinutes = authLockoutMinutes()
    val now = LocalDateTime.now(java.time.ZoneOffset.UTC)

    return connection.preparedQuery(
      """
        SELECT
          failed_count,
          first_failed_at,
          CASE
            WHEN first_failed_at IS NULL OR TIMESTAMPDIFF(MINUTE, first_failed_at, UTC_TIMESTAMP()) > ?
              THEN 1
            ELSE 0
          END AS window_expired
        FROM auth_login_attempts
        WHERE username = ?
        LIMIT 1
      """.trimIndent()
    ).execute(Tuple.of(windowMinutes, username))
      .compose { rows ->
        val row = rows.firstOrNull()
        val windowExpired = row?.getInteger("window_expired") == 1
        val previousCount = row?.getInteger("failed_count") ?: 0
        val newCount = if (row == null || windowExpired) 1 else previousCount + 1
        val firstFailedAt = if (row == null || windowExpired) now else (row.getLocalDateTime("first_failed_at") ?: now)
        val shouldLock = newCount >= maxAttempts
        val lockedUntil = if (shouldLock) now.plusMinutes(lockoutMinutes.toLong()) else null

        connection.preparedQuery(
          """
            INSERT INTO auth_login_attempts (
              username, failed_count, first_failed_at, last_failed_at, locked_until, last_ip, updated_at
            )
            VALUES (?, ?, ?, UTC_TIMESTAMP(), ?, ?, UTC_TIMESTAMP())
            ON DUPLICATE KEY UPDATE
              failed_count = VALUES(failed_count),
              first_failed_at = VALUES(first_failed_at),
              last_failed_at = VALUES(last_failed_at),
              locked_until = VALUES(locked_until),
              last_ip = VALUES(last_ip),
              updated_at = UTC_TIMESTAMP()
          """.trimIndent()
        ).execute(Tuple.of(username, newCount, firstFailedAt, lockedUntil, clientIp))
          .map {
            JsonObject()
              .put("failed_count", newCount)
              .put("locked", shouldLock)
              .put("lock_seconds", if (shouldLock) lockoutMinutes * 60 else 0)
          }
      }
  }

  fun loginUser(username: String, password: String, clientIp: String?, userAgent: String?): Future<JsonObject> {
    val promise = Promise.promise<JsonObject>()
    val safeUsername = username.trim()
    if (safeUsername.isEmpty() || password.isEmpty()) {
      promise.fail("Invalid credentials")
      return promise.future()
    }

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }

      val connection = connectionResult.result()
      getLoginLockState(connection, safeUsername)
        .compose { lockState ->
          if (lockState.getBoolean("locked", false)) {
            val lockSecs = lockState.getInteger("lock_seconds", 0)
            val mins = ((lockSecs + 59) / 60).coerceAtLeast(1)
            Future.failedFuture("Account temporarily locked. Try again in ${mins} minute(s).")
          } else {
            connection.preparedQuery(
              """
                SELECT id, username, password_hash, role, full_name, email, status
                FROM app_users
                WHERE username = ?
                LIMIT 1
              """.trimIndent()
            ).execute(Tuple.of(safeUsername))
              .compose { rows ->
                val user = rows.firstOrNull()
                if (user == null) {
                  recordFailedLogin(connection, safeUsername, clientIp).compose { failState ->
                    if (failState.getBoolean("locked", false)) {
                      Future.failedFuture("Account temporarily locked due to repeated failed logins.")
                    } else {
                      Future.failedFuture("Invalid credentials")
                    }
                  }
                } else {
                  val status = (user.getString("status") ?: "inactive").lowercase()
                  val passwordHash = user.getString("password_hash")
                  if (status != "active" || !AuthSecurity.verifyPassword(password, passwordHash)) {
                    recordFailedLogin(connection, safeUsername, clientIp).compose { failState ->
                      if (failState.getBoolean("locked", false)) {
                        Future.failedFuture("Account temporarily locked due to repeated failed logins.")
                      } else {
                        Future.failedFuture("Invalid credentials")
                      }
                    }
                  } else {
                    val userId = user.getLong("id")
                    val role = normalizeRole(user.getString("role"))
                    val token = AuthSecurity.generateSessionToken()
                    val tokenHash = AuthSecurity.hashToken(token)
                    val ttl = sessionTtlHours()
                    clearFailedLogin(connection, safeUsername)
                      .compose {
                        connection.preparedQuery(
                          """
                            INSERT INTO auth_sessions (
                              user_id, token_hash, expires_at, client_ip, user_agent, created_at, last_seen_at
                            )
                            VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? HOUR), ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())
                          """.trimIndent()
                        ).execute(Tuple.of(userId, tokenHash, ttl, clientIp, userAgent))
                      }
                      .compose {
                        connection.preparedQuery(
                          "UPDATE app_users SET last_login_at = UTC_TIMESTAMP() WHERE id = ?"
                        ).execute(Tuple.of(userId))
                      }
                      .map {
                        JsonObject()
                          .put("ok", true)
                          .put("token", token)
                          .put("username", user.getString("username"))
                          .put("role", role)
                          .put("full_name", user.getString("full_name"))
                          .put("email", user.getString("email"))
                          .put("expires_in_hours", ttl)
                      }
                  }
                }
              }
          }
        }
        .onSuccess { auth ->
          promise.complete(auth)
          connection.close()
        }
        .onFailure { e ->
          logger.warn { "Login failed for username=$safeUsername" }
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun validateSessionToken(token: String): Future<JsonObject> {
    val promise = Promise.promise<JsonObject>()
    val safeToken = token.trim()
    if (safeToken.isEmpty()) {
      promise.complete(JsonObject().put("ok", false))
      return promise.future()
    }

    val tokenHash = AuthSecurity.hashToken(safeToken)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }

      val connection = connectionResult.result()
      connection.preparedQuery(
        """
          SELECT
            s.id AS session_id,
            s.user_id,
            u.username,
            u.role,
            u.full_name,
            u.email,
            u.status
          FROM auth_sessions s
          INNER JOIN app_users u ON u.id = s.user_id
          WHERE s.token_hash = ?
            AND s.revoked_at IS NULL
            AND s.expires_at > UTC_TIMESTAMP()
          LIMIT 1
        """.trimIndent()
      ).execute(Tuple.of(tokenHash))
        .onSuccess { rows ->
          val row = rows.firstOrNull()
          if (row == null || (row.getString("status") ?: "inactive").lowercase() != "active") {
            promise.complete(JsonObject().put("ok", false))
            connection.close()
            return@onSuccess
          }

          val sessionId = row.getLong("session_id")
          connection.preparedQuery(
            "UPDATE auth_sessions SET last_seen_at = UTC_TIMESTAMP() WHERE id = ?"
          ).execute(Tuple.of(sessionId)).onComplete {
            val user = JsonObject()
              .put("user_id", row.getLong("user_id"))
              .put("username", row.getString("username"))
              .put("role", normalizeRole(row.getString("role")))
              .put("full_name", row.getString("full_name"))
              .put("email", row.getString("email"))
            promise.complete(JsonObject().put("ok", true).put("user", user))
            connection.close()
          }
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun revokeSessionToken(token: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val safeToken = token.trim()
    if (safeToken.isEmpty()) {
      promise.complete(false)
      return promise.future()
    }

    val tokenHash = AuthSecurity.hashToken(safeToken)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }

      val connection = connectionResult.result()
      connection.preparedQuery(
        """
          UPDATE auth_sessions
          SET revoked_at = UTC_TIMESTAMP()
          WHERE token_hash = ? AND revoked_at IS NULL
        """.trimIndent()
      ).execute(Tuple.of(tokenHash))
        .onSuccess { rows ->
          promise.complete(rows.rowCount() > 0)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun listAppUsers(): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }

      val connection = connectionResult.result()
      connection.query(
        """
          SELECT username, role, full_name, email, status, last_login_at, created_at, updated_at
          FROM app_users
          ORDER BY username ASC
        """.trimIndent()
      ).execute()
        .onSuccess { rows ->
          val result = JsonArray(
            StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson().put("role", normalizeRole(row.getString("role"))) }
              .collect(Collectors.toList())
          )
          promise.complete(result)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun upsertAppUser(data: JsonObject, actor: String): Future<JsonObject> {
    val promise = Promise.promise<JsonObject>()
    val username = data.getString("username", "").trim()
    val password = data.getString("password")?.trim()
    val role = normalizeRole(data.getString("role"))
    val status = normalizeStatus(data.getString("status"))
    val fullName = data.getString("full_name")?.trim()
    val email = data.getString("email")?.trim()

    if (username.isEmpty()) {
      promise.fail("username is required")
      return promise.future()
    }

    if (!password.isNullOrBlank()) {
      val policyError = validatePasswordPolicy(password)
      if (policyError != null) {
        promise.fail(policyError)
        return promise.future()
      }
    }

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }

      val connection = connectionResult.result()
      connection.preparedQuery(
        "SELECT id, password_hash FROM app_users WHERE username = ? LIMIT 1"
      ).execute(Tuple.of(username))
        .compose { rows ->
          val existing = rows.firstOrNull()
          if (existing == null) {
            if (password.isNullOrBlank()) {
              Future.failedFuture("password is required for new user")
            } else {
              val hashed = AuthSecurity.hashPassword(password)
              connection.preparedQuery(
                """
                  INSERT INTO app_users (
                    username, password_hash, role, full_name, email, status, created_at, updated_at
                  )
                  VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())
                """.trimIndent()
              ).execute(Tuple.of(username, hashed, role, fullName, email, status))
                .map(JsonObject().put("ok", true).put("created", true).put("username", username))
            }
          } else {
            val passwordHash = if (password.isNullOrBlank()) {
              existing.getString("password_hash")
            } else {
              AuthSecurity.hashPassword(password)
            }
            connection.preparedQuery(
              """
                UPDATE app_users
                SET password_hash = ?,
                    role = ?,
                    full_name = ?,
                    email = ?,
                    status = ?,
                    updated_at = UTC_TIMESTAMP()
                WHERE username = ?
              """.trimIndent()
            ).execute(Tuple.of(passwordHash, role, fullName, email, status, username))
              .map(JsonObject().put("ok", true).put("created", false).put("username", username))
          }
        }
        .onSuccess { result ->
          insertSecurityAuditEvent(
            actor = actor,
            action = "app_user_upsert",
            targetType = "app_user",
            targetId = username,
            details = JsonObject().put("role", role).put("status", status)
          )
          promise.complete(result)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun listActiveAuthSessions(limit: Int = 200): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    val safeLimit = limit.coerceIn(1, 1000)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }
      val connection = connectionResult.result()
      connection.query(
        """
          SELECT
            s.id,
            s.user_id,
            u.username,
            u.role,
            s.client_ip,
            s.user_agent,
            s.created_at,
            s.last_seen_at,
            s.expires_at
          FROM auth_sessions s
          INNER JOIN app_users u ON u.id = s.user_id
          WHERE s.revoked_at IS NULL
            AND s.expires_at > UTC_TIMESTAMP()
          ORDER BY s.last_seen_at DESC, s.created_at DESC
          LIMIT $safeLimit
        """.trimIndent()
      ).execute()
        .onSuccess { rows ->
          val result = JsonArray(
            StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson().put("role", normalizeRole(row.getString("role"))) }
              .collect(Collectors.toList())
          )
          promise.complete(result)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun revokeSessionById(id: Long): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }
      val connection = connectionResult.result()
      connection.preparedQuery(
        """
          UPDATE auth_sessions
          SET revoked_at = UTC_TIMESTAMP()
          WHERE id = ? AND revoked_at IS NULL
        """.trimIndent()
      ).execute(Tuple.of(id))
        .onSuccess { rows ->
          promise.complete(rows.rowCount() > 0)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun getSecurityAuditEvents(limit: Int = 200): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    val safeLimit = limit.coerceIn(1, 2000)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }
      val connection = connectionResult.result()
      connection.query(
        """
          SELECT event_at, actor, action, target_type, target_id, details
          FROM security_audit_log
          ORDER BY event_at DESC
          LIMIT $safeLimit
        """.trimIndent()
      ).execute()
        .onSuccess { rows ->
          val result = JsonArray(
            StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
              .collect(Collectors.toList())
          )
          promise.complete(result)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun getLoginLockouts(limit: Int = 100): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    val safeLimit = limit.coerceIn(1, 1000)
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }
      val connection = connectionResult.result()
      connection.query(
        """
          SELECT
            username,
            failed_count,
            first_failed_at,
            last_failed_at,
            locked_until,
            last_ip
          FROM auth_login_attempts
          WHERE failed_count > 0 OR (locked_until IS NOT NULL AND locked_until > UTC_TIMESTAMP())
          ORDER BY locked_until DESC, failed_count DESC, last_failed_at DESC
          LIMIT $safeLimit
        """.trimIndent()
      ).execute()
        .onSuccess { rows ->
          val result = JsonArray(
            StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
              .collect(Collectors.toList())
          )
          promise.complete(result)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  fun getSecurityStatus(): Future<JsonObject> {
    val promise = Promise.promise<JsonObject>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.failed()) {
        promise.fail(connectionResult.cause().message)
        return@getConnection
      }
      val connection = connectionResult.result()
      connection.query(
        """
          SELECT
            (SELECT COUNT(*) FROM app_users WHERE status = 'active') AS active_users,
            (SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NULL AND expires_at > UTC_TIMESTAMP()) AS active_sessions,
            (SELECT COUNT(*) FROM auth_login_attempts WHERE locked_until IS NOT NULL AND locked_until > UTC_TIMESTAMP()) AS locked_accounts
        """.trimIndent()
      ).execute()
        .onSuccess { rows ->
          val row = rows.firstOrNull()
          val status = JsonObject()
            .put("active_users", row?.getInteger("active_users") ?: 0)
            .put("active_sessions", row?.getInteger("active_sessions") ?: 0)
            .put("locked_accounts", row?.getInteger("locked_accounts") ?: 0)
            .put(
              "auth_policy",
              JsonObject()
                .put("attempt_window_minutes", authAttemptWindowMinutes())
                .put("max_failed_attempts", authMaxFailedAttempts())
                .put("lockout_minutes", authLockoutMinutes())
            )
            .put(
              "password_policy",
              JsonObject()
                .put("min_length", passwordMinLength())
                .put("require_uppercase", requireUppercase())
                .put("require_lowercase", requireLowercase())
                .put("require_digit", requireDigit())
                .put("require_special", requireSpecial())
            )
          promise.complete(status)
          connection.close()
        }
        .onFailure { e ->
          promise.fail(e.message)
          connection.close()
        }
    }
    return promise.future()
  }

  override fun stop() {
    super.stop()
    logger.info { "AWARE Micro: MySQL client shutdown" }
    sqlClient.close()
  }

  private fun setDatabaseSslMode(serverConfig: JsonObject, options: MySQLConnectOptions) {
    val sslMode = (
      SecretResolver.get("DATABASE_SSL_MODE", serverConfig.getString("database_ssl_mode"))
        ?: "disable"
      ).trim().lowercase()

    val caPath = SecretResolver.get("DATABASE_SSL_CA_CERT_PATH", serverConfig.getString("database_ssl_path_ca_cert_pem"))?.trim()
    val clientKeyPath = SecretResolver.get("DATABASE_SSL_CLIENT_KEY_PATH", serverConfig.getString("database_ssl_path_client_key_pem"))?.trim()
    val clientCertPath = SecretResolver.get("DATABASE_SSL_CLIENT_CERT_PATH", serverConfig.getString("database_ssl_path_client_cert_pem"))?.trim()

    val normalizedMode = when (sslMode) {
      "disable", "disabled" -> SslMode.DISABLED
      "prefer", "preferred" -> SslMode.PREFERRED
      "require", "required" -> SslMode.REQUIRED
      "verify_ca", "verify-ca" -> SslMode.VERIFY_CA
      "verify_identity", "verify-identity" -> SslMode.VERIFY_IDENTITY
      else -> {
        logger.warn { "Unknown DATABASE_SSL_MODE='$sslMode'; defaulting to DISABLED" }
        SslMode.DISABLED
      }
    }

    options.setSslMode(normalizedMode)

    if (!caPath.isNullOrEmpty()) {
      options.setPemTrustOptions(PemTrustOptions().addCertPath(caPath))
    }
    if (!clientKeyPath.isNullOrEmpty() && !clientCertPath.isNullOrEmpty()) {
      options.setPemKeyCertOptions(
        PemKeyCertOptions()
          .setKeyPath(clientKeyPath)
          .setCertPath(clientCertPath)
      )
    }
  }
}
