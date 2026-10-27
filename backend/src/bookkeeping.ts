import { createHash, randomUUID } from 'node:crypto';
import { sql, type Kysely, type Transaction } from 'kysely';
import type { Database } from './database.js';

type Db = Kysely<Database> | Transaction<Database>;
export type Receipt = { id: string; date: string; amount: string; method: 'cash'|'transfer'; voided: boolean; createdAt?: string };
export type RecordData = {
  id: string; kind: 'sale'|'expense'; date: string; version: number; amount: string; note: string; voided: boolean;
  channel?: string; origin?: string; entryKind?: string; customer?: string; customerId?: string | null; createdAt?: string;
  receipts?: Receipt[]; items?: { id: string; name: string; amount: string }[];
};
export type CustomerData = {
  id: string; kind: 'customer'; name: string; phone: string; address: string; notes: string;
  archived: boolean; version: number; createdAt: string;
};
export type Snapshot = RecordData | CustomerData;
export class FinancialError extends Error {
  constructor(public status: number, public code: string, public current?: Snapshot) { super(code); }
}
const invalid = () => new FinancialError(400, 'invalid_operation');
export const channels = ['langsung', 'gofood', 'grabfood', 'shopeefood'];
export function money(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,11}$/.test(value)) throw invalid();
  return value;
}
export function businessDate(value: unknown): string {
  if (typeof value !== 'string' || !/^20[0-9]{2}-[0-9]{2}-[0-9]{2}$/.test(value) ||
      !Number.isFinite(Date.parse(`${value}T00:00:00Z`)) || new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) !== value) throw invalid();
  return value;
}
export function jakartaDate(instant = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Jakarta', year: 'numeric', month: '2-digit', day: '2-digit' }).format(instant);
}
export function uuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) throw invalid();
  return value;
}
function text(value: unknown, limit: number, required = false): string {
  if (value === undefined && !required) return '';
  if (typeof value !== 'string' || value.length > limit || (required && !value.trim())) throw invalid();
  return value.trim();
}
function object(value: unknown): { [key: string]: unknown } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  return value as { [key: string]: unknown };
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.entries(value).sort(([a],[b]) => a.localeCompare(b)).map(([k,v])=>`${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`;
  return JSON.stringify(value);
}

export async function record(db: Db, id: string): Promise<RecordData | undefined> {
  const sale = await sql<RecordData>`SELECT id, 'sale' AS kind, business_date::text AS date, version, net_amount_rupiah::text AS amount,
    channel, origin, entry_kind AS "entryKind", customer_name AS customer, customer_id AS "customerId", note, (voided_at IS NOT NULL) AS voided,
    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt"
    FROM sales WHERE id=${id}::uuid`.execute(db);
  const root = sale.rows[0];
  if (root) {
    root.receipts = (await sql<Receipt>`SELECT r.id, r.received_date::text AS date, a.amount_rupiah::text AS amount, r.method,
      (r.voided_at IS NOT NULL) AS voided,
      to_char(r.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt"
      FROM receipts r JOIN receipt_allocations a ON a.receipt_id=r.id
      WHERE a.sale_id=${id}::uuid ORDER BY r.created_at,r.id`.execute(db)).rows;
    return root;
  }
  const expense = (await sql<RecordData>`SELECT id,'expense' AS kind,business_date::text AS date,version,note,
    (voided_at IS NOT NULL) AS voided, (SELECT COALESCE(sum(amount_rupiah),0)::text FROM expense_items WHERE expense_entry_id=e.id) AS amount,
    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt"
    FROM expense_entries e WHERE id=${id}::uuid`.execute(db)).rows[0];
  if (expense) expense.items = (await sql<{id:string;name:string;amount:string}>`SELECT id,name,amount_rupiah::text AS amount
    FROM expense_items WHERE expense_entry_id=${id}::uuid ORDER BY position`.execute(db)).rows;
  return expense;
}

export async function customer(db: Db, id: string): Promise<CustomerData | undefined> {
  return (await sql<CustomerData>`SELECT id,'customer' AS kind,name,phone,address,notes,archived,version,
    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt"
    FROM customers WHERE id=${id}::uuid`.execute(db)).rows[0];
}

