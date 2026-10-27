import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import { sql, type Kysely } from 'kysely';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Database } from './database.js';

type DB = Kysely<Database>;
type Device = { id: string; name: string; role: 'admin'|'standard'; credential_hash: string; revoked_at: Date|null };
type Enrollment = { id:string; name:string; credential_hash:string; pairing_code:string; state:string; purpose:string; attempts:number; valid:boolean };
export class AccessError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export const credentialHash = (secret:string) => createHash('sha256').update(secret).digest('hex');
const same = (a:string,b:string) => a.length===b.length && timingSafeEqual(Buffer.from(a),Buffer.from(b));
const idPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function identifier(value:unknown):string {
  if(typeof value!=='string'||!idPattern.test(value)) throw new AccessError(400,'invalid_request');
  return value;
}
function body(request:FastifyRequest):Record<string,unknown> {
  if(!request.body || typeof request.body!=='object'||Array.isArray(request.body)) throw new AccessError(400,'invalid_request');
  return request.body as Record<string,unknown>;
}
function deviceName(value:unknown) {
  if(typeof value!=='string'||value.trim().length<1||value.trim().length>80||/[\x00-\x1f\x7f]/.test(value)) throw new AccessError(400,'invalid_request');
  return value.trim();
}
function credential(request:FastifyRequest) {
  const match=/^Bearer ([0-9a-f-]{36})\.([A-Za-z0-9_-]{43})$/.exec(request.headers.authorization??'');
  if(!match || !idPattern.test(match[1]!)) throw new AccessError(401,'unauthorized');
  return {id:match[1]!,hash:credentialHash(match[2]!)};
}
async function audit(db:DB,event:string,actor:string|null=null,target:string|null=null) {
  await sql`INSERT INTO security_events(event,actor_id,target_id) VALUES(${event},${actor}::uuid,${target}::uuid)`.execute(db);
}
// PostgreSQL owns counters across API processes. No forwarded IP headers are trusted.
export async function rateLimit(db:DB,bucket:string,limit:number) {
  // Bound expired counter retention without a scheduler/service. Buckets contain hashes only.
  if(randomInt(100)===0) await sql`DELETE FROM access_rate_limits WHERE window_start<clock_timestamp()-interval '1 day'`.execute(db);
  const result=await sql<{attempts:number}>`INSERT INTO access_rate_limits(bucket,window_start,attempts)
    VALUES(${credentialHash(bucket)},clock_timestamp(),1) ON CONFLICT(bucket) DO UPDATE SET
    attempts=CASE WHEN access_rate_limits.window_start < clock_timestamp()-interval '1 minute' THEN 1 ELSE access_rate_limits.attempts+1 END,
    window_start=CASE WHEN access_rate_limits.window_start < clock_timestamp()-interval '1 minute' THEN clock_timestamp() ELSE access_rate_limits.window_start END RETURNING attempts`.execute(db);
  if(result.rows[0]!.attempts>limit) throw new AccessError(429,'too_many_attempts');
}
export async function authenticateDevice(db:DB,request:FastifyRequest):Promise<Device> {
  const proof=credential(request);
  await rateLimit(db,`authenticate:${request.ip}`,600);
  const row=(await sql<Device>`SELECT * FROM trusted_devices WHERE id=${proof.id}::uuid`.execute(db)).rows[0];
  if(!row || !same(row.credential_hash,proof.hash)) throw new AccessError(401,'unauthorized');
  if(row.revoked_at) throw new AccessError(403,'access_revoked');
  return row;
}
async function admin(db:DB,request:FastifyRequest) {
  const actor=await authenticateDevice(db,request);
  if(actor.role!=='admin') throw new AccessError(403,'administrator_required');
  return actor;
}
// Serializes bootstrap and administrator changes, including recovery/revocation.
async function managementLock(db:DB) { await sql`SELECT pg_advisory_xact_lock(220022)`.execute(db); }

export async function issueBootstrap(db:DB,recovery=false) {
  const token=randomBytes(32).toString('base64url');
  await db.transaction().execute(async tx=>{
    await managementLock(tx);
    const existing=(await sql`SELECT id FROM trusted_devices WHERE role='admin' AND revoked_at IS NULL`.execute(tx)).rows;
    if(existing.length && !recovery) throw new AccessError(409,'administrator_exists');
    // Supersede unconsumed tokens; an operator's latest request is authoritative.
    await sql`UPDATE administrator_bootstrap SET expires_at=now() WHERE consumed_by IS NULL`.execute(tx);
    await sql`INSERT INTO administrator_bootstrap(id,token_hash,expires_at,recovery) VALUES(${randomUUID()}::uuid,${credentialHash(token)},now()+interval '10 minutes',${recovery})`.execute(tx);
    await audit(tx,recovery?'operator_recovery_issued':'bootstrap_issued');
  });
  return token;
}

