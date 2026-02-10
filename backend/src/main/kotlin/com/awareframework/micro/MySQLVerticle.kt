package com.awareframework.micro

import org.apache.commons.lang.StringEscapeUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
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

class MySQLVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: MySQLPool

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val eventBus = vertx.eventBus()

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        // https://vertx.io/docs/4.3.3/apidocs/io/vertx/mysqlclient/MySQLConnectOptions.html
        val connectOptions = MySQLConnectOptions()
          .setHost(serverConfig.getString("database_host"))
          .setPort(serverConfig.getInteger("database_port"))
          .setDatabase(serverConfig.getString("database_name"))
          .setUser(serverConfig.getString("database_user"))
          .setPassword(serverConfig.getString("database_pwd"))
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
        
        // Create geofence system tables on startup
        createParticipantsTable()
        createRedZonesTable()
        createGeofenceAlertsTable()

        // ---- SENSOR DATA TABLES ----
        
        // Create sensor data tables on startup
        createBatteryReadingsTable()
        createScreenEventsTable()
        createNotificationsTable()
        createAccelAlertsTable()
        createAccelAiStateTable()

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

        // Get accelerometer anomaly alerts
        eventBus.consumer<JsonObject>("getAccelAlerts") { receivedMessage ->
          val activeOnly = receivedMessage.body().getBoolean("active_only", false)
          getAccelAlerts(activeOnly).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(response.result())
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to get accel alerts")
            }
          }
        }

        // Acknowledge accelerometer anomaly alert
        eventBus.consumer<JsonObject>("acknowledgeAccelAlert") { receivedMessage ->
          val alertId = receivedMessage.body().getInteger("alert_id")
          val acknowledgedBy = receivedMessage.body().getString("acknowledged_by", "admin")
          acknowledgeAccelAlert(alertId, acknowledgedBy).onComplete { response ->
            if (response.succeeded()) {
              receivedMessage.reply(JsonObject().put("ok", true))
            } else {
              receivedMessage.fail(500, response.cause().message ?: "Failed to acknowledge accel alert")
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
            `participant_id` VARCHAR(36) PRIMARY KEY,
            `device_id` VARCHAR(128) NOT NULL UNIQUE,
            `name` VARCHAR(100) NOT NULL,
            `red_zone_radius` INT DEFAULT 300,
            `status` ENUM('active', 'inactive') DEFAULT 'active',
            `risk_level` ENUM('low', 'moderate', 'high') DEFAULT 'low',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX `idx_device` (`device_id`),
            INDEX `idx_status` (`status`)
          )
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Created participants table" }
            promise.complete(true)
            connection.close()
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
            `state` VARCHAR(10) NOT NULL,
            `timestamp` BIGINT NOT NULL,
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

  fun createAccelAlertsTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
        if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val query = """
                CREATE TABLE IF NOT EXISTS `accel_alerts` (
                    `alert_id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                    `participant_id` VARCHAR(36) NOT NULL,
                    `triggered_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    `anomaly_type` VARCHAR(50) NOT NULL,
                    `anomaly_score` DOUBLE NOT NULL,
                    `acknowledged` TINYINT(1) DEFAULT 0,
                    `acknowledged_by` VARCHAR(100) NULL,
                    `acknowledged_at` TIMESTAMP NULL,
                    `metadata` JSON NULL,
                    INDEX `idx_participant` (`participant_id`),
                    INDEX `idx_acknowledged` (`acknowledged`),
                    INDEX `idx_triggered` (`triggered_at`)
                )
            """.trimIndent()

            connection.query(query).execute()
                .onSuccess {
                    logger.info { "Created accel_alerts table" }
                    promise.complete(true)
                    connection.close()
                }
                .onFailure { e ->
                    logger.error(e) { "Failed to create accel_alerts table" }
                    promise.fail(e.message)
                    connection.close()
                }
        } else {
            promise.fail(connectionResult.cause().message)
        }
    }
    return promise.future()
}

