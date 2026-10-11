# NOTES

## What I changed

**The feature.** `campaign.per_user_limit INT NOT NULL DEFAULT 2`, read through a new
field on the `Campaign` entity, enforced in `VoucherService.redeem` by counting rows in
the `redemption` ledger for `(campaign_id, user_id)` and rejecting at or above the limit.

The limit lives on the campaign because the brief says each campaign defines it. There is
no migration tool on the classpath and `schema.sql` is re-run on every boot, so the
`DEFAULT 2` is what makes existing campaigns inherit the default. In a real deployment the
equivalent is `ALTER TABLE campaign ADD COLUMN per_user_limit INT NOT NULL DEFAULT 2`.
The Java field carries the same default so a `Campaign` built in code is never born with
an accidental limit of 0, which would block every redemption.

`data.sql` is untouched, so the seeded campaigns exercise the default rather than
hand-picked values. Custom limits are covered by test fixtures in a high id range.

**The concurrency work.** The guard on its own is a check-then-act, and `@Transactional`
alone would not have fixed it: counting rows takes no lock on rows that do not yet exist,
so under `READ_COMMITTED` two callers can both pass the per-user check before either
inserts. That is write skew, and the isolation level permits it by design.

`redeem` is now `@Transactional` and takes `SELECT ... FOR UPDATE` on the campaign row
before reading anything it will make a decision on. One lock covers all three invariants,
because serialising a campaign's redemptions protects the stock counter, the per-user
count and each voucher's single use together.

The ordering was the subtle part. Keeping the voucher status check above the lock would
have left single-use broken: two callers both read the voucher as `ACTIVE` before either
locked, so the loser would mark an already-redeemed voucher again. Re-reading the voucher
inside the lock is not enough either, because Hibernate hands back the stale managed
instance already in the persistence context rather than the committed row. Hence
`findCampaignIdByCode`, a projection that locates the campaign without loading the entity,
so the one entity read happens under the lock.

Also replaced `findById(...).get()` with an explicit `"Campaign not found"` failure,
turning a `NoSuchElementException` 500 into a handled response.

**The audit call.** Moved behind `@TransactionalEventListener(AFTER_COMMIT)`. Once `redeem`
holds a row lock, an inline call to a remote host with no timeouts would hold that lock for
as long as the host hangs, blocking every redemption in the campaign. After commit, the
lock is released first, and an audit failure can no longer roll back a redemption the
caller was already told succeeded. The listener also logs the exception, which the previous
`catch` discarded.

This does **not** make the call non-blocking. `AFTER_COMMIT` callbacks run synchronously on
the committing thread, so the request thread still blocks for the full duration, which is
unbounded. See the limitations below.

**Tests.** 12 passing. Deliberately not `@Transactional`: the audit hook fires on
`AFTER_COMMIT`, so in a rolled-back test it would never fire and every "no audit call"
assertion would pass vacuously whatever the production code did. Real commits, explicit
fixture cleanup, `AuditClient` mocked throughout.

Beyond the limit cases, the suite covers: rejection leaving stock, voucher state and the
ledger untouched with no audit call; the limit being per campaign; a voided redemption
still counting; audit visibility from a separate `REQUIRES_NEW` transaction, which can only
succeed if the commit already happened; and two concurrency cases - six threads on
different vouchers as one user against a limit of 2, and six users contending for a single
voucher.

## Assumptions

- **A voided redemption still counts against the limit.** The `redemption` table is an
  append-only ledger; `voidVoucher` does not remove rows. The alternative - counting only
  vouchers currently `REDEEMED` - would make voiding a way to refill your allowance. This
  is visible in the seed data, where `user-99` holds two redemptions on campaign 1 (one
  `REDEEMED`, one `VOID`) and is therefore already at the default limit.
- Scope is per campaign, not per client and not global.
- It counts vouchers, not value, with no time window.
- The limit is data on the campaign. No endpoint to manage it, since the brief does not ask
  for campaign management.
- `userId` remains an untrusted query parameter with no auth. A per-user limit on a
  caller-supplied identity is trivially bypassable; out of scope, but worth saying.
- Failures keep returning HTTP 200 with `result: "FAILED"`. Making this one case a 409
  while every other failure stays 200 would be worse than either consistent choice.

## Noticed, deliberately not changed

**`AuditClient`.** A hardcoded audit URL and API key (`pzm_audit_7f3a91c4`) committed in
source, and a `RestTemplate` built with no connect or read timeout. This is the thing I
would fix first in real work. I moved the call out from under the lock because the lock
made it urgent, but deliberately stopped short of rewriting the client: timeouts, config
externalisation and secret handling are a change of a different shape from this feature,
and bundling them would have made the diff hard to review.

