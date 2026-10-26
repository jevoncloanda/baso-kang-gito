import { sql, type Kysely } from 'kysely';
import type { Database } from '../database.js';

export async function up(db: Kysely<Database>) {
  await sql`
    CREATE TABLE sales (
      id uuid PRIMARY KEY, business_date date NOT NULL, channel text NOT NULL CHECK (channel IN ('langsung','gofood','grabfood','shopeefood')),
      origin text NOT NULL CHECK (origin IN ('manual','pos')), origin_ref text,
      entry_kind text NOT NULL CHECK (entry_kind IN ('individual','daily_aggregate')),
      net_amount_rupiah bigint NOT NULL CHECK (net_amount_rupiah BETWEEN 1 AND 999999999999),
      customer_name text NOT NULL DEFAULT '' CHECK (length(customer_name)<=200), note text NOT NULL DEFAULT '' CHECK (length(note)<=1000),
      version integer NOT NULL CHECK (version>0), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
      voided_at timestamptz, void_reason text,
      CHECK (entry_kind<>'daily_aggregate' OR (channel<>'langsung' AND origin='manual')),
      CHECK ((voided_at IS NULL) = (void_reason IS NULL)), UNIQUE(origin,origin_ref)
    );
    CREATE UNIQUE INDEX sales_daily_aggregate ON sales(business_date,channel) WHERE entry_kind='daily_aggregate' AND voided_at IS NULL;
    CREATE INDEX sales_date ON sales(business_date);
    CREATE TABLE receipts (
      id uuid PRIMARY KEY, received_date date NOT NULL, amount_rupiah bigint NOT NULL CHECK (amount_rupiah BETWEEN 1 AND 999999999999),
      method text NOT NULL CHECK (method IN ('cash','transfer')), purpose text NOT NULL CHECK (purpose IN ('sale_payment','platform_settlement')),
      origin text NOT NULL DEFAULT 'manual' CHECK (origin='manual'), version integer NOT NULL DEFAULT 1 CHECK (version>0),
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), voided_at timestamptz, void_reason text,
      CHECK ((voided_at IS NULL) = (void_reason IS NULL))
    );
    CREATE INDEX receipts_date ON receipts(received_date);
    CREATE TABLE receipt_allocations (
      id uuid PRIMARY KEY, receipt_id uuid NOT NULL REFERENCES receipts(id), sale_id uuid NOT NULL REFERENCES sales(id),
      amount_rupiah bigint NOT NULL CHECK (amount_rupiah>0), UNIQUE(receipt_id,sale_id)
    );
    CREATE INDEX receipt_sale ON receipt_allocations(sale_id);
    CREATE TABLE expense_entries (
      id uuid PRIMARY KEY, business_date date NOT NULL, kind text NOT NULL DEFAULT 'business_expense' CHECK(kind='business_expense'),
      note text NOT NULL DEFAULT '' CHECK(length(note)<=1000), version integer NOT NULL CHECK(version>0),
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), voided_at timestamptz, void_reason text,
      CHECK ((voided_at IS NULL) = (void_reason IS NULL))
    );
    CREATE INDEX expense_date ON expense_entries(business_date);
    CREATE TABLE expense_items (
      id uuid PRIMARY KEY, expense_entry_id uuid NOT NULL REFERENCES expense_entries(id), name text NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 200),
      amount_rupiah bigint NOT NULL CHECK(amount_rupiah BETWEEN 1 AND 999999999999), position integer NOT NULL CHECK(position>=0), UNIQUE(expense_entry_id,position)
    );
    CREATE TABLE financial_operations (id uuid PRIMARY KEY, payload_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
    CREATE TABLE financial_revisions (
      sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, operation_id uuid NOT NULL REFERENCES financial_operations(id) DEFERRABLE INITIALLY DEFERRED,
      record_id uuid NOT NULL, reason text NOT NULL, before_record jsonb, after_record jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
    );
    -- One small shop: serialize writes and cursor assignment to preserve commit order.
    CREATE TABLE sync_state (id integer PRIMARY KEY CHECK(id=1), cursor bigint NOT NULL CHECK(cursor>=0));
    INSERT INTO sync_state VALUES(1,0);
    CREATE TABLE sync_changes (cursor bigint PRIMARY KEY, operation_id uuid NOT NULL UNIQUE REFERENCES financial_operations(id), record jsonb NOT NULL);

    CREATE FUNCTION check_financial_allocations() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN
      PERFORM id FROM sync_state WHERE id=1 FOR UPDATE;
      IF EXISTS(SELECT 1 FROM receipt_allocations a JOIN receipts r ON r.id=a.receipt_id JOIN sales s ON s.id=a.sale_id
        WHERE r.voided_at IS NULL AND s.voided_at IS NOT NULL)
        OR EXISTS(SELECT 1 FROM sales s JOIN receipt_allocations a ON a.sale_id=s.id JOIN receipts r ON r.id=a.receipt_id
          WHERE r.voided_at IS NULL GROUP BY s.id HAVING sum(a.amount_rupiah)>s.net_amount_rupiah)
        OR EXISTS(SELECT 1 FROM receipts r JOIN receipt_allocations a ON a.receipt_id=r.id
          WHERE r.voided_at IS NULL GROUP BY r.id HAVING sum(a.amount_rupiah)>r.amount_rupiah)
      THEN RAISE EXCEPTION 'Invalid receipt allocations' USING ERRCODE='23514'; END IF;
      RETURN NULL;
    END $$;
    CREATE CONSTRAINT TRIGGER allocation_integrity AFTER INSERT OR UPDATE OR DELETE ON receipt_allocations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_financial_allocations();
    CREATE CONSTRAINT TRIGGER sale_allocation_integrity AFTER UPDATE ON sales DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_financial_allocations();
    CREATE CONSTRAINT TRIGGER receipt_allocation_integrity AFTER UPDATE ON receipts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_financial_allocations();
  `.execute(db);
}

export async function down() {
  throw new Error('Bookkeeping rollback is forward-only: preserve financial data and deploy a corrective migration.');
}
