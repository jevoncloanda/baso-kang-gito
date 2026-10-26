import { Migrator, type Kysely } from 'kysely';
import type { Database } from './database.js';
import * as foundation from './migrations/001_foundation.js';
import * as bookkeeping from './migrations/002_bookkeeping.js';

export function createMigrator(db: Kysely<Database>, migrationTableSchema?: string) {
  return new Migrator({
    db,
    migrationTableSchema,
    provider: {
      // Explicit imports work identically in Windows ESM and compiled containers.
      async getMigrations() { return { '001_foundation': foundation, '002_bookkeeping': bookkeeping }; },
    },
  });
}