export async function customerList(db: Db, query: string, after: string, limit: number, archived: boolean) {
  if (typeof query!=='string' || query.length>200 || !Number.isInteger(limit) || limit<1 || limit>100) throw invalid();
  if (after!=='00000000-0000-0000-0000-000000000000') uuid(after);
  const rows=(await sql<{id:string;outstanding:string}>`SELECT c.id,
    COALESCE((SELECT sum(s.net_amount_rupiah-COALESCE((SELECT sum(a.amount_rupiah) FROM receipt_allocations a
      JOIN receipts r ON r.id=a.receipt_id WHERE a.sale_id=s.id AND r.voided_at IS NULL),0))
      FROM sales s WHERE s.customer_id=c.id AND s.channel='langsung' AND s.voided_at IS NULL),0)::text AS outstanding
    FROM customers c WHERE c.id>${after}::uuid AND (${archived} OR NOT c.archived)
    AND (strpos(lower(c.name),lower(${query.trim()}))>0 OR strpos(lower(c.phone),lower(${query.trim()}))>0)
    ORDER BY c.id LIMIT ${limit}`.execute(db)).rows;
  return {customers:await Promise.all(rows.map(async row=>({...await customer(db,row.id),outstanding:row.outstanding}))),
    next:rows.length===limit?rows.at(-1)!.id:null};
}

export async function customerHistory(db: Db, id: string, after: string, limit: number) {
  const root=await customer(db,id);
  if (!root) throw new FinancialError(404,'not_found');
  if (!Number.isInteger(limit) || limit<1 || limit>100) throw invalid();
  if (after!=='00000000-0000-0000-0000-000000000000') uuid(after);
  const ids=(await sql<{id:string}>`SELECT id FROM sales WHERE customer_id=${id}::uuid AND id>${after}::uuid ORDER BY id LIMIT ${limit}`.execute(db)).rows;
  const outstanding=(await sql<{amount:string}>`SELECT COALESCE(sum(s.net_amount_rupiah-COALESCE((SELECT sum(a.amount_rupiah)
    FROM receipt_allocations a JOIN receipts r ON r.id=a.receipt_id WHERE a.sale_id=s.id AND r.voided_at IS NULL),0)),0)::text AS amount
    FROM sales s WHERE s.customer_id=${id}::uuid AND s.channel='langsung' AND s.voided_at IS NULL`.execute(db)).rows[0]!.amount;
  return {customer:root,outstanding,records:await Promise.all(ids.map(row=>record(db,row.id))),next:ids.length===limit?ids.at(-1)!.id:null};
}

