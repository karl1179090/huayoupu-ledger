-- Synthetic complete ledger v10 schema, reconstructed from the f0a52a5 baseline.
-- Financial facts derive from the checked-in v9 synthetic fixture; category names add migration cases.
-- No production migration or current LedgerStore.onCreate runs while preparing this fixture.
BEGIN TRANSACTION;
CREATE TABLE accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,name TEXT NOT NULL,
            description TEXT NOT NULL,amount INTEGER NOT NULL,
            kind TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('ASSET','LIABILITY')),
            bank_code TEXT,card_last4 TEXT,archived INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,
            CHECK(amount BETWEEN -99999999999 AND 99999999999),
            CHECK(type='ASSET' OR amount>=0),CHECK(card_last4 IS NULL OR length(card_last4)=4 AND card_last4 NOT GLOB '*[^0-9]*'));
INSERT INTO "accounts" VALUES(1,'11111111-1111-4111-8111-111111111111','合成招商卡','合成固定余额',100000,'bank','ASSET','cmb','0007',0,5);
INSERT INTO "accounts" VALUES(2,'22222222-2222-4222-8222-222222222222','合成微信零钱','',20000,'wechat','ASSET',NULL,NULL,0,2);
INSERT INTO "accounts" VALUES(3,'33333333-3333-4333-8333-333333333333','合成归档负资产','',-5000,'cash','ASSET',NULL,NULL,1,8);
INSERT INTO "accounts" VALUES(4,'44444444-4444-4444-8444-444444444444','合成负债','',10000,'custom','LIABILITY',NULL,NULL,0,1);
CREATE TABLE app_wallet_bindings(package_name TEXT PRIMARY KEY NOT NULL,account_uid TEXT NOT NULL);
INSERT INTO "app_wallet_bindings" VALUES('com.tencent.mm','22222222-2222-4222-8222-222222222222');
CREATE TABLE capture_events (event_key TEXT PRIMARY KEY NOT NULL, received_at INTEGER NOT NULL);
CREATE TABLE capture_history(record_id INTEGER NOT NULL,sequence INTEGER NOT NULL,
            at INTEGER NOT NULL,kind TEXT NOT NULL,amount INTEGER,refunded INTEGER NOT NULL,
            raw_text TEXT NOT NULL,refund_mode TEXT NOT NULL,refund_id TEXT,refund_amount INTEGER,
            PRIMARY KEY(record_id,sequence));
