# Baso Kang Gito

Android-first bookkeeping for an Indonesian family bakso business. The development app saves customers, sales, Bon payments, shopping, corrections and voids in Room, calculates daily/monthly recaps, and optionally synchronizes with a local Fastify/PostgreSQL API. English and Bahasa Indonesia work offline.

**Not ready for sole family bookkeeping.** Trusted installation access, local PINs and encrypted recovery are implemented. Protected backup retention, family-device recovery drills, deployment hardening and independent security review remain rollout gates. Release sync requires an explicitly configured HTTPS endpoint. Use synthetic development records until rollout is reviewed.

## Architecture

- `android/`: Kotlin/Compose/Material 3, one app module; Room, durable outbox and WorkManager.
- `backend/`: Node 22, TypeScript, Fastify 5, Kysely, node-postgres; existing Node tests in `test/`.
- `compose.yaml`: local API and PostgreSQL 17, with named persistent volume `baso-kang-gito_postgres_data`.
- [OpenAPI](docs/openapi.yaml): implemented routes/contracts. [Buku Harian](docs/buku-harian.md): original design and acceptance criteria; its sample-only introduction is historical and does not describe the current code.
- `AGENTS.md`: agent instructions, separate from developer prerequisites.

Sales and receipts have separate dates/totals. Platform amounts are net after fees; settlements never create another sale or fee expense. Shopping totals derive from items. Business dates use Asia/Jakarta; audit timestamps use UTC. Exact integer rupiah travel as decimal strings. Received minus shopping is a cash difference, not profit or a complete cash balance.

Server transactions commit financial rows, operation UUID/hash/result, audit before/after snapshots and ordered change cursors together. Expected versions reject stale writes; voids retain tombstones. Android commits its local snapshot/outbox together, retries the same operation ID/body, acknowledges then pulls changes, and retains conflicts for review. Device authentication surrounds this protocol without changing financial operation identities. Synchronization is not a backup.

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

The API always protects financial routes with trusted-device credentials. `ENABLE_DEV_BOOKKEEPING=true` and a private `BOOKKEEPING_DEV_TOKEN` are used only by the isolated port-3001 test helper. The regular server never accepts that shared token. See Trusted device access below.

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

Migrations: `001_foundation`, `002_bookkeeping`, `003_customers`, `004_device_access`. Financial tables include `customers`, `sales`, `receipts`, `receipt_allocations`, `expense_entries`, `expense_items`, `financial_operations`, `financial_revisions`, `sync_state`, `sync_changes`, plus Kysely tracking/lock tables. Business migrations are forward-only; append corrective migrations and register them in `backend/src/migrations.ts`. Never change applied migrations or delete records for a rollback.

Financial routes require `Authorization: Bearer <installation-uuid>.<installation-secret>`: `POST /v1/operations`; `GET /v1/records/{id}`, `/v1/changes`, `/v1/daily?date=YYYY-MM-DD`, `/v1/monthly?month=YYYY-MM`. See OpenAPI for payloads, cursor pagination and errors.

Customers and Bon share one Android destination: search by name/phone, view outstanding balances and sale/payment history, create/edit/archive customers. Ordinary paid sales still allow a general customer or one-time name. New Bon requires an explicit Master Customer selection. Initial partial payments and later installments use receipts on the original sale; payment dates affect money received, never sales totals. Archived customers retain history and can still pay existing debts. Platform settlements stay outside customer Bon.

`save_customer` uses the same versioned operation/outbox/audit stream. `GET /v1/customers?q=...` lists customers with full balances; `GET /v1/customers/{id}` pages history. Sales carry a nullable UUID and a separate historical name snapshot. PostgreSQL migration 003 and Room migration 1→2 preserve old records/outboxes without guessing links. Existing unlinked sales can be explicitly linked through Edit entry. Current clients send `customerId` even when null; omission remains accepted for unchanged Milestone 1 operation retries. Upgrade all participating Android clients before customer synchronization: old APKs cannot read customer snapshots in the change stream. Conflicts preserve local input; review the entry/customer before retrying against the server version. A payment retry that exceeds the current balance remains rejected.

## Trusted device access and PIN

