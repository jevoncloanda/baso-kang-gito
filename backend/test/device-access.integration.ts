import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { sql } from 'kysely';
import { createDatabase } from '../src/database.js';
import { readConfig } from '../src/config.js';
import { createMigrator } from '../src/migrations.js';
import { buildApp } from '../src/app.js';
import { credentialHash, issueBootstrap } from '../src/device-access.js';
import { installationCredential } from './access-fixture.js';

test('trusted installation authority, recovery, abuse limits and financial authorization',async t=>{
  const root=createDatabase(readConfig().databaseUrl); const schema=`access_test_${randomUUID().replaceAll('-','')}`;
  await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(root);
  const url=new URL(readConfig().databaseUrl); url.searchParams.set('options',`-c search_path=${schema}`);
  const db=createDatabase(url.toString()); const app=buildApp(async()=>{},false,{db});
  t.after(async()=>{ await app.close(); await db.destroy(); assert.match(schema,/^access_test_[a-f0-9]{32}$/); await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(root); await root.destroy(); });
  assert.equal((await createMigrator(db,schema).migrateToLatest()).error,undefined);
  assert.deepEqual((await createMigrator(db,schema).migrateToLatest()).results,[]);
  let admin=installationCredential(); const standard=installationCredential(); const id=(proof:string)=>proof.split('.')[0]!;
  const post=(path:string,proof:string,payload:unknown={})=>app.inject({method:'POST',url:`/v1/access/${path}`,headers:{authorization:`Bearer ${proof}`,'content-type':'application/json'},payload:JSON.stringify(payload)});
  const get=(path:string,proof:string)=>app.inject({url:path,headers:{authorization:`Bearer ${proof}`}});
  let code='';
  await t.test('bootstrap rejects invalid and expired secrets with audit, no device creation',async()=>{
    assert.equal((await post('bootstrap',admin,{name:'Admin',token:'x'.repeat(43)})).statusCode,401);
    const expired=await issueBootstrap(db);
    await sql`UPDATE administrator_bootstrap SET expires_at=now()-interval '1 second'`.execute(db);
    assert.equal((await post('bootstrap',admin,{name:'Admin',token:expired})).statusCode,401);
    assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM trusted_devices`.execute(db)).rows[0]!.n,'0');
  });
  await t.test('concurrent first bootstrap consumes once, exact installation retry recovers lost response',async()=>{
    const token=await issueBootstrap(db); const other=installationCredential();
    const results=await Promise.all([post('bootstrap',admin,{name:'Admin',token}),post('bootstrap',other,{name:'Imposter',token})]);
    assert.deepEqual(results.map(r=>r.statusCode).sort(),[200,401]);
    const loser=results[0]!.statusCode===200?other:admin;
    if(results[0]!.statusCode!==200) admin=other;
    // Reset only test rate counters, so the retry assertion does not hit the deliberate 5/min limit.
    await sql`DELETE FROM access_rate_limits`.execute(db);
    assert.equal((await post('bootstrap',admin,{name:'Admin',token})).statusCode,200);
    assert.equal((await post('bootstrap',loser,{name:'Other',token})).statusCode,401);
    assert.equal((await sql<{n:string}>`SELECT count(*)::text AS n FROM trusted_devices`.execute(db)).rows[0]!.n,'1');
    await assert.rejects(issueBootstrap(db),/administrator_exists/);
  });
  await t.test('unauthorized reads and writes, shared development token and name impersonation are denied',async()=>{
    for(const path of ['/v1/changes','/v1/customers','/v1/customers/'+randomUUID(),'/v1/records/'+randomUUID(),'/v1/daily?date=2026-10-27','/v1/monthly?month=2026-10']) {
      assert.equal((await app.inject(path)).statusCode,401);
      assert.equal((await get(path,'old-shared-development-token')).statusCode,401);
    }
    assert.equal((await app.inject({method:'POST',url:'/v1/operations',payload:{}})).statusCode,401);
    assert.equal((await get('/v1/changes',`${id(admin)}.${'A'.repeat(43)}`)).statusCode,401);
    assert.equal((await get('/v1/changes',admin)).statusCode,200);
    assert.equal((await get('/v1/customers?q=a&q=b',admin)).statusCode,400);
  });
  await t.test('initiation is replayable only with bound installation proof; pending identity cannot self approve',async()=>{
    const first=await post('enrollments',standard,{name:'Mother tablet'}); assert.equal(first.statusCode,200,first.body); code=first.json().code;
    assert.match(code,/^\d{6}$/);
    assert.deepEqual((await post('enrollments',standard,{name:'Mother tablet'})).json(),first.json());
    assert.equal((await post('enrollments',`${id(standard)}.${'A'.repeat(43)}`,{name:'Mother tablet'})).statusCode,409);
    assert.equal((await post(`enrollments/${id(standard)}/decision`,standard,{approve:true,code})).statusCode,401);
    assert.equal((await post('enrollment/complete',standard)).statusCode,409);
    assert.equal((await post('enrollment/status',installationCredential())).statusCode,401);
  });
  await t.test('administrator verifies code and provisioning survives lost responses without duplicate devices',async()=>{
    assert.equal((await post(`enrollments/${id(standard)}/decision`,admin,{approve:true,code:code==='000000'?'000001':'000000'})).statusCode,400);
    assert.equal((await post(`enrollments/${id(standard)}/decision`,admin,{approve:true,code})).statusCode,200);
    assert.equal((await get('/v1/changes',standard)).statusCode,401);
    assert.equal((await post('enrollment/status',standard)).json().state,'approved');
    for(let n=0;n<2;n++) assert.equal((await post('enrollment/complete',standard)).statusCode,200);
    await sql`UPDATE device_enrollments SET expires_at=now()-interval '1 second' WHERE id=${id(standard)}::uuid`.execute(db);
    assert.equal((await post('enrollment/status',standard)).json().state,'active');
    assert.equal((await post('enrollment/complete',standard)).statusCode,200);
    assert.equal((await get('/v1/changes',standard)).statusCode,200);
    assert.equal((await get('/v1/access/devices',standard)).statusCode,403);
    assert.equal((await post(`devices/${id(admin)}/revoke`,standard)).statusCode,403);
    const stored=(await sql<{credential_hash:string}>`SELECT credential_hash FROM trusted_devices WHERE id=${id(standard)}::uuid`.execute(db)).rows[0]!;
    assert.equal(stored.credential_hash,credentialHash(standard.split('.')[1]!));
    assert.ok(!JSON.stringify(stored).includes(standard.split('.')[1]!));
  });
  await t.test('expiration, code attempt ceiling, approval replay and unauthorized administration',async()=>{
    await sql`DELETE FROM access_rate_limits`.execute(db);
    const expired=installationCredential(); await post('enrollments',expired,{name:'Expired'});
    await sql`UPDATE device_enrollments SET expires_at=now()-interval '1 second' WHERE id=${id(expired)}::uuid`.execute(db);
    assert.equal((await post('enrollment/status',expired)).json().state,'expired');
    assert.equal((await post(`enrollments/${id(expired)}/decision`,admin,{approve:false})).statusCode,409);
    const guessed=installationCredential(); const response=await post('enrollments',guessed,{name:'Guesses'}); const correct=response.json().code;
    for(let n=0;n<5;n++)assert.equal((await post(`enrollments/${id(guessed)}/decision`,admin,{approve:true,code:correct==='000000'?'000001':'000000'})).statusCode,400);
    assert.equal((await post(`enrollments/${id(guessed)}/decision`,admin,{approve:true,code:correct})).statusCode,409);
    assert.equal((await post(`enrollments/${id(standard)}/decision`,admin,{approve:true,code})).statusCode,409);
    assert.equal((await post(`enrollments/${id(guessed)}/decision`,standard,{approve:true,code:correct})).statusCode,403);
    assert.equal((await post(`devices/${id(admin)}/revoke`,admin)).statusCode,409);
  });
  await t.test('bootstrap and enrollment guessing are rate limited across requests',async()=>{
    await sql`DELETE FROM access_rate_limits`.execute(db);
    for(let n=0;n<5;n++)assert.equal((await post('bootstrap',installationCredential(),{name:'Attempt',token:'x'.repeat(43)})).statusCode,401);
    const limited=await post('bootstrap',installationCredential(),{name:'Attempt',token:'x'.repeat(43)});
    assert.equal(limited.statusCode,429); assert.equal(limited.headers['retry-after'],'60');
    for(let n=0;n<5;n++)assert.equal((await post('enrollments',installationCredential(),{name:'Request'})).statusCode,200);
    assert.equal((await post('enrollments',installationCredential(),{name:'Request'})).statusCode,429);
    await sql`DELETE FROM access_rate_limits`.execute(db);
  });
  await t.test('operator recovery permits administrator replacement and revocation cancels outstanding approvals',async()=>{
    const replacement=installationCredential(); const token=await issueBootstrap(db,true);
    assert.equal((await post('bootstrap',replacement,{name:'Replacement admin',token})).statusCode,200);
    const pending=installationCredential(); const enrollment=await post('enrollments',pending,{name:'Recovery',purpose:'pin_recovery'});
    assert.equal((await post(`enrollments/${id(pending)}/decision`,admin,{approve:true,code:enrollment.json().code})).statusCode,200);
    assert.equal((await post(`devices/${id(admin)}/revoke`,replacement)).statusCode,200);
    assert.equal((await get('/v1/access/devices',admin)).statusCode,403);
    assert.equal((await post('enrollment/complete',pending)).statusCode,409);
    assert.equal((await post(`devices/${id(standard)}/revoke`,admin)).statusCode,403);
    assert.equal((await post(`devices/${id(standard)}/revoke`,replacement)).statusCode,200);
    const reads=await Promise.all(Array.from({length:5},()=>get('/v1/changes',standard)));
    assert.ok(reads.every(r=>r.statusCode===403 && r.json().error==='access_revoked'));
    assert.equal((await app.inject({method:'POST',url:'/v1/operations',headers:{authorization:`Bearer ${standard}`},payload:{}})).statusCode,403);
  });
  await t.test('security audit retains decisions without authentication secrets',async()=>{
    const events=(await sql<{event:string}>`SELECT event,actor_id,target_id FROM security_events ORDER BY sequence`.execute(db)).rows;
    for(const expected of ['bootstrap_denied','administrator_bootstrapped','enrollment_requested','pairing_code_denied','enrollment_approved','enrollment_completed','operator_recovery_issued','administrator_recovered','device_revoked']) assert.ok(events.some(row=>row.event===expected),expected);
    assert.ok(!JSON.stringify(events).includes(admin.split('.')[1]!));
  });
  await t.test('concurrent approval and revocation serialize decisions; subsequent reads stay denied',async()=>{
    await sql`DELETE FROM access_rate_limits`.execute(db);
    const replacement=(await sql<{id:string}>`SELECT id FROM trusted_devices WHERE role='admin' AND revoked_at IS NULL`.execute(db)).rows[0]!;
    // Use fresh operator authority for an independently authenticated administrator client.
    const authority=installationCredential(); const token=await issueBootstrap(db,true);
    assert.equal((await post('bootstrap',authority,{name:'Concurrency admin',token})).statusCode,200);
    const pending=installationCredential(); const initiated=await post('enrollments',pending,{name:'Concurrent request'});
    const decisions=await Promise.all([0,1].map(()=>post(`enrollments/${id(pending)}/decision`,authority,{approve:true,code:initiated.json().code})));
    assert.deepEqual(decisions.map(r=>r.statusCode).sort(),[200,409]);
    assert.equal((await post('enrollment/complete',pending)).statusCode,200);
    const race=await Promise.all([get('/v1/changes',pending),post(`devices/${id(pending)}/revoke`,authority)]);
    assert.ok([200,403].includes(race[0]!.statusCode)); assert.equal(race[1]!.statusCode,200);
    assert.equal((await get('/v1/changes',pending)).statusCode,403);
    assert.equal((await get('/v1/access/me',authority)).json().role,'admin');
    assert.notEqual(replacement.id,id(authority));
  });
});
