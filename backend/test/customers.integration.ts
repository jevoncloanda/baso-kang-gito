import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { sql } from 'kysely';
import { buildApp } from '../src/app.js';
import { readConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';

test('customer migration, master records and Bon retain authoritative financial history across clients', async t => {
  const connection = readConfig().databaseUrl; const admin = createDatabase(connection);
  const schema = `customers_test_${randomUUID().replaceAll('-', '')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
  const url = new URL(connection); url.searchParams.set('options', `-c search_path=${schema}`);
  const db = createDatabase(url.toString()); const other = createDatabase(url.toString());
  const token = 'customer-integration-private-token'.repeat(2);
  const clientA = buildApp(async () => {}, false, { db, token });
  const clientB = buildApp(async () => {}, false, { db: other, token });
  t.after(async () => {
    await clientA.close(); await clientB.close(); await db.destroy(); await other.destroy();
    assert.match(schema, /^customers_test_[a-f0-9]{32}$/);
    await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin); await admin.destroy();
  });
  const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
  const post = (op: unknown, client = clientA) => client.inject({ method: 'POST', url: '/v1/operations', headers, payload: JSON.stringify(op) });
  const get = (path: string, client = clientA) => client.inject({ url: path, headers });
  const master = (data: Record<string, unknown> = { name: 'Ibu % Sari', phone: '08123456789' }) => ({
    id: randomUUID(), recordId: randomUUID(), expectedVersion: 0, action: 'save_customer', data,
  });
  const sale = (customerId?: string | null, paid = false) => ({ id: randomUUID(), recordId: randomUUID(), expectedVersion: 0,
    action: 'save_sale', data: { date: '2026-10-31', channel: 'langsung', amount: '100000', paid,
      ...(customerId === undefined ? {} : { customerId }), ...(paid ? { receiptId: randomUUID(), method: 'cash' } : {}) } });
  const payment = (recordId: string, expectedVersion: number, amount: string, date = '2026-11-01') => ({
    id: randomUUID(), recordId, expectedVersion, action: 'add_receipt',
    data: { id: randomUUID(), amount, date, method: 'transfer', reason: 'Bon payment received' },
  });
  const legacyId = randomUUID();
  await t.test('forward migration preserves legacy names without guessing customer links and reruns safely', async () => {
    assert.equal((await createMigrator(db, schema).migrateTo('002_bookkeeping')).error, undefined);
    await sql`INSERT INTO sales(id,business_date,channel,origin,entry_kind,net_amount_rupiah,customer_name,version)
      VALUES(${legacyId}::uuid,'2026-10-30','langsung','manual','individual',100000,'Ibu % Sari',1)`.execute(db);
    assert.equal((await createMigrator(db, schema).migrateToLatest()).error, undefined);
    assert.deepEqual((await createMigrator(db, schema).migrateToLatest()).results, []);
    const legacy = (await get(`/v1/records/${legacyId}`)).json();
    assert.equal(legacy.customer, 'Ibu % Sari'); assert.equal(legacy.customerId, null); assert.equal(legacy.amount, '100000');
    assert.deepEqual((await get('/v1/customers')).json().customers, []);
  });
  const customer = master(); let created: any; const partial = sale(customer.recordId, true);
  await t.test('create validates required and optional fields and retries retain one identity', async () => {
    for (const data of [{}, { name: ' ' }, { name: 'x'.repeat(201) }, { name: 'Valid', phone: 'x'.repeat(51) },
      { name: 'Valid', address: 'x'.repeat(501) }, { name: 'Valid', notes: 'x'.repeat(1001) }, { name: 'Valid', phone: 12 }]) {
      assert.equal((await post(master(data))).statusCode, 400);
    }
    const response = await post(customer); assert.equal(response.statusCode, 200, response.body); created = response.json();
    assert.equal(created.record.address, ''); assert.equal(created.record.notes, ''); assert.equal(created.record.archived, false);
    assert.deepEqual((await post(customer, clientB)).json(), created);
    assert.equal((await post({ ...customer, data: { name: 'Different' } })).statusCode, 409);
    const minimal = await post(master({ name: 'Optional only' })); assert.equal(minimal.statusCode, 200, minimal.body);
    assert.equal(minimal.json().record.phone, '');
    const archived = master({ name: 'Archived optional', archived: true });
    assert.equal((await post(archived)).statusCode, 200);
    const retained = await post({ ...archived, id: randomUUID(), expectedVersion: 1, data: { name: 'Still archived', reason: 'Edit optional fields' } });
    assert.equal(retained.statusCode, 200, retained.body); assert.equal(retained.json().record.archived, true);
  });
  await t.test('name and phone search are literal and independent clients receive customers', async () => {
    for (const query of ['sArI', '123456', '%']) {
      const response = await get(`/v1/customers?q=${encodeURIComponent(query)}`, clientB);
      assert.deepEqual(response.json().customers.map((c: any) => c.id), [customer.recordId]);
    }
    assert.deepEqual((await get('/v1/customers?q=_')).json().customers, []);
    assert.ok((await get('/v1/changes?after=0', clientB)).json().changes.some((c: any) => c.record.kind === 'customer' && c.record.id === customer.recordId));
  });
  await t.test('linked partial sale preserves snapshots and forbids new unidentified Bon without changing legacy retries', async () => {
    const response = await post({ ...partial, data: { ...partial.data, paymentAmount: '40000' } });
    assert.equal(response.statusCode, 200, response.body); assert.equal(response.json().record.customer, 'Ibu % Sari');
    assert.equal(response.json().record.receipts[0].amount, '40000');
    assert.equal((await get(`/v1/customers/${customer.recordId}`)).json().outstanding, '60000');
    assert.equal((await post(sale(null))).statusCode, 422);
    const legacy = sale(); assert.equal((await post(legacy)).statusCode, 200); assert.equal((await post(legacy)).statusCode, 200);
    assert.equal((await get(`/v1/records/${legacyId}`)).json().customerId, null);
    const tooMuch = sale(customer.recordId, true);
    assert.equal((await post({ ...tooMuch, data: { ...tooMuch.data, paymentAmount: '100001' } })).statusCode, 422);
    assert.equal((await get(`/v1/records/${tooMuch.recordId}`)).statusCode, 404);
    const general = sale(null, true); general.data.date = '2026-12-01';
    assert.equal((await post({ ...general, data: { ...general.data, customer: 'One-time customer' } })).statusCode, 200);
    const newDebt = await post({ id: randomUUID(), recordId: general.recordId, expectedVersion: 1, action: 'save_sale',
      data: { date: general.data.date, channel: 'langsung', amount: '110000', customerId: null, reason: 'Correct amount' } });
    assert.equal(newDebt.statusCode, 422); assert.equal(newDebt.json().error, 'customer_required');
    assert.equal((await get(`/v1/records/${general.recordId}`)).json().amount, '100000');
  });
  await t.test('customer edit audit, replay and stale update retain original sale name', async () => {
    const edit = { ...customer, id: randomUUID(), expectedVersion: 1, data: { ...customer.data, name: 'Ibu Renamed', reason: 'Correct name' } };
    const response = await post(edit); assert.equal(response.statusCode, 200, response.body);
    assert.deepEqual((await post(edit)).json(), response.json());
    assert.equal((await post({ ...edit, id: randomUUID() }, clientB)).statusCode, 409);
    assert.equal((await get(`/v1/records/${partial.recordId}`)).json().customer, 'Ibu % Sari');
    const audit = (await sql<{before_record: any; after_record: any}>`SELECT before_record,after_record FROM financial_revisions
      WHERE operation_id=${edit.id}::uuid`.execute(db)).rows[0]!;
    assert.equal(audit.before_record.name, 'Ibu % Sari'); assert.equal(audit.after_record.name, 'Ibu Renamed');
    const correction = await post({ id: randomUUID(), recordId: partial.recordId, expectedVersion: 1, action: 'save_sale',
      data: { date: '2026-10-31', channel: 'langsung', amount: '100000', customerId: customer.recordId, reason: 'Correct note', note: 'Confirmed' } });
    assert.equal(correction.statusCode, 200, correction.body); assert.equal(correction.json().record.customer, 'Ibu % Sari');
    const offlineCustomer = master({ name: 'Name at entry' }); await post(offlineCustomer);
    await post({ ...offlineCustomer, id: randomUUID(), expectedVersion: 1, data: { name: 'Renamed before upload', reason: 'Rename' } });
    const offlineSale = sale(offlineCustomer.recordId, true);
    const uploaded = await post({ ...offlineSale, data: { ...offlineSale.data, date: '2026-12-02', customer: 'Name at entry' } });
    assert.equal(uploaded.statusCode, 200, uploaded.body); assert.equal(uploaded.json().record.customer, 'Name at entry');
  });
  await t.test('archive hides selection and rejects new linkage while existing Bon still accepts installments', async () => {
    const archived = await post({ ...customer, id: randomUUID(), expectedVersion: 2,
      data: { name: 'Ibu Renamed', archived: true, reason: 'Archive duplicate contact' } });
    assert.equal(archived.statusCode, 200, archived.body);
    assert.ok(!(await get('/v1/customers')).json().customers.some((c: any) => c.id === customer.recordId));
    assert.ok((await get('/v1/customers?archived=true')).json().customers.some((c: any) => c.id === customer.recordId));
    const rejected = await post(sale(customer.recordId)); assert.equal(rejected.statusCode, 422); assert.equal(rejected.json().error, 'customer_unavailable');
    const first = payment(partial.recordId, 2, '20000'); const response = await post(first, clientB);
    assert.equal(response.statusCode, 200, response.body); assert.deepEqual((await post(first)).json(), response.json());
    assert.equal((await post(payment(partial.recordId, 3, '40001'))).statusCode, 422);
    assert.equal((await post(payment(partial.recordId, 3, '40000', '2026-11-02'))).statusCode, 200);
    assert.equal((await get(`/v1/customers/${customer.recordId}`)).json().outstanding, '0');
    const history = (await get(`/v1/customers/${customer.recordId}`)).json();
    assert.equal(history.records.length, 1); assert.equal(history.records[0].receipts.length, 3);
    assert.equal((await get('/v1/daily?date=2026-11-01')).json().totals.received, '20000');
    assert.equal((await get('/v1/daily?date=2026-11-02')).json().totals.sales, '0');
    assert.equal((await get('/v1/daily?date=2026-11-02')).json().totals.received, '40000');
  });
  await t.test('simultaneous payment versions conflict and preserve one allocation; corrections restore balance', async () => {
    const active = master({ name: 'Second customer' }); await post(active);
    const root = sale(active.recordId); await post(root);
    const results = await Promise.all([post(payment(root.recordId, 1, '70000')), post(payment(root.recordId, 1, '70000'), clientB)]);
    assert.deepEqual(results.map(r => r.statusCode).sort(), [200, 409]);
    const current = (await get(`/v1/records/${root.recordId}`)).json(); assert.equal(current.receipts.length, 1);
    assert.equal((await get(`/v1/customers/${active.recordId}`)).json().outstanding, '30000');
    const reversal = await post({ id: randomUUID(), recordId: root.recordId, expectedVersion: 2, action: 'void',
      data: { receiptId: current.receipts[0].id, reason: 'Payment entered for wrong customer' } });
    assert.equal(reversal.statusCode, 200, reversal.body);
    assert.equal((await get(`/v1/customers/${active.recordId}`)).json().outstanding, '100000');
    assert.equal((await get('/v1/daily?date=2026-10-31')).json().totals.sales, '300000');
    const pull = (await get('/v1/changes?after=0&limit=100', clientB)).json();
    assert.ok(pull.changes.some((c: any) => c.record.kind === 'customer' && c.record.archived));
    assert.ok(pull.changes.some((c: any) => c.record.id === root.recordId && c.record.receipts.some((r: any) => r.voided)));
  });
});