export function registerDeviceAccess(app:FastifyInstance,db:DB) {
  app.register(async routes=>{
    routes.addHook('onRequest',async(request,reply)=>{
      reply.header('Cache-Control','no-store');
      await rateLimit(db,`access:${request.ip}`,120);
    });
    routes.post('/bootstrap',async request=>{
      await rateLimit(db,`bootstrap:${request.ip}`,5); await rateLimit(db,'bootstrap:global',30);
      const data=body(request); const name=deviceName(data.name); const proof=credential(request);
      if(typeof data.token!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(data.token)) throw new AccessError(401,'invalid_bootstrap');
      const result=await db.transaction().execute(async tx=>{
        await managementLock(tx);
        const token=(await sql<{consumed_by:string|null;valid:boolean;recovery:boolean}>`SELECT consumed_by,expires_at>now() AS valid,recovery FROM administrator_bootstrap WHERE token_hash=${credentialHash(data.token as string)} FOR UPDATE`.execute(tx)).rows[0];
        if(!token) return false;
        if(token.consumed_by) {
          const existing=(await sql<Device>`SELECT * FROM trusted_devices WHERE id=${token.consumed_by}::uuid`.execute(tx)).rows[0];
          return token.valid && existing?.id===proof.id && !existing.revoked_at && same(existing.credential_hash,proof.hash);
        }
        if(!token.valid) return false;
        const active=(await sql`SELECT id FROM trusted_devices WHERE role='admin' AND revoked_at IS NULL`.execute(tx)).rows;
        if(active.length && !token.recovery) return false;
        await sql`INSERT INTO trusted_devices(id,name,credential_hash,role) VALUES(${proof.id}::uuid,${name},${proof.hash},'admin')`.execute(tx);
        await sql`UPDATE administrator_bootstrap SET consumed_by=${proof.id}::uuid WHERE token_hash=${credentialHash(data.token as string)}`.execute(tx);
        await audit(tx,token.recovery?'administrator_recovered':'administrator_bootstrapped',proof.id,proof.id);
        return true;
      });
      if(!result) { await audit(db,'bootstrap_denied'); throw new AccessError(401,'invalid_bootstrap'); }
      return {id:proof.id,role:'admin',state:'active'};
    });
    routes.post('/enrollments',async request=>{
      await rateLimit(db,`enroll:${request.ip}`,5); await rateLimit(db,'enroll:global',30);
      const data=body(request); const proof=credential(request); const name=deviceName(data.name);
      const purpose=data.purpose??'device'; if(!['device','pin_recovery'].includes(purpose as string)) throw new AccessError(400,'invalid_request');
      return db.transaction().execute(async tx=>{
        // Same installation can replay initiation after an interrupted response.
        await sql`SELECT pg_advisory_xact_lock(hashtextextended(${proof.id},0))`.execute(tx);
        const existing=(await sql<Enrollment>`SELECT *,expires_at>now() AS valid FROM device_enrollments WHERE id=${proof.id}::uuid`.execute(tx)).rows[0];
        if(existing) {
          if(!same(existing.credential_hash,proof.hash)||existing.name!==name||existing.purpose!==purpose) throw new AccessError(409,'enrollment_mismatch');
          return {id:existing.id,code:existing.pairing_code,state:existing.valid?existing.state:'expired'};
        }
        const code=randomInt(0,1_000_000).toString().padStart(6,'0');
        await sql`INSERT INTO device_enrollments(id,name,credential_hash,pairing_code,purpose,expires_at) VALUES(${proof.id}::uuid,${name},${proof.hash},${code},${purpose as string},now()+interval '10 minutes')`.execute(tx);
        await audit(tx,'enrollment_requested',null,proof.id);
        return {id:proof.id,code,state:'pending'};
      });
    });
    routes.post('/enrollment/status',async request=>{
      const proof=credential(request); await rateLimit(db,`poll:${proof.id}`,30);
      const row=(await sql<Enrollment>`SELECT *,expires_at>now() AS valid FROM device_enrollments WHERE id=${proof.id}::uuid`.execute(db)).rows[0];
      if(!row || !same(row.credential_hash,proof.hash)) throw new AccessError(401,'unauthorized');
      return {id:row.id,code:row.pairing_code,state:row.state==='active'?'active':row.valid?row.state:'expired'};
    });
    routes.post('/enrollment/complete',async request=>{
      const proof=credential(request);
      return db.transaction().execute(async tx=>{
        await managementLock(tx);
        const row=(await sql<Enrollment>`SELECT *,expires_at>now() AS valid FROM device_enrollments WHERE id=${proof.id}::uuid FOR UPDATE`.execute(tx)).rows[0];
        if(!row||!same(row.credential_hash,proof.hash)) throw new AccessError(401,'unauthorized');
        if(row.state==='active') { const active=await authenticateDevice(tx,request); return {id:active.id,role:active.role,state:'active'}; }
        if(!row.valid||row.state!=='approved') throw new AccessError(409,'enrollment_unavailable');
        await sql`INSERT INTO trusted_devices(id,name,credential_hash,role) VALUES(${row.id}::uuid,${row.name},${row.credential_hash},'standard')`.execute(tx);
        await sql`UPDATE device_enrollments SET state='active' WHERE id=${row.id}::uuid`.execute(tx);
        await audit(tx,'enrollment_completed',row.id,row.id);
        return {id:row.id,role:'standard',state:'active'};
      });
    });
    routes.get('/me',async request=>{ const actor=await authenticateDevice(db,request); return {id:actor.id,name:actor.name,role:actor.role}; });
    routes.get('/devices',async request=>{
      await admin(db,request);
      const devices=(await sql`SELECT id,name,role,created_at AS "createdAt",revoked_at AS "revokedAt" FROM trusted_devices ORDER BY created_at,id`.execute(db)).rows;
      const pending=(await sql`SELECT id,name,purpose,created_at AS "createdAt",expires_at AS "expiresAt",state,attempts FROM device_enrollments WHERE state IN ('pending','approved') AND expires_at>now() ORDER BY created_at,id`.execute(db)).rows;
      return {devices,pending};
    });
    routes.post('/enrollments/:id/decision',async request=>{
      const target=identifier((request.params as {id:string}).id); const data=body(request);
      if(typeof data.approve!=='boolean'||(data.approve && (typeof data.code!=='string'||!/^\d{6}$/.test(data.code)))) throw new AccessError(400,'invalid_request');
      const result=await db.transaction().execute(async tx=>{
        await managementLock(tx); const actor=await admin(tx,request);
        if(actor.id===target) throw new AccessError(403,'self_approval');
        const row=(await sql<Enrollment>`SELECT *,expires_at>now() AS valid FROM device_enrollments WHERE id=${target}::uuid FOR UPDATE`.execute(tx)).rows[0];
        if(!row||!row.valid||row.state!=='pending'||row.attempts>=5) throw new AccessError(409,'enrollment_unavailable');
        if(data.approve && !same(row.pairing_code,data.code as string)) {
          await sql`UPDATE device_enrollments SET attempts=attempts+1,state=CASE WHEN attempts=4 THEN 'rejected' ELSE state END WHERE id=${target}::uuid`.execute(tx);
          await audit(tx,'pairing_code_denied',actor.id,target); return false;
        }
        await sql`UPDATE device_enrollments SET state=${data.approve?'approved':'rejected'},approved_by=${actor.id}::uuid WHERE id=${target}::uuid`.execute(tx);
        await audit(tx,data.approve?'enrollment_approved':'enrollment_rejected',actor.id,target); return true;
      });
      if(!result) throw new AccessError(400,'incorrect_pairing_code');
      return {ok:true};
    });
    routes.post('/devices/:id/revoke',async request=>{
      const target=identifier((request.params as {id:string}).id);
      return db.transaction().execute(async tx=>{
        await managementLock(tx); const actor=await admin(tx,request);
        const row=(await sql<Device>`SELECT * FROM trusted_devices WHERE id=${target}::uuid FOR UPDATE`.execute(tx)).rows[0];
        if(!row) throw new AccessError(404,'not_found');
        if(row.role==='admin'&&!row.revoked_at) {
          const others=(await sql`SELECT id FROM trusted_devices WHERE role='admin' AND revoked_at IS NULL AND id<>${target}::uuid`.execute(tx)).rows;
          if(!others.length) throw new AccessError(409,'last_administrator');
        }
        await sql`UPDATE trusted_devices SET revoked_at=coalesce(revoked_at,now()) WHERE id=${target}::uuid`.execute(tx);
        await sql`UPDATE device_enrollments SET state='rejected' WHERE approved_by=${target}::uuid AND state='approved'`.execute(tx);
        await audit(tx,'device_revoked',actor.id,target); return {ok:true};
      });
    });
  },{prefix:'/v1/access'});
}
