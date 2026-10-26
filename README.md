# Baso Kang Gito

Android-first bookkeeping for an Indonesian family bakso business. The development app saves sales, receipts, shopping, corrections and voids in Room, calculates daily/monthly recaps, and optionally synchronizes with a local Fastify/PostgreSQL API. English and Bahasa Indonesia work offline.

**Not ready for sole family bookkeeping.** Production identity/authorization and recovery of unsynchronized Android data remain rollout gates. PostgreSQL backup/isolated restoration is tested below. Release sync is disabled. Use synthetic development records.

## Architecture

- `android/`: Kotlin/Compose/Material 3, one app module; Room, durable outbox and WorkManager.
- `backend/`: Node 22, TypeScript, Fastify 5, Kysely, node-postgres; existing Node tests in `test/`.
- `compose.yaml`: local API and PostgreSQL 17, with named persistent volume `baso-kang-gito_postgres_data`.
- [OpenAPI](docs/openapi.yaml): implemented routes/contracts. [Buku Harian](docs/buku-harian.md): original design and acceptance criteria; its sample-only introduction is historical and does not describe the current code.
- `AGENTS.md`: agent instructions, separate from developer prerequisites.

Sales and receipts have separate dates/totals. Platform amounts are net after fees; settlements never create another sale or fee expense. Shopping totals derive from items. Business dates use Asia/Jakarta; audit timestamps use UTC. Exact integer rupiah travel as decimal strings. Received minus shopping is a cash difference, not profit or a complete cash balance.

Server transactions commit financial rows, operation UUID/hash/result, audit before/after snapshots and ordered change cursors together. Expected versions reject stale writes; voids retain tombstones. Android commits its local snapshot/outbox together, retries the same operation ID/body, acknowledges then pulls changes, and retains conflicts for review. This development protocol is not production multi-device identity or backup.

## Prerequisites

- Git and a running Docker Linux engine with Docker Compose supporting `--wait`.
- Host scripts: Node **22.x, at least 22.20**, plus npm. Compose-only startup needs no host Node; its image uses Node 22. The npm lockfile is authoritative.
- Android Studio, a Gradle 9.1-compatible JDK (17–25), SDK Platform 36, Build Tools 36.0.0 and platform-tools/ADB. Minimum device: Android 8/API 26. Use a physical device; no emulator is required.

The wrapper pins/checksums Gradle 9.1.0. Gradle files pin AGP 9.0.1, Kotlin/Compose compiler 2.2.10, KSP 2.3.6 and Compose BOM 2026.02.00. First builds download build dependencies. Install missing SDK components yourself in Studio; commands below disable SDK auto-download. No personal agent tooling is a project prerequisite.

## Clone and configure

```powershell
git clone https://github.com/jevoncloanda/baso-kang-gito.git
cd baso-kang-gito
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

Unix equivalent: `test -f .env || cp .env.example .env`. Preserve existing configuration and volume credentials. In ignored `.env`, replace the example password in **both** `POSTGRES_PASSWORD` and `DATABASE_URL`. Use a random URL-safe password; percent-encode special URL characters. Generate a private random hex value under Node 22 with:

```powershell
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

For development sync, set `ENABLE_DEV_BOOKKEEPING=true` and generate a **separate** private `BOOKKEEPING_DEV_TOKEN` (32–128 letters/digits/underscores/hyphens). Otherwise only health is exposed. Never commit/share these values. The token is development access control, not user authentication.

Defaults are PostgreSQL `127.0.0.1:5432` and API `127.0.0.1:3000`. If PostgreSQL's host port is occupied/reserved, update `POSTGRES_PORT` and the port in `DATABASE_URL` together, e.g. to 5433. Internal PostgreSQL remains on 5432. Compose fixes the API host port at 3000; resolve conflicts before startup.

## Start the services

From the repository root:

```powershell
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
curl.exe --fail --silent --show-error http://127.0.0.1:3000/health
# Optional safe migration rerun; startup already migrates:
docker compose exec -T api node dist/migrate.js
```

Use `curl` on Unix. Health returns HTTP 200 `{"status":"ok"}` or HTTP 503 `{"status":"unavailable"}` when PostgreSQL is unavailable. It proves connectivity, not migration state. Both published ports bind to loopback; the API listens on all interfaces only inside its container. Do not expose this stack to the internet or alter bindings for phone access.

