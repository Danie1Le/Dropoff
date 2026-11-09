# How AI was used

Claude Code wrote the code, tests, docs and most issue text in this repo. The owner chose what to build, reviewed every
PR before merge, and answered a quiz at the end of each one (the questions are in `docs/milestones/M2.md` to `M8.md`; M0 and M1 have no guide). This page
says how that worked, how the output was checked, which mistakes the checks caught, and where the checks stop.

Every link below points at a pull request thread you can read.

## Tools

- **Claude Code**, in the terminal, with a project `CLAUDE.md` that points at `PROJECT_OVERVIEW.md`, `GLOSSARY.md` and
  the ADRs in `docs/adr/`, so each session starts from the same domain language and decisions.
- **Sub-agents**, which Claude Code starts for one job each: build one item, review one PR, fix one review.
- **Skills** (small instruction files the agents load): `tdd` for building, `code-review` for reviewing.
- **GitHub Issues and PRs** through the `gh` CLI. Tickets, review comments and fix replies all live there, so the
  evidence is in the repo's history and not in a chat log.
- Everything runs locally and costs nothing: Docker for Postgres, Redis and Kafka, Gradle for the build.

## The loop

One item from `docs/PLAN.md` becomes one small PR. The full process is in PLAN's "How every PR works"; this is the short version. Three roles handle it, each a fresh agent with no memory of the
others:

1. **Builder.** Reads the ticket and the decisions it cites, writes the failing tests first, then the code. It makes
   `./gradlew build` pass (Spotless, Error Prone, NullAway, unit tests, integration tests), ticks `docs/roadmap.html`,
   and opens the PR with the quiz questions.
2. **Reviewer.** Checks out the PR head, runs the build, then runs the `code-review` skill. The skill reviews on two
   axes: *Standards* (does the code follow `CODING_STANDARDS.md`?) and *Spec* (does it do what the ticket and PLAN
   say?). Findings are posted as one PR review, with inline comments tagged `[S#]` (standards), `[P#]` (spec) or `[B#]`
   (build or bug risk). The review also lists what the reviewer checked and found sound, so you can see what was
   examined and not only what failed.
3. **Fixer.** Fixes every comment, adds one new commit (no force-push, so threads stay readable), and replies on each
   thread with the commit sha and what changed, or a concrete reason for declining. It writes a failing test first when
   the comment is about behaviour.

The owner then reviews the PR and merges it. Review comments and replies are posted through the owner's GitHub account,
so on GitHub they all show the same author name.

## How output was checked

| Check | What it is good at | What it misses |
|---|---|---|
| Tests, written first | Pins the behaviour a ticket asks for; the fix commits below each add a test that was red before the fix | Only cases someone thought of |
| Build gates (Spotless, Error Prone, NullAway) | Style, null bugs, common Java mistakes | Anything that compiles and is wrong |
| CI integration suite on real Postgres, Redis and Kafka (Testcontainers) | Real SQL, locks and Kafka behaviour | Timing the test does not force |
| `code-review` on every PR, two axes | Spec drift, races, comments that disagree with the code | Whatever the reviewer shares a blind spot on (see Limits) |
| Owner review and quiz | The decisions: scope, trade-offs, whether the PR is the right size | Details the owner did not read line by line |

## Mistakes the review caught

These are real findings, each fixed in the same PR before merge. Most were fixed test-first, and the thread replies name the test.

### Late pickup race (M3-4, PR [#92](https://github.com/Danie1Le/Dropoff/pull/92))

A courier picks up an order, then goes offline before dispatch has processed the pickup event. Dispatch still thinks
the order is `ASSIGNED`, so it puts the order back to `WAITING` and offers it to a second courier. The first courier
then delivers, and the second stays `BUSY` forever. The reviewer
[traced this step by step](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505859916) and suggested making the
order's `PICKED_UP` status win. The fix restores the row when the late pickup arrives, withdraws the other courier's
offer (a new `WITHDRAWN` status) and locks rows in one fixed order.

A second review pass of the fix found three more holes, each with its own thread:
[delivery arriving before the late pickup](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505905976),
[a courier left holding two orders](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505905978), and
[a retry loop that ignored deadlock failures](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505905980).
It also caught that the fixer's explanation of *why* two steps need separate transactions was
[wrong](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505905981) (the real reason is lock order, not
self-blocking). The fixer agreed and
[corrected the comment](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505921277).

### Out-of-order copies (M5-3, PR [#108](https://github.com/Danie1Le/Dropoff/pull/108))