The Fastify API and PostgreSQL own device authorization independently of hosting. Migration `004_device_access` adds trusted devices, enrollment requests, one-time administrator tokens, security events and shared PostgreSQL rate counters. Business tables and the Room schema are unchanged. The regular server exposes protected financial routes regardless of the old development flag; it never accepts the legacy shared token.

After migrations, an operator with backend/database authority generates the first administrator token:

```powershell
npm --prefix backend run build
npm --prefix backend run access:bootstrap
```

For the local Compose API: `docker compose exec -T api node dist/bootstrap.js`. The command prints a 256-bit token once to the operator terminal, valid for ten minutes. Treat that terminal output as a secret. In Android, enter a device name and this token, then choose **Enroll administrator**. Installing first never grants authority. The database serializes consumption; another installation cannot replay it. An exact installation retry is safe after a lost response. The locally persisted credential can recover a completed enrollment through `/v1/access/me` even after the enrollment token expires. No operator token is embedded in the APK or saved by Android.

Additional devices choose **Register this device** without an operator token. Their encrypted installation proof is persisted before sending the request. A six-digit reference, name and full installation ID are displayed. The administrator opens **Tools → Device management**, verifies those details directly with the owner, enters the code and approves or rejects. Names are unverified requester input. Approval is bound to the exact installation proof, not merely the code. Requests expire after ten minutes; five incorrect code guesses reject that request. Initiation/bootstrap/polling have PostgreSQL rate limits across API processes. Complete provisioning then creates one standard trusted identity. Interrupted requests reuse their original ID/proof; expired or rejected pending requests need a new request. Approval does not silently activate financial access before completion.

Each installation generates 32 cryptographically random bytes and a UUID. This is equivalent in entropy to a server-issued random bearer: the server grants validity only through bootstrap or administrator approval, stores only a SHA-256 verifier, and never echoes the secret. TLS must protect all remote requests. Devices have only `admin` or `standard` roles. Both can bookkeep; only administrators can manage devices. Bearers remain valid until revoked; there is no second access/refresh-token system or automatic expiry. Replace/rotate a compromised identity by enrolling a fresh one and revoking the old identity. Keystore protection reduces offline extraction risk but does not provide cryptographic proof of possession; a stolen live bearer can be replayed until revoked.

Android stores the credential, enrollment session, salted PIN verifier and throttle state in an AES-GCM encrypted atomic file under `noBackupFilesDir`, using a nonexportable Android Keystore key. PIN verification uses PBKDF2-HMAC-SHA256 with 210,000 iterations and a fresh 32-byte salt. Six digits have low entropy; Keystore protection and persistent throttling are essential. After five failed attempts, delay starts at 30 seconds and doubles to a 15-minute maximum. Monotonic time ignores wall-clock changes; reboot restarts the current delay. Data is never deleted for guessing. This does not defend a rooted device, malicious modified APK or extracted process memory, and Room itself remains protected by the Android sandbox rather than database encryption.

Create/confirm the PIN after enrollment. Cold launches require it. A background stay of 30 seconds locks again; brief navigation and configuration changes retain the session. **Tools → Change PIN** requires the current PIN. PIN fields are masked, never saved as instance-state drafts, and secret derivation buffers are cleared where possible. The activity uses `FLAG_SECURE` for screenshots and recent-app previews. Existing financial drafts and document-picker registrations survive the background grace period; saveable notebook state is retained across the PIN gate.

Forgotten PIN or invalidated Keystore: use **Forgot PIN?** / recovery registration, obtain fresh administrator approval (explicitly labelled PIN recovery), then create a new PIN. Financial data is preserved. The previous identity must be revoked by the administrator. No unauthenticated PIN-reset bypass exists. If the only administrator is lost, the operator uses `npm --prefix backend run access:bootstrap -- --recover`, enrolls a replacement administrator, then revokes the lost one. Recovery issuance is audited and invalidates earlier unused operator tokens; it does not silently revoke other devices. The last active administrator cannot be revoked through the app.