Migrations: `001_foundation` and `002_bookkeeping`. Tables include `sales`, `receipts`, `receipt_allocations`, `expense_entries`, `expense_items`, `financial_operations`, `financial_revisions`, `sync_state`, `sync_changes`, plus Kysely tracking/lock tables. Migration 002 is forward-only; append corrective migrations and register them in `backend/src/migrations.ts`. Never change applied migrations or delete records for a rollback.

Enabled financial routes require `Authorization: Bearer <private-development-token>`: `POST /v1/operations`; `GET /v1/records/{id}`, `/v1/changes`, `/v1/daily?date=YYYY-MM-DD`, `/v1/monthly?month=YYYY-MM`. See OpenAPI for payloads, cursor pagination and errors.

## Backend scripts

With Node 22 selected, from the root:

```powershell
npm --prefix backend ci
npm --prefix backend run typecheck
npm --prefix backend run build
npm --prefix backend test
npm --prefix backend run test:integration
```

If PowerShell blocks `npm.ps1`, use `npm.cmd` instead; no execution-policy change is necessary. Integration tests require the configured development PostgreSQL. The connectivity test reapplies/checks migrations in the configured database; financial tests create and remove only their own fresh random schema. Never point tests at production. Coverage includes exact totals, partial/platform receipts, atomic rollback, corrections/voids, idempotency, concurrent clients and ordered pull.

For hot reload:

```powershell
docker compose stop api
docker compose up -d db --wait
npm --prefix backend run db:migrate
npm --prefix backend run dev
```

Host scripts load root `.env`; keep `HOST=127.0.0.1`. Compose constructs its internal URL from `POSTGRES_*`; host scripts use `DATABASE_URL`. Editing `.env` cannot change credentials already in a volume. Correct existing credentials deliberately; do not delete the volume.

## Android build and physical device

Open `android/` in Studio and select the installed SDK/JDK. Let Studio create ignored `android/local.properties`, or put your actual SDK path in `sdk.dir`. Use forward slashes and escape a Windows drive colon (`C\:/...`). Select a compatible installed JDK through Studio or process-local `JAVA_HOME`; no global changes or copied personal paths are needed.

For sync, add `bookkeeping.devToken=<private-development-token>` to that ignored file, matching `.env`. Debug embeds this token in its APK and fixes the URL at `http://127.0.0.1:3000`; rebuild after changing it. Empty token disables sync; release always disables it. Debug/validation permit cleartext only for 127.0.0.1. Arbitrary LAN endpoints are unsupported.

From `android/`:

```powershell
.\gradlew.bat assembleDebug testValidationUnitTest lintDebug '-Pandroid.builder.sdkDownload=false'
```

On Unix use `./gradlew`. The actual unit task is **`testValidationUnitTest`**, not `testDebugUnitTest`, because `testBuildType` is `validation`. APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Reports: `android/app/build/reports/`.

From the repository root, with an authorized, unlocked physical phone/tablet:

```powershell
adb devices -l
# Select ANDROID_SERIAL if multiple devices are attached.
adb reverse tcp:3000 tcp:3000
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n id.basokanggito.app/.MainActivity
```

The phone's localhost belongs to the phone. ADB reverse forwards it to the computer's loopback API without LAN exposure. Recreate mappings after reconnection. WorkManager also requires a connected network. Check the enabled API flag, matching APK token and in-app status. Inspect existing records before test writes; never uninstall/clear storage to solve an installation/signing error.

`MainActivity` opens `FunctionalNotebook` with Room `bookkeeping.db`, outbox and metadata/drafts. No sample records are seeded. `TestSamples.kt`/`PrototypeApp.kt` are separate debug/validation fixtures. Saved records and drafts are separate; Room saves work offline. WorkManager requests work after save/startup plus 15-minute periodic work, subject to Android scheduling. Failed records require explicit review. This does not guarantee recovery/backup.

Warm Slate & Terracotta remains in `ui/BasoTheme.kt`; English/Indonesian, light/dark, 48 dp targets and 56 dp actions remain. Navigation switches at 600 dp; summary/history at 840 dp content width; forms/details stay within 640 dp. Functional-screen large text, TalkBack, parent usability and physical Xiaomi acceptance remain rollout checks.

## Isolated device tests

Existing tests use separate `id.basokanggito.app.validation` and isolated Room databases, with automatic WorkManager sync disabled. They must not clear/uninstall the real notebook. Start PostgreSQL, enable the private development token, and keep the physical device awake/unlocked.

In another terminal from `backend/`, with Node 22 and dependencies installed:

```powershell
node --env-file=../.env --import tsx test/device-server.ts
```

This helper listens only on `127.0.0.1:3001` and creates its own `device_test_*` schema. Tests block every acknowledgment for a selected record until explicitly released, so automatic HTTP retries cannot bypass the lost-response proof. It does not seed the main ledger. With `--container` it listens on the container interface; publish that port only with `127.0.0.1:3001:3001`. From the root:

```powershell
adb reverse tcp:3001 tcp:3001
cd android
.\gradlew.bat connectedValidationAndroidTest '-Pandroid.builder.sdkDownload=false'
```

The generated task is **`connectedValidationAndroidTest`**, not `connectedDebugAndroidTest`. Tests target real entry/correction, database reopen, offline queueing, lost responses, two Room clients and conflicts. Prototype tests separately cover language, dark/large text, keyboard and simulated expanded width; they do not prove these on all functional screens or a tablet. Test source coverage is not proof of a successful run.

Stop the helper cleanly from `backend/` to remove its own schema:

```powershell
node --env-file=../.env --import tsx test/device-server.ts --stop
adb reverse --remove tcp:3001
```

If interrupted, inspect leftover test schemas rather than resetting the database. Never drop unknown schemas/volumes.

## PostgreSQL backup and isolated recovery

With Node 22 and the Compose database running, choose a new file in an existing protected directory **outside this checkout and outside the database volume**:

```powershell
$backupFile = Read-Host 'Full path for a new backup file outside the checkout'
node backend/scripts/backup.mjs backup $backupFile
if ($LASTEXITCODE -ne 0) { throw 'Backup failed' }
node backend/scripts/backup.mjs restore $backupFile baso_restore_check
if ($LASTEXITCODE -ne 0) { throw 'Isolated restore failed' }
```

`backup` streams a consistent `pg_dump --format=custom` snapshot through Docker, publishes only after success, and refuses to overwrite an existing archive. An optional final argument selects a source database for disposable recovery drills. `restore` creates a new database and uses `pg_restore --single-transaction --exit-on-error`; it refuses the configured live database and any existing target. An unsuccessful restore can leave its newly created empty database for inspection. Never remove an unknown database to retry. The destination filesystem must support hard links for atomic archive publication. Restrict filesystem access and keep a protected copy on another computer/storage device; encryption, retention scheduling and remote storage are not automated here.

The existing `npm --prefix backend run test:integration` now also needs the Docker CLI/Compose connection. Its recovery test creates synthetic source/restore databases and an archive outside the checkout, then verifies exact sales/receipts/shopping totals, correction revisions, operation identity/replay, cursor continuation and migration reruns. It also verifies overwrite refusal and corrupt-restore rollback, and cleans only its own randomly named resources. For an actual recovery drill, inspect `kysely_migration`, financial records/totals and `financial_operations`/`financial_revisions` in the isolated database before considering any live switch.

**Restoring an older backup is not automatic client reconciliation.** Stop writes, preserve the original database and every device's Room/outbox data, and account for all changes after the backup. Existing devices may have newer cursors and acknowledged records that are absent from the restored server. Do not simply point them at an older restored database; rollback reconciliation remains a separate recovery procedure.

Android records, drafts, recovery snapshots, pending operation IDs/bodies and scheduled work survive the tested database/process restart boundaries. Unsynchronized records still exist only on that device: losing it before upload loses those records. Android cloud backup remains disabled. Before family rollout, require a reviewed user-controlled encrypted export/restore of Room records, outbox, drafts and sync metadata, or an explicitly accepted policy that never relies on an unsynchronized device as the sole record. Do not copy live SQLite files without handling their WAL/transaction consistency.

The staged process test can be repeated after installing `installValidation` and `installValidationAndroidTest` from `android/` and starting the isolated helper:

```powershell
adb shell am instrument -w -r -e class id.basokanggito.app.NotebookProcessTest -e stage seed id.basokanggito.app.validation.test/id.basokanggito.app.NotebookTestRunner
adb shell am force-stop id.basokanggito.app.validation
adb shell am instrument -w -r -e class id.basokanggito.app.NotebookProcessTest -e stage verify id.basokanggito.app.validation.test/id.basokanggito.app.NotebookTestRunner
```

Only the validation package is stopped; the real notebook is untouched. This tests committed restart boundaries and durable scheduled work, not reboot, week-long outages, battery restrictions or execution at every WorkManager backoff deadline.

