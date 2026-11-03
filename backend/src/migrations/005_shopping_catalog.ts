import { sql, type Kysely } from 'kysely';
import type { Database } from '../database.js';

export async function up(db: Kysely<Database>) {
  await sql`
    CREATE FUNCTION shopping_item_id(value text) RETURNS uuid LANGUAGE sql IMMUTABLE STRICT AS $$
      SELECT (substr(h,1,8)||'-'||substr(h,9,4)||'-3'||substr(h,14,3)||'-'||
        to_hex((('x'||substr(h,17,1))::bit(4)::int & 3) | 8)||substr(h,18,3)||'-'||substr(h,21,12))::uuid
      FROM (SELECT md5('bkg-shopping-item-v1:'||value) AS h) hash
    $$;
    CREATE TABLE shopping_catalog (
      id uuid PRIMARY KEY, name text NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 200),
      unit text NOT NULL DEFAULT '' CHECK(length(unit)<=50), archived boolean NOT NULL DEFAULT false,
      version integer NOT NULL DEFAULT 1 CHECK(version>0),
      created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE shopping_item_names (
      name text PRIMARY KEY, master_id uuid NOT NULL REFERENCES shopping_catalog(id)
    );
    CREATE TABLE shopping_catalog_state (id integer PRIMARY KEY CHECK(id=1), revision bigint NOT NULL);
    INSERT INTO shopping_catalog_state VALUES(1,0);
    CREATE FUNCTION bump_shopping_catalog() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN UPDATE shopping_catalog_state SET revision=revision+1 WHERE id=1; RETURN NULL; END $$;
    CREATE TRIGGER catalog_revision AFTER INSERT OR UPDATE ON shopping_catalog FOR EACH STATEMENT EXECUTE FUNCTION bump_shopping_catalog();
    CREATE TRIGGER catalog_alias_revision AFTER INSERT ON shopping_item_names FOR EACH STATEMENT EXECUTE FUNCTION bump_shopping_catalog();
    -- Only identical names share an identity. Case, spacing and spelling variants stay separate for review.
    INSERT INTO shopping_catalog(id,name)
      SELECT shopping_item_id(name),name FROM (SELECT DISTINCT name FROM expense_items) names;
    INSERT INTO shopping_item_names(name,master_id) SELECT name,id FROM shopping_catalog;
    ALTER TABLE expense_items ADD COLUMN master_id uuid REFERENCES shopping_catalog(id);
    UPDATE expense_items SET master_id=shopping_item_id(name);
    ALTER TABLE expense_items ALTER COLUMN master_id SET NOT NULL;
    CREATE INDEX expense_items_master ON expense_items(master_id);
    CREATE VIEW shopping_catalog_review AS
      SELECT lower(regexp_replace(trim(name),'[[:space:]]+',' ','g')) AS matching_name,
        array_agg(name ORDER BY name) AS names, array_agg(master_id ORDER BY name) AS master_ids
      FROM shopping_item_names GROUP BY lower(regexp_replace(trim(name),'[[:space:]]+',' ','g')) HAVING count(*)>1;
  `.execute(db);
}

export async function down() {
  throw new Error('Preserve catalog links and financial history; deploy a corrective migration.');
}
