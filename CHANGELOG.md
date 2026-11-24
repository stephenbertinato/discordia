# Changelog

## 1.2.0 — 2026-11-24

### The page-0 and SkyCofl lanes were silent, and `NO_ANCHOR` blamed the market

This is the fix for "I got a few flips when I joined and nothing for ten minutes".

A full scan prices a listing against a book that **contains** it. A page-0 scan, and a SkyCofl fast
lane, price the same listing against a **cached** book that does not. The anchor rule demanded
exactly one copy at the listing's price and treated a missing next price as "no flip", so in the
live lanes:

- the candidate's own entry was absent, so it never looked like the cheapest copy at its price;
- `resaleAnchor` returned 0 for **every** underpriced listing — that is, for every listing that
  could ever have been a flip;
- the live lanes could therefore never announce anything, however long you played.

The only flips you ever saw were the ones from the first full book. After that the feed was dead
by construction, and the reason codes reported a healthy-looking `NO_ANCHOR` for all of it.

Fixed by making the anchor lane-aware:

- `resaleAnchor` now takes the cached pool **and** the prices the current scan saw, and
  `candidateInPool` says whether the cached book already holds this auction. It returns 0 only
  when a rival copy really is cheaper, or really does sit at the same price.
- The pool **count** behind the listing floor and the confidence score counts listings the cached
  book has not seen yet, so a 6-listing book no longer looks like 5 listings to a live lane.
- `NO_ANCHOR` (another copy beats this listing) and `NOT_CHEAPEST` (it is the only copy, with
  nothing above it) are now separate codes, so the two failures are distinguishable.

Verified live, and asserted offline: `AlgorithmGuardProbe` gains a lane-parity section — a page-0
scan and a full scan must agree on both the buy price and the target, two copies at one price in
one live scan must both be refused, and a live listing above the cached cheapest must be refused.
67 checks, `verify.sh` exits 0. Three consecutive page-0 scans against a 36,000-listing book went
from **0 flips before the fix** to a real flip after it.

### Faster, honestly

- The heartbeat interval is now **2 s** (was 5 s). It is a 68 KB request, so the extra cost is
  about 2 MB/hour, and it is what bounds how quickly a new market tick is noticed.
- Measured latency from Hypixel publishing to the chat line: heartbeat request 28–37 ms warm
  (419 ms cold), page-0 request 70–96 ms, page-0 parse 80–140 ms, announcement pulse ≤ 1 s. A new
  listing now reaches chat about **3 s** after the tick, instead of about 6 s.

Two things this release deliberately does **not** do, because they are not available:

- It does not make you first to every flip. Hypixel publishes no listing before its own market
  tick, and everyone polling the same public endpoints sees a listing at the same moment. Buying
  before other players requires reading the auction-house UI or buying automatically, which is
  automation against Hypixel's rules and is exactly what this mod refuses to do.
- It does not lower the safety gates to manufacture more alerts. The page-0 lane now finds what
  the full scan finds; it does not find things the gates would reject.

Version 1.2.0. Artifact: `ahflip/build/libs/discordia-1.2.0.jar`.


# Changelog

## 1.1.0 — 2026-11-24

An algorithm release. Every change here either refuses a flip the engine used to announce or finds
a flip it used to miss; none of them loosen a safety gate.

### Profit is booked at a conservative exit, not at the asking price

- The hard profitability gate now uses a **lower bound**, not the optimistic ask: the gross exit is
  `min(target, realized p25)`, then less a **1.5% reserve** for price drift, the second listing fee
  and execution slippage. The p25 is used once there are **5** sales of the exact variant
  (`conservativeSalesQuantile`, `riskBufferPct`).
- Profit, margin, the margin cap and the liquidity gates are all computed at that floor. The ask is
  still shown as the target, and the detail line now ends with the floor it survives, for example
  `(floor 10.62M after 1.5% reserve)` or `(floor 8.94M, p25 of 9 sales)`.
- A flip whose profit only exists at a perfect exit is now suppressed instead of announced.

### A slow flip is refused as a capital trap

