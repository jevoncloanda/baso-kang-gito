import { spawn } from 'node:child_process';
import { createReadStream, createWriteStream } from 'node:fs';
import { link, open, realpath, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { randomUUID } from 'node:crypto';

const root = fileURLToPath(new URL('../../', import.meta.url));
const [command, filename, database] = process.argv.slice(2);
const identifier = value => typeof value === 'string' && /^[a-z][a-z0-9_]{0,62}$/.test(value);

// Arguments are separate argv entries; database identifiers never become shell source.
async function postgres(source, args, input, output) {
  const child = spawn('docker', ['compose', 'exec', '-T', 'db', 'sh', '-c', source, 'backup', ...args], {
    cwd: root, stdio: [input ? 'pipe' : 'ignore', output ? 'pipe' : 'ignore', 'ignore'],
  });
  const completed = new Promise((accept, reject) => {
    child.once('error', () => reject(new Error('PostgreSQL utility could not start')));
    child.once('close', code => code === 0 ? accept() : reject(new Error('PostgreSQL utility failed')));
  });
  const work = [completed,
    ...(input ? [pipeline(createReadStream(input), child.stdin)] : []),
    ...(output ? [pipeline(child.stdout, createWriteStream(output, { flags: 'wx', mode: 0o600 }))] : []),
  ];
  try { await Promise.all(work); }
  catch (failure) { child.kill(); await Promise.allSettled(work); throw failure; }
}

async function archiveHeader(path) {
  const file = await open(path, 'r');
  try {
    const header = Buffer.alloc(5);
    const result = await file.read(header, 0, header.length, 0);
    if (result.bytesRead !== 5 || header.toString() !== 'PGDMP') throw new Error('Invalid custom archive');
  } finally { await file.close(); }
}

try {
  if (!filename || !['backup', 'restore'].includes(command) || (database !== undefined && !identifier(database)) || (command === 'restore' && !database)) {
    throw new Error('Usage: node backend/scripts/backup.mjs backup <outside-repository-file> [source-database] | restore <file> <new-database>');
  }
  const file = resolve(filename);
  if (command === 'backup') {
    const parent = await realpath(dirname(file));
    const checkout = await realpath(root);
    const location = relative(checkout, parent);
    if (location === '' || (!location.startsWith(`..${sep}`) && location !== '..' && !isAbsolute(location))) {
      throw new Error('Backups must be outside the repository');
    }
    const temporary = `${file}.partial-${randomUUID()}`;
    try {
      // pg_dump uses a consistent MVCC snapshot; no live-table locks/reset are required.
      await postgres('exec pg_dump --username="$POSTGRES_USER" --dbname="${1:-$POSTGRES_DB}" --format=custom --no-owner --no-privileges', database ? [database] : [], undefined, temporary);
      await archiveHeader(temporary);
      // Publish only after successful dump; never replace an existing archive.
      await link(temporary, file);
    } finally { await unlink(temporary).catch(() => {}); }
    console.log('Backup completed outside the database volume. Store a protected copy off this computer.');
  } else {
    await archiveHeader(file);
    // Refuse the configured live database even if it is missing. createdb refuses any existing target.
    await postgres('test "$1" != "$POSTGRES_DB" && exec createdb --username="$POSTGRES_USER" --maintenance-db=postgres --template=template0 "$1"', [database]);
    await postgres('exec pg_restore --username="$POSTGRES_USER" --dbname="$1" --single-transaction --exit-on-error --no-owner --no-privileges', [database], file);
    console.log('Restore completed into a new isolated database. Verify migrations, financial totals and operation history before use.');
  }
} catch (failure) {
  // Utility stderr may include financial rows or credentials: report failure without echoing it.
  if (!['backup', 'restore'].includes(command)) console.error('Usage: node backend/scripts/backup.mjs backup <outside-repository-file> [source-database] | restore <file> <new-database>');
  else console.error(command === 'backup'
    ? 'Backup failed; no existing archive was replaced. Check Docker, the source database and an external writable destination.'
    : 'Restore failed; no existing database was replaced. A newly created target may remain for inspection.');
  process.exitCode = 1;
}
