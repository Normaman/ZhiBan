-- Legacy demo shops must no longer appear online or accept simulated sends.
DELETE FROM connector_bindings WHERE shop_id IN (SELECT id FROM shops WHERE mode = 'demo');
UPDATE shops SET knowledge_version = NULL
WHERE mode = 'demo' AND knowledge_version IN (
  SELECT id FROM knowledge_versions WHERE version_label = 'demo-kb-v1'
);
DELETE FROM knowledge_versions WHERE version_label = 'demo-kb-v1'
  AND shop_id IN (SELECT id FROM shops WHERE mode = 'demo');
DELETE FROM product_details WHERE shop_id IN (SELECT id FROM shops WHERE mode = 'demo');
DELETE FROM product_snapshots WHERE shop_id IN (SELECT id FROM shops WHERE mode = 'demo');
UPDATE shops
SET name = CASE WHEN name LIKE '演示%' THEN '拼多多店铺' ELSE name END,
    connector_account_id = 'unbound',
    platform_shop_id = 'unbound',
    mode = 'live',
    binding_state = 'unbound',
    connection_state = 'disconnected',
    connection_seen_at = 0,
    connection_error_code = NULL
WHERE mode = 'demo';
