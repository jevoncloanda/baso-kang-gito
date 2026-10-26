# Baso Kang Gito

An Android-first bookkeeping application for a family bakso business in Indonesia. It will replace handwritten sales, payment and shopping records on phones and a Xiaomi tablet. Reliability, familiar Bahasa Indonesia workflows and offline use come first.

The foundation includes a database-backed API health endpoint, migrations, tests, local containers and an adaptive native Android UI prototype. The Android prototype uses labelled example data only. Production bookkeeping, authentication, sync, financial reports, exports and POS are not implemented.

## Stack and structure

```text
android/                 Kotlin, Jetpack Compose, Material 3; one app module
backend/                 TypeScript, Fastify 5, Kysely, node-postgres
  src/migrations/        Append-only database migrations
  test/                  Node test runner: unit and PostgreSQL integration
docs/openapi.yaml        API contract
docs/buku-harian.md      Proposed model, screen flow and Milestone 1 acceptance
compose.yaml             Local API and PostgreSQL 17
.env.example             Non-sensitive local examples
AGENTS.md                Instructions for future changes
```

Node 22 LTS matches the installed runtime (minimum 22.20). npm and the backend lockfile keep dependency installation reproducible. Kysely provides SQL-oriented queries and transactional, locked migrations without an ORM service. PostgreSQL 17 uses a persistent named volume. Containers use maintained Node 22/PostgreSQL 17 Alpine image tags; these receive patch updates and are not production image pins.

## Local development

Prerequisites: Node 22.20+ (22.x), npm, Docker Desktop with Linux containers and Docker Compose. Run these commands from the repository root, through RTK as required by `AGENTS.md`.

```powershell
Copy-Item .env.example .env
# Edit .env: replace the password in POSTGRES_PASSWORD and DATABASE_URL.
# Use a URL-safe password; URL-encode special characters in connection URLs.
rtk npm --prefix backend ci
rtk proxy docker compose up -d --build --wait
rtk proxy docker compose logs api
rtk proxy docker compose down
```

On this Windows setup, prefix **each shell invocation** with:

```powershell
$env:HOME='C:\Users\User'; $env:CLAUDE_CONFIG_DIR='C:\Users\User\.claude'
```

Compose runs migrations before starting the API. Visit `http://127.0.0.1:3000/health`: `200 {"status":"ok"}` means PostgreSQL accepted a query; `503 {"status":"unavailable"}` means it did not. This is a readiness check, not a guarantee of applied migrations. The unauthenticated API and PostgreSQL bind to loopback only. This Compose setup is for local development, not deployment.

For backend hot reload, run only the database in Docker (stop the container API first if already running):

```powershell
rtk proxy docker compose stop api
rtk proxy docker compose up -d db --wait
rtk npm --prefix backend run db:migrate
rtk npm --prefix backend run dev
```

`.env` is loaded by Node for local scripts. Compose derives its internal database URL from `POSTGRES_*`; host scripts use `DATABASE_URL`. Keep the password/database/user consistent. Changing `.env` does not change credentials in an existing PostgreSQL volume. Never delete that volume to fix credentials; update them deliberately in PostgreSQL. `docker compose down` preserves data; do not use `down -v` for routine shutdown.

If port 5432 is occupied, change `POSTGRES_PORT` and the port in `DATABASE_URL` together (the inspected machine uses 5433 for this project because `postgres-dev` already owns 5432). The container-internal database port remains 5432.

```powershell
rtk npm --prefix backend run typecheck
rtk npm --prefix backend run build
rtk npm --prefix backend test
rtk npm --prefix backend run test:integration
rtk proxy docker compose config --quiet
```

Integration tests require the local database and apply migrations without deleting records. They verify connectivity, migration tracking/retries and health. `001_foundation` performs a harmless query; Kysely creates only its internal tracking/lock tables. Add reviewed migration files in order and register them in `backend/src/migrations.ts`; explicit imports work on Windows and in compiled containers. Never modify applied migrations. The foundation has no business schema to roll back. Future schema changes need a reviewed rollback/data preservation plan.

## Android

Open `android/` in Android Studio. The project uses AGP 9.0.1 (built-in Kotlin 2.2.10), the matching Compose compiler plugin, Compose BOM 2026.02.00, Activity Compose 1.12.4 and Gradle 9.1.0. These fixed stable versions support SDK 36; Gradle 9.1 supports the installed JDK 25. JVM bytecode targets Java 17. Minimum device: Android 8/API 26; confirm the family's devices before lowering/raising it.

