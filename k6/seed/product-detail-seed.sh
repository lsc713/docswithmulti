#!/usr/bin/env bash
set -euo pipefail
SEED_COUNT="${SEED_COUNT:-100}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3310}"
MYSQL_USER="${MYSQL_USER:-product}"
MYSQL_PASS="${MYSQL_PASS:-product}"
MYSQL_DB="${MYSQL_DB:-product_db}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/productIds.json}"
PREFIX="product_lt_$(date +%s)_$$_"
command -v mysql >/dev/null
command -v jq >/dev/null
[[ "$SEED_COUNT" =~ ^[1-9][0-9]*$ ]]
db() { mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" "$@"; }

db <<SQL
DROP PROCEDURE IF EXISTS seed_product_detail_loadtest;
DELIMITER //
CREATE PROCEDURE seed_product_detail_loadtest(IN p_count INT, IN pfx VARCHAR(80))
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE c INT;
  DECLARE s INT;
  DECLARE root_id, mid_id, leaf_id BIGINT;
  DECLARE color_attr, size_attr, material_attr, origin_attr BIGINT;
  DECLARE red_id, green_id, blue_id, small_id, medium_id, large_id BIGINT;
  DECLARE material_id, origin_id, product_id, sku_id, color_id, size_id BIGINT;
  DECLARE color_name, size_name VARCHAR(20);

  START TRANSACTION;
  INSERT INTO category(parent_id,name,level) VALUES(NULL,CONCAT(pfx,'root'),1);
  SET root_id=LAST_INSERT_ID();
  INSERT INTO category(parent_id,name,level) VALUES(root_id,CONCAT(pfx,'mid'),2);
  SET mid_id=LAST_INSERT_ID();
  INSERT INTO category(parent_id,name,level) VALUES(mid_id,CONCAT(pfx,'leaf'),3);
  SET leaf_id=LAST_INSERT_ID();

  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'color')); SET color_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'size')); SET size_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'material')); SET material_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'origin')); SET origin_attr=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'red'); SET red_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'green'); SET green_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'blue'); SET blue_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'S'); SET small_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'M'); SET medium_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'L'); SET large_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(material_attr,'cotton'); SET material_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(origin_attr,'KR'); SET origin_id=LAST_INSERT_ID();

  WHILE i <= p_count DO
    INSERT INTO product(name,category_id) VALUES(CONCAT(pfx,i),leaf_id);
    SET product_id=LAST_INSERT_ID();
    INSERT INTO product_attribute(product_id,attribute_id,is_variant) VALUES
      (product_id,color_attr,TRUE),(product_id,size_attr,TRUE),
      (product_id,material_attr,FALSE),(product_id,origin_attr,FALSE);

    SET c=1;
    WHILE c <= 3 DO
      SET color_id=CASE c WHEN 1 THEN red_id WHEN 2 THEN green_id ELSE blue_id END;
      SET color_name=CASE c WHEN 1 THEN 'red' WHEN 2 THEN 'green' ELSE 'blue' END;
      SET s=1;
      WHILE s <= 3 DO
        SET size_id=CASE s WHEN 1 THEN small_id WHEN 2 THEN medium_id ELSE large_id END;
        SET size_name=CASE s WHEN 1 THEN 'S' WHEN 2 THEN 'M' ELSE 'L' END;
        INSERT INTO product_sku(product_id,sku_code,option_summary,price)
          VALUES(product_id,CONCAT(pfx,'sku_',i,'_',c,'_',s),CONCAT(color_name,'/',size_name),10000+i);
        SET sku_id=LAST_INSERT_ID();
        INSERT INTO product_stock(sku_id,available_qty) VALUES(sku_id,100);
        INSERT INTO sku_attribute_value(sku_id,attribute_value_id) VALUES(sku_id,color_id),(sku_id,size_id);
        SET s=s+1;
      END WHILE;
      SET c=c+1;
    END WHILE;

    INSERT INTO product_image(product_id,s3_key,sort_order) VALUES
      (product_id,CONCAT(pfx,i,'/1.jpg'),0),(product_id,CONCAT(pfx,i,'/2.jpg'),1),(product_id,CONCAT(pfx,i,'/3.jpg'),2);
    INSERT INTO product_descriptive_value(product_id,attribute_value_id) VALUES
      (product_id,material_id),(product_id,origin_id);
    SET i=i+1;
  END WHILE;
  COMMIT;
END//
DELIMITER ;
CALL seed_product_detail_loadtest(${SEED_COUNT}, '${PREFIX}');
DROP PROCEDURE seed_product_detail_loadtest;
SQL

db -N -B -r -e "SELECT JSON_ARRAYAGG(JSON_OBJECT('productId', product_id, 'skuId', sku_id)) FROM (SELECT p.id AS product_id, MIN(s.id) AS sku_id FROM product p JOIN product_sku s ON s.product_id=p.id JOIN product_stock st ON st.sku_id=s.id AND st.available_qty > 0 WHERE LEFT(p.name,CHAR_LENGTH('${PREFIX}'))='${PREFIX}' GROUP BY p.id ORDER BY p.id) p" > "$OUT"
actual=$(jq length "$OUT")
[ "$actual" -eq "$SEED_COUNT" ] || { echo "기대 $SEED_COUNT != 실제 $actual" >&2; exit 1; }