**Offline and revocation:** enrolled devices unlock and record transactions locally without network access. Never-enrolled devices cannot open the notebook offline. Revocation rejects every subsequent backend authentication check; a request already authorized before revocation may finish. An offline device cannot discover remote revocation until it communicates. On detection, sync stops without marking pending operations as financial failures or deleting Room. PIN unlock then permits encrypted backup export or reauthorization; normal notebook access is blocked. Revoking an administrator also cancels their unfinished approvals. New authorization can replay the original pending operation IDs safely.

**Replacement and restoration:** restore the portable encrypted `.bkg` backup using the initial restore action, enroll the replacement installation, create its PIN, and choose **Open notebook and enable sync**. Restore is also offered after enrollment but before that final action. No pull occurs before this explicit decision, so enrollment cannot populate an empty database and block restoration. Already nonempty notebooks are never automatically replaced/merged. Backup allowlists contain financial snapshots, customers, operations and drafts only: no credential, Keystore material, PIN verifier, enrollment proof or administrator token. Restore resets pull cursors and preserves operation IDs, allowing processed operations and newer server snapshots to reconcile. Unsynchronized records lost with both a device and its latest backup cannot be recovered from the server.

**Deployment and recovery limits:** configure an HTTPS endpoint and reviewed TLS termination before remote use; this task does not deploy hosting or establish its TLS/proxy policy. The API does not trust forwarded IP headers, so a proxy/NAT shares rate quotas; review that policy during deployment. PostgreSQL is the authority on every authenticated request; no stale token cache exists. Security event rows contain event names and actor/target UUIDs, never secrets. Retain/protect database backups and audit data. An older PostgreSQL backup can roll back revocations: before exposing a restored server, an operator must revoke restored device identities, reject outstanding enrollments, expire bootstrap tokens and bootstrap fresh authorization. Do not treat a financial restore as proof that access metadata is current.

### Milestone 2.2 verification

Fresh verification on 27 October 2026, on the uncommitted working tree based on clean `main` revision `1f562a1a187d53fd2361446ab99ad06c8ccb758e`:

| Check | Result |
| --- | --- |
| Node 22.23.4 backend typecheck, test typecheck and build | Passed |
| Backend unit / PostgreSQL integration tests | 9 / 36 passed; 0 failed, 0 skipped; includes forward migration/rerun and real PostgreSQL backup/restore |
| Android `assembleDebug` / `testValidationUnitTest` | Passed / 25 passed, 0 failed, 0 skipped |
| Android `lintDebug` | 0 errors, 14 warnings |
| Physical Samsung SM-S942B, Android 17 | Full validation suite: 54 passed, 0 failed, 0 skipped |
| Focused lifecycle regression / PIN visual test | 1 / 1 passed; English light and Indonesian dark at 2× text visually inspected |
| Separate access process-stop proof | Seed and verify each passed, stopping only the isolated validation package |

The physical suite exercised bootstrap, administrator pairing approval, interrupted enrollment, PIN setup/unlock/change, persistent throttling, revocation with pending-money preservation, portable backup without authorization cloning, authenticated financial replay, and native document pickers. The staged `DeviceAccessProcessTest` retained its credential, PIN verifier and exact pending operation across force-stop, then synchronized twice without duplicating the receipt. A verified draft restoration race during recreation was fixed by reading current Room metadata before initializing saved forms; rotation, brief backgrounding and a 31-second lock retained the draft. Security review also checked protected reads/writes, stale credential callbacks, revocation of an approving administrator, single-use races, rate limits and secret exclusion. No additional material verified defect remained after fixes.

Two isolated clients on one Samsung are not two physical devices. Offline paths used unavailable endpoints/local operations, not an airplane-mode drill. Dedicated execution of a revoked WorkManager worker, hardware Keystore invalidation, reboot, wall-clock manipulation, prolonged outages/Doze/battery restrictions, TalkBack, Xiaomi/tablet and parent acceptance remain unverified. Independent security review, reviewed deployment/TLS/proxy policy, protected backup retention and operator recovery drills remain rollout requirements. Passing tests are not production-security certification. No real notebook was cleared or replaced; no hosting deployment, commit or push was performed.

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

