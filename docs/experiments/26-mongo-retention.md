# 26 — Seven indexes that did not exist, and a retention policy that fails open

**Phase 9c.** `MongoIndexes`, `tools/security/settlement-retention.sh`

---

## Hypothesis

The criterion is *"TTL demonstrably expires raw payloads"*, and the phase text
says *"TTL enforcement on raw payloads in Mongo (indexes from phase 8)"*.

**This system has no raw-payload collection.** The ledger stores structured
projections of events; nothing anywhere persists a raw Kafka message or a raw
webhook body. So the criterion as literally worded has no subject, and inventing
a collection in order to expire it would be building a component to delete it —
which is the exact inverse of the rule this project runs on.

What Mongo does hold that is genuinely raw third-party input is `settlement_line`:
lines from a provider's settlement file, carrying their references and amounts.
Once reconciled it is disposable and re-ingestible from the source file. That is
where a retention control belongs. The `journal` deliberately gets none — it is a
financial record that must be kept for years, and a TTL on it would be a
data-loss feature wearing a compliance badge.

The prediction was that this would be a small piece of work: add a date field,
add a TTL index, watch a document vanish.

## Setup

```
tools/security/settlement-retention.sh
```

The drill inserts three documents into one batch and waits:

| document | `ingestedAt` | expected |
|---|---|---|
| `expired` | 30 days ago | removed — retention is 7 days |
| `fresh` | now | survives |
| `nofield` | *absent* | ? |

Mongo's TTL monitor sleeps 60 seconds between passes, which would make this a
two-minute wait for a property that is either true or false, so the drill turns
it down to 3 for the run and restores it afterwards. That changes how *often*
expiry is checked, never *whether* a document is eligible.

## Actual result

**The first thing the drill printed was that no index existed at all.**

```
db.journal.getIndexes()          ->  [ { _id: 1 } ]      47,452 documents
db.settlement_line.getIndexes()  ->  [ { _id: 1 } ]
```

`JournalEntry` and `SettlementLine` have carried `@Indexed` since phases 6e and
8 — including `@Indexed(unique = true)` on `JournalEntry.eventId`, written as the
guard against at-least-once redelivery. Spring Data MongoDB has defaulted
`auto-index-creation` to false since 3.0. **Every one of those annotations has
been decoration since the day it was written.**

After creating them explicitly:

```
 1. THE INDEX EXISTS - checked, not assumed
   settlement_line:
     _id_                      {"_id":1}
     ix_settlement_batch       {"batchId":1}
     ix_settlement_payment     {"paymentId":1}
     ttl_settlement_ingested   {"ingestedAt":1}   expireAfterSeconds=604800
   journal:
     _id_                      {"_id":1}
     ux_journal_event          {"eventId":1}
     ix_journal_payment        {"paymentId":1}
     ix_journal_recorded       {"recordedAt":1}
   ok   settlement_line has a TTL                            604800
   ok   journal deliberately has none                        none

 2. THREE DOCUMENTS, THREE FATES
   ok   the 30-day-old line was expired                      0
   ok   the fresh line survived                              1

 3. THE DOCUMENT THE POLICY CANNOT SEE
   ok   a line with no ingestedAt is NOT expired             1
     lines with no ingestedAt: 7 of 14
```

And the reconciliation job, measured before and after — the same job, the same
data, the only change being that the indexes now exist:

| | aggregation |
|---|---|
| no indexes (the state since phase 8) | **64 ms** |
| with the indexes | **30 ms / 35 ms** |

## What surprised me

**A unique index that was never unique.** `@Indexed(unique = true)` on
`JournalEntry.eventId` was written as the guard against duplicate delivery — the
documented, expected behaviour of the pipeline feeding this collection. It did
not exist, so for the entire life of the project there has been nothing stopping
a duplicate journal entry.

Checked before fixing:

```
total docs      : 47452
distinct eventId: 47452
duplicate groups: 0
```

Zero duplicates, and **that is luck rather than design**. The consumer checks
MySQL first and writes the journal only when MySQL says the event is new, so the
*ordering* has been doing the deduplication the whole time. Two consumer
instances racing between that check and this write would produce a duplicate that
nothing catches. Creating the unique index on 47,452 existing documents succeeded,
which is itself the proof that no duplicate was ever written.

The money is not affected — `LedgerPosting` deduplicates against MySQL, which
does have its constraint. What was unguarded is the read model.

**Experiment 22 explained its own measurement wrongly, and I wrote it.** That page
concluded *"It is fast because `SettlementLine.paymentId` carries `@Indexed`,
which was written for exactly this and is the difference between a report and an
overnight job."* The index did not exist. The 43ms was real and the reason was
invented: the aggregation is driven from the settlement side, so a three-line
batch means three passes rather than 28,668 — the small driving set was doing the
work I credited to an index. Corrected in place rather than quietly edited,
because the interesting part is that a confident causal claim survived a review
that only checked the number.

**An index that exists only as an annotation is worse than no index.** No index at
all is a known gap. An `@Indexed` in the source is a claim that somebody handled
it, and it survives every code review, because the reviewer's eye lands on the
annotation and stops. Which is why `MongoIndexes` **logs the indexes that exist**
rather than only creating them — the claim becomes checkable in a log line
instead of a source file.

**Retention fails open, and it fails open on exactly the wrong documents.** A
document whose TTL field is missing, null, or not a date is not expired by
Mongo — it is *skipped*. Not overdue, not queued, not reported. Silently, forever.
The drill found **7 of 14** settlement lines in that state: every line written
before `ingestedAt` existed. A retention claim would therefore have been false
for precisely the oldest data, which is the data most likely to be the subject of
the request that prompted the policy.

**The backfill's timestamp source mattered more than the backfill.** The obvious
fix sets `ingestedAt = now()` on the affected documents. It is one character
shorter and it grants every historical line a *fresh full retention period* —
turning a data-retention fix into a data-retention extension. A MongoDB
`ObjectId` encodes the second it was generated, so `$toDate` on `_id` recovers
when the document was really inserted. The 6 backfilled lines came back stamped
`10:52` that morning rather than with the clock at startup.

**64ms to 30ms is worth having and is not the story the original text told.** The
index roughly halves the aggregation. Experiment 22 implied the difference
between "a report and an overnight job". Both numbers are fine; the honest
statement is that this dataset is too small for the index to be load-bearing yet,
and it will be the difference at a scale this project has not reached.

## Standing questions

- **The TTL is 7 days and that number is a guess.** Long enough that a dispute
  raised the next working day still has the batch, short enough that this system
  is not an indefinite archive of another company's file. Nobody with authority
  over retention policy chose it.
- **Nothing alerts on documents with no TTL field.** The backfill fixes today's;
  a future code path that inserts without `ingestedAt` reintroduces the same
  silent gap, and the only thing that would catch it is running this drill again.
  A `countDocuments({ingestedAt: {$exists: false}})` gauge is the obvious control
  and is not built.
- **`vault_access_log` has no retention at all.** Flagged in experiment 25 and
  still true. It is MySQL rather than Mongo, so this work does not touch it.
- **The unique index closes a race that was never observed.** Duplicate journal
  entries have not happened. The guard is now real, and whether the race is
  reachable in this deployment — one consumer instance — is not established.
