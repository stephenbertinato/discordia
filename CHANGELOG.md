# Changelog

## 1.5.0 — 2026-11-24

### The purse is the only cost ceiling

`maxBudgetShare` and `maxItemCost` are deleted, and `/discordia maxcost` is removed with them.
`maxCost()` now returns the purse and nothing else: in game the client already re-reads the purse
every pulse, so a 500M listing is judged on whether you can afford it, not on 25% of a number.
Asking whether a good flip is "worth it" is the margin gate's job, not a cost cap's.

### The margin hurdle rises with the buy price

A flat 8% is the wrong test for a 500M flip: it treats 40M of locked capital the same as 4M. Below
`marginScaleFrom` (**10M**) the base `minMarginPct` still applies; every `marginScaleStep`
(**20M**) above it adds `marginScalePct` (**1%**), up to `maxScaledMarginPct` (**20%**). That is
8% at 10M, 10% at 50M, 15% at 150M, and the 20% cap past ~210M. The cap is deliberate: the
577.6M Terminator that started this nets **23.9%** after fees, so a 20% cap clears it with room
while a 25% cap would have rejected a 138M profit. The same curve now applies to
book-only flips, non-BIN snipes and craft flips.

`largeProfitOverride` is gone too. It let any flip with 1M of absolute profit skip the percentage
gate, which is exactly how a 500M item returning 2% used to be announced. Profit size is no longer
an argument for a thin return.

### What this broke, and what was actually wrong

Four probe fixtures had been passing only because of that override, and they surfaced one at a
time during verification:

- the Gyrokinetic weekly-median control made **8.26%** on a 40M entry, which needs 9.5%;
- the recombobulator control made **9.7%** on a 48M entry, which needs 10%;
- the partial-blade fixture made **6.4%** on a 30M entry, which needs 9%.

Each fixture was testing something else — the weekly guard, the recomb ceiling, the scroll ceiling —
so each was given a real gap rather than the gate being softened. The blade case needed a separate
cheap price (`bladeBook(scrollAsk, cheapPrice)`): at any price that cleared the new hurdle, the
next partial listing became the binding constraint and the scroll ceiling stopped being tested at
all. It now enters at 26M against a 33.41M full-blade cap, so the cap it asserts is the cap that
binds.

`AlgorithmGuardProbe` is now **91 checks**, including the exact curve (8% at 5M, 10% at 50M, 20% at
500M, still 20% at 2B), that the 577.6M/23.9% Terminator clears the hurdle, and that `maxCost()`
equals the purse at 512M. `./verify.sh` exits 0.


## 1.4.0 — 2026-11-24

### Craft flips: recipes, ingredient prices and replacement cost

The one class the finder could not see at all. Cofl's `CraftCost` finder flags auctions that cost
less than the item costs to build; this release adds the data and the two lanes behind that idea.

**The recipe table is bundled, not fetched.** `tools/build-craft-data.mjs` extracts **2,002**
crafting outputs (3x3 grids, flattened to present ingredients) plus **523** star-cost tables and
**86** reforge stones from NotEnoughUpdates-REPO (MIT) into `recipes.json` and `upgrades.json`,
committed with the mod. A scan therefore needs no third-party service to know what an item is made
of, and the recipe graph cannot go down with someone else's API.

**Everything is priced from live sources.** Every ingredient resolves to the cheapest route that
can actually be proven right now: bazaar instant buy walking the real ask book for the quantity
needed, the NPC price, an auction LBIN (only when at least two copies exist, so one stale listing
cannot price a recipe), or crafting it in turn down to six levels deep. A cycle in the graph
resolves to unknown instead of looping. Any unpriceable ingredient makes the whole floor **unknown
rather than optimistic** — a floor missing a component would make a heavily upgraded item look
cheap to recreate.

**Modifiers are real costs.** Stars are 1,850 Dragon essence for a Terminator (7.6M at today's
bazaar), the five master stars are 210.3M, a recombobulator is 9.6M, and a Precise reforge is the
Optical Lens plus 100k. Base craft 350.1M plus those gives a **577.6M** replacement cost for a 5★
5-master★ Precise Terminator, against the 753M it resold for. Enchantments are deliberately not
modelled: merging books is a different cost problem, and leaving them out can only understate the
floor.

Two lanes use that number:

- **Craft flips (`CRAFT`).** Buy the ingredients, craft, sell. The ingredient bill is an exact
  cost; the exit is the same conservative floor the auction lane uses (weekly median, then the
  realized p25 once there are five sales, then the 1.5% drift/relist reserve). A recipe with no
  weekly evidence is not a flip however cheap its inputs are.
- **Replacement-cost ceiling on auctions.** A listing's resale target is now capped at what the
  item costs to build, plus `maxCraftPremiumPct` (25%). A book claiming a 900M resale on something
  craftable for 300M is describing an exit that does not exist. The detail line says so:
  `(craft floor cap 577.6M)`.

New knobs: `crafts`, `minCraftProfit`, `minCraftMarginPct`, `maxCraftMarginPct`, `maxCraftDepth`,
`minCraftSalesPerDay`, `maxCraftPremiumPct`. New reason codes: `CRAFT_UNPRICED`, `CRAFT_NO_EXIT`.
Recipes with no market at all are skipped silently rather than counted, so two thousand empty
recipes cannot bury the auction diagnostics.

