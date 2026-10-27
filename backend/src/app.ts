import Fastify, { type FastifyError } from 'fastify';
import type { Kysely } from 'kysely';
import type { Database } from './database.js';
import { FinancialError } from './bookkeeping.js';
import { registerBookkeeping } from './bookkeeping-routes.js';
import { AccessError, registerDeviceAccess } from './device-access.js';

export function buildApp(checkDatabase: () => Promise<unknown>, logger = false, bookkeeping?: {db:Kysely<Database>}) {
  const app = Fastify({
    logger: logger ? {
      redact: ['req.headers.authorization', 'req.headers.cookie'],
      serializers: { req: (request: { method: string }) => ({ method: request.method }) },
    } : false,
  });

  app.get('/health', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    try {
      await checkDatabase();
      return { status: 'ok' };
    } catch {
      request.log.warn('Database health check failed');
      return reply.code(503).send({ status: 'unavailable' });
    }
  });

  if (bookkeeping) {
    registerDeviceAccess(app,bookkeeping.db);
    registerBookkeeping(app,bookkeeping.db);
  }
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ error: 'Not Found' }));
  app.setErrorHandler<FastifyError>((error, request, reply) => {
    if (error instanceof AccessError) {
      request.log.warn({event:error.code},'Device access refused');
      if(error.status===429) reply.header('Retry-After','60');
      return reply.code(error.status).send({error:error.code});
    }
    if (error instanceof FinancialError) return reply.code(error.status).send({error:error.code,...(error.current?{current:error.current}:{})});
    if (error.validation || error.statusCode===400 || error.statusCode===413) return reply.code(error.statusCode??400).send({error:'invalid_request'});
    if (error.code==='23505') return reply.code(409).send({error:'duplicate_identifier'});
    if (error.code==='23514' || error.code==='23503') return reply.code(422).send({error:'financial_constraint'});
    // Error messages may contain database credentials or user input.
    request.log.error('Request failed');
    reply.code(500).send({ error: 'Internal Server Error' });
  });
  return app;
}
