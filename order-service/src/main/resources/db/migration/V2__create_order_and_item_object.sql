-- order 먼저
INSERT INTO orders (id, status, created_at)
VALUES (1, 'DELIVERY_WAITING', NOW());

-- order_item
INSERT INTO order_item (id, order_id, status)
VALUES (101, 1,  'ACTIVE'),
       (102, 1,  'ACTIVE');