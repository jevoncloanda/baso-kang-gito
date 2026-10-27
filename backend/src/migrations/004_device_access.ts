import { sql, type Kysely } from 'kysely';
import type { Database } from '../database.js';

export async function up(db: Kysely<Database>) {
  await sql`
    CREATE TABLE trusted_devices (
      id uuid PRIMARY KEY, name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
      credential_hash text NOT NULL UNIQUE CHECK (length(credential_hash)=64),
      role text NOT NULL CHECK (role IN ('admin','standard')),
      created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz
    );
    CREATE TABLE device_enrollments (
      id uuid PRIMARY KEY, name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
      credential_hash text NOT NULL UNIQUE CHECK (length(credential_hash)=64),
      pairing_code text NOT NULL CHECK (pairing_code ~ '^[0-9]{6}$'),
      purpose text NOT NULL CHECK (purpose IN ('device','pin_recovery')),
      state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','approved','rejected','active')),
      attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
      created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
      approved_by uuid REFERENCES trusted_devices(id)
    );
    CREATE TABLE administrator_bootstrap (
      id uuid PRIMARY KEY, token_hash text NOT NULL UNIQUE CHECK (length(token_hash)=64),
      expires_at timestamptz NOT NULL, consumed_by uuid REFERENCES trusted_devices(id),
      recovery boolean NOT NULL DEFAULT false
    );
    CREATE TABLE security_events (
      sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      occurred_at timestamptz NOT NULL DEFAULT now(), event text NOT NULL,
      actor_id uuid, target_id uuid
    );
    CREATE TABLE access_rate_limits (
      bucket text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL
    );
  `.execute(db);
}
