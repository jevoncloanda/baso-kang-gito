import { Migrator, type Kysely } from 'kysely';
import type { Database } from './database.js';
import * as foundation from './migrations/001_foundation.js';
import * as bookkeeping from './migrations/002_bookkeeping.js';
import * as customers from './migrations/003_customers.js';
import * as deviceAccess from './migrations/004_device_access.js';
import * as shoppingCatalog from './migrations/005_shopping_catalog.js';
import * as gofoodChannels from './migrations/006_gofood_channels.js';

export function createMigrator(db: Kysely<Database>, migrationTableSchema?: string) {
  return new Migrator({
    db,
    migrationTableSchema,
    provider: {
      // Explicit imports work identically in Windows ESM and compiled containers.
      async getMigrations() { return { '001_foundation': foundation, '002_bookkeeping': bookkeeping, '003_customers': customers, '004_device_access': deviceAccess, '005_shopping_catalog': shoppingCatalog, '006_gofood_channels': gofoodChannels }; },
    },
  });
}
