# Discordia Flip Algorithm — Hardening and Profitability Brief

**Audience:** a senior Java engineer/AI with write access to this repository.

**Mission:** make Discordia's flip detection faster and materially safer, with a hard no-bad-alert
policy and a fee-inclusive, evidence-backed profitability gate.

**Repository:** this repository (the `ahflip/` client and the shared `collector/` core).

**Baseline:** the code in the commit that contains this file. Read the code before changing it.

---

## 1. Read this before touching the algorithm

The current system is deliberately conservative. It already has exact variant keys, realized-sale
evidence, global weekly-median guards, fee calculations, recombobulator and Wither-scroll ceilings,
fast-lane deduplication, and rate limiting. Do not replace those safeguards with a simpler score.
Do not loosen a guard merely to produce more alerts. Coverage is subordinate to correctness.

The user's phrase **“always profitable”** is a product goal, not a mathematical guarantee. A
player-driven auction market can always produce a loss through a crash, a manipulated listing, a
missed variant, a fee change, an unobserved attribute, or an item that simply does not sell. Never
label a flip “guaranteed”, “risk-free”, or “certain profit”. Encode the requirement as:

> **No alert may be emitted unless the conservative, fee-inclusive lower-bound exit value is
> strictly profitable, every material input is known and fresh enough, and the result clears the
> configured risk gates. Unknown is not zero. A missing input blocks the alert.**

If a change cannot prove that its new alerts satisfy that rule, it must suppress them and report the
reason. A smaller number of defensible alerts is better than more alerts with hidden risk.

The rest of this brief defines the required model, variables, acceptance tests, and handoff.

---

## 2. Non-negotiable constraints

1. **API-only, no automation.** Continue using public Hypixel and SkyCofl HTTP APIs. Do not read
   the in-game auction-house UI, inventory packets, screen contents, or memory. Do not buy, bid,
   click, send movement, or automate any game action.
2. **Client-side and user-controlled.** Clicking a result may only open `/viewauction <uuid>`.
3. **No new account requirement for core operation.** SkyCofl's public API must remain usable
   without an API key. A key may be optional, never required for a build or basic scan.
4. **Plain Java 21 in `core/`.** Do not add Minecraft imports to the engine. The engine must remain
   usable from `FlipConsole` and the standalone collector.
5. **Do not commit secrets.** No tokens, session cookies, `.env` values, local configs, generated
   sales caches, or private collector credentials.
6. **Do not commit build artifacts.** `.tooling/`, `libs/`, `build/`, `run/`,
   `collector-state/`, and JARs are ignored.
7. **Preserve existing configuration and command compatibility** unless a documented migration is
   included. Users' `/discordia margin`, `minprofit`, `maxcost`, ignore list, and collector filters
   must keep working.
8. **Do not weaken the rate budget.** All Cofl callers share `CoflRateLimiter`. Keep the global
   limits at least as strict as the current 100/minute and 30/10 seconds, including category
   reservations.
9. **No silent failure.** A failed detail lookup, stale book, pending global summary, malformed NBT,
   or API error must be distinguishable from “no qualifying flips”.
10. **No fabricated data or promises.** Tests use deterministic fixtures. Do not claim a live
    backtest unless the exact data window and commands are included.

If a requested improvement conflicts with one of these constraints, stop and document the conflict
rather than bypassing it.

---

## 3. Current architecture and data flow

```
Hypixel /auctions (full dump) ──┐
Hypixel /auctions_ended ────────┤
                                ├─> MarketSnapshot ─┐
SkyCofl active feed (2 s) ──────┘                   ├─> FlipEngine.compute()
SkyCofl auction detail ────────────────────────────┘        │
                                                            ├─> Flip list for chat
SoldStats (7-day local sales) ──────────────────────────────┤
GlobalSales (weekly summaries) ─────────────────────────────┤
AttributePricing / Bazaar / NPC / Forge / Kat ─────────────┘
```

### 3.1 Normal scan

- `MarketService` polls the cheap `/auctions_ended` heartbeat every 5 seconds (the embedded
  collector uses 3 seconds).
- When Hypixel's `lastUpdated` changes, it fetches page 0 (the newest listings) and scores those
  against the cached full book.
- The full book is rebuilt every 10 minutes (`fullRefreshMs = 600000`).
- The normal page-0 path is the fallback and source of truth.

### 3.2 Fast lane

- `FastAuctionFeed` polls SkyCofl's newest-active-auction feed every **2 seconds**.
- It uses a cache-buster because the CDN default is five seconds.
- It only fetches auction detail for a new BIN when the item tag already exists in the cached book
  and the ask is within **1.35×** of the cheapest cached listing for that tag.
- Eligible details go into a priority queue; a separate drain thread processes them.
- Detail failures retry with backoff on a scheduled executor and are not marked seen.
- Only a successful detail or an ended auction retires a UUID.
- Fast results merge with normal results for up to 120 seconds; normal results win by UUID.
- The same `FlipEngine` and safety gates must be used by both lanes.

### 3.3 Core classes

| Class | Responsibility |
|---|---|
| `FlipEngine` | Auction, bazaar, sniping, NPC, forge, Kat valuations |
| `FlipConfig` | Every filter, threshold, and scan timing |
| `MarketSnapshot` | Parses auctions/NBT, builds exact/group/base price pools |
| `ItemVariant` | Variant identity: item, tier, reforge, stars, pet, enchants, scrolls, recomb |
| `SoldStats` | Local 7-day realized sales and volume, plus global summary cache |
| `GlobalSales` | SkyCofl weekly summaries, pending/retry/backoff, cache persistence |
| `AttributePricing` | Evidence-based premiums for ultimate enchants and pet levels |
| `Tax` | AH listing fee, claim tax, bazaar tax helpers |
| `FastAuctionFeed` | Fast candidate feed, queue, detail drain, retry |
| `CoflRateLimiter` | Shared Cofl request budget and category reservations |
| `MarketService` | Local scan loop and fast-lane integration |
| `DiscordiaClient` | Chat commands, announcements, status, ignore filters |