Debug uses `http://127.0.0.1:3000` and requires enrollment; no shared token is embedded in its APK. For release, set `bookkeeping.apiUrl=https://your-reviewed-api-host` in ignored `local.properties`; an empty URL disables connectivity. Remote access requires TLS. Validation uses isolated port 3001; `bookkeeping.devToken` configures only its private test-helper controls. Debug/validation cleartext is limited to loopback.

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

The phone's localhost belongs to the phone. ADB reverse forwards it to the computer's loopback API without LAN exposure. Recreate mappings after reconnection. WorkManager also requires a connected network. Check the configured endpoint, device authorization and in-app status. Inspect existing records before test writes; never uninstall/clear storage to solve an installation/signing error.

`MainActivity` opens `FunctionalNotebook` with Room `bookkeeping.db`, outbox and metadata/drafts. No sample records are seeded. `TestSamples.kt`/`PrototypeApp.kt` are separate debug/validation fixtures. Saved records and drafts are separate; Room saves work offline. WorkManager requests work after save/startup plus 15-minute periodic work, subject to Android scheduling. Failed records require explicit review. This does not guarantee recovery/backup.

Warm Slate & Terracotta remains in `ui/BasoTheme.kt`; English/Indonesian, light/dark, 48 dp targets and 56 dp actions remain. Navigation switches at 600 dp; summary/history at 840 dp content width; forms/details stay within 640 dp. Functional-screen large text, TalkBack, parent usability and physical Xiaomi acceptance remain rollout checks.

## Isolated device tests

Existing tests use separate `id.basokanggito.app.access.validation` and isolated Room databases, with automatic WorkManager sync disabled. They must not clear/uninstall the real notebook. Start PostgreSQL, enable the private development token, and keep the physical device awake/unlocked.

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

Android records, drafts, recovery snapshots, pending operation IDs/bodies and scheduled work survive the tested database/process restart boundaries. Unsynchronized changes made after the latest usable Android backup still exist only on that device. Android cloud backup remains disabled. Keep an encrypted copy outside the device and rehearse restoration before family rollout. Do not copy live SQLite files without handling their WAL/transaction consistency.

### Encrypted Android backup and recovery

Open **Tools → Backup & restore** (Indonesian: **Alat → Cadangkan & pulihkan**). Enter and confirm a unique recovery passphrase of 12–512 characters, choose **Create encrypted backup**, then choose a location with Android's document picker. The suggested name is `Baso-Kang-Gito-Backup-YYYY-MM-DD.bkg`. Success appears only after the encrypted destination has been closed and read back with matching length and SHA-256 digest. Keep the file outside the original device and keep its password separately. A forgotten password cannot be recovered; the local app PIN is unrelated. The last verified export time does not prove the file remains available or restorable.

To recover, install a compatible app on a fresh or safely empty workspace, choose **Choose backup to restore**, select the file and enter its password. Review the authenticated creation date, app/format/schema versions, record/customer/operation counts and draft status, then explicitly confirm. Existing records, archived/voided records, any operation history or meaningful unfinished work block replacement. There is no delete-to-restore button or automatic merge. Keep the existing device and its backup; do not clear its data to bypass this guard.

Format **1**, logical payload **1**, Room schema **2** are supported. The authenticated binary header contains the app version, creation time, KDF parameters, a fresh 16-byte salt and 12-byte nonce. AES-256-GCM authenticates the header and payload with a 128-bit tag. PBKDF2-HMAC-SHA256 uses 600,000 iterations; readers accept 600,000–1,000,000. This uses the platform crypto implementation without new dependencies. Unsupported format/schema/KDF settings are refused. The logical payload is capped at 64 MiB, each field at 2 MiB and each table at 100,000 rows; a notebook exceeding these limits requires a future format change. Encryption streams bytes, but logical snapshots and some GCM providers use memory proportional to the bounded payload.

The snapshot includes customers, financial records with items/receipts, server snapshots, **all** pending/failed/acknowledged/reviewed operations, exact operation bodies and prior snapshots, recoverable forms, payment recovery chains and conflict state. A Room transaction captures a coherent point in time, including committed WAL changes. Local writes and sync are coordinated with the same repository locks. Passwords stay in memory only; credentials, debug tokens, preferences, arbitrary metadata and WorkManager scheduling are excluded. Password-derived keys are not stored. Backups are portable using the passphrase, without the original Android Keystore.

