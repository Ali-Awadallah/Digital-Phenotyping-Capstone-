# Database Security Runbook (Local/Server Deployment)

This project currently protects participant data in two layers:

1. MySQL at-rest encryption (full database tables + logs).
2. Application-level AES-256-GCM encryption for selected sensitive text fields.
3. API key access control for ingest/admin endpoints (optional but supported).
4. SQL injection hardening via parameterized SQL queries in backend DB access paths.
5. Security audit trail for sensitive admin actions.

This file documents how to operate and verify the database security setup.

## 1) What Is Encrypted

### MySQL (data at rest)
- InnoDB tables: encrypted (`ENCRYPTION='Y'`).
- InnoDB redo logs: encrypted.
- InnoDB undo logs: encrypted.
- Binary logs: encrypted.

### Application layer
- `notifications.title`
- `notifications.content`
- `participants.name`
- `red_zones.name`
- `accelerometer.x_enc`, `accelerometer.y_enc`, `accelerometer.z_enc` (encrypted mirrors for demo visibility)

Stored format for app-encrypted values: `enc:v1:<base64>`.

## 1.1 Security Audit Log

`security_audit_log` records sensitive actions:
- participant upserts (manual/admin flows)
- red-zone insert/delete
- geofence alert acknowledgements
- signature alert acknowledgements

Use this query:

```sql
SELECT event_at, actor, action, target_type, target_id, details
FROM security_audit_log
ORDER BY event_at DESC
LIMIT 100;
```

## 1.2 API Key + Session Access Control (RBAC)

Backend supports:
- `API_KEY_INGEST` for sensor ingestion endpoints
- `API_KEY_ADMIN` for admin endpoints (`/api/participants`, `/api/zones`, `/api/alerts`, `/api/signature-alerts`)

If both keys are empty, ingest endpoints are open (local dev mode), but admin dashboard endpoints still require session login.
Clients can pass key using:
- `Authorization: Bearer <key>`
- `X-API-Key: <key>`
- `?api_key=<key>`

Dashboard/session auth:
- `POST /api/auth/login` with username/password (`app_users` table)
- `Authorization: Bearer <session_token>` for admin/dashboard APIs
- RBAC roles: `admin`, `analyst`, `viewer`, `doctor`, `ingest`

## 1.3 Least-Privilege Database Users

Use dedicated DB accounts instead of shared/root users:

- `aware_backend`: backend runtime (DML + startup schema migration role).
- `aware_signature`: signature engine runtime (DML + startup schema-check role).
- `aware_dashboard_ro`: optional read-only analytics/report user.

Apply roles/users script:

```powershell
Get-Content -Raw backend/mysql/least_privilege.sql | docker exec -i dp-mysql mysql -uroot -prootpassword
```

Then set:

- `DATABASE_USER`, `DATABASE_PASSWORD` for backend service.
- `SIGNATURE_DB_USER`, `SIGNATURE_DB_PASSWORD` for signature engine service.

After schema is stable, revoke schema role from backend user:

```sql
REVOKE `dp_backend_schema` FROM 'aware_backend'@'%';
SET DEFAULT ROLE `dp_backend_runtime` TO 'aware_backend'@'%';
```

## 2) Current Compose Configuration

`backend/docker-compose.yml` enables MySQL encryption with:

- `--early-plugin-load=keyring_file.so`
- `--keyring_file_data=/var/lib/mysql-keyring/keyring`
- `--default_table_encryption=ON`
- `--innodb_redo_log_encrypt=ON`
- `--innodb_undo_log_encrypt=ON`
- `--binlog_encryption=ON`

Volumes:
- `mysql_data`: MySQL data files
- `mysql_keyring`: encryption keyring file

## 3) Verify Encryption Is Active

Run:

```powershell
docker exec dp-mysql mysql -uroot -prootpassword aware_db -e "SHOW VARIABLES LIKE 'default_table_encryption'; SHOW VARIABLES LIKE 'innodb_redo_log_encrypt'; SHOW VARIABLES LIKE 'innodb_undo_log_encrypt'; SHOW VARIABLES LIKE 'binlog_encryption';"
```

Expected: all values are `ON`.

Check plugin:

```powershell
docker exec dp-mysql mysql -uroot -prootpassword aware_db -e "SELECT PLUGIN_NAME, PLUGIN_STATUS FROM information_schema.plugins WHERE PLUGIN_NAME='keyring_file';"
```

Expected: `keyring_file = ACTIVE`.

Check table encryption:

```powershell
docker exec dp-mysql mysql -uroot -prootpassword aware_db -e "SELECT TABLE_NAME, CREATE_OPTIONS FROM information_schema.tables WHERE TABLE_SCHEMA='aware_db' AND ENGINE='InnoDB' ORDER BY TABLE_NAME;"
```

Expected: `CREATE_OPTIONS` contains `ENCRYPTION='Y'` for all InnoDB tables.

## 4) Encrypt Existing Tables (One-Time / Re-run Safe)

If needed after schema imports:

```powershell
Get-Content -Raw backend/mysql/enable_existing_table_encryption.sql | docker exec -i dp-mysql mysql -uroot -prootpassword aware_db
```

## 5) Key Security Rules

The keyring file is the DB encryption key material. Protect it as strictly as the data.

- Never commit keyring files to git.
- Restrict host access to Docker volumes.
- Encrypt host/server disk where Docker volumes are stored.
- Limit MySQL root access and rotate DB credentials.
- Treat backups of `mysql_data` and `mysql_keyring` as sensitive secrets.

Important: encrypted backups are unreadable without matching keyring material.

## 6) Key Rotation

Rotate InnoDB master key periodically:

```sql
ALTER INSTANCE ROTATE INNODB MASTER KEY;
```

You can run it with:

```powershell
docker exec dp-mysql mysql -uroot -prootpassword aware_db -e "ALTER INSTANCE ROTATE INNODB MASTER KEY;"
```

Recommended rotation cadence: every 60-90 days (or on security events).

Rotate API/auth keys and app data key in `.env`:

```powershell
cd backend
python rotate_keys.py
```

This updates:

- `API_KEY_INGEST`
- `API_KEY_ADMIN`
- `DATA_ENCRYPTION_KEY_B64`

After rotation, restart backend and signature engine containers.

## 7) Backup and Restore

Back up both:

1. `mysql_data`
2. `mysql_keyring`

Restore must keep them consistent (same point-in-time set). If you restore data without the matching keyring, encrypted tables cannot be decrypted.

## 8) Incident Response (Minimum)

If key or DB credentials are suspected compromised:

1. Stop external access immediately.
2. Rotate DB passwords.
3. Rotate InnoDB master key.
4. Reissue app encryption key (`DATA_ENCRYPTION_KEY_B64`) and re-encrypt affected app-level fields if required.
5. Review logs and isolate affected systems.

## 9) Presentation Summary

Use this short statement:

"We secure participant data with AES-256 at rest in MySQL (tables and logs) and AES-256-GCM for sensitive fields at application level, with controlled key storage, key rotation, and protected backups."
