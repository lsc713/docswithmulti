CREATE USER IF NOT EXISTS 'product_replicator'@'%' IDENTIFIED BY 'product_replicator';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'product_replicator'@'%';

CREATE USER IF NOT EXISTS 'product_reader'@'%' IDENTIFIED BY 'product_reader';
GRANT SELECT ON product_db.* TO 'product_reader'@'%';
