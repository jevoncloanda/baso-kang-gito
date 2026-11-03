import { sql, type Kysely, type Transaction } from 'kysely';
import type { Database } from './database.js';

type Db = Kysely<Database> | Transaction<Database>;
export type CatalogItem = { id:string; kind:'shopping_item'; name:string; unit:string; archived:boolean; version:number; aliases:string[] };

export async function catalogItem(db:Db,id:string):Promise<CatalogItem|undefined> {
  return (await sql<CatalogItem>`SELECT c.id,'shopping_item' AS kind,c.name,c.unit,c.archived,c.version,
    ARRAY(SELECT name FROM shopping_item_names WHERE master_id=c.id ORDER BY name) AS aliases
    FROM shopping_catalog c WHERE id=${id}::uuid`.execute(db)).rows[0];
}

// Legacy APKs keep writing names; resolve them into the same catalog inside the financial transaction.
export async function resolveShoppingName(db:Db,name:string):Promise<string> {
  const alias=(await sql<{id:string}>`SELECT master_id AS id FROM shopping_item_names WHERE name=${name}`.execute(db)).rows[0];
  if (alias) return alias.id;
  const id=(await sql<{id:string}>`SELECT shopping_item_id(${name}) AS id`.execute(db)).rows[0]!.id;
  await sql`INSERT INTO shopping_catalog(id,name) VALUES(${id}::uuid,${name}) ON CONFLICT(id) DO NOTHING`.execute(db);
  await sql`INSERT INTO shopping_item_names(name,master_id) VALUES(${name},${id}::uuid)`.execute(db);
  return id;
}

export async function catalogPage(db:Db,after:string,limit:number,knownRevision?:string) {
  const revision=(await sql<{revision:string}>`SELECT revision::text FROM shopping_catalog_state WHERE id=1`.execute(db)).rows[0]!.revision;
  if (knownRevision===revision) return {items:[],next:null,revision,notModified:true};
  const rows=(await sql<{id:string}>`SELECT id FROM shopping_catalog WHERE id>${after}::uuid ORDER BY id LIMIT ${limit}`.execute(db)).rows;
  return {items:await Promise.all(rows.map(row=>catalogItem(db,row.id))),next:rows.length===limit?rows.at(-1)!.id:null,revision,notModified:false};
}
