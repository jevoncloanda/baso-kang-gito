# Buku Harian: Milestone 0.5 design

Production workflow proposal only: no business migrations, API routes, durable financial forms or synchronization engine are implemented here. A separate sample-only adaptive UI prototype now illustrates the screens; its scope, tokens and validation are documented in the root README. It is not the Milestone 1 bookkeeping implementation.

## Confirmed amount basis

The family records **net earnings after platform fees** for GoFood, GrabFood and ShopeeFood (confirmed during this review). Preserve that workflow. Label the recap **Penjualan bersih** and platform input **Pendapatan bersih setelah potongan platform**. Langsung records the actual sale price after any customer discount. Do not call this gross turnover or accounting profit.

Platform fees already deducted from the entered net amount must not also be entered as Belanja. Do not invent gross sales or fees from a net-only figure. Future POS can add known gross/fee breakdowns and normalize into the same net measure. If POS knows only gross while fees remain unknown, show a pending net amount explicitly; review that extension before enabling platform POS, rather than silently summing gross with confirmed net records.

Sales date and receipt date are independent. Use one shop business timezone, provisionally Asia/Jakarta (WIB), rather than each phone's timezone. Store business dates as DATE and audit timestamps as UTC instants. Confirm the shop's timezone before importing real records. Amounts are positive whole rupiah, stored exactly as integers (PostgreSQL BIGINT / Android Long); never floating point. Define a bounded amount range and overflow-safe totals in implementation; transport large integers without JavaScript precision loss.

## Minimum relational model

One business, no full accounting ledger, product catalogue or master customer required. UUIDs are generated once on the device and retained through retries. Headers have `id`, `version`, `created_at`, `updated_at`, and nullable `voided_at`/`void_reason`. Children have stable IDs and belong to a header's version/atomic write.

| Table | Essential fields beyond shared metadata | Purpose |
| --- | --- | --- |
| `sales` | `business_date`, `channel`, `origin`, nullable `origin_ref`, `entry_kind`, `net_amount_rupiah`, optional `customer_name`, optional `note` | One recognized sale or one platform daily aggregate; the common reporting source for manual entries and future POS. |
| `receipts` | `received_date`, `amount_rupiah`, `method`, `purpose`, `origin`, nullable `origin_ref`, optional `payer_name`, optional `note` | Money actually received, independently of revenue recognition. |
| `receipt_allocations` | `id`, `receipt_id` FK, `sale_id` FK, `amount_rupiah`; unique `(receipt_id, sale_id)` | Apply one receipt to one or many sales; permit several partial receipts against one sale. |
| `expense_entries` | `business_date`, `kind`, optional `note` | One paid shopping session. `kind=business_expense`; future personal withdrawals use a distinct kind and are excluded from business expense totals. |
| `expense_items` | `id`, `expense_entry_id` FK, `name`, `amount_rupiah`, `position` | Named shopping rows. Session total is the sum of active rows, not a separately editable amount. |

`channel` is a constrained value: `langsung`, `gofood`, `grabfood`, `shopeefood`. `origin` identifies who created the financial record (`manual`, later `pos` or `event`), independently of channel. `entry_kind` distinguishes individual sales from daily aggregates. A POS GoFood order is therefore origin POS, channel GoFood, individual; a handwritten GoFood daily total is manual, GoFood, daily aggregate.

Use a unique `(origin, origin_ref)` when the source provides a reference. A POS source reference is its stable order ID; completing the order creates/updates the common sale in the same database transaction, not another "income" copy. Product/order lines can remain in a future POS module.

Allow only one active platform aggregate per channel/date. Editing it changes that record. Future individual POS sales and an aggregate covering those same sales must never both contribute to a recap: switch a channel to individual mode on an agreed business date, enforced transactionally. Source-ID uniqueness alone does not prevent aggregate/detail overlap. An aggregate replacement needs explicit reconciliation of linked receipts; never silently delete it or infer coverage from equal amounts.

Receipt methods initially need only `cash` and `transfer`. Purpose is `sale_payment` or `platform_settlement`; reserve `event_deposit` for the event workflow, not an unlabelled extra income button. Settlement is a receipt purpose, not a fifth sales channel. A multi-day platform payout can allocate to multiple daily sales without changing their dates. In the first slice, receipts may allocate to one selected sale; the relation already permits a later multi-sale settlement editor without redesign.

Customer names are optional snapshots; do not create a master customer just to save a sale. Add a nullable customer FK when customer management is actually built. Likewise, introduce bookings and a real booking link before allowing event deposits. Do not ship orphan deposits without a booking or customer context.

### Derived values and examples

