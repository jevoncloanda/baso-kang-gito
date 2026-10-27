import { createDatabase } from './database.js';
import { readConfig } from './config.js';
import { issueBootstrap } from './device-access.js';

const db=createDatabase(readConfig().databaseUrl);
try {
  if(process.argv.slice(2).some(arg=>arg!=='--recover')) throw Error('Use bootstrap [--recover]');
  const token=await issueBootstrap(db,process.argv.includes('--recover'));
  // Intentional operator-only secret output. Never invoked by the HTTP server.
  process.stdout.write(`One-time administrator enrollment token (expires in 10 minutes):\n${token}\n`);
} catch {
  process.stderr.write('Bootstrap refused. Check migrations and operator recovery instructions.\n'); process.exitCode=1;
} finally { await db.destroy(); }
