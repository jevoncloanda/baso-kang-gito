import { Migrator, type Kysely } from 'kysely';
import type { Database } from './database.js';
import * as foundation from './migrations/001_foundation.js';
import * as bookkeeping from './migrations/002_bookkeeping.js';
import * as customers from './migrations/003_customers.js';
import * as deviceAccess from './migrations/004_device_access.js';

export function createMigrator(db: Kysely<Database>, migrationTableSchema?: string) {
  return new Migrator({
    db,
    migrationTableSchema,
    provider: {
      // Explicit imports work identically in Windows ESM and compiled containers.
      async getMigrations() { return { '001_foundation': foundation, '002_bookkeeping': bookkeeping, '003_customers': customers, '004_device_access': deviceAccess }; },
    },
  });
}