- **Penjualan bersih:** active sales by sale business date, optionally grouped by channel. Never sum receipts into this figure.
- **Uang diterima:** active receipts by receipt date. This includes future deposits; applying a deposit later does not receive money again.
- **Belanja:** active business expense item sums by paid business date. Withdrawals remain separate. Shopping is paid immediately in this slice; supplier credit needs a later model.
- **Belum diterima:** sale net amount minus active receipt allocations, across dates. Distinguish customer receivables from amounts due from platforms by channel; no separate debt table is needed. Do not subtract only the selected day's receipts.
- Monthly recaps sum the same measures over business dates. Do not store duplicate daily/monthly totals. Do not call receipts minus spending a cash balance without opening balances and all money movements, or call sales minus shopping accounting profit.

| Scenario | Correct result |
| --- | --- |
| GoFood net earnings Rp200.000 on Monday; payout Tuesday | Monday sale Rp200.000, no Monday receipt. Tuesday receipt/allocation Rp200.000, no Tuesday sale. No second fee expense. |
| Customer buys Rp100.000, pays Rp40.000 now and Rp60.000 tomorrow | One sale Rp100.000, two dated receipts, remaining balance Rp60.000 then zero. |
| Later event workflow: deposit Rp500.000, completed sale Rp1.000.000 | Deposit is a receipt with no sale yet. On completion recognize one sale; allocate the existing deposit without another receipt. Remaining due Rp500.000. |
| Shopping: Daging Rp150.000 + Bumbu Rp25.000 | One expense session, two items, total Rp175.000. |

For every active allocation, lock and validate the affected receipt and sale in a transaction: allocated amounts cannot exceed the receipt or the sale's net amount. Positive receipts may retain unallocated amounts; display these explicitly as advances/unmatched funds, never negative debt or automatic revenue. A payout different from recorded net earnings requires reconciliation, not an invented fee or forced "Lunas" flag.

### Corrections, retry safety and synchronization

- Saved records are corrected by ID with an expected version and a retained before/after revision history. Keep the business date separate from correction time so historical recaps change transparently. A real return/refund is a new business event, not an erased sale; defer that workflow until scoped.
- Void mistaken records with a reason; keep tombstones for sync and omit voided rows from totals. Do not delete referenced sales/receipts or silently unallocate money. Correct related amounts/allocations atomically, or ask the user to correct the linked receipt first. No last-write-wins for money.
- Each create/edit/void gets a persisted operation UUID. Retry the same operation with the same payload and return its stored result; reject reuse with a different payload. Save deduplication/result metadata and all financial rows in one server transaction. Repeated taps, reconnects and lost acknowledgments must not create new receipts or sales.
- Android later uses Room with local record changes plus a durable outbox committed together. Retry pending operations in order per record; retain them until acknowledged. Server-assigned change sequence/cursor and tombstones support pull; client clocks do not decide ordering. Do not start with a general-purpose sync framework.
- A stale base version becomes a visible conflict for review, preserving the local draft and server version. Concurrent payments must not over-allocate a sale. Never mark a record synchronized before server commit/acknowledgment.
- Audit revisions, operation deduplication and the local outbox are support storage, not additional business ledgers. Review the small write contract, authentication and business ownership before any remote write/sync is exposed. No public unauthenticated financial API.

## First feature: screens and interactions

Product surface: a parent's working notebook, primarily on a Xiaomi tablet. Familiar dated entries and fast input take priority over charts, KPI cards or enterprise navigation. Reuse Compose Material 3, system light/dark support and safe insets. No new visual framework is needed.

| Screen | Minimum content and behavior |
| --- | --- |
| **Buku Harian** (home) | Today by default, previous/next day and date picker. Prominent **Catat Penjualan** and **Catat Belanja** actions. Compact text totals for Penjualan bersih, Uang diterima, Belanja; daily chronological history with channel, optional name, amount and outstanding status. **Rekap Bulanan** is a secondary link. |
| **Catat/Ubah Penjualan** | Numeric rupiah amount first; date defaults to selected day, channel defaults Langsung, customer name and note optional. Direct sales default to **Sudah diterima** with a same-date receipt and **Tunai** method (Transfer available); changing to **Belum diterima** saves no receipt. Platform entry explicitly asks for net daily earnings, defaults to **Belum cair**, and never invents a receipt. Saving a paid sale and its receipt/allocation is atomic. |
| **Catat/Ubah Belanja** | Selected date, one initial row with **Nama barang** and **Jumlah (Rp)**, **Tambah barang**, and always-visible calculated **Total belanja**. Save the whole session atomically. Do not require quantities, units, suppliers or categories. |
| **Detail catatan** | Tap a history row. Show sale/payment dates separately or all shopping lines. **Ubah**, **Batalkan catatan**, and for an unpaid sale **Catat uang diterima**. Receipt form defaults to remaining amount and today, permits a smaller partial amount, and rejects overpayment instead of silently changing the sale. Show receipt history and remaining due. |
| **Rekap Bulanan** | Month selector; exact sales, receipts and expense totals; sales split by the four channels and a simple daily list. Tap a day to open its notebook. No chart is required. |

