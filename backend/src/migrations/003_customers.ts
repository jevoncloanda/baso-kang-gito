import { sql, type Kysely } from 'kysely';
import type { Database } from '../database.js';

export async function up(db: Kysely<Database>) {
  await sql`
    CREATE TABLE customers (
      id uuid PRIMARY KEY, name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
      phone text NOT NULL DEFAULT '' CHECK (length(phone)<=50),
      address text NOT NULL DEFAULT '' CHECK (length(address)<=500),
      notes text NOT NULL DEFAULT '' CHECK (length(notes)<=1000),
      archived boolean NOT NULL DEFAULT false, version integer NOT NULL DEFAULT 1 CHECK (version>0),
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
    );
    ALTER TABLE sales ADD COLUMN customer_id uuid REFERENCES customers(id);
    ALTER TABLE sales ADD CONSTRAINT customer_direct_only CHECK (customer_id IS NULL OR channel='langsung');
    CREATE INDEX sales_customer ON sales(customer_id) WHERE customer_id IS NOT NULL;
    CREATE INDEX customers_name ON customers(lower(name),id);
  `.execute(db);
}