fun createAccelAiStateTable(): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
        if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val query = """
                CREATE TABLE IF NOT EXISTS `accel_ai_state` (
                    `state_id` INT PRIMARY KEY,
                    `last_end_time_str` VARCHAR(64) NULL
                )
            """.trimIndent()

            connection.query(query).execute()
                .onSuccess {
                    // Ensure the singleton row exists
                    connection.query("INSERT IGNORE INTO accel_ai_state (state_id, last_end_time_str) VALUES (1, NULL)")
                        .execute()
                        .onSuccess {
                            logger.info { "Created accel_ai_state table" }
                            promise.complete(true)
                            connection.close()
                        }
                        .onFailure { e ->
                            logger.error(e) { "Failed to seed accel_ai_state row" }
                            promise.fail(e.message)
                            connection.close()
                        }
                }
                .onFailure { e ->
                    logger.error(e) { "Failed to create accel_ai_state table" }
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
    val state = data.getString("state")
    val timestamp = data.getLong("timestamp")
    
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO screen_events (device_id, state, timestamp)
          VALUES ('$deviceId', '$state', $timestamp)
        """.trimIndent()
        connection.query(query).execute()
          .onSuccess {
            logger.info { "Inserted screen event for $deviceId: $state" }
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
          SELECT p.*, b.percentage, b.charging_status 
          FROM participants p 
          LEFT JOIN battery_readings b ON p.device_id = b.device_id 
          AND b.timestamp = (SELECT MAX(timestamp) FROM battery_readings b2 WHERE b2.device_id = p.device_id) 
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
    val name = data.getString("name", "Unknown")
    val redZoneRadius = data.getInteger("red_zone_radius", 300)
    val status = data.getString("status", "active")
    val riskLevel = data.getString("risk_level", "low")

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val query = """
          INSERT INTO participants (participant_id, device_id, name, red_zone_radius, status, risk_level)
          VALUES ('$participantId', '$deviceId', '$name', $redZoneRadius, '$status', '$riskLevel')
          ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            red_zone_radius = VALUES(red_zone_radius),
            status = VALUES(status),
            risk_level = VALUES(risk_level)
        """.trimIndent()
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

fun getAccelAlerts(activeOnly: Boolean): Future<JsonArray> {
    val promise = Promise.promise<JsonArray>()
    sqlClient.getConnection { connectionResult ->
        if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val whereClause = if (activeOnly) "WHERE acknowledged = 0" else ""
            val query = """
                SELECT
                    alert_id,
                    participant_id,
                    triggered_at,
                    anomaly_type,
                    anomaly_score,
                    acknowledged,
                    acknowledged_by,
                    acknowledged_at,
                    metadata
                FROM accel_alerts
                $whereClause
                ORDER BY triggered_at DESC
                LIMIT 200
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
                    logger.error(e) { "Failed to get accel alerts" }
                    promise.fail(e.message)
                    connection.close()
                }
        } else {
            promise.fail(connectionResult.cause().message)
        }
    }
    return promise.future()
}

fun acknowledgeAccelAlert(alertId: Int, acknowledgedBy: String): Future<Boolean> {
    val promise = Promise.promise<Boolean>()
    sqlClient.getConnection { connectionResult ->
        if (connectionResult.succeeded()) {
            val connection = connectionResult.result()
            val safeBy = acknowledgedBy.replace("'", "''")
            val query = """
                UPDATE accel_alerts
                SET acknowledged = 1, acknowledged_by = '$safeBy', acknowledged_at = NOW()
                WHERE alert_id = $alertId
            """.trimIndent()

            connection.query(query).execute()
                .onSuccess {
                    logger.info { "Acknowledged accel alert: $alertId by $safeBy" }
                    promise.complete(true)
                    connection.close()
                }
                .onFailure { e ->
                    logger.error(e) { "Failed to acknowledge accel alert" }
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
        connection.query("SELECT * FROM location WHERE device_id = '$deviceId' ORDER BY timestamp DESC LIMIT 1").execute()
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