Read the class-level comments and the full `ahflip/README.md` before changing any formula.

---

## 4. Terms and formal model

Use these names consistently in code, logs, tests, and documentation.

| Term | Definition |
|---|---|
| `P` | BIN purchase price in coins (what the buyer pays) |
| `A` | Additional unavoidable cost: recomb, missing scrolls, forge inputs, materials, etc. |
| `C` | Total capital committed: `P + A` |
| `T` | Gross resale price used for the decision |
| `F(T)` | Total AH seller fees: listing fee + claim tax |
| `N(T)` | Net sale proceeds: `T - F(T)` |
| `profit` | `N(T) - C` |
| `marginPct` | `profit / C * 100` |
| `anchor` | The next-cheapest listing you can realistically undercut (normally `0.99 × next`) |
| `support` | Number of comparable active listings near the anchor (currently within ±10%) |
| `evidence` | Realized sales, global weekly summary, book, comparable sales, or a component value |
| `conservativeExit` | A lower-bound exit value after fees, not the most optimistic ask |
| `saleProbability` | Probability of selling within the configured horizon, estimated from observed volume |
| `riskAdjustedProfit` | Expected profit after capital lock and unsold risk |
| `stale` | Older than the relevant freshness limit for that evidence type |
| `unknown` | Missing, pending, unavailable, malformed, or contradictory evidence |

### 4.1 What “bad flip” means

A candidate is a **bad flip** if any of these is true:

- its variant identity is incomplete or mismatched;
- the listing is not a valid, unexpired BIN;
- a required fee, cost, or tax was omitted;
- the target is an asking price rather than supported resale evidence;
- the only evidence is a thin, stale, pumped, or one-sided pool;
- the resale target is above a hard ceiling from recomb, scrolls, or weekly median;
- the profit at `conservativeExit` is not strictly positive after all modeled costs;
- the expected sale horizon or probability is outside the allowed risk limit;
- the same auction has already been announced;
- an input is pending/unavailable and the policy requires it;
- the scanner is stale or the feed is in an error state that could make the result invalid.

The engine must be able to produce a **reason code** for every rejection. “Did not pass filters” is
not an adequate diagnostic.

### 4.2 What “always profitable” means operationally

At alert time, require all of the following:

1. `conservativeExit > 0`.
2. `profit = N(conservativeExit) - C > 0`.
3. `profit >= minProfit` and `marginPct >= minMarginPct`, subject only to documented absolute-
   profit overrides.
4. The target is derived from realized sales or a supported book, never from an unsupported ask.
5. A safety buffer is reserved for price drift, relisting, and execution slippage.
6. The sale-probability / horizon gate passes.
7. Every material variable is known and fresh enough.

This is not a promise that the future sale price will be met. It is a guarantee that the alert was
not produced by an arithmetic error, an unsupported price, or a hidden unknown. Say that clearly in
user-facing documentation.

---

## 5. Complete variable taxonomy

The following is the minimum set to reason about. If a variable is not modeled, it must not silently
contribute value. If it is value-bearing but unknown, reject the candidate or label it as an
unsupported variant.

### 5.1 Auction record fields

| Variable | Source | Why it matters | Required handling |
|---|---|---|---|
| `uuid` | Auction | Stable identity and deduplication | Canonical key; never announce twice; normalize case |
| `item_name` | Auction | Base item identity | Strip reforge prefix; do not use alone for pricing |
| `tier` | Auction | Rarity | For pets, use `petInfo.tier`, not displayed tier |
| `starting_bid` | Auction | BIN price / starting bid | BIN: purchase price; non-BIN: never treat as instant buy |
| `highest_bid_amount` | Auction | Current non-BIN bid | Only for snipes; not a BIN price |
| `bin` | Auction | Instant-buy flag | Fast lane and normal BIN alerts require true |
| `end` | Auction | Expiry / snipe window | Reject expired; retire UUID; snipe lead-time gate |
| `start` | Auction | Listing age | Freshness and “new” semantics |
| `claimed` | Auction | Already sold/claimed | Exclude |
| `auctioneer` | Auction | Seller identity | Manipulation/seller-pattern checks if available |
| `profile_id` / `coop` | Auction | Seller profile/co-op | Only for risk analysis; never expose private data |
| `category` | Auction | Item category sanity | Reject mismatches against parsed id |
| `bids` | Auction | Non-BIN bid history | Snipes/competition; never a BIN price |
| `item_bytes` | Auction | Compressed NBT | Decode failure means reject, not “plain” |
| `item_lore` | Auction | Cosmetics and display clues | Dye/rune/skin checks; never the sole valuation source |
| `lastUpdated` | Snapshot | Market tick age | Staleness gate; not a scanner heartbeat |
| `page` / order | Snapshot | Newest-first page | Fast/normal coverage and duplicate checks |
| `count` / stack size | NBT/auction | Quantity | Compute per-unit and total value correctly |
| `extra` / unknown fields | Auction | Future schema | Ignore for value unless explicitly modeled; log schema drift |

Do not invent fields the API does not provide. If a field is present but unparsed, mark it unknown
and follow the reject rule when it could change value.