`AlgorithmGuardProbe` is now **83 checks**, including: one blaze rod at 100 prices blaze powder at
_exactly_ 100; a 40-coin finished listing does not lower a 100-coin floor while acquisition still
takes the cheaper route; an unpriced ingredient yields `UNKNOWN`, never zero; five Dragon stars, a
recomb and a Precise stone cost exactly 321,500; an unknown reforge fails closed; a 100-coin
recipe against a 130 median is a flip; the same recipe against an 80 median is not; and two weekly
sales are refused as `CRAFT_NO_EXIT`.

Live console run after the change: 36,283 rejections with `CRAFT_NO_EXIT` no longer in the top
six, and no craft flips announced because the console's weekly-summary file is empty — the lane is
gated on evidence, which is the intended behaviour.


## 1.3.1 — 2026-11-24

### A verified market exit outranks the book's margin cap

Reviewing a tracked flipper's history showed the single largest class of missed flips: a plain
accessory mislisted at a fraction of its value. `Vaccine Artifact` bought at **17 coins** and sold
near **15.7M**, `Tarantula Talisman` 500k -> 7.9M, `Shriveled Wasp` 100k -> 16.9M, `Spider
Artifact` 790k -> 7.65M, `Aurora Dye` 3.95M -> 38.95M — every one of these was refused by
`IMPLAUSIBLE_MARGIN`, because a 900,000% margin on the book's own evidence is indeed implausible.

The book is not the only evidence. When the market-wide weekly summary has at least
`minGlobalSalesForTrustedExit` (**50**) sales, that median is an exit nobody in this book controls,
and the target has already been clamped to `maxTargetOverGlobalMedian` on it. A listing far below a
verified median is cheap in fact, so the margin cap no longer applies to it. The detail line says so:
`(verified exit, 412 weekly sales)`.

Nothing else moved. The buy price still has to sit under `maxBuyOverGlobalMedian` of the same median,
the conservative p25 exit and 1.5% reserve still set the profit, the hold-horizon and risk-adjusted
gates still run, and a summary with fewer than 50 sales keeps the old cap — the probe asserts both
sides (`17 coins under a 15.6M verified median is priced`, `6 weekly sales: still refused`).

`AlgorithmGuardProbe` is now **70 checks**; `./verify.sh` exits 0.


## 1.3.0 — 2026-11-24

### Sub-second delivery, measured at 148–167 ms

Two facts were measured against the live API before designing anything:

- **The tick is exactly 60,000 ms.** Consecutive `lastUpdated` transitions were 60,000 ms apart,
  and the measured period held at 60.3 s with 0 ms drift over six ticks.
- **Every new listing lands on page 0.** Over one tick, the full book was 44,775 auctions over 45
  pages and **130 of 130** newly created auctions were on page 0. So page 0 is the whole
  new-listing feed, not a 1-in-36 sample, and a tick change is exactly when to fetch it.

New `MarketTicker` predicts the next tick from the period it measures and opens a 150 ms burst
for ~1.2 s before the boundary and ~2 s after it, sleeping otherwise. That is what makes
sub-second delivery affordable: the heartbeat is **172 KB** (not 68 KB), so a flat 150 ms poll
would be 172 KB × 6.7/s, and even the flat 2 s poll of 1.2.0 was about 300 MB/hour.

Measured, tick detected to scored flips ready, over six consecutive ticks:

| Step | Measured |
|---|---|
| page-0 request | 58–70 ms |
| page-0 parse | 79–86 ms |
| scoring | 11–18 ms |
| **total** | **148–167 ms** |

The announcement no longer waits for the pulse: `MarketService` calls an update listener the
moment a scan publishes, and the client dispatches it to the game thread immediately. The 2 Hz
pulse remains only as the purse refresh and as a safety net.

Cost: about 13 heartbeat requests a minute, ~2.2 MB/min or ~130 MB/hour, down from ~300 MB/hour.
If a predicted tick does not arrive, the window widens into a bounded hunt and the next transition
re-measures the period, so a wrong prediction costs one late scan, never a dead feed.
`/discordia status` gains a `tick` row with the measured period, the drift and the burst/idle poll
counts. The first scan after joining still builds the full book (~4.4 s), which is unavoidable.

### Reading the auction-house UI: asked, and deliberately not built

Reading the open menu's slots (or the container packets) would work without OCR, but it would not
scan everything — the GUI shows 45 slots in its own order, so 44,775 listings is ~995 pages,
minutes per sweep, and Hypixel rate-limits the auction house on top of that. It is strictly slower
than the page-0 path for the listings that matter.

It is also against Hypixel's rules: reading auction data through the UI to see listings before
other players is the unfair advantage those rules prohibit. Whether Watchdog's heuristics happen to
catch a given build does not change the rule or the ban risk, so it is not built, and the README
keeps its promise that the mod does not read the in-game auction-house UI or packets.

Version 1.3.0. Artifact: `ahflip/build/libs/discordia-1.3.0.jar`.


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
- The announcement pulse runs at 2 Hz instead of 1 Hz: the scan threads publish asynchronously,
  and that loop is what turns a result into a chat line, so its period was added to every flip.
- Measured latency from Hypixel publishing to the chat line: heartbeat request 28–37 ms warm
  (419 ms cold), page-0 request 70–96 ms, page-0 parse 80–140 ms, announcement pulse ≤ 0.5 s. A
  new listing now reaches chat about **3 s** after the tick, instead of about 6 s.

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
