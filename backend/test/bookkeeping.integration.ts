import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { sql } from 'kysely';
import { createDatabase } from '../src/database.js';
import { readConfig } from '../src/config.js';
import { createMigrator } from '../src/migrations.js';
import { buildApp } from '../src/app.js';

test('financial operations, two independent clients, retries, conflicts and exact recaps', async t=>{
  const connection=readConfig().databaseUrl; const admin=createDatabase(connection);
  const schema=`bookkeeping_test_${randomUUID().replaceAll('-','')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
  const url=new URL(connection); url.searchParams.set('options',`-c search_path=${schema}`);
  const db=createDatabase(url.toString()); const otherDb=createDatabase(url.toString());
  const token='test-development-token-'.repeat(2);
  const clientA=buildApp(async()=>{},false,{db,token});
  const clientB=buildApp(async()=>{},false,{db:otherDb,token});
  t.after(async()=>{
    await clientA.close(); await clientB.close(); await db.destroy(); await otherDb.destroy();
    // Only the fresh, randomly named test schema is removed; business data is untouched.
    if (!/^bookkeeping_test_[a-f0-9]{32}$/.test(schema)) throw Error('Invalid test schema');
    await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin); await admin.destroy();
  });
  const migration=await createMigrator(db,schema).migrateToLatest(); assert.equal(migration.error,undefined);
  assert.deepEqual((await createMigrator(db,schema).migrateToLatest()).results,[]);
  const headers={authorization:`Bearer ${token}`,'content-type':'application/json'};
  const post=async(op:unknown,client=clientA)=>client.inject({method:'POST',url:'/v1/operations',headers,payload:JSON.stringify(op)});
  const get=async(url:string,client=clientA)=>client.inject({url,headers});
  const sale=(paid:boolean,channel='langsung')=>({id:randomUUID(),action:'save_sale',recordId:randomUUID(),expectedVersion:0,
    data:{date:'2026-10-31',amount:'100000',channel,paid,...(paid?{receiptId:randomUUID(),method:'cash'}:{})}});
  const paid=sale(true); const unpaid=sale(false); const platform=sale(false,'gofood');
  let paidRecord:any; let expense:any;
  await t.test('authentication and paid/unpaid/platform create',async()=>{
    assert.equal((await clientA.inject('/v1/changes')).statusCode,401);
    for (const op of [paid,unpaid,platform]) {
      const response=await post(op); assert.equal(response.statusCode,200,response.body);
      if(op===paid) {paidRecord=response.json().record; assert.equal(paidRecord.receipts[0].amount,'100000');}
      else assert.deepEqual(response.json().record.receipts,[]);
    }
    const day=(await get('/v1/daily?date=2026-10-31')).json();
    assert.equal(day.totals.sales,'300000'); assert.equal(day.totals.received,'100000');
  });
  await t.test('lost acknowledgement/retry returns same response, mismatched reuse rejected',async()=>{
    const first=(await post(paid)).json(); const retry=await post({...paid,data:{...paid.data}});
    assert.deepEqual(retry.json(),first);
    assert.equal((await post({...paid,data:{...paid.data,amount:'200000'}})).statusCode,409);
    assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM sales`.execute(db)).rows[0]!.n,'3');
  });
  await t.test('partial receipts and platform settlement never become another sale',async()=>{
    for (const [root,amount,date] of [[unpaid,'40000','2026-10-31'],[unpaid,'60000','2026-11-01'],[platform,'100000','2026-11-01']] as const) {
      const current=(await get(`/v1/records/${root.recordId}`)).json();
      const response=await post({id:randomUUID(),action:'add_receipt',recordId:root.recordId,expectedVersion:current.version,
        data:{id:randomUUID(),amount,date,method:'transfer',reason:'Payment received'}});
      assert.equal(response.statusCode,200,response.body);
    }
    const next=(await get('/v1/daily?date=2026-11-01')).json().totals;
    assert.equal(next.sales,'0'); assert.equal(next.received,'160000');
    assert.equal((await get('/v1/monthly?month=2026-10')).json().totals.received,'140000');
    assert.equal((await get('/v1/monthly?month=2026-11')).json().totals.received,'160000');
  });
  await t.test('atomic rollback of an invalid paid create',async()=>{
    const bad=sale(true); bad.data.receiptId=paid.data.receiptId;
    const response=await post(bad); assert.equal(response.statusCode,409,response.body);
    assert.equal((await get(`/v1/records/${bad.recordId}`)).statusCode,404);
    assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM financial_operations WHERE id=${bad.id}::uuid`.execute(db)).rows[0]!.n,'0');
  });
  await t.test('shopping item sum, correction history and tombstones',async()=>{
    const op={id:randomUUID(),action:'save_expense',recordId:randomUUID(),expectedVersion:0,data:{date:'2026-10-31',items:[
      {id:randomUUID(),name:'Daging',amount:'150000'},{id:randomUUID(),name:'Bumbu',amount:'25000'}]}};
    const created=await post(op); assert.equal(created.statusCode,200,created.body); expense=created.json().record;
    assert.equal(expense.amount,'175000');
    const edit=await post({...op,id:randomUUID(),expectedVersion:1,data:{...op.data,reason:'Correct quantity',items:[{...op.data.items[0]!,amount:'125000'}]}});
    assert.equal(edit.json().record.amount,'125000');
    const audit=(await sql<{before_record:any;after_record:any}>`SELECT before_record,after_record FROM financial_revisions WHERE record_id=${op.recordId}::uuid ORDER BY sequence DESC LIMIT 1`.execute(db)).rows[0]!;
    assert.equal(audit.before_record.items.length,2); assert.equal(audit.after_record.items.length,1);
    assert.equal((await post({id:randomUUID(),action:'void',recordId:op.recordId,expectedVersion:2,data:{reason:'Mistaken entry'}})).statusCode,200);
    assert.equal((await get('/v1/daily?date=2026-10-31')).json().totals.expenses,'0');
  });
  await t.test('two clients cannot silently overwrite or over-allocate',async()=>{
    const root=sale(false); await post(root);
    const operations=[clientA,clientB].map((client,index)=>post({id:randomUUID(),action:'add_receipt',recordId:root.recordId,expectedVersion:1,
      data:{id:randomUUID(),date:'2026-11-01',amount:'70000',method:'cash',reason:`Client ${index}`}},client));
    const replies=await Promise.all(operations); assert.deepEqual(replies.map(r=>r.statusCode).sort(),[200,409]);
    const current=(await get(`/v1/records/${root.recordId}`,clientB)).json(); assert.equal(current.receipts.length,1);
    assert.equal((await post({id:randomUUID(),action:'add_receipt',recordId:root.recordId,expectedVersion:current.version,
      data:{id:randomUUID(),date:'2026-11-01',amount:'40000',method:'cash',reason:'Too much'}})).statusCode,422);
    const edit=await post({...root,id:randomUUID(),expectedVersion:1,data:{date:'2026-10-31',channel:'langsung',amount:'90000',reason:'Stale edit'}});
    assert.equal(edit.statusCode,409); assert.equal(edit.json().current.version,2);
  });
  await t.test('aggregate uniqueness is transactional across clients',async()=>{
    const duplicate=sale(false,'gofood'); const result=await post(duplicate,clientB);
    assert.equal(result.statusCode,409); assert.equal(result.json().current.id,platform.recordId);
  });
  await t.test('paid corrections protect receipt history and explicit void reverses both',async()=>{
    assert.equal((await post({id:randomUUID(),action:'save_sale',recordId:paid.recordId,expectedVersion:1,
      data:{date:'2026-10-31',channel:'langsung',amount:'50000',reason:'Wrong total'}})).statusCode,422);
    assert.equal((await post({id:randomUUID(),action:'void',recordId:paid.recordId,expectedVersion:1,data:{reason:'Mistake'}})).statusCode,422);
    const voided=await post({id:randomUUID(),action:'void',recordId:paid.recordId,expectedVersion:1,data:{reason:'Mistaken paid sale',includeReceipts:true}});
    assert.equal(voided.statusCode,200,voided.body); assert.equal(voided.json().record.receipts[0].voided,true);
    assert.equal((await get(`/v1/records/${paid.recordId}`)).json().voided,true);
    assert.equal(paidRecord.amount,'100000');
  });
  await t.test('incremental pull paginates in commit order and preserves voids',async()=>{
    let cursor='0'; const all:any[]=[];
    for (;;) { const batch=(await get(`/v1/changes?after=${cursor}&limit=2`,clientB)).json();
      all.push(...batch.changes); if(!batch.hasMore) break; assert.notEqual(batch.cursor,cursor); cursor=batch.cursor;
    }
    for(let i=1;i<all.length;i++) assert.ok(BigInt(all[i].cursor)>BigInt(all[i-1].cursor));
    assert.ok(all.some(c=>c.record.id===expense.id&&c.record.voided));
    const ops=(await sql<{n:string}>`SELECT count(*)::text AS n FROM financial_operations`.execute(db)).rows[0]!.n;
    assert.equal(all.length,Number(ops));
  });
});