### 5.2 NBT and item metadata

| Field | Why it matters | Required handling |
|---|---|---|
| `ExtraAttributes.id` | SkyBlock item identity | Required for normal items; fallback only with a parsed name and explicit lower confidence |
| `modifier` | Reforge | Exact for expensive reforge; price-band pooling only for cheap ones |
| `upgrade_level` / `dungeon_item_level` | Stars | Part of variant; exact level comparison |
| `enchantments` | Enchants | Sorted canonical hash; exact pool; normal enchants are not automatically valuable |
| Ultimate enchant levels | Premium attributes | Only value with realized comparable sales and a bazaar book ceiling |
| `rarity_upgrades` | Recombobulated | Part of identity; non-recomb capped by recombed net |
| `petInfo.type` | Pet identity | `PET` alone is not an item id |
| `petInfo.tier` | Actual pet rarity | Tier Boost changes display, not actual tier |
| `petInfo.heldItem` | Pet item | Part of variant/value |
| `petInfo.exp` / level | Pet level | Level is value; exact or tightly bounded bucket |
| `petInfo.candyUsed` | Pet candy | Can affect value; model or treat as unknown |
| `ability_scroll` | Wither scrolls | 0/3, 1/3, 2/3, 3/3; cost missing scrolls from instant bazaar depth |
| `dye_item` / `dyed_color` | Applied dye | Exclude or value explicitly; never let dye set the base price |
| `runes` / rune id | Runes | Exclude as currently done |
| `skin` / skin id | Applied/unapplied skin | Unapplied skin items excluded; applied skin adds no modeled value |
| `attributes` / stat rolls | Item attributes | Only value with exact realized evidence; otherwise unknown |
| Gemstones / slots | Gemstone value | Model exact gem type/quality or treat as unknown |
| `hot_potato_count` / Art of War | Upgrades | Value-bearing; model or include in exact variant |
| Farming for Dummies / other books | Upgrades | Value-bearing; model or treat as unknown |
| `ability` / `stats` / `mana` | Item stats | Not a price by itself; only through variant/comparables |
| `color` / display-only tags | Cosmetic | Do not let display-only metadata alter price |
| Unknown ExtraAttributes key | Potential premium | Unknown value-bearing key blocks a premium claim |

If the parser sees an unrecognized value-bearing compound on an item class where such fields can
change price, it must not collapse the item into a plain pool. Either create a variant dimension or
reject with `UNSUPPORTED_ATTRIBUTE`.

### 5.3 Variant identity dimensions

The exact key must include every dimension that can materially change value:

- SkyBlock item id;
- actual rarity/tier;
- reforge (with the cheap/expensive distinction);
- stars / dungeon level;
- pet type, actual tier, level, and held item;
- sorted enchant signature and ultimate levels;
- ability scroll set (Wither Impact completeness);
- recombobulated flag;
- stack count if price is not per-unit;
- any modeled gemstone/attribute/upgrade dimension;
- dyes, runes, and skins policy (currently excluded or zero-value);
- pet candy if modeled.

Produce three levels with explicit trust penalties:

| Pool | Key | Allowed when | Multiplier |
|---|---|---|---|
| Exact | All modeled dimensions | At least `minAuctionListings` | 1.00 |
| Group | Drops only normal-enchant signature | Exact too thin | 0.92 |
| Base | Item + tier only | Both too thin and only with higher bar | 0.80 |

Do not use the base pool when any premium attribute could be present. Do not pool a Tier-Boosted
Epic pet with a genuine Legendary. Do not pool a partial Wither-Impact blade with a full one.

### 5.4 Active-book statistics

Track and expose at least:

- listing count (`n`);
- cheapest price;
- second-cheapest price;
- next price after the candidate;
- median;
- p25 / p75;
- min / max after outlier handling;
- median absolute deviation (MAD);
- relative spread (`MAD / median`);
- support count around the anchor (currently ±10%);
- duplicate-price count;
- exact/group/base pool used;
- pool age and snapshot `lastUpdated`;
- number of listings cheaper than the candidate;
- number of listings within 1%, 2%, 5%, and 10% of the candidate;
- whether the candidate is the unique cheapest;
- whether the next listing is close enough to leave a real gap.

The anchor rule must remain conservative:

- if any listing is cheaper than the candidate, no flip;
- if the candidate shares its exact price with another listing, no flip;
- if there is no next listing, no flip;
- the anchor is normally `next × 0.99`, never the candidate’s own ask;
- require support near the anchor, not just one lonely listing;
- reject if the relative spread is too wide;
- reject if the anchor is stale relative to the current snapshot.

### 5.5 Realized sales and global summaries

Local evidence:

- 7-day rolling window, deduplicated by sold-auction id;
- exact variant signature first; cheap-reforge band fallback only when exact reforge is absent;
- stars, pet level, held item, pet tier, scrolls, recomb, dye policy must match;
- old sale rows missing a field are wildcards only where the existing schema intentionally treats
  them that way; do not invent precision;
- count, median, p25, p75, min, max, sales/day, sales/hour;
- observed days floored at 1 and capped at 7 for rate purposes;
- `pending` and `unavailable` are not zero;
- local evidence can override a global zero only when the local rows are genuine and fresh.

Global evidence:

- SkyCofl 7-day summary keyed by the authoritative market tag (pets use `PET_<TYPE>`);
- count, median, p25, perDay;
- source timestamp and cache age;
- pending/unavailable/retry state;
- request errors, 429, 5xx, malformed response, missing contract fields;
- never use a single local sale as a substitute for the market-wide summary when the plain-item
  guard is required;
