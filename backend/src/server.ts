import { sql } from 'kysely';
import { buildApp } from './app.js';
import { readConfig, readBookkeepingConfig } from './config.js';
import { createDatabase } from './database.js';

const config = readConfig();
const db = createDatabase(config.databaseUrl);
const development=readBookkeepingConfig();
const app = buildApp(() => sql`select 1`.execute(db), true, development?{db,token:development.token}:undefined);
app.addHook('onClose', async () => db.destroy());

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, () => {
    app.close().catch(() => { process.exitCode = 1; });
  });
}

try {
  await app.listen({ host: config.host, port: config.port });
} catch {
  app.log.error('API failed to start');
  await app.close();
  process.exitCode = 1;
}