export async function applyOperation(db: Kysely<Database>, input: unknown) {
  const op = object(input); const id=uuid(op.id); const recordId=uuid(op.recordId);
  if (!Number.isSafeInteger(op.expectedVersion) || Number(op.expectedVersion)<0 || Number(op.expectedVersion)>2147483646) throw invalid();
  const version=Number(op.expectedVersion); const data=object(op.data);
  const hash=createHash('sha256').update(canonical(input)).digest('hex');
  return db.transaction().execute(async tx => {
    const state=(await sql<{cursor:string}>`SELECT cursor::text FROM sync_state WHERE id=1 FOR UPDATE`.execute(tx)).rows[0]!;
    const duplicate=(await sql<{payload_hash:string;result:{record:Snapshot;cursor:string}}>`SELECT payload_hash,result FROM financial_operations WHERE id=${id}::uuid`.execute(tx)).rows[0];
    if (duplicate) {
      if (duplicate.payload_hash!==hash) throw new FinancialError(409,'operation_reused');
      return duplicate.result;
    }
    const before: Snapshot | undefined=await record(tx,recordId) ?? await customer(tx,recordId);
    if ((before?.version ?? 0)!==version) throw new FinancialError(409,'version_conflict',before);
    if (before && before.kind!=='customer' && before.voided) throw new FinancialError(409,'record_voided',before);
    const reason=version>0 ? text(data.reason,500,true) : 'Created';
    if (op.action==='save_customer') {
      if (before && before.kind!=='customer') throw invalid();
      const name=text(data.name,200,true); const phone=text(data.phone,50); const address=text(data.address,500); const notes=text(data.notes,1000);
      if (data.archived!==undefined && typeof data.archived!=='boolean') throw invalid();
      const archived=data.archived??before?.archived??false;
      if (before) await sql`UPDATE customers SET name=${name},phone=${phone},address=${address},notes=${notes},archived=${archived},
        version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
      else await sql`INSERT INTO customers(id,name,phone,address,notes,archived) VALUES(${recordId}::uuid,${name},${phone},${address},${notes},${archived})`.execute(tx);
    } else if (op.action==='save_sale') {
      if (before && before.kind!=='sale') throw invalid();
      const date=businessDate(data.date); const amount=money(data.amount);
      if (!channels.includes(String(data.channel))) throw invalid();
      const channel=String(data.channel); const kind=channel==='langsung'?'individual':'daily_aggregate';
      const customerId=data.customerId===undefined ? before?.customerId??null : data.customerId===null ? null : uuid(data.customerId);
      if (customerId && channel!=='langsung') throw invalid();
      let customerName=text(data.customer,200); const note=text(data.note,1000);
      if (customerId) {
        const linked=await customer(tx,customerId);
        if (!linked || (linked.archived && before?.customerId!==customerId)) throw new FinancialError(422,'customer_unavailable');
        customerName=before?.customerId===customerId ? before.customer! : data.customer===undefined ? linked.name : text(data.customer,200,true);
      }
      const initial=data.paid===true ? (data.paymentAmount===undefined ? amount : money(data.paymentAmount)) : '0';
      if (BigInt(initial)>BigInt(amount)) throw new FinancialError(422,'overpayment');
      if (!before && data.paid!==true && data.paymentAmount!==undefined) throw invalid();
      // Omitted customerId identifies durable Milestone 1 operations; never rewrite their bodies on retry.
      const received=before?.receipts!.filter(r=>!r.voided).reduce((sum,r)=>sum+BigInt(r.amount),0n)??0n;
      if (channel==='langsung' && !customerId && ((!before && 'customerId' in data && BigInt(initial)<BigInt(amount)) ||
          (before && 'customerId' in data && received===BigInt(before.amount) && BigInt(amount)>received) ||
          (before?.customerId && BigInt(amount)>before.receipts!.filter(r=>!r.voided).reduce((sum,r)=>sum+BigInt(r.amount),0n)))) {
        throw new FinancialError(422,'customer_required');
      }
      // Until POS cutover is explicitly designed, manual platform records are aggregates only.
      const overlap=(await sql<{id:string}>`SELECT id FROM sales WHERE business_date=${date}::date AND channel=${channel}
        AND id<>${recordId}::uuid AND voided_at IS NULL AND (${kind}='daily_aggregate' OR entry_kind='daily_aggregate') LIMIT 1`.execute(tx)).rows[0];
      if (overlap) throw new FinancialError(409,'daily_aggregate_exists',await record(tx,overlap.id));
      if (before) {
        if (data.paid!==undefined || data.receiptId!==undefined || data.method!==undefined || data.paymentAmount!==undefined) throw invalid();
        const allocated=before.receipts!.filter(r=>!r.voided).reduce((sum,r)=>sum+BigInt(r.amount),0n);
        if (BigInt(amount)<allocated) throw new FinancialError(422,'below_received',before);
        if (channel!==before.channel && allocated>0n) throw new FinancialError(422,'linked_receipts',before);
        await sql`UPDATE sales SET business_date=${date}::date,channel=${channel},entry_kind=${kind},net_amount_rupiah=${amount}::bigint,
          customer_name=${customerName},customer_id=${customerId}::uuid,note=${note},version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
      } else {
        if (typeof data.paid!=='boolean') throw invalid();
        await sql`INSERT INTO sales(id,business_date,channel,origin,entry_kind,net_amount_rupiah,customer_name,customer_id,note,version)
          VALUES(${recordId}::uuid,${date}::date,${channel},'manual',${kind},${amount}::bigint,${customerName},${customerId}::uuid,${note},1)`.execute(tx);
        if (data.paid) await addReceipt(tx,recordId,channel,{id:data.receiptId,date,amount:initial,method:data.method});
      }
    } else if (op.action==='save_expense') {
      if (before && before.kind!=='expense') throw invalid();
      const date=businessDate(data.date); const note=text(data.note,1000);
      if (!Array.isArray(data.items) || data.items.length<1 || data.items.length>100) throw invalid();
      const items=data.items.map(value=>{ const item=object(value); return {id:uuid(item.id),name:text(item.name,200,true),amount:money(item.amount)}; });
      if (new Set(items.map(i=>i.id)).size!==items.length) throw invalid();
      if (before) {
        await sql`UPDATE expense_entries SET business_date=${date}::date,note=${note},version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
        await sql`DELETE FROM expense_items WHERE expense_entry_id=${recordId}::uuid`.execute(tx);
      } else await sql`INSERT INTO expense_entries(id,business_date,note,version) VALUES(${recordId}::uuid,${date}::date,${note},1)`.execute(tx);
      for (const [position,item] of items.entries()) await sql`INSERT INTO expense_items(id,expense_entry_id,name,amount_rupiah,position)
        VALUES(${item.id}::uuid,${recordId}::uuid,${item.name},${item.amount}::bigint,${position})`.execute(tx);
    } else if (op.action==='add_receipt') {
      if (!before || before.kind!=='sale') throw invalid();
      const amount=money(data.amount); const received=before.receipts!.filter(r=>!r.voided).reduce((sum,r)=>sum+BigInt(r.amount),0n);
      if (received+BigInt(amount)>BigInt(before.amount)) throw new FinancialError(422,'overpayment',before);
      await addReceipt(tx,recordId,before.channel!,data);
      await sql`UPDATE sales SET version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
    } else if (op.action==='void') {
      if (!before || before.kind==='customer') throw invalid();
      if (data.receiptId!==undefined) {
        const receiptId=uuid(data.receiptId);
        if (before.kind!=='sale' || !before.receipts!.some(r=>r.id===receiptId&&!r.voided)) throw invalid();
        await sql`UPDATE receipts SET voided_at=now(),void_reason=${reason},version=version+1,updated_at=now() WHERE id=${receiptId}::uuid`.execute(tx);
        await sql`UPDATE sales SET version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
      } else if (before.kind==='sale') {
        if (before.receipts!.some(r=>!r.voided) && data.includeReceipts!==true) throw new FinancialError(422,'linked_receipts',before);
        await sql`UPDATE receipts SET voided_at=now(),void_reason=${reason},version=version+1,updated_at=now()
          WHERE voided_at IS NULL AND id IN (SELECT receipt_id FROM receipt_allocations WHERE sale_id=${recordId}::uuid)`.execute(tx);
        await sql`UPDATE sales SET voided_at=now(),void_reason=${reason},version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
      } else await sql`UPDATE expense_entries SET voided_at=now(),void_reason=${reason},version=version+1,updated_at=now() WHERE id=${recordId}::uuid`.execute(tx);
    } else throw invalid();
    const after=(await record(tx,recordId) ?? await customer(tx,recordId))!; const cursor=(BigInt(state.cursor)+1n).toString(); const result={record:after,cursor};
    await sql`INSERT INTO financial_operations(id,payload_hash,result) VALUES(${id}::uuid,${hash},${JSON.stringify(result)}::jsonb)`.execute(tx);
    await sql`INSERT INTO financial_revisions(operation_id,record_id,reason,before_record,after_record)
      VALUES(${id}::uuid,${recordId}::uuid,${reason},${before?JSON.stringify(before):null}::jsonb,${JSON.stringify(after)}::jsonb)`.execute(tx);
    await sql`INSERT INTO sync_changes(cursor,operation_id,record) VALUES(${cursor}::bigint,${id}::uuid,${JSON.stringify(after)}::jsonb)`.execute(tx);
    await sql`UPDATE sync_state SET cursor=${cursor}::bigint WHERE id=1`.execute(tx);
    return result;
  });
}