- apply backoff and `Retry-After`; do not stampede.

Evidence precedence:

1. exact-variant realized sales (thick) → strongest;
2. exact-variant realized sales (thin) → target capped by the next listing and a higher margin;
3. comparable attribute sales with adequate sample and share → evidence for a premium, with a
   book-price ceiling;
4. global weekly median → sanity/cap, not a free upgrade for a thin local print;
5. active book gap → only the strict book-only path;
6. unsupported ask → never a target.

### 5.6 Fees, costs, and capital

Model every coin that changes hands:

- AH listing fee: 1% below 10M, 2% from 10M through 100M, 2.5% above 100M (use exact brackets; test
  values at and around each boundary);
- AH claim tax: 0 at or below 1M; otherwise 1%, capped so the claim never falls below 1M; use
  `Tax.ahClaimTax` and test the cap;
- bazaar tax: 1.25% in the current model (configurable; support the 1.125%/1% variants only if
  exposed as a setting, not silently);
- Recombobulator: live top ask and depth; fallback only with a documented stale default;
- missing Wither scrolls: instantaneous bazaar fill for all missing scrolls; incomplete depth means
  no blade trade;
- forge inputs: walk the ask book level by level; output sale walks the bid book; incomplete depth
  means skip;
- NPC sale: NPC price and daily cap; one item per listing assumption unless stack size is known;
- Kat: pet cost, coin cost, material cost at bazaar, duration; level-1-only rule unless the full
  cost curve is modeled;
- budget/purse: total available coins, max budget share, absolute item cap;
- relisting/undercut reserve: set aside a configured buffer for a second listing fee and price
  reduction;
- capital lock: coins tied up until sale are not free; apply `riskAdjustedProfit`;
- opportunity cost: compare against the next-best use only as a ranking signal, not as fake profit.

Never subtract the same fee twice. Never compute profit as `target - askingPrice` without `N(T)`.
Never compare a non-recomb item to a recombed ask without subtracting the recomb cost.

### 5.7 Liquidity and time

For each candidate, estimate:

- sales per day and per hour for the exact variant, falling back to the base item only as a weaker
  signal;
- active supply in the relevant pool;
- time to sell using Little's law: `(listings + 1) / salesPerHour × 60`, with the denominator
  floored at one hour and capped at 24 hours if that matches the existing model;
- probability of sale within a configured horizon;
- expected price decay while waiting;
- chance of being undercut after listing;
- auction slot / playtime constraints.

Do not present a large profit on an item that sells once a month as equivalent to a fast flip. A
high headline profit with no liquidity is a capital trap. The risk-adjusted score must penalize it,
and a hard horizon gate may suppress it.

### 5.8 Market regime and external variables

Where the data is available and relevant, account for:

- time of day and day of week;
- Hypixel update / maintenance / API outage;
- mayor perks, Derpy, and other market-wide modifiers;
- events and content updates that change demand;
- bazaar price spikes and supply shocks;
- inflation and sudden market-wide repricing;
- seasonal items and limited-time availability;
- manipulated books: repeated prices, fake depth, wash-like sale patterns, one-sided books;
- seller concentration and repeated relists;
- cross-market discrepancies (BIN vs auction, bazaar vs AH) that are not executable.

Do not overfit to one event. A regime detector must be conservative: when the market is in an
unusual state, raise the evidence bar or suppress, not lower it.

### 5.9 Execution and plumbing variables

- feed poll interval (2 s fast lane) and cache-busting;
- CDN cache age and server clock skew;
- normal heartbeat (5 s; embedded collector 3 s);
- page-0 freshness and full-book age (10 min);
- detail-request latency and failures;
- rate budget: 100/min and 30/10 s total; feed 30/min and 5/10 s; detail 50/min and 18/10 s;
  global 20/min and 7/10 s (preserve or tighten);
- queue depth, retry attempts, backoff, and scheduled retry generation;
- duplicate UUIDs across fast and normal lanes;
- end-time handling and expiry;
- thread safety around shared maps and queues;
- persistence of announced IDs across restarts;
- memory bounds for seen IDs and queues;
- logging redaction; never log tokens or private data.

### 5.10 User-controlled variables

- budget / purse;
- max budget share per item;
- absolute max item cost;
- minimum profit and margin;
- maximum sane margin;
- minimum confidence;
- minimum sales/day and minimum exact sales;
- plain-item global guard;
- book-only allowance;
- snipes and non-BIN visibility;
- ignored item names;
- risk tolerance / max hold time (if added);
- live notifications on/off;
- collector filters and connection state.

Configuration changes must apply consistently to normal and fast lanes and to the collector. A
lowered filter may reveal an existing flip, but it must never bypass a hard safety gate.

---

## 6. Required valuation model

### 6.1 Build one strict candidate evaluator

Refactor, if needed, so fast and normal paths call the same pure valuation function. The function
must accept a candidate listing, a reference snapshot, sold evidence, pricing evidence, and config.
It must return either:

- an accepted `Flip` with full provenance; or
- a rejection with a stable reason code and the values that failed.

Do not duplicate formulas in `MarketService`, `CollectorScanner`, `FastAuctionFeed`, and
`DiscordiaClient`. The chat layer formats results; it must not re-price them.

Suggested result shape:

```text
Evaluation(
  decision: ACCEPT | REJECT,
  reason: enum/string,
  buyPrice,
  additionalCost,
  totalCost,
  conservativeExit,
  netExit,
  profit,
  marginPct,
  confidence,
  saleProbability,
  expectedHours,
  riskAdjustedProfit,
  evidenceSource,
  poolName,
  targetCaps[],
  freshness,
  warnings[]
)
```

