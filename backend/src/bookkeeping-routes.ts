import { timingSafeEqual } from 'node:crypto';
import { sql, type Kysely } from 'kysely';
import type { FastifyInstance } from 'fastify';
import type { Database } from './database.js';
import { applyOperation, businessDate, changes, customerList, customerHistory, FinancialError, record, recap, uuid } from './bookkeeping.js';

export function registerBookkeeping(app: FastifyInstance, db: Kysely<Database>, token: string) {
  app.register(async routes => {
    routes.addHook('onRequest', async (request,reply) => {
      reply.header('Cache-Control','no-store');
      const supplied=Buffer.from(request.headers.authorization??''); const expected=Buffer.from(`Bearer ${token}`);
      if (supplied.length!==expected.length || !timingSafeEqual(supplied,expected)) return reply.code(401).send({error:'unauthorized'});
    });
    routes.post('/operations', async request => applyOperation(db,request.body));
    routes.get<{Querystring:{q?:string;after?:string;limit?:string;archived?:string}}>('/customers', async request => {
      if (request.query.archived!==undefined && !['true','false'].includes(request.query.archived)) throw new FinancialError(400,'invalid_query');
      return customerList(db,request.query.q??'',request.query.after??'00000000-0000-0000-0000-000000000000',Number(request.query.limit??100),request.query.archived==='true');
    });
    routes.get<{Params:{id:string};Querystring:{after?:string;limit?:string}}>('/customers/:id', async request =>
      customerHistory(db,uuid(request.params.id),request.query.after??'00000000-0000-0000-0000-000000000000',Number(request.query.limit??100)));
    routes.get<{Querystring:{after?:string;limit?:string}}>('/changes', async request => changes(db,request.query.after??'0',Number(request.query.limit??100)));
    routes.get<{Params:{id:string}}>('/records/:id', async (request,reply) => {
      const result=await record(db,uuid(request.params.id));
      if (!result) return reply.code(404).send({error:'not_found'});
      return result;
    });
    routes.get<{Querystring:{date:string;after?:string;limit?:string}}>('/daily', async request => {
      const date=businessDate(request.query.date); const limit=Number(request.query.limit??100);
      if (!Number.isInteger(limit)||limit<1||limit>100) throw new FinancialError(400,'invalid_query');
      const after=request.query.after?uuid(request.query.after):'00000000-0000-0000-0000-000000000000';
      const ids=(await sql<{id:string}>`SELECT id FROM (
        SELECT s.id FROM sales s WHERE s.business_date=${date}::date OR EXISTS(SELECT 1 FROM receipt_allocations a JOIN receipts r ON r.id=a.receipt_id WHERE a.sale_id=s.id AND r.received_date=${date}::date)
        UNION SELECT id FROM expense_entries WHERE business_date=${date}::date) d WHERE id>${after}::uuid ORDER BY id LIMIT ${limit}`.execute(db)).rows;
      const end=new Date(`${date}T00:00:00Z`); end.setUTCDate(end.getUTCDate()+1);
      return {date,totals:await recap(db,date,end.toISOString().slice(0,10)),records:await Promise.all(ids.map(row=>record(db,row.id))),next:ids.length===limit?ids.at(-1)!.id:null};
    });
    routes.get<{Querystring:{month:string}}>('/monthly', async request => {
      const month=request.query.month;
      if (!/^20[0-9]{2}-(0[1-9]|1[0-2])$/.test(month??'')) throw new FinancialError(400,'invalid_query');
      const start=`${month}-01`; const end=new Date(`${start}T00:00:00Z`); end.setUTCMonth(end.getUTCMonth()+1);
      const to=end.toISOString().slice(0,10);
      const days=(await sql<{date:string;sales:string;received:string;expenses:string}>`SELECT d::text AS date,
        sum(sales)::text AS sales,sum(received)::text AS received,sum(expenses)::text AS expenses FROM (
        SELECT business_date AS d,net_amount_rupiah AS sales,0::bigint AS received,0::bigint AS expenses FROM sales WHERE voided_at IS NULL
        UNION ALL SELECT received_date,0,amount_rupiah,0 FROM receipts WHERE voided_at IS NULL
        UNION ALL SELECT e.business_date,0,0,i.amount_rupiah FROM expense_entries e JOIN expense_items i ON i.expense_entry_id=e.id WHERE e.voided_at IS NULL
        ) entries WHERE d>=${start}::date AND d<${to}::date GROUP BY d ORDER BY d DESC`.execute(db)).rows;
      return {month,totals:await recap(db,start,to),days};
    });
  },{prefix:'/v1'});
}
