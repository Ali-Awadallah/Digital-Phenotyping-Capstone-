ALTER DATABASE aware_db ENCRYPTION='Y';

DROP PROCEDURE IF EXISTS encrypt_all_innodb_tables;

DELIMITER //
CREATE PROCEDURE encrypt_all_innodb_tables()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE table_name_var VARCHAR(255);
  DECLARE cur CURSOR FOR
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = 'aware_db'
      AND engine = 'InnoDB';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO table_name_var;
    IF done = 1 THEN
      LEAVE read_loop;
    END IF;

    SET @sql = CONCAT(
      'ALTER TABLE `aware_db`.`',
      REPLACE(table_name_var, '`', '``'),
      '` ENCRYPTION=''Y'''
    );
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;
END//
DELIMITER ;

CALL encrypt_all_innodb_tables();
DROP PROCEDURE encrypt_all_innodb_tables;