Keep the existing `Flip` fields for compatibility and add fields only when they improve the user
explanation or diagnostics.

### 6.2 Target construction: minimum of defensible caps

Construct the gross target as the **minimum** of all applicable supported values, not the maximum:

```text
T0 = min(
  nextCheapest × (1 - undercutPct),
  thickSalesMedian × 0.98,
  thinSalesAnchor,
  globalWeeklyMedian × maxTargetMultiple,
  recombEquivalentCeiling,
  fullBladeCeilingAfterMissingScrolls,
  attributeComparableCeiling,
  other modeled component ceiling
)
```

Rules:

- If there is no defensible target, reject.
- `nextCheapest` is not valid unless the candidate is the unique cheapest and support exists.
- A thin sample cannot project above its realized median.
- A global median cannot lift a target; it can only cap it.
- An attribute comparable can replace the book target only when it has enough samples and share,
  and only within its own cap.
- A non-recomb item is capped by `N(recombedCopy × 0.99) - recombCost`.
- A partial blade is capped by `N(fullBladeCopy × 0.99) - missingScrollCost`; if bazaar depth is
  incomplete, reject.
- Do not use a high asking price as evidence of demand.

### 6.3 Conservative lower bound

The current median is useful, but “always profitable” requires a lower bound. Use a documented
lower quantile of realized sales (p25 or a conservative equivalent) and a configurable safety
buffer, rather than the median, for the hard profitability gate. Keep the median for display and
for the current target calculations only where it is explicitly capped.

Example gate:

```text
conservativeGross = min(T0, salesP25, globalP25OrMedianCap) × (1 - riskBufferPct)
net = ahNet(conservativeGross)
profit = net - (P + A)
require profit > 0
require profit >= minProfit
require margin >= minMargin
```

Do not invent statistical significance from three sales. A lower quantile with a tiny sample is not
a guarantee; require the existing sample thresholds, and suppress when the distribution is too
sparse or too wide.

### 6.4 Risk-adjusted score

For ranking and optional gating, estimate:

```text
expectedProfit = saleProbability × profit - (1 - saleProbability) × capitalCharge
riskAdjustedProfit = expectedProfit - expectedPriceDecay
```

`capitalCharge` and `expectedPriceDecay` must be documented, configurable, and conservative. If
there is not enough data to estimate them, do not fabricate a precise probability; use a coarse
liquidity bucket and widen the margin requirement instead.

The user wants every eligible flip announced as soon as it appears, not a top-5-only feed. Keep
announcing every accepted new UUID. Ranking is for `/discordia list` and `/discordia open`; it must
not suppress eligible announcements.

### 6.5 Hard gate order

Apply gates in this order and record the first failure:

1. valid BIN, unexpired, positive price, not claimed;
2. decode NBT and build a complete variant;
3. cosmetic/unsupported-dimension policy;
4. budget and max-cost gate;
5. book readiness, unique-cheapest, anchor/support, spread;
6. realized-sale evidence and thin/thick policy;
7. plain-item global guard;
8. component ceilings (recomb, scrolls, attributes);
9. conservative target and fee-inclusive profit;
10. margin/profit cap and confidence;
11. liquidity/horizon/risk-adjusted gate;
12. freshness and scanner health;
13. duplicate announcement;
14. accept.

If an earlier gate cannot be evaluated because data is pending, reject with `PENDING_EVIDENCE`; do
not fall through to a less reliable pool.

### 6.6 Manipulation and outlier policy

At minimum:

- trim extreme prices only within the existing robust bounds; never let trimming hide the fact that
  the book is wide;
- reject a pool whose relative spread is above `maxRelativeSpread`;
- reject if the candidate is not the unique cheapest;
- reject if support at the anchor is insufficient;
- reject a thin-sample target materially above the realized median;
- reject “too good” margins above `maxAuctionMarginPct` unless a genuine comparable target earns
  the documented wider cap;
- reject a plain item without a trustworthy weekly median;
- flag repeated identical prices, one-sided depth, and suspicious seller/relist patterns if the
  data supports it;
- never let a manipulated active ask become a sale comparable.

---

## 7. Fast lane and announcement requirements

1. Keep polling the fast feed every 2 seconds with cache-busting and the shared budget.
2. Keep the queue lossless: every eligible new BIN is queued; the drain never silently discards.
3. Do not mark a UUID seen until detail parsing succeeds or the auction has ended.
4. Retry temporary failures off the drain thread with backoff; do not block other candidates.
5. Normal and fast results must deduplicate by canonical UUID.
6. Announce every accepted new flip immediately; no top-5 cap and no batching delay.
7. Persist announced IDs across restarts so the same auction is not re-announced. Use a bounded,
   atomically written file; do not store only in memory.
8. If an auction has ended or is within the execution buffer, do not announce.
9. If the fast lane is unhealthy, the normal scan remains authoritative and the UI must say the
   fast lane is degraded.
10. Expose diagnostics in `/discordia status`:
    - feed state and last successful poll age;
    - queue depth, in-flight detail count, retries;
    - last detail success age and last error;
    - fast accepted/rejected counts by reason;
    - normal scan age and market tick age;
    - announced count and last announcement age.

The status output must distinguish “no qualifying flips” from “scanner broken”, “rate limited”,
“feed stale”, and “evidence pending”.

---

## 8. Implementation plan

Use small, reviewable commits. A safe sequence:

### Phase 1 — Baseline and characterization

