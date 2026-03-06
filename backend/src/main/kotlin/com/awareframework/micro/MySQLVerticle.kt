package com.awareframework.micro

import org.apache.commons.lang.StringEscapeUtils
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
          .setHost(System.getenv("DATABASE_HOST") ?: serverConfig.getString("database_host"))
          .setPort(System.getenv("DATABASE_PORT")?.toIntOrNull() ?: serverConfig.getInteger("database_port"))
          .setDatabase(System.getenv("DATABASE_NAME") ?: serverConfig.getString("database_name"))
          .setUser(System.getenv("DATABASE_USER") ?: serverConfig.getString("database_user"))
          .setPassword(System.getenv("DATABASE_PASSWORD") ?: serverConfig.getString("database_pwd"))
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

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-vertx-jdbc-client_changes-in-client-components#running_queries_on_managed_connections
        connection
          .query("SELECT * FROM $table WHERE device_id = '$device_id' AND timestamp between $start AND $end ORDER BY timestamp ASC")
          .execute()
          .onFailure { e ->
            logger.error(e) { "Failed to retrieve data." }
            connection.close()
            dataPromise.fail(e.message)
          }
          .onSuccess { rows ->
            logger.info { "$device_id : retrieved ${rows.size()} records from $table" }
            connection.close()
            dataPromise.complete(JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
              .collect(Collectors.toList())))
          }
      }
    }

    return dataPromise.future()
  }

  fun updateData(device_id: String, table: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          val updateItem =
            "UPDATE `$table` SET data = $entry WHERE device_id = '$device_id' AND timestamp = ${entry.getDouble("timestamp")}"

          // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-vertx-jdbc-client_changes-in-client-components#running_queries_on_managed_connections
          connection.query(updateItem)
            .execute()
            .onFailure { e ->
              logger.error(e) { "Failed to process update." }
              connection.close()
            }
            .onSuccess { _ ->
              logger.info { "$device_id updated $table: ${entry.encode()}" }
              connection.close()
            }
        }
      } else {
        logger.error(connectionResult.cause()) { "Failed to establish connection." }
      }
    }
  }

  fun deleteData(device_id: String, table: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val timestamps = mutableListOf<Double>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          timestamps.add(entry.getDouble("timestamp"))
        }

        val deleteBatch =
          "DELETE FROM `$table` WHERE device_id = '$device_id' AND timestamp in (${timestamps.stream().map(Any::toString).collect(
            Collectors.joining(",")
          )})"
        connection.query(deleteBatch)
          .execute()
          .onFailure { e ->
            logger.error(e) { "Failed to process delete batch." }
            connection.close()
          }
          .onSuccess { _ ->
            logger.info { "$device_id deleted from $table: ${data.size()} records" }
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
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connect = connectionResult.result()
        val queryCreateTable = "CREATE TABLE IF NOT EXISTS `$table` (`_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY, `timestamp` DOUBLE NOT NULL, `device_id` VARCHAR(128) NOT NULL, `data` JSON NOT NULL, INDEX `timestamp_device` (`timestamp`, `device_id`))"
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

    createTable(table)
      .onSuccess { _ ->
        sqlClient.getConnection { connectionResult ->
          if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val rows = data.size()
            val values = ArrayList<String>()
            for (i in 0 until data.size()) {
              val entry = data.getJsonObject(i)
              // https://github.com/eclipse-vertx/vert.x/commit/ea0eddb129530ab3719c0ef86b471894876ec519#diff-07f061e092a63da24a06ab4507d15125e3377034f21eee18c6d4261f6714e709L241
              values.add("('$device_id', '${entry.getDouble("timestamp")}', '${StringEscapeUtils.escapeJavaScript(entry.encode())}')")
            }
            val insertBatch =
              "INSERT INTO `$table` (`device_id`,`timestamp`,`data`) VALUES ${values.stream().map(Any::toString).collect(
                Collectors.joining(",")
              )}"
            connection.query(insertBatch)
              .execute()
              .onFailure { e ->
                logger.error(e) { "Failed to process batch." }
                connection.close()
              }
              .onSuccess { _ ->
                logger.info { "$device_id inserted to $table: $rows records" }
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
            `device_id` VARCHAR(128) NOT NULL UNIQUE,
            `device_type` VARCHAR(32) NOT NULL DEFAULT 'unknown',
            `name` VARCHAR(100) NOT NULL,
            `red_zone_radius` INT DEFAULT 300,
            `status` ENUM('active', 'inactive') DEFAULT 'active',
            `risk_level` ENUM('low', 'moderate', 'high') DEFAULT 'low',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX `idx_participant_id` (`participant_id`),
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
                    connection.query("ALTER TABLE participants MODIFY COLUMN participant_id VARCHAR(128) NOT NULL").execute()
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
                    connection.query(
                      """
                      DELETE p1
                      FROM participants p1
                      INNER JOIN participants p2
                        ON p1.device_id = p2.device_id
                       AND p1.id < p2.id
                      """.trimIndent()
                    ).execute().recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query("CREATE UNIQUE INDEX ux_participants_device_id ON participants(device_id)").execute()
                      .recover { Future.succeededFuture() }
                  }
                  .compose {
                    connection.query("CREATE INDEX idx_participant_id ON participants(participant_id)").execute()
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
            `participant_id` VARCHAR(36) NOT NULL,
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
            INDEX `idx_sa_created` (`created_at`),
            INDEX `idx_sa_status` (`status`),
            INDEX `idx_sa_ack` (`acknowledged_at`)
          )
        """.trimIndent()

        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created signature_alerts table (if missing)" }
            promise.complete(true)
            connection.close()
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
          VALUES ('$deviceId', $percentage, '$chargingStatus', $timestamp)
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, '$utcTime', '$event')
        """.trimIndent()
        connection.query(query).execute()
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
            `y` DOUBLE NULL,
            `z` DOUBLE NULL,
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_timestamp` (`timestamp`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created accelerometer table" }
            promise.complete(true)
            connection.close()
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
                  Future.succeededFuture()
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
    val utcTime = java.time.Instant.ofEpochMilli(timestamp)
      .atZone(java.time.ZoneOffset.UTC)
      .toLocalDateTime()
      .toString()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO `accelerometer` (`device_id`, `timestamp`, `utc_time`, `x`, `y`, `z`)
          VALUES ('$deviceId', $timestamp, '$utcTime', $x, $y, $z)
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, '$utcTime', $x, $y, $z)
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, '$utcTime', $latitude, $longitude, $altitude, $accuracy, NOW())
        """.trimIndent()
        connection.query(query).execute()
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

  fun insertNotification(data: JsonObject): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    val deviceId = data.getString("device_id")
    val appName = data.getString("app_name", "").replace("'", "''")
    val title = data.getString("title", "").replace("'", "''")
    val content = data.getString("content", "").replace("'", "''")
    val category = data.getString("category", "").replace("'", "''")
    val kind = data.getString("kind", "posted")
    val timestamp = data.getLong("timestamp")
    val dismissedAt = data.getLong("dismissed_at", 0L)
    
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val dismissedAtValue = if (dismissedAt > 0) "$dismissedAt" else "NULL"
        val query = """
          INSERT INTO notifications (device_id, app_name, title, content, category, kind, timestamp, dismissed_at)
          VALUES ('$deviceId', '$appName', '$title', '$content', '$category', '$kind', $timestamp, $dismissedAtValue)
        """.trimIndent()
        connection.query(query).execute()
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
        val query = """
          SELECT * FROM battery_readings 
          WHERE device_id = '$deviceId' 
          ORDER BY timestamp DESC LIMIT 1
        """.trimIndent()
        connection.query(query).execute()
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
            p.*,
            b.percentage,
            b.charging_status,
            CASE
              WHEN LOWER(COALESCE(p.device_type, '')) IN ('phone', 'watch') THEN LOWER(p.device_type)
              WHEN phone_data.has_phone = 1 AND watch_data.has_watch = 1 THEN 'both'
              WHEN phone_data.has_phone = 1 THEN 'phone'
              WHEN watch_data.has_watch = 1 THEN 'watch'
              ELSE 'unknown'
            END AS source_type
          FROM participants p
          LEFT JOIN (
            SELECT br.device_id, br.percentage, br.charging_status
            FROM battery_readings br
            INNER JOIN (
              SELECT device_id, MAX(id) AS max_id
              FROM battery_readings
              GROUP BY device_id
            ) latest_battery ON latest_battery.max_id = br.id
          ) b ON p.device_id = b.device_id
          LEFT JOIN (
            SELECT device_id, 1 AS has_phone
            FROM (
              SELECT DISTINCT device_id FROM accelerometer
              UNION
              SELECT DISTINCT device_id FROM gyroscope
              UNION
              SELECT DISTINCT device_id FROM location
              UNION
              SELECT DISTINCT device_id FROM screen_events
            ) phone_sources
          ) phone_data ON p.device_id = phone_data.device_id
          LEFT JOIN (
            SELECT device_id, 1 AS has_watch
            FROM (
              SELECT DISTINCT device_id FROM wearable_heart_rate
              UNION
              SELECT DISTINCT device_id FROM wearable_steps
              UNION
              SELECT DISTINCT device_id FROM wearable_sleep
              UNION
              SELECT DISTINCT device_id FROM wearable_oxygen
              UNION
              SELECT DISTINCT device_id FROM wearable_respiratory
            ) watch_sources
          ) watch_data ON p.device_id = watch_data.device_id
          ORDER BY p.name ASC
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess { rows ->
            val result = JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
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
        connection.query("SELECT * FROM participants WHERE device_id = '$deviceId' LIMIT 1").execute()
          .onSuccess { rows ->
            val result = rows.firstOrNull()?.toJson()
            promise.complete(result)
            connection.close()
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
    val participantId = data.getString("participant_id") ?: java.util.UUID.randomUUID().toString()
    val deviceId = data.getString("device_id")
    val deviceType = data.getString("device_type", "unknown")
    val name = data.getString("name", "Unknown")
    val redZoneRadius = data.getInteger("red_zone_radius", 300)
    val status = data.getString("status", "active")
    val riskLevel = data.getString("risk_level", "low")
    val isAutoLink = data.getBoolean("is_auto_link", false)

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = if (isAutoLink) {
          """
          INSERT IGNORE INTO participants (participant_id, device_id, device_type, name, red_zone_radius, status, risk_level, created_at, updated_at)
          VALUES ('$participantId', '$deviceId', '$deviceType', '$name', $redZoneRadius, '$status', '$riskLevel', NOW(), NOW())
          """.trimIndent()
        } else {
          """
          INSERT INTO participants (participant_id, device_id, device_type, name, red_zone_radius, status, risk_level, created_at, updated_at)
          VALUES ('$participantId', '$deviceId', '$deviceType', '$name', $redZoneRadius, '$status', '$riskLevel', NOW(), NOW())
          ON DUPLICATE KEY UPDATE
            device_type = IF(VALUES(device_type) = 'unknown', device_type, VALUES(device_type)),
            name = VALUES(name),
            red_zone_radius = VALUES(red_zone_radius),
            status = VALUES(status),
            risk_level = VALUES(risk_level),
            updated_at = NOW()
          """.trimIndent()
        }
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Upserted participant: $participantId" }
            promise.complete(participantId)
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
        // Get zones for this participant + global zones (participant_id IS NULL)
        val whereClause = if (participantId != null) {
          "WHERE participant_id = '$participantId' OR participant_id IS NULL"
        } else {
          ""
        }
        connection.query("SELECT * FROM red_zones $whereClause ORDER BY name ASC").execute()
          .onSuccess { rows ->
            val result = JsonArray(StreamSupport.stream(rows.spliterator(), false)
              .map { row -> row.toJson() }
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
    val name = data.getString("name", "Unnamed Zone")
    val latitude = data.getDouble("latitude")
    val longitude = data.getDouble("longitude")
    val radius = data.getInteger("radius", 300)
    val zoneType = data.getString("zone_type", "custom")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val participantVal = if (participantId != null) "'$participantId'" else "NULL"
        val query = """
          INSERT INTO red_zones (zone_id, participant_id, name, latitude, longitude, radius, zone_type)
          VALUES ('$zoneId', $participantVal, '$name', $latitude, $longitude, $radius, '$zoneType')
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Inserted red zone: $zoneId" }
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
        connection.query("DELETE FROM red_zones WHERE zone_id = '$zoneId'").execute()
          .onSuccess {
            logger.info { "Deleted red zone: $zoneId" }
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
              .map { row -> row.toJson() }
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
          VALUES ('$alertId', '$participantId', '$zoneId', '$zoneName', $latitude, $longitude, $distance)
        """.trimIndent()
        connection.query(query).execute()
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
        val safeBy = acknowledgedBy.replace("'", "''")
        val query = """
          UPDATE geofence_alerts
          SET acknowledged = TRUE, acknowledged_by = '$safeBy', acknowledged_at = NOW()
          WHERE alert_id = '$alertId'
        """.trimIndent()

        connection.query(query).execute()
          .onSuccess {
            logger.info { "Acknowledged alert: $alertId by $safeBy" }
            vertx.eventBus().publish(
              "alerts.changed",
              JsonObject()
                .put("action", "acknowledge")
                .put("alert_type", "geofence")
                .put("alert_id", alertId)
                .put("acknowledged_by", safeBy)
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
        val query = """
          SELECT COUNT(*) as count FROM geofence_alerts 
          WHERE participant_id = '$participantId' 
          AND zone_id = '$zoneId' 
          AND triggered_at > DATE_SUB(NOW(), INTERVAL $windowMinutes MINUTE)
        """.trimIndent()
        connection.query(query).execute()
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
        connection.query("SELECT id, device_id, timestamp, utc_time, latitude, longitude, altitude, accuracy, created_at FROM location WHERE device_id = '$deviceId' ORDER BY timestamp DESC LIMIT 1").execute()
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
          VALUES ('$deviceId', $timestamp, $bpm, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $startTime, $endTime, $count, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
    val title = StringEscapeUtils.escapeSql(data.getString("title", "Sleep"))
    val notes = StringEscapeUtils.escapeSql(data.getString("notes", ""))

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO wearable_sleep (device_id, start_time, end_time, title, notes, created_at)
          VALUES ('$deviceId', $startTime, $endTime, '$title', '$notes', NOW())
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, $systolic, $diastolic, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, $weightKg, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, $percentage, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
          VALUES ('$deviceId', $timestamp, $rate, NOW())
        """.trimIndent()
        connection.query(query).execute()
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
            id, participant_id, hour_start, alert_code, alert_name,
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
        val safeBy = acknowledgedBy.replace("'", "''")

        val query = """
          UPDATE signature_alerts
          SET acknowledged_at = NOW(),
              acknowledged_by = '$safeBy',
              status = 'acknowledged'
          WHERE id = $id AND acknowledged_at IS NULL
        """.trimIndent()

        connection.query(query).execute()
          .onSuccess {
            logger.info { "Acknowledged signature alert: $id by $safeBy" }
            vertx.eventBus().publish(
              "alerts.changed",
              JsonObject()
                .put("action", "acknowledge")
                .put("alert_type", "signature")
                .put("id", id)
                .put("acknowledged_by", safeBy)
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

  override fun stop() {
    super.stop()
    logger.info { "AWARE Micro: MySQL client shutdown" }
    sqlClient.close()
  }

  private fun setDatabaseSslMode(serverConfig: JsonObject, options: MySQLConnectOptions) {
    val sslMode = serverConfig.getString("database_ssl_mode")
    when (sslMode) {
      null, "", "disable", "disabled" -> {
        options.setSslMode(SslMode.DISABLED)
      }
      "prefer", "preferred" -> {
        options.setSslMode(SslMode.PREFERRED)
        if (serverConfig.containsKey("database_ssl_path_ca_cert_pem")) {
          options.setPemTrustOptions(PemTrustOptions().addCertPath(serverConfig.getString("database_ssl_path_ca_cert_pem")))
          if (serverConfig.containsKey("database_ssl_path_client_key_pem")) {
            options.setPemKeyCertOptions(PemKeyCertOptions()
                .setKeyPath(serverConfig.getString("database_ssl_path_client_key_pem"))
                .setCertPath(serverConfig.getString("database_ssl_path_client_cert_pem")))
          }
        }
      }
    }
  }
}
