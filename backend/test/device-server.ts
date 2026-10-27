// Private integration-test server: real PostgreSQL, fresh isolated schema, no business fixtures.
import { randomUUID } from 'node:crypto';
import { sql } from 'kysely';
import { readConfig, readTestControlConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';
import { buildApp } from '../src/app.js';
import { uuid } from '../src/bookkeeping.js';
import { bootstrapFixture } from './access-fixture.js';
import { issueBootstrap } from '../src/device-access.js';

const config=readConfig(); const auth=readTestControlConfig(); if(!auth) throw Error('Configure private development token');
if(process.argv.includes('--stop')) {
  const response=await fetch('http://127.0.0.1:3001/test/shutdown',{method:'POST',headers:{authorization:`Bearer ${auth.token}`}});
  if(!response.ok)throw Error('Test server shutdown rejected');
  process.exit(0);
}
const schema=`device_test_${randomUUID().replaceAll('-','')}`;
const admin=createDatabase(config.databaseUrl); await sql`CREATE SCHEMA ${sql.id(schema)}`.execute(admin);
const url=new URL(config.databaseUrl);url.searchParams.set('options',`-c search_path=${schema}`);
const db=createDatabase(url.toString());
const migrated=await createMigrator(db,schema).migrateToLatest();if(migrated.error)throw migrated.error;
const provisioning=buildApp(async()=>{},false,{db});
const administrator=await bootstrapFixture(provisioning,db);
await provisioning.close();
const app=buildApp(async()=>{},false,{db});
app.post('/test/bootstrap',async(request,reply)=>{
  if(request.headers.authorization!==`Bearer ${auth.token}`)return reply.code(401).send({error:'unauthorized'});
  return {token:await issueBootstrap(db,true)};
});
app.post('/test/enroll',async(request,reply)=>{
  if(request.headers.authorization!==`Bearer ${auth.token}`)return reply.code(401).send({error:'unauthorized'});
  const proof=(request.body as {credential?:unknown})?.credential;
  if(typeof proof!=='string'||!/^([0-9a-f-]{36})\.([A-Za-z0-9_-]{43})$/.test(proof))return reply.code(400).send({error:'invalid_request'});
  const headers={authorization:`Bearer ${proof}`};
  const enrollment=await app.inject({method:'POST',url:'/v1/access/enrollments',headers,remoteAddress:`fixture-${proof.split('.')[0]}`,payload:{name:'Isolated Android fixture'}});
  if(enrollment.statusCode!==200)return reply.code(enrollment.statusCode).send(enrollment.json());
  const approved=await app.inject({method:'POST',url:`/v1/access/enrollments/${proof.split('.')[0]}/decision`,headers:{authorization:`Bearer ${administrator}`},payload:{approve:true,code:enrollment.json().code}});
  if(approved.statusCode!==200)return reply.code(approved.statusCode).send(approved.json());
  const completed=await app.inject({method:'POST',url:'/v1/access/enrollment/complete',headers,payload:{}});
  return reply.code(completed.statusCode).send(completed.json());
});
// A transport may transparently retry a buffered POST. Block every acknowledgment
// until the test explicitly releases it, rather than assuming exactly one attempt.
const blockedAcknowledgments=new Set<string>();
const droppedAttempts=new Map<string,number>();
app.post('/test/acknowledgments',async(request,reply)=>{
  if(request.headers.authorization!==`Bearer ${auth.token}`)return reply.code(401).send({error:'unauthorized'});
  const body=request.body as {recordId?:unknown;blocked?:unknown};
  const id=uuid(body?.recordId);
  if(typeof body.blocked!=='boolean')return reply.code(400).send({error:'invalid_request'});
  if(body.blocked)blockedAcknowledgments.add(id);else blockedAcknowledgments.delete(id);
  return {attempts:droppedAttempts.get(id)??0};
});
app.addHook('onSend',async(request,reply,payload)=>{
  const body=request.body as {action?:string;recordId?:string}|undefined;
  if(request.method==='POST'&&request.url==='/v1/operations'&&body?.recordId&&blockedAcknowledgments.has(body.recordId)&&reply.statusCode===200){
    droppedAttempts.set(body.recordId,(droppedAttempts.get(body.recordId)??0)+1);reply.raw.destroy();
  }
  return payload;
});
let stopping=false;
async function close(){
  if(stopping)return;stopping=true;await app.close();await db.destroy();
  if(!/^device_test_[a-f0-9]{32}$/.test(schema))throw Error('Invalid test schema');
  await sql`DROP SCHEMA ${sql.id(schema)} CASCADE`.execute(admin);await admin.destroy();
}
process.once('SIGINT',()=>{void close();});process.once('SIGTERM',()=>{void close();});
app.post('/test/shutdown',async(request,reply)=>{
  if(request.headers.authorization!==`Bearer ${auth.token}`)return reply.code(401).send({error:'unauthorized'});
  setTimeout(()=>{void close();},100);
  return {stopped:true};
});
// Container runs must publish this port on host loopback only.
try {
  await app.listen({host:process.argv.includes('--container')?'0.0.0.0':'127.0.0.1',port:3001});
  console.log('Isolated device integration API listening on port 3001');
} catch (error) {
  await close();
  throw error;
}