- Read `ahflip/README.md` and all core classes.
- Run `cd ahflip && ./verify.sh` and record the baseline result.
- Add deterministic regression tests for the existing guards listed in section 9 before changing
  behaviour. Tests must not make public network calls.
- Add reason codes and evaluation provenance in a way that does not change accepted flips yet.

### Phase 2 — Evidence model

- Introduce a typed evidence object and explicit freshness/trust states.
- Replace implicit `null`/zero meanings with `UNKNOWN`, `PENDING`, `UNAVAILABLE`, `ZERO`, `KNOWN`.
- Make pool selection and fallback penalties explicit and testable.
- Ensure one evaluator is used by both fast and normal lanes.

### Phase 3 — Conservative profitability

- Add the lower-bound target and safety buffer.
- Add fee-inclusive `totalCost` and `net` calculations, including recomb/scroll add-ons.
- Add risk-adjusted liquidity gate with documented defaults.
- Remove any path where an ask can become a target without realized support.
- Add hard gate tests and property tests.

### Phase 4 — Execution and observability

- Persist announced IDs.
- Add fast-lane counters and status rows.
- Add structured rejection logging (rate-limited, no secrets).
- Add a deterministic replay/backtest probe with fixtures.

### Phase 5 — Documentation and handoff

- Update `README.md`, `ahflip/README.md`, and this brief with what changed and remaining limits.
- Update `verify.sh` to run the new probes.
- Build both targets.
- Commit and push to this same repository, default branch unless the owner says otherwise.
- Do not force-push or rewrite history.

---

## 9. Required tests and evidence

### 9.1 Must-have unit/regression cases

| Case | Expected |
|---|---|
| AH fee at 9.99M, 10M, 10.01M, 100M, 100.01M | Exact bracket result; no discontinuity |
| Claim tax at 1M, 1.01M, huge sale | Zero, 1%, and cap correct |
| Non-BIN bid vs BIN price | Never treated as an instant purchase |
| Cheapest listing with no support | Rejected |
| Duplicate identical price | Rejected |
| Another listing cheaper | Rejected |
| Thin sale 1–4 sales, next ask far above sales median | Rejected |
| Thick exact sale, target 98% median | Accepted only after fees and margin |
| Plain item with global count < 8 | Rejected/pending, never zero |
| Global zero with real local sale | Local evidence wins, documented |
| Global pending | No verified plain flip; book-only labels remain explicit |
| Recomb non-recomb vs recombed copy | Ceiling subtracts live recomb cost |
| Partial Wither blade, deep scroll book | Full-blade ceiling after scroll cost |
| Partial Wither blade, shallow scroll book | Rejected |
| Tier Boosted Epic displayed as Legendary | Priced at actual Epic, held item retained |
| Dyed/rune/skin item | Excluded or explicitly unsupported, never base-pool inflation |
| Cheap reforge vs expensive reforge | Same-band only for cheap; no Lustrous/Gilded mixing |
| Bazaar fill across levels | Exact depth-weighted cost |
| Forge output depth | Incomplete fill skips |
| Fast/normal same candidate | One announcement, normal evidence wins |
| Retry then success | One announcement, no lost candidate |
| Restart after announcement | No duplicate announcement |
| Expired fast candidate | Retired, not announced |
| Stale snapshot | Rejected/flagged |
| Unknown NBT field | No unsupported premium |

Existing probes that must keep passing:

```bash
cd ahflip
./verify.sh
```

`verify.sh` currently runs the build and three offline probes:
`GlobalFailureProbe`, `GlobalGateProbe`, and `CoflContractProbe`. Add the new algorithm probe to this
script; do not replace the existing coverage.

Standalone collector must still build:

```bash
cd collector
./build.sh
```

### 9.2 Property/invariant tests

Add deterministic tests for:

- `profit <= 0` can never be accepted;
- increasing the target without evidence cannot happen;
- lowering the evidence tier cannot increase target or confidence;
- a candidate that is not unique-cheapest can never be accepted as an auction flip;
- exact/group/base keys never mix incompatible variants;
- fees are monotonic and never double-counted;
- the same UUID cannot produce two announcements;
- pending/unavailable never equals zero;
- an ask above the weekly cap cannot become the target;
- `C = P + A` and `N(T)` are used consistently.

### 9.3 Replay/backtest

Add a fixture-driven replay probe that accepts recorded snapshots and sale rows without network
access. Label each candidate by what happened within a documented horizon:

- profitable if a subsequent sale clears the modeled costs;
- unprofitable if it clears below cost;
- unsold/unknown if no sale appears in the horizon.

Report at least:

- number of alerts;
- negative-realized alerts (target: zero on the fixture set);
- median and minimum realized profit;
- time-to-sell calibration;
- false-suppression rate on known good fixtures;
- parity between fast and normal lanes.

Do not tune thresholds on the same fixtures used to report success. Hold out a portion. If the data
set is too small, say so and keep the stricter gate.

### 9.4 Performance and reliability

- Do not regress the 2-second feed cadence or the shared rate budget.
- Do not add unbounded queues, threads, or maps.
- Ensure stop/start and retry generation guards still work.
- Run a scan against a live API only as an optional smoke test; do not make CI depend on it.
- Report exact commands, exit codes, and observed counts. Do not claim a runtime test that was not
  performed.

---

## 10. Acceptance criteria

The task is complete only when all of these are true:

1. `cd ahflip && ./verify.sh` exits 0.
2. `cd collector && ./build.sh` exits 0.
3. New deterministic probes are wired into `verify.sh` and pass.
4. Every accepted auction path computes exact fees and additional costs.
5. No test can construct an accepted flip with non-positive conservative net profit.
6. No test can construct an accepted flip from an unsupported ask, mismatched variant, stale
   snapshot, or pending required evidence.
7. Fast and normal lanes use the same evaluator and deduplicate by UUID.
8. Announced IDs survive a restart (or the limitation is documented and the requirement is
   explicitly waived by the owner).
9. `/discordia status` distinguishes healthy/no-flips from feed, detail, rate-limit, and evidence
   failures.
10. README and algorithm documentation are updated with the new model, defaults, and remaining
    limits.
11. No build artifacts, caches, secrets, or local configs are committed.
12. The commit is pushed to the same repository, with a report of exact commands and results.

If any criterion cannot be met, stop and report the blocker rather than claiming success.

---

## 11. What not to do

- Do not add auto-buy, auto-bid, macro, packet reading, UI scraping, or any automation.
- Do not lower `minProfit`, margin, confidence, support, or global-sample requirements just to make
  the feed busier.
- Do not treat an asking price as a sale.
- Do not use the base pool for an item with premium attributes.
- Do not use a global median to lift a thin local print.
- Do not remove the recomb or scroll ceiling because a particular listing “looks cheap”.
- Do not count pending/unavailable as zero.
- Do not announce an item twice because fast and normal paths both saw it.
- Do not claim “guaranteed profit” or “zero risk” anywhere.
- Do not add a new dependency unless the existing plain-Java approach cannot do the job; if you do,
  update the build scripts and docs and keep the collector build working.
- Do not rewrite history or force-push.

---

## 12. Deliverable and report format

When done, provide:

1. A concise summary of the algorithm changes.
2. The exact files changed.
3. The exact build/test commands and exit codes.
4. Before/after metrics from the deterministic probes, with the fixture scope stated.
5. Any live smoke test details, clearly separated from offline tests.
6. Remaining limitations and risks.
7. The commit hash and confirmation that it was pushed to the same repository.

Update this file's status section if the implementation differs from the plan. Do not silently
change the mission.

---

## Appendix A — Current defaults

These are the baseline values in `FlipConfig`; preserve their meaning unless a migration is
documented. New safety parameters should default to stricter, not looser.

```text
budget                         50,000,000
maxBudgetShare                 0.25
maxItemCost                    0
minProfit                      400,000
minMarginPct                   8
maxAuctionMarginPct            60
maxRelativeSpread              0.6
minConfidence                  0.35
minAuctionListings             6
minAuctionSalesPerDay          1
minAuctionSalesForFlip         1
fullSalesTrust                 5
thinSalesMarginPct             5
maxThinAnchorOverSales         1.0
maxBuyOverGlobalMedian         1.15
maxBuyOverGlobalMedianWithSales 1.25
maxTargetOverGlobalMedian      1.40
minGlobalSalesForPriceGuard    8
requireGlobalPriceForPlainFlips true
largeProfitOverride            1,000,000
bookOnlyFlips                  true
bookOnlyMinProfit              1,000,000
bookOnlyMinMarginPct           8
bookOnlyMinConfidence          0.35
bookOnlyMinSupport             2
minAuctionSupport              2
minVariantListingsWithSales    3        (listing floor with >=5 exact-variant sales)
conservativeSalesQuantile      0.25     (hard gate uses p25, not the median)
riskBufferPct                  1.5      (reserved off the exit for drift/relist/slippage)
maxHoldHours                   168      (7 days; 0 disables)
maxBookOnlyHoldHours           72       (book-only has no sale evidence behind the target)
unknownLiquidityMarginPremiumPct 2      (added to the margin gate when the hold is unknown)
unsoldMarkdownPct              10       (markdown that forces an unsold item to move)
capitalChargePerDayPct         0.5      (opportunity cost of locked coins)
requirePositiveRiskAdjusted    true
recombCost                     11,000,000 (fallback; live bazaar ask preferred)
auctionReferenceQuantile       0.25
bazaarTax                      0.0125
bazaarCapture                  0.10
minBazaarMarginPct             0.2
maxBazaarMarginPct             15
minBazaarPrice                 10
minBazaarUnitProfit            1
minCoinsPerHour                50,000
minBazaarSalesPerDay           1,000
minNpcProfit                   5,000
minNpcMarginPct                1
minForgeProfit                 200,000
maxForgeMarginPct              150
minKatProfit                   250,000
minKatMarginPct                10
minKatListings                 2
snipeWindowMinutes             30
snipeMinLeadSeconds            45
minSnipeProfit                 100,000
minSnipeMarginPct              10
minSnipeConfidence             0.25
minSnipeListings               3
limit                          100
fastAuctions                   true
scanIntervalMs                 5,000
fullRefreshMs                  600,000
```

Fee helpers:

```text
AH listing fee: price < 10M ? 1% : price <= 100M ? 2% : 2.5%
AH claim tax:   sale <= 1M ? 0 : min(1% × sale, sale - 1M)
AH net:         sale - listingFee - claimTax
```

The fast lane uses a 2-second poll, a 1.35× tag-price prefilter, and the shared
100/minute + 30/10-second Cofl budget. The normal page-0 scan remains the fallback.

---

## Appendix B — Definition-of-done checklist