Forms/detail are destinations within the notebook, not five top-level navigation tabs. Receipts are entered in sale context; customer/deposit dashboards are not required. An optional customer name must not imply a verified customer identity. For an unpaid direct sale, show a gentle **Tambahkan nama agar mudah dicari** hint without silently creating a customer.

Tablet: use a readable history pane with an adjacent entry/detail pane when available width allows; keep amounts aligned and primary actions near the work. Phone: use the same content in one full-width destination with normal Back navigation. Choose by usable width, not a hardcoded device model. No fixed-height forms or horizontal scrolling. Validate portrait and landscape before accepting the feature.

Use at least 48 dp touch targets (prefer 56 dp main buttons), 18 sp body/input text, visible labels, accessible names for row actions and TalkBack-readable rupiah/statuses. Format `Rp25.000` while retaining exact numeric input. Use a numeric keyboard, clear focus order and Next-to-next-row; keep the save/total area above the keyboard. Support 200% font scale without hiding controls. Status must not depend on color alone.

**Fast entry and mistakes:** only amount is mandatory for a normal direct sale; date/channel/payment have visible defaults. For shopping, each nonempty row needs name and amount; ignore a completely blank trailing row, highlight half-filled/zero/negative rows inline and preserve the draft on error. Adding a row focuses its name. Removing a row offers **Urungkan**, without a modal. Saving once returns to the same date, announces **Tersimpan**, and makes the next entry one tap away. Platform **Ubah total hari ini** opens the existing aggregate; never append another total for that day/channel.

Editing a sale does not silently rewrite receipt history. Show the linked money received separately; an erroneous immediate receipt can be corrected alongside the sale in one explicit, atomic edit. Reject reducing a sale below its valid allocations unless the linked correction is included. For a saved cancellation, show the precise effect inline (including affected receipt amounts) and an explicit cancel action; retain audit/undo data rather than a generic confirmation dialog. Any undo that would conflict with a newer revision must stop for review.

Back and app restart retain an unfinished local draft, separately from saved financial records; an explicit **Buang draf** action clears it. Empty history says **Belum ada catatan untuk tanggal ini** with entry actions. A failed save keeps inputs and presents **Coba lagi** without duplicating the operation. Local save works offline; show **Tersimpan di perangkat**, **Menunggu sinkronisasi** or **Perlu diperiksa** only when those states actually exist. Never claim cloud backup/sync in a local-only release.

## Milestone 1 slice and acceptance

Implement this notebook slice after the proposed model is reviewed: direct sales, platform net daily aggregates, paid shopping rows, basic later/partial receipts from a sale, edits/voids, and daily/monthly recaps. Use Room for durable local operation. Master customers, event/deposit screens, multi-sale settlement matching, POS, refunds, Excel export and a general sync engine remain separate slices. Do not market this first slice as the complete Phase 1 app.

A local-only pilot on one device can be useful first, but must be explicitly accepted as such: it is not multi-device safe or remotely backed up. Do not ship it as the family's sole financial record until a tested recovery/backup path exists. Before rolling out to several devices, implement the reviewed authenticated API/write contract and bounded outbox sync with conflict handling; do not allow independent unmerged notebooks to appear synchronized.

Acceptance conditions:

1. Save direct Rp25.000 with default payment: one sale and one receipt/allocation, including after a repeated tap/retry; no customer required.
2. Save GoFood net Rp200.000: sales increase, receipts do not. A later Rp200.000 receipt clears the balance without increasing sales; no duplicate fee expense. A second day-total entry opens the existing aggregate.
3. A Rp100.000 sale with receipts Rp40.000 and Rp60.000 on different dates yields one sale and the correct dated money/remaining balance. Over-allocation is rejected atomically.
4. Daging Rp150.000 + Bumbu Rp25.000 totals Rp175.000. Add/remove/undo rows, reject partial rows, and retain all rows after restart or failed save.
5. Corrections and voids update the appropriate day/month once, retain history, and do not silently lose payments. Dates crossing a month boundary use the shop timezone. Invalid/overflowing amounts cannot be saved.
6. Offline save/read/edit survives process death and reboot. Drafts are not counted in recaps. Any implemented sync survives lost acknowledgments, stale concurrent edits, duplicate delivery and restart without duplicate money.
7. Check totals using the examples above in unit/database tests; test financial atomicity and concurrency if server writes are included. Exercise the complete feature on the actual Xiaomi tablet and a phone, including 200% text, TalkBack, keyboard, portrait and landscape. Samsung shell testing is not tablet workflow validation.
8. Backend/Android required checks pass, and the chosen pilot's backup/recovery or synchronization boundary is documented and tested. No deployment is part of this milestone.

No unresolved business decision blocks this design after confirmation of platform net earnings. Before live use, verify the shop timezone, whether a "net" platform figure contains other adjustments, opening unpaid balances, and the family's first-device/backup rollout. These affect rollout/data reconciliation; do not invent fees, opening balances or historical sales. Exact future deposit/refund/POS rules can wait for their workflows.