## Shutdown and troubleshooting

```powershell
docker compose down
```

This preserves the project volume. Never use `down -v` or clear app storage for routine shutdown. Persistence is not a tested backup.

- Docker pipe/engine errors: start Desktop, wait for its Linux engine, rerun startup; preserve unrelated resources.
- Port bind error: Windows can reserve an apparently idle port; change both PostgreSQL `.env` port values.
- Health/migration failures: check database readiness, credentials and migration results. Keep logs private; do not print `.env` or resolved Compose secrets.
- Node engine warning: select Node 22.x for host scripts. Compose runs its own Node 22 independently.
- SDK/JDK error: select actual installed paths; do not install SDK packages implicitly.
- Missing debug test tasks: use validation tasks; inspect `gradlew.bat tasks --all`.
- Stale SDK path lint error: escape the drive colon; force reanalysis with `lintDebug --rerun-tasks` if needed.
- Device offline/no Compose hierarchy: reconnect, authorize, unlock and recreate reverse mappings; preserve data.
- Sync failure/conflict: check flags/token/API/forwarding/network constraints and review retained operations. Reinstallation is not recovery.

## Milestone 1.1 reliability verification

Verified on 26 October 2026 against base revision `78e6ab21ea273a2df5b8cb5610d270251164fbf9`, preserving the preceding home-setup documentation cleanup:

| Check | Result |
| --- | --- |
| Node 22 backend typecheck/build | Passed |
| Backend unit / PostgreSQL integration tests | 8 / 17 passed; includes backup/restore and legacy timestamp compatibility |
| Android debug build / JVM tests | Passed / 9 passed |
| Android lint | 0 errors, 13 warnings |
| Physical Samsung SM-S942B, Android 17 | Complete suite: 19 passed; final data/recovery compatibility rerun: 6 passed |
| Separate process-stop proof | Seed and verification each passed after stopping only the validation package |

The original one-response-drop assertion was transport-dependent: buffered POSTs can be transparently retried, so one lost response need not make synchronization return failure. The deterministic test gate now blocks every acknowledgment until explicitly released. Tests verify server commit while the same operation remains pending, database/process restart, identical retry, no duplicated money, and acknowledgment/reconciliation. No financial assertions were removed. Device lock/doze caused separate visibility failures; test windows now keep the screen on without changing global settings. A redundant screenshot-only keyboard-close request was removed after it timed out following successful financial assertions.

Two independent Room databases on one physical Samsung create offline records in both directions, synchronize to the same isolated PostgreSQL schema, retain pending local edits during remote pulls, detect stale edits, recover authorization failures and converge on the same chronological history. This is not testing two physical devices or production identity.

Failed-edit reopening commits the local correction, server snapshot and reason before archiving old operations, atomically. Both transaction rollback and interruption before UI draft persistence are tested. Simple recovered payments retain identity, amount, date and method. Complex payment chains, differing server/local payments and queued voids remain intact for explicit review when the existing form cannot represent them; reopening must not invent payments or resurrect a voided record. Automatic merging/rebasing of those cases is not implemented.

History is newest-first by business date, creation timestamp, then stable ID, including receipts. Corrections retain creation time. Existing PostgreSQL timestamps provide canonical server chronology; unsynchronized timestamps are provisional and may reorder on first acknowledgment. A one-time bounded pull replay upgrades older cached timestamps without decreasing the durable cursor or rewriting operation bodies/hashes/results. Legacy replay responses preserve already-known timestamps. No financial dates, financial rules or applied migrations changed; Room's schema remains version 1.

The backup drill restored synthetic sales, receipts, expenses, corrections, audit history, operation replay results and migration tracking into disposable databases, and verified cursor continuation and failure detection. Test schemas/databases were cleaned; no development/production ledger was reset. See the backup section for operational limits, particularly old-backup/client reconciliation and unsynchronized device loss.

Remaining rollout work: reviewed encrypted Android export/restore, protected off-computer backup retention and restore drills, handling post-backup client state, Xiaomi/TalkBack/parent acceptance, and production authentication/device ownership before remote use. Reboot, prolonged outages, Doze/battery restrictions and execution at exact WorkManager retry intervals remain unverified. WorkManager request persistence was tested across process stop; it is not a guarantee of background timing. Release sync remains disabled. The original design document's introduction is historical; use its financial invariants/acceptance criteria alongside these actual results.

No new business features, production deployment, dependency upgrades, commits or pushes are part of Milestone 1.1.