INSERT INTO "capture_history" VALUES(1,1,1793937600000,'payment',10000,0,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(2,1,1793937600000,'payment',2000,0,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(3,1,1793937600000,'payment',7000,0,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(4,1,1793937600000,'payment',9000,0,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(5,1,1793937600000,'payment',0,10000,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(6,1,1793937600000,'payment',5000,0,'合成迁移历史','cumulative',NULL,NULL);
INSERT INTO "capture_history" VALUES(7,1,1793937600000,'payment',3000,0,'合成迁移历史','cumulative',NULL,NULL);
CREATE TABLE capture_images (record_id INTEGER PRIMARY KEY NOT NULL, image BLOB);
CREATE TABLE custom_categories (
            name TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('EXPENSE', 'INCOME')),
            PRIMARY KEY(direction, name));
INSERT INTO "custom_categories" VALUES('合成同名','EXPENSE');
INSERT INTO "custom_categories" VALUES('合成同名','INCOME');
INSERT INTO "custom_categories" VALUES('合成无引用','EXPENSE');
CREATE TABLE import_audit_history(batch_uid TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,
 file_sha256 TEXT NOT NULL,options_digest TEXT NOT NULL,result_json TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE import_batch_records(batch_uid TEXT NOT NULL,record_uid TEXT NOT NULL,PRIMARY KEY(batch_uid,record_uid));
CREATE TABLE import_batches(batch_uid TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,file_sha256 TEXT NOT NULL,
 options_digest TEXT NOT NULL,submission_token TEXT NOT NULL UNIQUE,result_json TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE merchant_icon_assets (merchant_key TEXT PRIMARY KEY NOT NULL, image BLOB NOT NULL);
CREATE TABLE merchant_icons (record_id INTEGER PRIMARY KEY NOT NULL, merchant_key TEXT NOT NULL);
CREATE TABLE record_deletions(record_id INTEGER PRIMARY KEY NOT NULL,expires_at INTEGER NOT NULL);
INSERT INTO "record_deletions" VALUES(6,1794024000000);
CREATE TABLE record_identities(record_id INTEGER NOT NULL,scope TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(record_id,scope));
INSERT INTO "record_identities" VALUES(1,'wechat','SYNTHETIC-V9-0001');
INSERT INTO "record_identities" VALUES(2,'wechat','SYNTHETIC-V9-0002');
INSERT INTO "record_identities" VALUES(3,'wechat','SYNTHETIC-V9-0003');
INSERT INTO "record_identities" VALUES(4,'wechat','SYNTHETIC-V9-0004');
INSERT INTO "record_identities" VALUES(5,'wechat','SYNTHETIC-V9-0005');
INSERT INTO "record_identities" VALUES(6,'wechat','SYNTHETIC-V9-0006');
INSERT INTO "record_identities" VALUES(7,'wechat','SYNTHETIC-V9-0007');
CREATE TABLE record_transfer_details(record_uid TEXT PRIMARY KEY NOT NULL,
 from_account_uid TEXT,to_account_uid TEXT,fee_cents INTEGER,fee_mode TEXT CHECK(fee_mode IN ('EXTRA','DEDUCTED')),
 from_selection_source TEXT,to_selection_source TEXT,from_manual_locked INTEGER NOT NULL DEFAULT 0,
 to_manual_locked INTEGER NOT NULL DEFAULT 0,from_evidence TEXT,to_evidence TEXT,
 from_evidence_fingerprint TEXT,to_evidence_fingerprint TEXT,from_reference TEXT,to_reference TEXT,
 evidence_version INTEGER NOT NULL DEFAULT 1,review_code TEXT,fee_manual_locked INTEGER NOT NULL DEFAULT 0,recognition_evidence TEXT);
CREATE TABLE record_wallet_effects(record_uid TEXT NOT NULL,account_uid TEXT NOT NULL,
 applied_cents INTEGER NOT NULL CHECK(applied_cents<>0),PRIMARY KEY(record_uid,account_uid));
INSERT INTO "record_wallet_effects" VALUES('cccccccc-cccc-4ccc-8ccc-000000000001','11111111-1111-4111-8111-111111111111',-10000);
INSERT INTO "record_wallet_effects" VALUES('cccccccc-cccc-4ccc-8ccc-000000000002','22222222-2222-4222-8222-222222222222',2000);
CREATE TABLE record_wallet_links(record_uid TEXT PRIMARY KEY NOT NULL,
 eligibility TEXT NOT NULL CHECK(eligibility IN ('ACTIVE','LEGACY_EXCLUDED','RESTORE_EXCLUDED','IMPORT_EXCLUDED')),
 account_uid TEXT,selection_source TEXT,manual_locked INTEGER NOT NULL DEFAULT 0,
 payment_evidence TEXT,evidence_fingerprint TEXT,review_code TEXT,revision INTEGER NOT NULL DEFAULT 0,account_reference TEXT);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000001','ACTIVE','11111111-1111-4111-8111-111111111111','MANUAL',1,NULL,NULL,NULL,1,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000002','ACTIVE','22222222-2222-4222-8222-222222222222','MANUAL',1,NULL,NULL,NULL,2,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000003','LEGACY_EXCLUDED','33333333-3333-4333-8333-333333333333','MANUAL',1,NULL,NULL,NULL,3,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000004','RESTORE_EXCLUDED','22222222-2222-4222-8222-222222222222','MANUAL',1,NULL,NULL,NULL,4,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000005','ACTIVE','11111111-1111-4111-8111-111111111111','MANUAL',1,NULL,NULL,NULL,5,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000006','ACTIVE','11111111-1111-4111-8111-111111111111','MANUAL',1,NULL,NULL,NULL,6,NULL);
INSERT INTO "record_wallet_links" VALUES('cccccccc-cccc-4ccc-8ccc-000000000007','ACTIVE',NULL,NULL,0,NULL,NULL,'WALLET_REQUIRED',7,NULL);
CREATE TABLE records (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE,
            source TEXT NOT NULL, origin TEXT NOT NULL, raw_text TEXT NOT NULL,
            received_at INTEGER NOT NULL, amount INTEGER, direction TEXT,
            merchant TEXT NOT NULL, category TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            transaction_id TEXT, reliable INTEGER NOT NULL, time_explicit INTEGER NOT NULL,
            state TEXT NOT NULL, reason TEXT NOT NULL, duplicate_id INTEGER, note TEXT NOT NULL, standalone_refund TEXT, refund_provider TEXT, refund_original INTEGER, refund_amount INTEGER, refund_mode TEXT, refund_id TEXT, transaction_identities TEXT NOT NULL DEFAULT '[]');
INSERT INTO "records" VALUES(1,'cccccccc-cccc-4ccc-8ccc-000000000001','IMAGE','微信','合成v9迁移夹具',1793937600000,10000,'EXPENSE','合成商户1','餐饮美食',1793937600000,'SYNTHETIC-V9-0001',1,1,'POSTED','',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0001"}]');
INSERT INTO "records" VALUES(2,'cccccccc-cccc-4ccc-8ccc-000000000002','IMAGE','微信','合成v9迁移夹具',1793937600000,2000,'INCOME','合成商户2','餐饮',1793937600000,'SYNTHETIC-V9-0002',1,1,'POSTED','',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0002"}]');
INSERT INTO "records" VALUES(3,'cccccccc-cccc-4ccc-8ccc-000000000003','IMAGE','微信','合成v9迁移夹具',1793937600000,7000,'EXPENSE','合成商户3','餐饮',1793937600000,'SYNTHETIC-V9-0003',1,1,'POSTED','',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0003"}]');
INSERT INTO "records" VALUES(4,'cccccccc-cccc-4ccc-8ccc-000000000004','IMAGE','微信','合成v9迁移夹具',1793937600000,9000,'EXPENSE','合成商户4','合成陌生类别',1793937600000,'SYNTHETIC-V9-0004',1,1,'POSTED','',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0004"}]');
INSERT INTO "records" VALUES(5,'cccccccc-cccc-4ccc-8ccc-000000000005','IMAGE','微信','合成v9迁移夹具',1793937600000,0,'EXPENSE','合成商户5','合成一级/二级',1793937600000,'SYNTHETIC-V9-0005',1,1,'POSTED','',NULL,'',NULL,'wechat',10000,10000,'cumulative',NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0005"}]');
INSERT INTO "records" VALUES(6,'cccccccc-cccc-4ccc-8ccc-000000000006','IMAGE','微信','合成v9迁移夹具',1793937600000,5000,'EXPENSE','合成商户6','合成撤销分类',1793937600000,'SYNTHETIC-V9-0006',1,1,'POSTED','',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0006"}]');
INSERT INTO "records" VALUES(7,'cccccccc-cccc-4ccc-8ccc-000000000007','IMAGE','微信','合成v9迁移夹具',1793937600000,3000,'EXPENSE','合成商户7','日用购物',1793937600000,'SYNTHETIC-V9-0007',1,1,'REVIEW','请选择钱包后入账',NULL,'',NULL,NULL,NULL,NULL,NULL,NULL,'[{"scope":"wechat","value":"SYNTHETIC-V9-0007"}]');
DELETE FROM "sqlite_sequence";
INSERT INTO "sqlite_sequence" VALUES('accounts',4);
INSERT INTO "sqlite_sequence" VALUES('records',7);
CREATE TABLE wallet_import_aliases(source_uid TEXT PRIMARY KEY NOT NULL,local_account_uid TEXT NOT NULL);
CREATE TABLE wallet_movements(movement_uid TEXT PRIMARY KEY NOT NULL,operation_uid TEXT NOT NULL,
            account_uid TEXT NOT NULL,record_uid TEXT,kind TEXT NOT NULL,delta_cents INTEGER NOT NULL,
            balance_before INTEGER NOT NULL,balance_after INTEGER NOT NULL,created_at INTEGER NOT NULL,summary TEXT NOT NULL);
INSERT INTO "wallet_movements" VALUES('dddddddd-dddd-4ddd-8ddd-000000000001','eeeeeeee-eeee-4eee-8eee-000000000001','11111111-1111-4111-8111-111111111111','cccccccc-cccc-4ccc-8ccc-000000000001','POST',-10000,110000,100000,1793937600000,'合成历史流水');
INSERT INTO "wallet_movements" VALUES('dddddddd-dddd-4ddd-8ddd-000000000002','eeeeeeee-eeee-4eee-8eee-000000000002','22222222-2222-4222-8222-222222222222','cccccccc-cccc-4ccc-8ccc-000000000002','POST',2000,18000,20000,1793937600000,'合成历史流水');
CREATE TABLE wallet_settings(singleton INTEGER PRIMARY KEY CHECK(singleton=1),default_account_uid TEXT,revision INTEGER NOT NULL DEFAULT 0);
INSERT INTO "wallet_settings" VALUES(1,'11111111-1111-4111-8111-111111111111',3);
CREATE TABLE wallet_submissions(token TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,result_uid TEXT NOT NULL,created_at INTEGER NOT NULL);
INSERT INTO "wallet_submissions" VALUES('SYNTHETIC-CAPTURE-COMMIT','CAPTURE_RESULT','cccccccc-cccc-4ccc-8ccc-000000000001',1793937600000);
CREATE INDEX records_time ON records(occurred_at);
CREATE INDEX records_transaction ON records(transaction_id);
CREATE INDEX records_received ON records(received_at);
CREATE INDEX identities_lookup ON record_identities(scope,value);
CREATE INDEX records_state_time ON records(state,occurred_at DESC,id DESC);
CREATE INDEX records_time_id ON records(occurred_at DESC,id DESC);
CREATE INDEX merchant_icons_key ON merchant_icons(merchant_key);
CREATE INDEX movements_account_time ON wallet_movements(account_uid,created_at DESC,movement_uid);
CREATE INDEX movements_record ON wallet_movements(record_uid);
CREATE INDEX wallet_links_account ON record_wallet_links(account_uid);
CREATE INDEX wallet_effects_account ON record_wallet_effects(account_uid);
CREATE INDEX transfer_from_account ON record_transfer_details(from_account_uid);
CREATE INDEX transfer_to_account ON record_transfer_details(to_account_uid);
CREATE INDEX import_records_record ON import_batch_records(record_uid);
CREATE INDEX wallet_aliases_account ON wallet_import_aliases(local_account_uid);
COMMIT;
PRAGMA user_version=10;
