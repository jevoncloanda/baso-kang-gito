import { readConfig } from './config.js';
import { createDatabase } from './database.js';
import { createMigrator } from './migrations.js';

const db = createDatabase(readConfig().databaseUrl);
try {
  const { error, results } = await createMigrator(db).migrateToLatest();
  for (const result of results ?? []) {
    console.log(JSON.stringify({ migration: result.migrationName, status: result.status }));
  }
  if (error) throw error;
} catch {
  console.error(JSON.stringify({ error: 'Database migration failed; check database access and migration files' }));
  process.exitCode = 1;
} finally {
  await db.destroy();
}
