-- Run this as MySQL root/admin user.
-- Purpose: create least-privilege accounts for backend runtime and signature engine runtime.
-- Update the passwords below before running in any shared environment.

CREATE ROLE IF NOT EXISTS `dp_backend_runtime`;
CREATE ROLE IF NOT EXISTS `dp_backend_schema`;
CREATE ROLE IF NOT EXISTS `dp_signature_runtime`;
CREATE ROLE IF NOT EXISTS `dp_dashboard_readonly`;

-- Backend runtime: DML access to application tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON `aware_db`.* TO `dp_backend_runtime`;

-- Backend schema role: needed because backend startup auto-creates/migrates tables.
GRANT CREATE, ALTER, INDEX ON `aware_db`.* TO `dp_backend_schema`;

-- Signature engine runtime: reads/writes features/alerts and also runs startup table checks.
GRANT SELECT, INSERT, UPDATE, DELETE ON `aware_db`.* TO `dp_signature_runtime`;
GRANT CREATE, ALTER, INDEX ON `aware_db`.* TO `dp_signature_runtime`;

-- Optional read-only account for BI/reporting access.
GRANT SELECT ON `aware_db`.* TO `dp_dashboard_readonly`;

CREATE USER IF NOT EXISTS 'aware_backend'@'%' IDENTIFIED BY 'CHANGE_ME_BACKEND_PASSWORD';
CREATE USER IF NOT EXISTS 'aware_signature'@'%' IDENTIFIED BY 'CHANGE_ME_SIGNATURE_PASSWORD';
CREATE USER IF NOT EXISTS 'aware_dashboard_ro'@'%' IDENTIFIED BY 'CHANGE_ME_DASHBOARD_PASSWORD';

ALTER USER 'aware_backend'@'%' IDENTIFIED BY 'CHANGE_ME_BACKEND_PASSWORD';
ALTER USER 'aware_signature'@'%' IDENTIFIED BY 'CHANGE_ME_SIGNATURE_PASSWORD';
ALTER USER 'aware_dashboard_ro'@'%' IDENTIFIED BY 'CHANGE_ME_DASHBOARD_PASSWORD';

REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'aware_backend'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'aware_signature'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'aware_dashboard_ro'@'%';

GRANT `dp_backend_runtime` TO 'aware_backend'@'%';
GRANT `dp_backend_schema` TO 'aware_backend'@'%';
GRANT `dp_signature_runtime` TO 'aware_signature'@'%';
GRANT `dp_dashboard_readonly` TO 'aware_dashboard_ro'@'%';

SET DEFAULT ROLE `dp_backend_runtime`, `dp_backend_schema` TO 'aware_backend'@'%';
SET DEFAULT ROLE `dp_signature_runtime` TO 'aware_signature'@'%';
SET DEFAULT ROLE `dp_dashboard_readonly` TO 'aware_dashboard_ro'@'%';

FLUSH PRIVILEGES;

-- Optional hardening after schema stabilizes:
-- REVOKE `dp_backend_schema` FROM 'aware_backend'@'%';
-- SET DEFAULT ROLE `dp_backend_runtime` TO 'aware_backend'@'%';

SHOW GRANTS FOR 'aware_backend'@'%';
SHOW GRANTS FOR 'aware_signature'@'%';
SHOW GRANTS FOR 'aware_dashboard_ro'@'%';
