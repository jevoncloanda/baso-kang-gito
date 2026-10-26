// Private integration-test server: real PostgreSQL, fresh isolated schema, no business fixtures.
import { randomUUID } from 'node:crypto';
import { sql } from 'kysely';
import { readConfig, readBookkeepingConfig } from '../src/config.js';
import { createDatabase } from '../src/database.js';
import { createMigrator } from '../src/migrations.js';
import { buildApp } from '../src/app.js';

const config=readConfig(); const auth=readBookkeepingConfig(); if(!auth) throw Error('Configure private development token');
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
const app=buildApp(async()=>{},false,{db,token:auth.token});
const droppedExpenses=new Set<string>();
app.addHook('onSend',async(request,reply,payload)=>{
  const body=request.body as {action?:string;recordId?:string}|undefined;
  if(request.method==='POST'&&request.url==='/v1/operations'&&body?.action==='save_expense'&&body.recordId&&!droppedExpenses.has(body.recordId)&&reply.statusCode===200){
    droppedExpenses.add(body.recordId);reply.raw.destroy();
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
await app.listen({host:'127.0.0.1',port:3001});console.log('Isolated device integration API listening on loopback port 3001');