Restore authenticates into private temporary storage, checks payload structure/relationships, then rechecks local emptiness and inserts everything in one Room transaction while synchronization and local writes are excluded. Insertion failure rolls back the entire transaction. It keeps the live Room connection rather than replacing SQLite files. Temporary files are deleted on cancellation, failure, completed restoration and next process startup after interruption; Android flash storage does not guarantee forensic secure erasure. SAF cannot atomically publish a destination with every provider: failed exports attempt to delete the incomplete document and otherwise tell you to remove it manually. Provider read-back verifies completed bytes, not future retention or remote durability.

Stable financial UUIDs, operation UUIDs, sequence, expected versions and exact request bodies survive recovery. Restore resets the download cursor for full reconciliation and clears stale sync status. On the next explicitly configured sync, the existing server recognizes already-processed operations and returns their original result; newer server snapshots are pulled, and conflicting unfinished edits require review. An older backup is never presumed newer than the server. This Android recovery procedure differs from restoring an older **server** PostgreSQL backup, which still requires the reconciliation precautions above.

If the original device is lost, only data included in a usable backup or already on the server can be recovered. Restoring financial data does **not** authorize backend access: enroll the replacement installation, establish its PIN and explicitly enable notebook synchronization as described above. Two isolated Room clients on one Samsung demonstrate portability between installations; they do not establish Xiaomi or two-physical-device compatibility.

Verified on 27 October 2026 on the uncommitted Milestone 2/2.1 working tree based on `81acfef`: backend typecheck/build, 9 unit tests and 25 PostgreSQL integration tests passed; Android debug build and 22 JVM tests passed; lint reported 0 errors and 14 warnings. Physical Samsung SM-S942B (Android 17) passed the complete 44-test installed validation suite, followed by all 5 backup UI tests after the final theme-surface correction. Actual system document pickers exported, read back, opened and restored an encrypted test document. Tests covered 11 backup data scenarios, including raw snapshots/recovery history, concurrent writers, corrupt/wrong-password/incompatible backups, invalid relationships and operation/snapshot mismatches, safe-empty refusal and late writes, transaction rollback, old-backup replay of customer/sale/payment/correction operations, repeated retries and a later version conflict. Replay retained one sale and the original two receipts (Rp 60,000 received); no extra sale/payment was created. The native picker test used an empty notebook; separate encrypted data/replay tests used synthetic financial records.

An additional `BackupProcessTest` seed → force-stop **only** `id.basokanggito.app.access.validation` → verify run passed both stages, preserving restored customer/payment/draft data and exact operation IDs, bodies, prior snapshots and sequences. Its owned fixture is deleted after verification. To repeat, install the validation and validation-test APKs, then use the staged commands above with `id.basokanggito.app.BackupProcessTest` in place of `NotebookProcessTest`. The test runner keeps its own window awake during data-only tests; it changes no global device setting. English light, Indonesian dark, large-text and expanded-width simulations were checked; screenshots were visually inspected. These simulations are not physical-tablet testing. Xiaomi, two physical devices, TalkBack, older Android versions, reboot/prolonged outages, and cloud/provider retention remain unverified. Test schemas and owned documents were cleaned; the real notebook was not cleared or replaced.

The staged process test can be repeated after installing `installValidation` and `installValidationAndroidTest` from `android/` and starting the isolated helper:

```powershell
adb shell am instrument -w -r -e class id.basokanggito.app.NotebookProcessTest -e stage seed id.basokanggito.app.access.validation.test/id.basokanggito.app.NotebookTestRunner
adb shell am force-stop id.basokanggito.app.access.validation
adb shell am instrument -w -r -e class id.basokanggito.app.NotebookProcessTest -e stage verify id.basokanggito.app.access.validation.test/id.basokanggito.app.NotebookTestRunner
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
- Sync failure/conflict: check enrollment/PIN/access state/API/forwarding/network constraints and review retained operations. Reinstallation is not recovery.

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