**`voidVoucher`.** Takes no lock, so it can still interleave with a redemption in flight.
It cannot cause a double redemption, but it can void a voucher mid-redeem and leave stock
inconsistent. It also voids an already-`REDEEMED` voucher without restoring stock, takes no
`userId`, and writes no audit event. Extending the lock to cover it pulls the whole write
path into scope.

**Stock versus voucher inventory - an observation, not a confirmed defect.** Campaign 1
declares `total_stock = 100` and `remaining_stock = 100`, but `data.sql` ships six voucher
rows for it, two of which already carry redemption history while `remaining_stock` sits at
its initial value. Two readings fit the code equally well: `data.sql` is an abridged sample
of a larger inventory, or `remaining_stock` is a counter maintained independently of voucher
rows that simply was not decremented in the seed. Nothing in the code distinguishes them,
and `redeem` never cross-checks the two. The intended relationship remains unclear. It does not
block the feature either way, because the per-user limit reads the ledger and never consults
stock.

**Smaller things, all left alone:** `CampaignStatsService` is N+1, one query per voucher, and
has the same `findById(...).get()` as redeem did; `voucher.code` has no unique constraint or
index though `findByCode` assumes one match; injection style is mixed, field `@Autowired` in
`VoucherService` and `VoucherController` against constructor injection in the stats classes;
timestamps use `java.util.Date`; there is no migration tool, so `schema.sql` plus
`spring.sql.init.mode: always` re-seeds on every boot.

## What would break on three instances behind a load balancer

**The datasource, before anything else.** `jdbc:h2:mem:campaigndb` is in-process memory, so
three instances are three independent databases, each dropped and re-seeded from
`schema.sql` + `data.sql` at boot. A redemption on instance A is invisible to B and C, so a
user hits their limit only if the balancer keeps routing them to the same instance - with
round-robin the effective limit becomes the configured limit times the instance count. The
same breaks single-use: one voucher can be redeemed once per instance. And
`/campaigns/{id}/stats` answers differently depending on who replies. No amount of locking
helps, because there is no shared state to contend over.

**Pointing them at a shared database is not safe as the code stands.** With
`spring.sql.init.mode: always` and a `schema.sql` that opens with `DROP TABLE`, a second
instance booting would drop the tables under the first, and a rolling restart would wipe
live data. A shared database needs the init scripts replaced with real migrations first.

**Only then do the races matter**, and that is what the campaign row lock addresses - for
`redeem`. The cost is a throughput ceiling: the lock serialises every redemption in a
campaign, including unrelated users, which is exactly the wrong shape for a flash sale. The
scalable alternative is a `user_seq` column on `redemption` with a unique index on
`(campaign_id, user_id, user_seq)`, letting the database reject the loser and contending
only between one user's own concurrent attempts. It costs a column, an index and business
logic driven by an exception, and it would still leave stock unprotected, so it was the
wrong trade for this exercise.

Nothing else here holds per-instance state: no caches, no in-memory counters, no scheduled
jobs that would fire three times.

## With another day

1. Timeouts on `AuditClient`, and delivery handed to a queue or an `@Async` executor so a
   hung audit host cannot hold a request thread. Externalise the URL and the key.
2. Flyway, and a shared-database configuration with the init scripts removed.
3. Verify the two concurrency tests actually fail without the lock, by reverting it on a
   scratch branch. Right now they demonstrate the invariants hold; they do not prove the
   races were reproducible here.
4. Replace the campaign-wide lock with the unique-index approach if contention ever matters.
5. A unique constraint and index on `voucher.code`.
6. Proper HTTP status codes across all failure paths, as one consistent change.
7. `remaining_stock` either derived from voucher rows or reconciled, once the sample-data
   question is answered.
8. Surface the limit and the user's remaining allowance in `CampaignStats` and
   `RedeemResponse`, so the storefront can tell a user why they were refused before they try.

## Reflections

**What did you get wrong first, and how did you notice?**
My initial gap was understanding the requirements and the existing Java/Spring code. I
needed explanations before I could assess the proposed change. I used AI for most of the
implementation and technical review, so I cannot claim its design mistakes as my own.
Through that review, I learned that a transaction keeps database changes together, but
does not by itself stop two requests from passing the same limit check.

**Which AI suggestion did you reject, and why?**
The draft notes said I had emailed to clarify the sample stock numbers. I had not sent
any email, so I corrected that claim and it was removed. Technical review also identified
overstated claims about the concurrency tests and audit timing, but I needed help to
understand those corrections. I did not independently discover those technical issues.

**What took you longest?**
I cannot confidently identify a single step as the longest. The part I repeatedly needed
help with was understanding the requirements and what the implementation did. Setting up
Java also required assistance. I ran the tests locally and shared the output for review,
but I still need to improve my ability to explain and evaluate the concurrency design
without assistance.

*These reflections describe my participation and learning; most of the code and technical
analysis were produced with AI assistance.*
