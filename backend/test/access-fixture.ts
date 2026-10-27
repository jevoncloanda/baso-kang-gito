import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Kysely } from 'kysely';
import type { Database } from '../src/database.js';
import { issueBootstrap } from '../src/device-access.js';

export const installationCredential=()=>`${randomUUID()}.${randomBytes(32).toString('base64url')}`;
export async function bootstrapFixture(app:FastifyInstance,db:Kysely<Database>) {
  const credential=installationCredential(); const token=await issueBootstrap(db);
  const response=await app.inject({method:'POST',url:'/v1/access/bootstrap',headers:{authorization:`Bearer ${credential}`},payload:{name:'Synthetic administrator',token}});
  assert.equal(response.statusCode,200,response.body); return credential;
}
export async function enrollFixture(app:FastifyInstance,administrator:string) {
  const credential=installationCredential(); const id=credential.split('.')[0]!;
  const initiated=await app.inject({method:'POST',url:'/v1/access/enrollments',headers:{authorization:`Bearer ${credential}`},payload:{name:'Synthetic second client'}});
  assert.equal(initiated.statusCode,200,initiated.body);
  const approved=await app.inject({method:'POST',url:`/v1/access/enrollments/${id}/decision`,headers:{authorization:`Bearer ${administrator}`},payload:{approve:true,code:initiated.json().code}});
  assert.equal(approved.statusCode,200,approved.body);
  const completed=await app.inject({method:'POST',url:'/v1/access/enrollment/complete',headers:{authorization:`Bearer ${credential}`},payload:{}});
  assert.equal(completed.statusCode,200,completed.body); return credential;
}
