import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { sql } from 'kysely';
import { createDatabase } from '../src/database.js';
import { readConfig } from '../src/config.js';
import { createMigrator } from '../src/migrations.js';
import { applyOperation, changes, record, recap } from '../src/bookkeeping.js';

test('GoFood rollout preserves legacy attribution, account settlements and initial-payment relationships', async t=>{
  const connection=readConfig().databaseUrl;
  const admin=createDatabase(connection);
  const schema=`channels_test_${randomUUID().replaceAll('-','')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
  const url=new URL(connection); url.searchParams.set('options',`-c search_path=${schema}`);
  const db=createDatabase(url.toString());
  t.after(async()=>{
    await db.destroy(); assert.match(schema,/^channels_test_[a-f0-9]{32}$/);
    await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin); await admin.destroy();
  });
  assert.equal((await createMigrator(db,schema).migrateTo('005_shopping_catalog')).error,undefined);
  const sale=(channel:string,paid=false)=>({id:randomUUID(),recordId:randomUUID(),expectedVersion:0,action:'save_sale',
    data:{date:'2026-10-31',channel,amount:'41000',paid,...(paid?{receiptId:randomUUID(),method:'cash'}:{})}});
  const legacy=sale('gofood'); const old=await applyOperation(db,legacy);
  const before=await sql`SELECT * FROM sales`.execute(db);
  assert.equal((await createMigrator(db,schema).migrateToLatest()).error,undefined);
  assert.deepEqual((await createMigrator(db,schema).migrateToLatest()).results,[]);
  assert.deepEqual((await sql`SELECT * FROM sales`.execute(db)).rows,before.rows);
  assert.deepEqual(await applyOperation(db,legacy),old);
  const mamat=sale('gofood_mamat'); const gito=sale('gofood_gito');
  const first=await applyOperation(db,mamat); await applyOperation(db,gito);
  assert.deepEqual(await applyOperation(db,mamat),first);
  await assert.rejects(applyOperation(db,sale('gofood_mamat')),error=>Object(error).code==='daily_aggregate_exists');
  const receipt={id:randomUUID(),recordId:mamat.recordId,expectedVersion:1,action:'add_receipt',
    data:{id:randomUUID(),date:'2026-11-01',amount:'41000',method:'transfer',reason:'Platform settlement'}};
  await applyOperation(db,receipt);
  const october=await recap(db,'2026-10-01','2026-11-01');
  assert.equal(october.sales,'123000'); assert.equal(october.received,'0'); assert.equal(october.gofoodTotal,'123000');
  assert.deepEqual(new Map(october.byChannel.map(row=>[row.channel,row.amount])),new Map([
    ['gofood','41000'],['gofood_mamat','41000'],['gofood_gito','41000'],
  ]));
  assert.equal((await recap(db,'2026-11-01','2026-12-01')).sales,'0');
  assert.equal((await recap(db,'2026-11-01','2026-12-01')).received,'41000');
  assert.equal((await record(db,mamat.recordId))?.channel,'gofood_mamat');
  assert.equal((await record(db,gito.recordId))?.receipts?.length,0);
  await assert.rejects(applyOperation(db,{...receipt,id:randomUUID()}),error=>Object(error).code==='version_conflict');
  const paid=sale('langsung',true); const created=await applyOperation(db,paid);
  assert.equal(created.record.kind,'sale');
  if(created.record.kind!=='sale') throw Error('Expected sale');
  assert.equal(created.record.initialReceiptId,paid.data.receiptId);
  const later=sale('langsung'); await applyOperation(db,later);
  await applyOperation(db,{...receipt,id:randomUUID(),recordId:later.recordId,data:{...receipt.data,id:randomUUID(),date:'2026-10-31'}});
  assert.equal((await record(db,later.recordId))?.initialReceiptId,undefined);
  const corrected=await applyOperation(db,{id:randomUUID(),recordId:paid.recordId,expectedVersion:1,action:'correct_date',data:{date:'2026-11-01',reason:'Correct sales date'}});
  if(corrected.record.kind!=='sale') throw Error('Expected sale');
  assert.equal(corrected.record.initialReceiptId,paid.data.receiptId);
  assert.equal(corrected.record.createdAt,created.record.createdAt);
  assert.equal(corrected.record.receipts?.[0]?.date,'2026-10-31');
  const pulled=await changes(db,'0',100);
  assert.ok(pulled.changes.some(change=>change.record.kind==='sale'&&change.record.channel==='gofood_gito'));
  // Strict installed APKs cannot consume either new value; rollout must upgrade every reader first.
  const oldChannels=['langsung','gofood','grabfood','shopeefood'];
  assert.equal(oldChannels.includes(mamat.data.channel),false);
  assert.equal(oldChannels.includes(gito.data.channel),false);
});