order-service keeps a local copy of merchants and menu items, updated from Kafka. The first version overwrote the copy
with whatever arrived last, so a closed merchant could reappear as open if two changes were handled out of order
(async delivery, a restart republish, concurrent edits, a replayed dead-letter record). The reviewer
[showed the paths that reorder events](https://github.com/Danie1Le/Dropoff/pull/108#discussion_r4507264160). The fix
adds a `version` column, bumps it under a row lock, carries it in the event, and makes the copy's upsert apply only a
newer version. A new test sends version 2 then version 1 and checks the copy keeps 2
([reply](https://github.com/Danie1Le/Dropoff/pull/108#discussion_r4507294124)).

### Two active orders (M4-2, PR [#98](https://github.com/Danie1Le/Dropoff/pull/98))

The courier location endpoint looked up the courier's active order with a call that fails if two rows match. A courier
can legitimately hold two (an earlier fix, ticket #85, allows it on purpose), so every location update would have
returned a 500 and rolled back the saved location. The reviewer
[found it](https://github.com/Danie1Le/Dropoff/pull/98#discussion_r4506719310) by reading the earlier ticket's Javadoc
against the new code, and warned against the obvious wrong fix (a unique index would have broken #85). The fix reads a
list, publishes one event per order, runs after commit, and adds an index. A new test with a courier on two orders was
red before the fix ([reply](https://github.com/Danie1Le/Dropoff/pull/98#discussion_r4506738157)).

### Histogram range (M6-1, PR [#116](https://github.com/Danie1Le/Dropoff/pull/116))

The dispatch-time timer used Micrometer's default histogram range of 1 ms to 30 s. Real waits run for minutes, so the
p95 shown in Grafana would have been capped at 30 s, far below the waits near the 15-minute alert threshold in #13. The reviewer
[spotted the default](https://github.com/Danie1Le/Dropoff/pull/116#discussion_r4507749222). The range is now 1 s to
30 min ([reply](https://github.com/Danie1Le/Dropoff/pull/116#discussion_r4507765466)). The same review also found that
a gauge kept its last value when its database query kept failing
([comment](https://github.com/Danie1Le/Dropoff/pull/116#discussion_r4507749225)), which would hide an outage and keep the 15-minute alert from firing. A failed load now reads as NaN (test `aFailedFirstLoadReadsAsNaN`).

### Dedupe atomicity (M5-1, PR [#106](https://github.com/Danie1Le/Dropoff/pull/106))

The Kafka consumers record each event id so a duplicate is skipped, and the docs said the id commits in the same
transaction as the work. For pickup and delivery events the work runs in its own transaction, so a crash between the two
commits would run the work twice. The reviewer
[pointed out](https://github.com/Danie1Le/Dropoff/pull/106#discussion_r4506970445) that this is safe only because status
guards make a re-run do nothing, and that the docs claimed more than the code did. The fix corrected three docs and added
a test that deletes the dedupe row, redelivers the event and checks nothing changes
([reply](https://github.com/Danie1Le/Dropoff/pull/106#discussion_r4507001947)). The same review found that the
duplicate-skip test
[could not fail](https://github.com/Danie1Le/Dropoff/pull/106#discussion_r4506970450), because it asserted a row count
the primary key already guarantees. It now checks the log output.

## What people decided

- **What to build and in what order.** The owner set the scope and milestones in `PROJECT_OVERVIEW.md` and
  `docs/PLAN.md`. A final review pass over the finished plan was a gate before any code (the commit
  `docs: build plan for M0-M8 (gate passed)`).
- **Architecture decisions** are in `docs/adr/` and in GitHub decision issues. Be aware that several decision issues
  carry the note "Decided by Claude on the user's delegation ... Pending user review in the final plan"
  (for example [#36](https://github.com/Danie1Le/Dropoff/issues/36) and
  [#37](https://github.com/Danie1Le/Dropoff/issues/37)): the owner delegated those, and approved them through the final
  plan review, not one by one.
- **Departures from the plan went to the owner.** When the service images came out over the 300 MB limit set in #36
  (305-315 MB on the stock Java runtime, as recorded in the [#36 amendment](https://github.com/Danie1Le/Dropoff/issues/36#issuecomment-6727514165)), the builder measured it, proposed a `jlink`-trimmed runtime on plain Alpine, and
  wrote in the PR body "Please review this change from the planned base image"
  ([PR #118](https://github.com/Danie1Le/Dropoff/pull/118)). It was merged by the owner, and #36 and the plan were
  amended to record it.
- **Merge decisions and quizzes.** The build guides make the owner's review and the quiz the last step before merge. The
  quiz answers are not stored in the repo.

## Limits

- **The reviewer is also an AI.** It is a fresh agent with fresh context, which helps, but it is the same kind of tool as
  the builder. A wrong assumption both share will pass. The reviews found real bugs, but a clean review is not
  proof of correctness.
- **Review comments are not independent human evidence.** All review threads post through one GitHub account. This page
  does not claim any specific bug that only the owner's own reading found, because the PR history does not record that.
- **Tests only cover what someone wrote down.** The M3-4 race needed three review rounds, which says the first design
  and its first tests missed cases. The deadlock retry in #92 has no test, because a real deadlock cannot be forced
  reliably in an integration test ([reply](https://github.com/Danie1Le/Dropoff/pull/92#discussion_r4505921147)).
- **Local runs are partial.** The author's machine is low on memory, so locally only `./gradlew build -x integrationTest`
  plus the touched integration tests run. The full integration suite runs in GitHub CI.
- **The AI can be confidently wrong.** The fixer's wrong explanation in #92 is one example. The docs in this repo were
  checked against the code in review (several findings above are comments that disagreed with the code), but some may
  still be stale.
- **Scope is deliberate.** The known gaps (no auth, no payments) are decisions in the plan, not things the AI missed.
- **Nothing here is production-tested.** It runs on a laptop with Docker, and the performance numbers in
  `docs/perf/README.md` come from that laptop.