Required: JDK 17+ compatible with Gradle 9.1 (Android Studio's bundled JDK 25 is available here), Android SDK Platform 36, Build-Tools 36.0.0, and platform-tools. Set the SDK path in ignored `android/local.properties` (`sdk.dir=C:/Users/User/AppData/Local/Android/Sdk`) or select it through Android Studio. Configure the Gradle JDK in Studio; no global environment changes are necessary. The official Gradle wrapper pins and checksums its distribution; the first run downloads Gradle and Android build dependencies.

From `android/`, use the existing Studio JDK for this process (no global settings change). Keep SDK auto-download disabled when validating the already installed components:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
rtk proxy .\gradlew.bat help assembleDebug testDebugUnitTest lintDebug '-Pandroid.builder.sdkDownload=false'
# Only with a connected, unlocked physical device; use its serial from adb devices -l:
$env:ANDROID_SERIAL='<physical-device-serial>'
rtk proxy .\gradlew.bat connectedDebugAndroidTest '-Pandroid.builder.sdkDownload=false'
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`. JVM tests check sample input validation, overflow, totals, net-sale/receipt separation and text-role contrast. Instrumentation checks navigation, dynamic shopping rows/undo, draft restoration, resizing, large text and content states. No networking, local database, navigation framework or sync dependencies are added. Android backup is disabled until sensitive local-data handling is designed.

## Adaptive Android prototype

The app opens a clearly labelled **Sample mode · Not business records** notebook (Indonesian: **Mode contoh · Bukan catatan usaha**). It includes Daily Book, Add sale, Add shopping with dynamic rows and live totals, transaction details/editing, and a monthly recap with day drill-down. Date/month arrows explore the fixed October 2026 examples. Saving changes the example session only; platform daily totals replace the existing example for that channel/date. No real payments, API requests or database writes occur.

`ui/SampleData.kt` keeps illustrative records separate from production data. Drafts, selection and edited examples use Android saved-instance state (`rememberSaveable`) to survive configuration changes and supported task restoration. They are not durable bookkeeping, backup or a guarantee of persistence after force-stop/task removal. Closing a form retains its unfinished draft; a successful sample save clears it. Samples must be replaced by reviewed storage/financial rules before real use.

The small foundation stays within Compose Material 3:

| Role | Decision |
| --- | --- |
| Color | Warm Slate & Terracotta: ivory canvas `#F8F5F1`, white surfaces, slate primary `#303B53`, text `#253047`. Terracotta accent roles use accessible variants of `#BD6254`; muted gold is a restrained tertiary role. Dark uses deep slate canvas `#171B23`, surfaces `#222731`, and light slate functional color `#C2CBDD`. System bar icon contrast follows the theme. |
| Typography | Native Android sans; Material role scale, 18 sp body, 22 sp section titles, 36 sp main financial figure. System font scaling is preserved. |
| Space and shape | 8/16/24/32 dp spacing; 8/16/20 dp shape roles. No shadows/gradients or nested card scaffolding. |
| Interaction | Filled amount fields with digit grouping and exact raw integer values; compact shopping rows with stable IDs, add/remove/undo focus and IME Next progression. Material accessibility/behavior, 48 dp minimum icon targets, 56 dp primary actions, explicit labels and validation. |
| Reuse | Theme, spacing, outline vectors, amount display, entry input, transaction row and empty/loading/error states. No generic component framework. |

Adaptation follows **window width**, not device identity: below 600 dp uses a bottom bar and a single column; 600 dp and above uses a rail; at least 840 dp of content width splits summary/actions from history (or monthly channel totals). Forms and details are constrained to 640 dp. Forms scroll with IME insets and retain a visible total/save footer. Large text stacks compact value rows and shopping inputs. Both navigation destinations expose the same content and actions on every width.

The refined presentation uses one compact screen header and sample strip, slate selected navigation, a prominent net-sales summary with smaller received/shopping figures, paired entry actions when space allows, and denser history without decorative icon tiles. Filled fields replace repeated outlines and numbered shopping sections. Dark surfaces and accents are specified separately. Forms and summary values stack at large text sizes; totals/save remain outside the scrolling form above keyboard insets.

The brand palette is centralized in `ui/BasoTheme.kt` as Material roles, including surface/elevation, inverse, error and container pairs. Slate remains the dominant functional color; the existing sample strip carries a soft terracotta accent. The terracotta foreground is deepened to `#9F4D40` in light mode for readable small text; dark mode uses a lighter `#EDB3A6` accent. Gold has a tertiary role but is not added to screens without a meaningful use. Receipt/payment statuses keep explicit neutral text; error roles are independently red. This theme change preserves typography, spacing, navigation, form/input behavior, EN/ID localization and sample-only boundaries, with no component replacements or new dependencies.

English is the default development/review UI. `res/values/strings.xml` and `res/values-in/strings.xml` supply complete English/Indonesian labels, validation, accessibility text, plural counts and built-in sample names. Typed names/notes and technical identifiers are not translated. Dates follow the selected locale; amounts remain exact rupiah with Indonesian digit grouping in either language. The header's EN/ID menu changes language. Android 13+ uses native `LocaleManager` and declares both languages for system per-app settings; earlier versions use an appearance-only preference and activity recreation. Both languages are packaged together, including app bundles, without an on-demand translation dependency. Drafts survive language recreation; financial records are still sample state only.

Only `junit:junit:4.13.2` was added during the initial prototype, for local tests; this refinement adds no runtime dependencies. The existing Espresso 3.7.0 fix is retained. Backend and Docker are untouched.

### Warm Slate & Terracotta validation (26 October 2026)

`assembleDebug`, all 7 JVM tests and `lintDebug` passed. The expanded contrast test verifies text-role pairs at 4.5:1 and outline/focus pairs at 3:1 in both palettes. Lint remains at zero errors and the same 9 documented warnings; no new warnings or runtime dependencies were introduced.

All 12 physical Samsung SM-S942B / Android 17 tests passed, zero failures/errors/skips: the existing 10 interactions plus normal-size dark compact forms/error states and expanded navigation/recap. A first combined dark capture test could not display the rail after switching simulated density from a focused form; compact and expanded screenshots now use independent fixed-width setups. The original resize and actual landscape/recreation tests remain unchanged and pass. After test cleanup, APK installation returned Success and the final cold launch returned Status: ok.

Reviewed actual captures for contrast, restrained accent distribution, selected bottom/rail navigation, financial hierarchy, filled forms, input validation, Indonesian labels and 200% text. No theme-specific contrast issue was found in the checked role pairs or reviewed screens. Captures are ignored local build artifacts under `android/app/build/reports/warm-slate/`:

| Rendering | Light | Dark |
| --- | --- | --- |
| Compact | [Daily Book](android/app/build/reports/warm-slate/prototype-phone.png), [shopping](android/app/build/reports/warm-slate/prototype-expense.png) | [Daily Book](android/app/build/reports/warm-slate/prototype-dark-phone.png), [sale](android/app/build/reports/warm-slate/prototype-dark-sale.png), [validation](android/app/build/reports/warm-slate/prototype-dark-sale-error.png) |
| Expanded | [Daily Book](android/app/build/reports/warm-slate/prototype-expanded.png) | [Daily Book](android/app/build/reports/warm-slate/prototype-dark-expanded.png), [recap](android/app/build/reports/warm-slate/prototype-dark-recap-expanded.png) |

Expanded width and enlarged text are Compose overrides on the Samsung, not physical-tablet verification. TalkBack/switch access, a Xiaomi tablet, older Android devices and color-vision-deficiency simulation were not checked. Backend/Docker are unchanged and were not rerun. No deployment, commit, push or global device-setting change occurred; the app remains sample-only.

### UI/UX refinement validation (historical, 26 October 2026)

| Check | Actual result |
| --- | --- |
| Build and JVM tests | `assembleDebug` passed; `testDebugUnitTest`: 7 passed, zero failures/skips. Includes cursor-offset mapping for grouped amounts and English/Indonesian resource/format-argument parity. |
| Lint | `lintDebug` passed, zero errors, 9 warnings: the original 7 plus API-33-only `localeConfig` and lint's legacy `in` versus BCP-47 `id` mismatch. The pre-33 selector fallback is implemented; Indonesian resources and native switching were verified on the Samsung. No lint suppression or dependency upgrades were added. |
| Physical tests | `connectedDebugAndroidTest`: 10 passed, zero failures/errors/skips, Samsung SM-S942B / Android 17. Retains the original seven cases and adds Indonesian rendering, native EN→ID→EN switching with a draft, and shopping IME Next/add/remove/undo focus at 200% text. |
| Installation/launch | After test cleanup, `adb install -r` returned Success and `am start -W` returned Status: ok / COLD. The verified debug APK was left installed. |
| Screenshot review | Reviewed compact history, sale/shopping/detail/recap, expanded layout, real landscape, dark 200% text, and Indonesian sale/shopping/history. No clipping of primary controls observed; long forms scroll and shopping rows stack. |

Before/after captures are ignored local build artifacts:

| Screen | Before | After |
| --- | --- | --- |
| Daily Book | [Before](android/app/build/reports/before-refinement/prototype-phone.png) | [English](android/app/build/reports/prototype-phone.png), [Indonesian](android/app/build/reports/prototype-phone-id.png) |
| Shopping | [Before](android/app/build/reports/before-refinement/prototype-expense.png) | [After](android/app/build/reports/prototype-expense.png), [200% dark](android/app/build/reports/prototype-large-expense.png) |
| Expanded width | [Before](android/app/build/reports/before-refinement/prototype-expanded.png) | [After](android/app/build/reports/prototype-expanded.png) |

The first nine-case refinement run passed. A subsequent ten-case run had six “No compose hierarchies found” visibility failures; ADB showed the phone awake/unlocked during diagnosis, so the trigger remains unconfirmed. Repeating the unchanged ten-case suite passed. No product workaround, global screen setting or weakened assertion was used. Earlier lint errors from reading localized resources through `LocalContext` were corrected by using Compose's observable `LocalResources`/`LocalConfiguration` APIs.

Expanded width and 200% text use Compose test overrides on the physical Samsung, not a physical tablet or emulator. Actual activity landscape/portrait recreation and native language switching retain the draft. No Xiaomi tablet, pre-Android-13 device, TalkBack/switch-access pass, real freeform resizing, parent usability session or exhaustive keyboard/device matrix was available. The first real bookkeeping milestone still needs durable storage, recovery and reviewed financial behavior; this APK remains sample-only. No deployment, commit or push occurred.

### Initial adaptive prototype validation (historical, 26 October 2026)

| Check | Result |
| --- | --- |
| Debug build | `assembleDebug` passed. |
| Local tests | `testDebugUnitTest`: 5 passed, including exact/overflow-safe shopping totals, invalid inputs, sample sales versus receipts, and at least 4.5:1 contrast for tested text-role pairs in both palettes. |
| Lint | `lintDebug` passed with zero errors and the same seven pre-existing warnings (tool/dependency/target updates, backup/transfer rules, app icon). Introduced state/modifier/unused-resource warnings were corrected. |
| Physical tests | 7 passed, zero failures/skips on Samsung SM-S942B / Android 17: navigation, saved-state restoration, dynamic shopping rows/undo, recap drill-down, 200% text/dark theme, state recovery, compact/expanded resizing, and real activity landscape/portrait recreation. |
| Installation/launch | Final `adb install -r` returned Success; `am start -W` returned Status: ok / COLD. The sample prototype was left installed and open after test cleanup. |
| Screenshots | Device captures in ignored `android/app/build/reports/prototype-{phone,sale,expense,detail,recap,expanded,large-dark,large-form,landscape}.png`. Visually reviewed the screen set and confirmed system-bar contrast after the correction. |

Expanded screenshots use a Compose test density override on the physical Samsung, not a physical tablet or emulator. Landscape was exercised through the real MainActivity, with form values retained after activity recreation. Screen orientation requests were restored, and the test-only keep-screen-on window flag changes no phone timeout setting. One run failed because the phone auto-locked during review; the final run passed after unlocking. An initial instrumentation-test compile issue used a nonexistent bounds-height property; using the supported bottom-minus-top calculation fixed it.

Accessibility checks covered theme-role contrast, 200% text rendering, reachable primary actions with a 48 dp target assertion, labelled controls/amount semantics, scrollable forms, and a visible save action while typing in the large-text test. This is not a full TalkBack or switch-access audit. The Xiaomi tablet, physical split-screen/freeform resizing, exhaustive keyboard combinations, long-record performance and every font/device combination remain unverified.

Before functional development, try the flow with the parents on the Xiaomi tablet, perform a TalkBack pass, review real financial correction/payment semantics, and replace sample saved-state storage with the reviewed Room/API design plus tested recovery. Do not remove the sample notice or use this APK as the family ledger. No backend/Docker checks were rerun; no deployment, commit or push occurred.

At Milestone 0 the required SDK packages were absent. They are now installed through the user's Android Studio setup: Platform 36 revision 2, Build-Tools 36.0.0 and platform-tools 37.0.1, with Gradle 9.1.0 already cached. This validation reused ignored `android/local.properties` pointing to `C:/Users/User/AppData/Local/Android/Sdk` and bundled OpenJDK 25.0.3; no SDK packages were installed or global settings changed by the agent.

The Samsung SM-S942B runs Android 17 and connects through wireless ADB. The first instrumentation attempt exposed Compose's transitive Espresso 3.5.0 calling removed `InputManager.getInstance()`. `android/app/build.gradle.kts` now explicitly selects Espresso 3.7.0, aligned with the existing test runner; application dependencies and the test assertions are unchanged. A subsequent attempt found no Compose hierarchy while the phone was asleep/locked; physical UI tests require an awake, unlocked screen.

PowerShell must quote `'-Pandroid.builder.sdkDownload=false'`; an initial unquoted invocation was split into an invalid Gradle task argument before checks ran. This was a command-line quoting problem, not a project configuration failure.

Milestone 0.5 lint had no errors and seven warnings: SDK 36 is not the newest target, newer Gradle/Compose/Activity versions exist, explicit Android 12+ backup/transfer rules are absent, and an application icon is absent. A native dependency (`libandroidx.graphics.path.so`) was packaged without debug-symbol stripping; the debug build succeeded. Dependency upgrades and branding are not necessary to validate this shell. Review backup/transfer exclusions before storing real financial data.

## Milestone 0.5 validation (26 October 2026)

| Check | Actual result |
| --- | --- |
| Gradle configuration and dependency resolution | Passed through `help` and the build; Android Studio's UI Sync action was not separately exercised. |
| `assembleDebug` | Passed; APK at `android/app/build/outputs/apk/debug/app-debug.apk`. |
| `testDebugUnitTest` | Completed `NO-SOURCE`; zero JVM tests exist, so this is not a unit-test suite pass. |
| `lintDebug` | Passed: zero errors, seven warnings described above. |
| Physical installation and launch | `adb install -r` returned Success; `am start -W` returned Status: ok on Samsung SM-S942B / Android 17. |
| `connectedDebugAndroidTest` | Passed on the unlocked physical Samsung after the Espresso fix: one test, zero failures/errors/skips. No emulator used. |
| Rendered shell | Inspected the physical-device screenshot in `android/app/build/reports/device-shell.png`; Indonesian text renders without clipping in portrait dark mode. Tablet, landscape, TalkBack and enlarged-text behavior were not exercised. |

The connected-test runner cleaned up the installed application after testing; a subsequent launch returned `Activity class ... does not exist`. Reinstalling the built APK restored it, and the final cold launch returned Status: ok. The debug app was left installed and open. This cleanup is not a production-app failure. The HTML test report is `android/app/build/reports/androidTests/connected/debug/index.html`; the lint report is `android/app/build/reports/lint-results-debug.html`. These are ignored build artifacts, not committed documentation.

Only an explicit test dependency was added to fix the observed Android 17 compatibility problem. Backend/Docker checks were not rerun because their implementation is unchanged. No financial schema, business UI or sync was implemented; no deployment, commit or push was performed.

## Milestone 0 validation (historical, 26 October 2026)

- Passed: backend dependency installation/lockfile, TypeScript source and test type checks, build, five unit tests, and one real PostgreSQL integration test.
- Passed: Compose configuration validation, multi-stage image build (including `npm ci`), healthy database/API startup, compiled migration command and repeat execution.
- Passed: live Postman assertions for HTTP 200, expected HTTP 503 during a controlled database outage, and recovery to HTTP 200. Each request passed one assertion; Postman exits 1 for the expected non-2xx response even when its assertion passes.
- Passed: migration history persisted after restart; only `kysely_migration` and `kysely_migration_lock` exist in the public schema. The existing `postgres-dev` container was preserved.
- Passed: OpenAPI syntax lint. Postman governance lint requires login and was not run; syntax lint needs no account.
- Passed: official Gradle wrapper JAR SHA-256 verification and availability of the selected Android artifacts in Google's Maven repository. Android compilation, lint and instrumentation tests remain unverified because required SDK packages are missing; Gradle/build dependencies were not downloaded.

The ignored local `.env` was created with a generated password and host database port 5433. Both project containers were left running and healthy. No commits, pushes, production deployment or global configuration changes were made.

## Buku Harian design and next milestone

The family confirmed that platform entries use **net earnings after platform fees**. The proposed [Buku Harian model and screen flow](docs/buku-harian.md) keeps sales, dated receipts/allocations and shopping sessions/items separate. It documents the minimum schema, financial/sync invariants, practical Bahasa Indonesia interactions, Milestone 1 boundaries and concrete acceptance examples. This is a proposal, not an implemented business schema or feature.

Future manual/QR POS must feed the same financial reporting model. Keep one lightweight backend suitable for the eventual 2 GB/1 vCPU VPS; production deployment remains out of scope.
