import { Kysely, PostgresDialect } from 'kysely';
import pg from 'pg';

// Financial SQL is typed at its result boundary in bookkeeping.ts.
export type Database = Record<string, never>;

export function createDatabase(connectionString: string) {
  const pool = new pg.Pool({ connectionString, max: 5, connectionTimeoutMillis: 5000, query_timeout: 5000 });
  pool.on('error', () => {
    console.error(JSON.stringify({ level: 'error', message: 'Idle PostgreSQL connection failed' }));
  });
  return new Kysely<Database>({
    dialect: new PostgresDialect({ pool }),
  });
}
