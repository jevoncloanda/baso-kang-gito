import { sql, type Kysely } from 'kysely';
import type { Database } from '../database.js';

export async function up(db: Kysely<Database>) {
  await sql`ALTER TABLE sales DROP CONSTRAINT sales_channel_check;
    ALTER TABLE sales ADD CONSTRAINT sales_channel_check
      CHECK(channel IN ('langsung','gofood','gofood_mamat','gofood_gito','grabfood','shopeefood'));
    CREATE INDEX financial_creation_revision ON financial_revisions(record_id,sequence) WHERE before_record IS NULL;`.execute(db);
}

export async function down() {
  throw new Error('Preserve account attribution; use a corrective migration rather than rewriting channels.');
}
