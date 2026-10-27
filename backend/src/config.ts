export function readConfig(env: NodeJS.ProcessEnv = process.env) {
  const databaseUrl = env.DATABASE_URL ?? '';
  try {
    const url = new URL(databaseUrl);
    if (!['postgres:', 'postgresql:'].includes(url.protocol) || !url.hostname || url.pathname.length < 2) {
      throw new Error();
    }
  } catch {
    throw new Error('DATABASE_URL must be a PostgreSQL URL with a host and database name');
  }

  const port = env.PORT ?? '3000';
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    throw new Error('PORT must be an integer between 1 and 65535');
  }
  const host = env.HOST ?? '127.0.0.1';
  if (!host.trim() || host !== host.trim()) throw new Error('HOST must be a nonempty hostname or IP address');

  return { databaseUrl, host, port: Number(port) };
}

export function readTestControlConfig(env: NodeJS.ProcessEnv = process.env) {
  if (env.ENABLE_DEV_BOOKKEEPING!=='true') return null;
  if (env.NODE_ENV==='production') throw new Error('Test-helper control access is prohibited in production');
  const token=env.BOOKKEEPING_DEV_TOKEN??'';
  if (!/^[a-zA-Z0-9_-]{32,128}$/.test(token)) throw new Error('BOOKKEEPING_DEV_TOKEN must be a private random development token');
  return {token};
}
