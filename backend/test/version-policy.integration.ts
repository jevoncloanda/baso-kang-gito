import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { sql } from 'kysely';
import { createDatabase } from '../src/database.js';
import { readConfig } from '../src/config.js';
import { createMigrator } from '../src/migrations.js';
import { buildApp } from '../src/app.js';
import { validatePolicy } from '../src/version-policy.js';
import { bootstrapFixture } from './access-fixture.js';

test('version rejection preserves financial operations and device authority, then compatible retries recover exactly once',async t=>{
  const connection=readConfig().databaseUrl;const admin=createDatabase(connection);const schema=`version_test_${randomUUID().replaceAll('-','')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
  const url=new URL(connection);url.searchParams.set('options',`-c search_path=${schema}`);const db=createDatabase(url.toString());
  let policy=validatePolicy(JSON.parse(await readFile(new URL('../release-policy.json',import.meta.url),'utf8')));
  const app=buildApp(async()=>{},false,{db},async()=>policy);
  t.after(async()=>{await app.close();await db.destroy();assert.match(schema,/^version_test_[a-f0-9]{32}$/);await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin);await admin.destroy();});
  assert.equal((await createMigrator(db,schema).migrateToLatest()).error,undefined);
  const token=await bootstrapFixture(app,db);
  const op={id:randomUUID(),recordId:randomUUID(),expectedVersion:0,action:'save_sale',data:{date:'2026-11-04',channel:'gofood_mamat',amount:'41000',paid:false}};
  const send=(version?:string,body:unknown=op)=>app.inject({method:'POST',url:'/v1/operations',headers:{authorization:`Bearer ${token}`,...(version?{'x-app-version-code':version}:{})},payload:body as object});
  const original=await send();assert.equal(original.statusCode,200,original.body);
  policy=validatePolicy({...policy,latestVersionCode:3,latestVersionName:'0.0.3',minimumSupportedVersionCode:3,recommendedVersionCode:3,allowMissingVersion:false});
  const pending={...op,id:randomUUID(),recordId:randomUUID(),data:{...op.data,channel:'gofood_gito'}};
  const before=(await sql<{n:string}>`SELECT count(*)::text AS n FROM financial_operations`.execute(db)).rows[0]!.n;
  for(const version of [undefined,'1','2']){const denied=await send(version,pending);assert.equal(denied.statusCode,426);assert.equal(denied.json().error,'APP_UPDATE_REQUIRED');}
  assert.equal((await send('2')).statusCode,426);
  const device=(await sql<{id:string}>`SELECT id FROM trusted_devices`.execute(db)).rows[0]!.id;
  for(const endpoint of ['/v1/access/bootstrap','/v1/access/enrollments','/v1/access/enrollment/status','/v1/access/enrollment/complete',`/v1/access/devices/${device}/revoke`,`/v1/access/enrollments/${randomUUID()}/decision`]){
    assert.equal((await app.inject({method:'POST',url:endpoint,headers:{authorization:`Bearer ${token}`,'x-app-version-code':'2'},payload:{}})).statusCode,426,endpoint);
  }
  assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM financial_operations`.execute(db)).rows[0]!.n,before);
  assert.equal((await sql<{revoked_at:null}>`SELECT revoked_at FROM trusted_devices WHERE id=${device}::uuid`.execute(db)).rows[0]!.revoked_at,null);
  assert.equal((await app.inject({url:'/v1/changes',headers:{authorization:`Bearer ${token}`,'x-app-version-code':'2'}})).statusCode,426);
  assert.equal((await app.inject({url:'/v1/changes',headers:{authorization:'Bearer invalid','x-app-version-code':'3'}})).statusCode,401);
  assert.equal((await app.inject('/v1/app-version')).statusCode,200);
  assert.deepEqual((await send('3')).json(),original.json());
  const accepted=await send('3',pending);assert.equal(accepted.statusCode,200,accepted.body);
  assert.deepEqual((await send('3',pending)).json(),accepted.json());
  assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM sales`.execute(db)).rows[0]!.n,'2');
});
