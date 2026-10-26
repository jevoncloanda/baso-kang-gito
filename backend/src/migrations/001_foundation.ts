import { sql, type Kysely } from 'kysely';

// Exercise transactional migration tracking without introducing business tables.
export async function up(db: Kysely<unknown>): Promise<void> {
  await sql`select 1`.execute(db);
}

export async function down(db: Kysely<unknown>): Promise<void> {
  await sql`select 1`.execute(db);
}