async function addReceipt(db: Db,saleId:string,channel:string,data:{[key:string]:unknown}) {
  const id=uuid(data.id); const date=businessDate(data.date); const amount=money(data.amount);
  if (!['cash','transfer'].includes(String(data.method))) throw invalid();
  const purpose=channel==='langsung'?'sale_payment':'platform_settlement';
  await sql`INSERT INTO receipts(id,received_date,amount_rupiah,method,purpose)
    VALUES(${id}::uuid,${date}::date,${amount}::bigint,${String(data.method)},${purpose})`.execute(db);
  await sql`INSERT INTO receipt_allocations(id,receipt_id,sale_id,amount_rupiah) VALUES(${randomUUID()}::uuid,${id}::uuid,${saleId}::uuid,${amount}::bigint)`.execute(db);
}

export async function changes(db:Db, after:string,limit:number) {
  if (!/^(0|[1-9][0-9]{0,17})$/.test(after) || !Number.isInteger(limit) || limit<1 || limit>100) throw invalid();
  const rows=(await sql<{cursor:string;record:Snapshot}>`SELECT cursor::text,record FROM sync_changes WHERE cursor>${after}::bigint ORDER BY sync_changes.cursor LIMIT ${limit}`.execute(db)).rows;
  // Enrich legacy snapshots for display only. Never rewrite financial snapshots or stored replay results.
  const missingIds=[...new Set(rows.flatMap(({record})=>record.kind==='customer'?[]:[
    ...(record.createdAt?[]:[record.id]), ...(record.receipts??[]).filter(receipt=>!receipt.createdAt).map(receipt=>receipt.id),
  ]))];
  if (missingIds.length) {
    const timestamps=(await sql<{id:string;kind:string;createdAt:string}>`SELECT id,'sale' AS kind,
      to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS "createdAt" FROM sales WHERE id=ANY(${missingIds}::uuid[])
      UNION ALL SELECT id,'expense',to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') FROM expense_entries WHERE id=ANY(${missingIds}::uuid[])
      UNION ALL SELECT id,'receipt',to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') FROM receipts WHERE id=ANY(${missingIds}::uuid[])`.execute(db)).rows;
    const createdAt=new Map(timestamps.map(row=>[`${row.kind}:${row.id}`,row.createdAt]));
    for (const {record} of rows) {
      if (record.kind==='customer') continue;
      const rootTime=createdAt.get(`${record.kind}:${record.id}`);
      if (!record.createdAt && rootTime) record.createdAt=rootTime;
      for (const receipt of record.receipts??[]) {
        const receiptTime=createdAt.get(`receipt:${receipt.id}`);
        if (!receipt.createdAt && receiptTime) receipt.createdAt=receiptTime;
      }
    }
  }
  return {changes:rows,cursor:rows.at(-1)?.cursor??after,hasMore:rows.length===limit};
}

export async function recap(db:Db,from:string,to:string) {
  businessDate(from); businessDate(to);
  const result=(await sql<{sales:string;received:string;expenses:string}>`SELECT
    (SELECT COALESCE(sum(net_amount_rupiah),0)::text FROM sales WHERE voided_at IS NULL AND business_date>=${from}::date AND business_date<${to}::date) AS sales,
    (SELECT COALESCE(sum(amount_rupiah),0)::text FROM receipts WHERE voided_at IS NULL AND received_date>=${from}::date AND received_date<${to}::date) AS received,
    (SELECT COALESCE(sum(i.amount_rupiah),0)::text FROM expense_entries e JOIN expense_items i ON i.expense_entry_id=e.id WHERE e.voided_at IS NULL AND e.business_date>=${from}::date AND e.business_date<${to}::date) AS expenses`.execute(db)).rows[0]!;
  const byChannel=(await sql<{channel:string;amount:string}>`SELECT channel,sum(net_amount_rupiah)::text AS amount FROM sales WHERE voided_at IS NULL AND business_date>=${from}::date AND business_date<${to}::date GROUP BY channel`.execute(db)).rows;
  return {...result,cashDifference:(BigInt(result.received)-BigInt(result.expenses)).toString(),byChannel};
}