- New hold-horizon gate: an item whose expected hold exceeds `maxHoldHours` (**168 h**) is refused,
  and a book-only flip — one with no same-variant sale behind its target — gets a tighter
  `maxBookOnlyHoldHours` (**72 h**).
- Expected value must be positive (`requirePositiveRiskAdjusted`): `p × profit` from a Poisson sale
  probability `1 − exp(−horizon/hold)`, minus the cost of not selling (one more listing fee plus a
  10% markdown) minus the cost of locked capital (0.5%/day).
- When the hold cannot be estimated, the margin requirement rises by **2%**
  (`unknownLiquidityMarginPremiumPct`) instead of the item being treated as instant.

### Rare items are found instead of skipped

- The listing floor drops from **6** to **3** when the exact variant already has **5** realized
  sales (`minVariantListingsWithSales`). Realized sales now also carry confidence on their own
  (`min(1, n/10)`), which is what a thin book cannot provide.
- Only the listing *count* moves. The unique-cheapest rule, the ±10% support requirement, the
  relative-spread cap, the recombobulator ceiling, the Wither-scroll ceiling and the weekly-median
  gate are unchanged.

### Losses closed

- **Scrolls are capital.** `buy price + missing scroll cost` must fit inside the item cap. A cheap
  partial Hyperion needing hundreds of millions of scrolls used to pass the budget check because
  only the listing price was tested. Now it is `OVER_BUDGET`.
- **Non-BIN snipes** take the same 1.5% reserve on their BIN reference: a bid is not a purchase and
  the reference is not that auction's resale price.

### Diagnostics: `/discordia status` now answers "why is the list empty?"

- New `Rejections` reason-code layer with 24 stable codes, incremented per refusal in the auction
  and snipe paths. `/discordia status` shows a `why` row for the last scan, for example
  `1187 rejected | THIN_POOL 820, NO_SALES 190, MARGIN 96, CAPITAL_TRAP 22`.
- `FlipConsole` prints the same counts, and the new offline probe asserts individual codes.
- An empty feed can now be told apart from a broken one: thin book, waiting on Cofl, genuinely no
  demand, too slow to be worth the coins, or a component ceiling that cannot be met.

### Verification

- New `AlgorithmGuardProbe`: **59 deterministic offline checks** covering the AH fee brackets at
  9.99M/10M/10.01M/100M/100.01M (including the real bracket cliff), claim-tax clamping at 1M, the
  rule that a non-BIN bid is never a purchase price, Little's law and the Poisson probability, the
  anchor/support/duplicate-price rules, the thin-sample manipulation guard, the plain-item weekly
  gate (zero, pending and trusted), the recombobulator and Wither-scroll ceilings, the blade capital
  check, the thin-book rescue, and `CAPITAL_TRAP`.
- Wired into `verify.sh`, which now runs all four offline probes and exits 0.
- Fixed the `GlobalFailureProbe` `FAULT404_RING` wait: 2 s against a 2 s in-loop retry sleep made it
  fail about two runs in three. The assertion is unchanged; the wait is now 8 s.

### Build

- `ahflip/build.sh` falls back to `python3` when `node` is absent, so the documented build works on
  a machine without Node.
- `build.sh`, `verify.sh`, `collector/build.sh` and `collector/run.sh` are committed executable.
  They were recorded as `100644`, so the documented `./build.sh` and `./verify.sh` failed with
  "Permission denied".
- Version bumped to 1.1.0. Artifact: `ahflip/build/libs/discordia-1.1.0.jar`.

### Not in this release

- Announced auction IDs are still remembered in memory only, so a restart can re-announce an
  auction that is still listed. Persisting them is the next piece of work.
- No replay/backtest harness: the fixture set is too small to tune thresholds on, and tuning on the
  same fixtures that report success is exactly what the engineering brief forbids.

The mod still never buys, bids, clicks or confirms anything, reads no game UI or packets, needs no
API key, and sends nothing anywhere. No alert is guaranteed, risk-free or certain — a flip finder
can reject a bad price, it cannot make a player-driven market safe.