- [x] Read the current engine and README.
- [x] Baseline build/tests recorded.
- [x] Evidence states modeled; unknown never silently zero.
- [x] Exact variant and cosmetic rules preserved/extended.
- [x] Conservative lower bound and risk buffer implemented.
- [x] All fees and add-on costs included exactly once.
- [x] Recomb/scroll/attribute/weekly ceilings enforced.
- [x] Liquidity and expected horizon modeled.
- [x] Fast and normal evaluator unified (both lanes already call `FlipEngine.compute`).
- [ ] Duplicate alerts prevented across restart — still in-memory only; documented as a limit.
- [x] Fast-lane status/diagnostics exposed (plus per-scan reason codes).
- [x] Regression fixtures added to `verify.sh`.
- [x] Collector build passes.
- [x] README/brief updated.
- [x] Commit pushed to the same repository.
- [x] No guarantee language; limitations documented.

---

## 13. Implementation status (2026-11-24)

This section records what was actually built against the plan above, and where it differs.

### 13.1 What changed

| Area | Change |
|---|---|
| Conservative exit | New `RiskModel.conservativeGross`: gross exit = `min(target, realized p25) × (1 − riskBufferPct)`. The p25 is used only when at least `fullSalesTrust` (5) exact-variant sales exist, because the Cofl summary carries no quartile and `median` must not be passed off as `p25`. Profit, margin and every gate are then computed at that floor; the ask is still displayed as the target. |
| Hold horizon | New `maxHoldHours` (168) and `maxBookOnlyHoldHours` (72). A known hold beyond the horizon is `CAPITAL_TRAP`. An unknown hold adds `unknownLiquidityMarginPremiumPct` to the margin requirement instead of passing silently. |
| Risk-adjusted gate | `RiskModel.saleProbability` is Poisson (`1 − exp(−horizon/hold)`); expected profit subtracts the unsold cost (one more listing fee + `unsoldMarkdownPct` markdown) and a capital charge (`capitalChargePerDayPct`). `requirePositiveRiskAdjusted` refuses a non-positive expectation. |
| Thin-book rescue | The listing floor drops from `minAuctionListings` (6) to `minVariantListingsWithSales` (3) when the exact variant has ≥ 5 realized sales. Spread, support, anchor and every ceiling are unchanged; realized sales now also carry confidence on their own (`min(1, n/10)`), which is what makes the rescue usable. |
| Blade capital | `buy price + missing scroll cost` must fit inside `maxCost()`. Previously only the listing price was checked, so a cheap partial blade needing hundreds of millions of scrolls passed the budget gate. |
| Snipes | The non-BIN reference takes the same `riskBufferPct` reserve, because a bid is not a purchase. |
| Reason codes | New `Rejections` with 24 stable codes. The auction and snipe paths increment one per refusal; `FlipEngine.rejections()` exposes them, `/discordia status` shows a `why` row for the last scan, `FlipConsole` prints them, and the probe asserts them. |
| Build | `build.sh` falls back to `python3` when `node` is missing, so the documented build works on a machine without Node. The four shell scripts are now committed executable (they were 100644, so `./build.sh` failed with "Permission denied"). |
| Probe harness | `GlobalFailureProbe`'s `FAULT404_RING` wait was 2 s against a 2 s in-loop retry sleep and failed about two runs in three. The assertion is unchanged; the wait is now 8 s. |

### 13.2 Where this differs from the plan

- **No new `Evaluation` type.** Section 6.1 suggests a pure evaluator returning a typed result.
  Both lanes already share `FlipEngine.compute`, so the reordering needed (sales evidence before
  the pool anchor) was done inside `auctionFlips` and the result is still a `Flip`. Changing the
  `Flip` record would have broken `CollectorClient` and `FlipConsole` for no behavioural gain.
- **The lower bound is variant-local, not global.** `GlobalSales` sets `p25 = median` because the
  Cofl analysis endpoint exposes no quartile (see its comment at the return of `fetch`). Applying
  that value as a hard lower bound would have quietly made the weekly median a floor, which
  suppresses nearly every plain-item book flip. The gate therefore uses a real p25 where one
  exists (≥ 5 local exact-variant sales) and relies on the existing weekly-median caps elsewhere.
- **Announced IDs are still memory-only.** Criterion 8 is not met. It is listed as a known limit
  rather than silently claimed.
- **No replay/backtest probe (9.3).** The fixture set is too small and too synthetic to tune
  thresholds on, and section 9.3 itself says to keep the stricter gate in that case. The guard
  probe covers the invariant half of 9.2 instead.

### 13.3 Commands run and results

```text
$ cd ahflip && ./verify.sh                 -> exit 0
  java ... GlobalFailureProbe              -> PASS (9 offline cases, loopback only)
  java ... GlobalGateProbe                 -> PASS (Titanium 8.90M -> 10.78M, +1.40M, 16%)
  java ... CoflContractProbe               -> PASS (offline alias checks)
  java ... AlgorithmGuardProbe             -> ALL GUARDS PASS (59 checks)
$ cd collector && ./build.sh               -> exit 0
$ cd ahflip && ./build.sh                  -> build/libs/discordia-1.0.0.jar
```

Alerts on the fixture set: no accepted auction flip books a non-positive profit (the gate is
structural), and the Titanium control's booked profit fell from 1.56M to 1.40M purely because it is
now measured at the conservative floor rather than at the ask. That is the intended direction.

### 13.4 Remaining limits

1. The p25 gate needs five exact-variant sales; below that the median-based target is used with
   the 1.5% reserve only. A thin sample still cannot produce a lower bound.
2. The Poisson sale probability treats sales as a stationary process. It is not: mayors, events
   and updates move rates, and a 7-day window smooths that away.
3. Nothing here makes a player-driven market safe. No alert is guaranteed, risk-free or certain,
   and a crash, a manipulated listing or a missed variant can still lose coins.

