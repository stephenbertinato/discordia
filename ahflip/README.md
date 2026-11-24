# Discordia

A client-side Hypixel SkyBlock flip finder. It reads public market data, applies conservative
filters, and prints live candidates to your own chat.

The in-game client is **auction-house only**. Bazaar order flips and conversion income are not
generated or sent to chat; the bazaar feed is used only when available to value AH items. The
standalone console retains those extra sections for diagnostics.

**It never buys, bids, clicks, or confirms anything.** Clicking a chat notification or running
`/discordia open` opens one auction (`/viewauction <uuid>`); you still decide and click Buy. The mod
does not poll or track keyboard input; all actions are chat commands or clickable chat lines.

## Live chat notifications

Discordia starts with live notifications enabled. Bare `/discordia` toggles them off or back on.
While on, **every new qualifying BIN flip** is announced as soon as it enters the feed, as a
clickable chat line with
the buy price, target sale price, realized median, estimated profit, and expected time to sell.
Only BIN auctions are shown by default. Non-BIN ending auctions stay hidden until
`/discordia toggle nonbin`; when enabled, `/discordia list` groups them separately and notifications
are tagged `[BIN]` or `[non-BIN]`. Clicking a line runs `/viewauction <uuid>`; it does not buy anything
for you. A red `[ignore]` button at the end of the same line hides that item name from future
notifications. Ignore entries persist in `config/discordia-ignored.txt`; `/discordia unignore <item>`
restores one.

On a fresh install the mod starts the collector feed automatically inside the game; the scanner
subsection below describes that same heartbeat/page-0/full-book loop either way. The scanner uses up
to 100 results, accepts a six-listing book, and keeps strong gaps even when there is no same-variant
sale. Those unverified entries are marked `[book]` and use the next real
listing as the target; their median is `-`. They still require two supporting listings, at least
1M profit, an 8% margin, 35% confidence, at least 1 recent sale/day for the item, and an exact or
same-variant group pool (never the mixed base-item pool). Those entries are suppressed when the
market-wide 7-day summary has zero sales. If the global summary is unavailable but local rows
exist, the observed local rows are used; with no local rows the item stays pending/unavailable
rather than being treated as proof that it never sells. Plain items additionally require a
trusted weekly median before they are shown (see the plain-item guard below), because a thin
or pending summary is exactly where a pumped active ask can look like the resale price.
At first scan the public SkyBlock item resource is loaded from
`/resources/skyblock/items` (about 5,700 item ids), with the v2 route as a fallback; the live check
showed both routes returning the same 5,691 ids. Every live auction, bazaar, sale, and global-summary
id is registered too. An item with no sale rows resolves to an explicit `count=0, perDay=0` record
instead of `null`; the status line reports the catalog, known-id, and global-cache counts. If the
resource endpoint is unavailable, live ids are still registered and the catalog count stays at zero
until a later retry.
An existing `ahflip-sales.json` file is copied to its `discordia-sales.json` name on first launch, so
the old 7-day history is retained.

The budget is read from the Hypixel sidebar `Purse:` line (commas and `k`/`m`/`b` suffixes are
supported) and is updated as the purse changes. There is no separate budget setting in the mod.

### Chat commands

The command tree is registered locally, so `/discordia` and its subcommands appear in Minecraft's
tab completion. It is still a client-side command: the server never receives it.

```text
/discordia                     toggle live notifications
/discordia toggle              toggle live notifications
/discordia toggle nonbin       show/hide non-BIN ending auctions
/discordia list                print the current BIN flips
/discordia open                open the best flip (/viewauction <uuid>)
/discordia copy                copy the best flip's command to the clipboard
/discordia margin 10%          minimum auction/snipe margin
/discordia minprofit 500k      minimum auction/snipe profit
/discordia maxcost 100m        maximum item cost (off clears it)
/discordia status              show the active values and global-source health
/discordia help                show the compact command list
/discordia collector           show the automatic in-mod collector
/discordia collector <url> [token]  optional external collector
/discordia collector off       return to the local scanner
/discordia globalfault 429     developer-only in-game Cofl failure injection
/discordia globalfault off     return to the real Cofl source
```

Command responses use a compact colored `[Discordia]` layout: `OK` for accepted changes,
`USAGE` for malformed input, `!` for warnings, and indented rows for status data. Amounts accept
`K`, `M`, and `B` suffixes, decimals, and commas. Settings are saved in
`config/discordia-config.json` and apply to the next scan. `maxcost` is also bounded by the purse;
book-only auction flips keep their stricter 1M/8% safety floor even if the normal floor is lowered.

## How the scanning works

The auction dump is expensive and the market only ticks about once a minute. Measured:

| Endpoint | Size | Role |
|---|---|---|
| `/auctions?page=N` | **~2.4 MB per page**, 42 pages | the book |
| `/auctions_ended` | **~68 KB** | heartbeat + realized sales |

**Reading `/discordia status`:** `scan` is the last collector poll/push, while `tick` is the last
Hypixel market update. The scanner polls every 3–5 seconds, but Hypixel publishes a new auction
tick only about once a minute, so a healthy feed normally shows a small `scan` age and a larger
`tick` age. A large `scan` age means the poll loop/connection is stalled; a large `tick` age alone
is normal and does not mean the scanner stopped.

`auctions_ended` carries the same `lastUpdated` as the auction dump (verified: both reported
`1795507192072`), so it is a cheap "has the market moved?" probe. Page 0 is sorted newest-first
(verified: page 0 start times run up to the current tick, page 41 is 20 minutes older), so every
newly listed auction shows up there.

So the loop is:

1. Poll the 68 KB heartbeat every **5 s** (the embedded collector uses **3 s**). Also ingest its sales into the rolling stats.
2. When `lastUpdated` moves, fetch **page 0 only** (~2.4 MB) and score those ~1,000 newest listings
   against the cached book.
3. In parallel, poll SkyCofl's fast newest-auction feed and score qualifying new BINs immediately.
4. Rebuild the full book (~100 MB) only every **10 min**, to keep reference prices honest.

That is roughly **800 MB/hour** at the defaults instead of ~6 GB/hour for a full dump every tick.
It is the price of live data on a client; raise `fullRefreshMs` to trade freshness for bandwidth.
A hosted collector shared by many players (the Cofl model) is the only way to make this cheap.

### Fast auction lane

The normal loop is limited by Hypixel's market tick, so it cannot see a listing until Hypixel
publishes the next auction snapshot. While that is running, Discordia also watches
[SkyCofl's newest-active-auction feed](https://sky.coflnet.com/data) every **2 seconds**. The feed
is normally CDN-cached for five seconds, so the fast lane uses a cache-buster to read the current
in-memory snapshot instead. SkyCofl shows new auctions within a few seconds, and its auction-detail
route carries the NBT and enchantment data needed to rebuild the same variant keys as the normal
book.

For a new BIN, the fast lane only fetches details when the item's tag is already in the cached
book and the ask is within **1.35×** of the cheapest cached listing for that tag. Every eligible
auction is put in a priority backlog immediately; a separate drain thread works through it
continuously instead of discarding everything beyond a per-poll cap. Failed detail lookups stay in
the backlog and retry, so a burst cannot strand a good flip when the feed rotates.

The feed polls, auction-detail requests, and global-sales lookups share one Cofl rate budget
(100/minute and 30/10 seconds, with global lookups capped below the fast lane's share). It then
runs the same `FlipEngine`, price guards, global-sale checks, and filters as the normal scan. Fast
results are merged for up to two minutes and normal page-0 results always win by auction UUID. If
SkyCofl is unavailable, slow, rate-limited, or has an incomplete detail row, the normal scan
continues and the fast lane retries instead of silently dropping the candidate.

This lane uses SkyCofl's public API under its normal rate limits; it needs no account or API key.
It is best-effort, not a guarantee that every new auction is seen before the next Hypixel tick. The
normal page-0 scan remains the source of truth and will fill in anything the fast lane missed.

### Collector mode

The mod starts a collector **inside the game process** on a fresh install. It binds an ephemeral
loopback port, starts the same heartbeat/page-0/full-book loop, computes flips server-side, and
connects the client over WebSocket. There is no token, separate process, or setup step. The feed is
local-only and the port is random, so other machines cannot connect to it.

```text
/discordia collector           show the automatic in-mod collector
/discordia collector off       stop the embedded collector and resume the local scanner
```

The embedded collector is still useful for users who want a separate feed later, or for a shared
private collector. The repository also includes a standalone collector under `../collector/`; it
adds a token and a fixed port for that advanced case. The in-game client consumes the same protocol:

```text
/discordia collector ws://127.0.0.1:8787/ws <token>
/discordia collector off
```

Build and run the standalone option with `collector/build.sh` and `collector/run.sh`; see
`collector/README.md`. The client stops its local scanner while any collector is active and
reconnects automatically.

Collector filters are synchronized in both directions. When you change `/discordia margin`,
`/discordia minprofit`, `/discordia maxcost`, or the purse (budget), the client sends the new
values over the WebSocket. The collector recomputes from its last fetched book immediately, so
lowering a filter can reveal additional flips; raising one removes them server-side too. The
engine's book-only safety floor (8% and 1M) still applies. Ignore entries and the non-BIN
show/hide switch remain display filters in the client.

The collector still waits for Hypixel's ~60-second market tick; it removes duplicate polling and
gives several clients one feed, not the upstream limit.

### What this does and does not fix

It removes timer drift: you no longer sleep a blind 60 s and miss a whole tick. You see new listings
within ~5 s of the API publishing them.

It does **not** make you faster than the API tick itself. Everyone polling the same endpoint sees the
same listings at the same moment; the edge is in the filters, not in the polling. Anything faster
means reading the in-game auction house UI, which is automation and against Hypixel's rules.

## Build

```bash
./build.sh
```

First run downloads the 26.2 client jar, Fabric Loader, Gson and Minecraft's library set into
`.tooling/` and `libs/` (gitignored). Output: `build/libs/discordia-1.0.0.jar`.

Install: Fabric Loader 0.20.1+ profile for 26.2, drop the jar in `.minecraft/mods`. No Fabric API
dependency.

Minecraft 26.x ships **unobfuscated** and Mojang publishes no `client_mappings` for 26.2, so Loom
cannot provision mappings (`Failed to find official mojang mappings for 26.2`). The build compiles
directly against the client jar. That is why there is no `build.gradle`.

## Run the engine without Minecraft

```bash
java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.FlipConsole --budget 50m
```

Flags (both `--flag value` and `--flag=value`):

| Flag | Default | Meaning |
|---|---|---|
| `--budget` | `50m` | total coins you will deploy |
| `--min-profit` | `400k` | minimum profit per flip, after tax |
| `--margin` | `8` | minimum auction return, percent |
| `--max-margin` | `60` | auction sanity cap |
| `--confidence` | `0.35` | minimum price-confidence score |
| `--limit` | `100` | results per market |
| `--no-bazaar` / `--no-ah` | | skip a market |
| `--fast` | off | newest page only, scored against the full book (mimics the in-game loop) |

The console keeps its sale history in `build/discordia-sales.json`, so repeated runs accumulate volume.

## Layout

```
src/main/java/dev/mmm/discordia/
  core/            plain Java, no Minecraft imports
    HypixelApi     public bazaar + auction endpoints, parallel page fetch
    MarketSnapshot parsing, order-book sides, BIN grouping
    Nbt            minimal NBT reader: item_bytes -> ExtraAttributes + lore
    ItemVariant    reforge, stars, pet level and enchant signature keys
    SoldStats      7-day realized-sale window, persisted
    FlipEngine     filters and maths
    Tax            bazaar tax, AH fee brackets, claim tax
    FlipConfig     every tunable
  tools/FlipConsole  run the engine without Minecraft
  DiscordiaClient     entrypoint, key polling, chat output
  MarketService    heartbeat scanner, full book every 10 min
  CollectorClient  WebSocket client used by collector mode
```

The embedded collector reuses the standalone collector sources under `../collector/`; the mod build
compiles those plain-Java classes into the jar, so there is no extra runtime dependency.

## Auction snipes (non-BIN, ending soon)

A separate list of normal auctions whose current bid is well under the BIN reference price. Non-BIN
auctions are a different market: a live dump had **2,602** of them against 38,614 BIN listings, and
they resolve on a timer instead of a fixed price, which is why they are not mixed into the BIN flips.

For each non-BIN auction the mod reads `end`, `highest_bid_amount` (falling back to `starting_bid`
when nobody has bid) and the same item variant signature used elsewhere. The reference is the BIN
pool: exact variant first, then the enchant-stripped group, then name/tier — at least 3 comparables.
That thin floor is deliberate: requiring the usual 8 would have excluded almost every auction item.

Filters: ends in 45 seconds to 30 minutes, bid within `budget × maxBudgetShare`, at least **100k**
profit after resale tax, at least **10%** margin, confidence ≥ 0.25. Results are sorted by **end
time**, not profit, because the section is only useful if you can still bid. Each row shows a **max
bid**: the highest bid that still leaves the minimum profit, so you never have to do the fee math
while a timer runs.

Live output:

```
Scythe Blade   LEGENDARY   5.49M -> 6.48M   +863.3k   16%   ends in 13m
  /viewauction 1659cb4a3fbe47dea2942093bad01a54
  ends in 13m, bid 5.49M vs 6.48M; max bid 6.25M
```

This section is **sparse by nature**: only 21 auctions ended within 10 minutes of that dump, and
most had bids already near the reference. One hit passed the filters. Widening the window to 30
minutes is what makes it useful without scraping the bottom of the book.

The bid is not a purchase: another bidder can outbid you, and the max bid is a ceiling rather than a
target. `J` opens the auction so you can bid yourself; nothing here bids for you.

## Deterministic income

A separate section for coins that do not depend on market timing.

**Listings under the NPC price.** Prices come from
`/v2/resources/skyblock/items` (`npc_sell_price`, 2,375 items with a positive value); the auction
lore does not contain the NPC line, which is why the resources endpoint is used. A listing qualifies
when `npc − price ≥ 5k` and the margin is at least 1%. Those thresholds are deliberately small:
the largest gap in a live dump was **19.2k on an 800k item**, so a 100k floor would have hidden
every opportunity. There are no fees on either side, but Hypixel's daily NPC sell cap applies, and
prices assume one item per listing (stacks would make the gap larger, never smaller).

**Forge conversions.** Recipes are bundled from the NotEnoughUpdates repo (`items/*.json`,
`recipes[type=forge]`): inputs, output count and duration. At runtime the cost is built by **walking
the bazaar ask book**: each input level contributes `price × available amount`, so 160 Enchanted
Mithril is priced at what those 160 units actually cost, not at the top ask. The output is sold the
same way through the bid book. If either side lacks enough depth, the recipe is skipped. Revenue is
that fill minus 1.25% tax and `per day = profit × 86400 / duration`. Only recipes whose inputs *and*
output are bazaar-traded are priced; anything else is skipped rather than guessed.

Margins above **150%** are rejected (`maxForgeMarginPct`). A number that large usually means one
lonely bid or one cheap level, not an edge. Bundled today: Refined Diamond/Mithril/Titanium,
Bejeweled Handle, Golden Plate, Perfect Amber Gem, Drill Engine, Mithril and Titanium Drill Engines.

Live output:

```
Perfect Chestplate - Tier I   RARE   800.0k -> 819.2k   19.2k   2%   NPC
Golden Plate (forge)           -     1.59M -> 2.28M     687.7k  43%  2.75M/day
Drill Engine (forge)           -     3.12M -> 4.26M     1.14M   36%  910.4k/day
```

Refined Mithril is now excluded: its 256% margin survived the depth walk (160 units really were
available at 1,404 and the 809k bid was real), which is precisely the kind of number the cap exists
for.

`J` on a forge conversion does nothing useful (there is no command), so the mod tells you to open
the forge instead.

**Kat upgrades.** `kat.json` is generated from the official Hypixel SkyBlock Wiki's `Kat/Costs`
table (fetched 2026-11-24): **209 upgrades across 61 pets**, each with a base coin cost, a duration
and its material list. Material names are resolved to SkyBlock ids through
`/v2/resources/skyblock/items`, so no names are hand-mapped. This is a verifiable source rather
than a copied spreadsheet — the same table lists Armadillo's Legendary→Mythic upgrade that the
SkyblockRepo pet JSON omits.

Only **level-1** pets are evaluated. Kat's coin cost drops by 0.3% per level, and level 1 is the
only level where the published base price is exact; a level-1 pet also stays level 1 because Kat
preserves XP. Buy price is the median level-1 listing of the source rarity, sale price is the lower
quartile of the level-1 target rarity after AH tax, and materials are priced at the live bazaar ask.
Both pools need at least 2 listings and every material must be bazaar-traded; otherwise the upgrade
is skipped. One pet can be at Kat at a time, so `per day = profit × 86400 / duration`; Kat Flowers
and Bouquets can shorten the timer but cost coins and are not modelled.

From a live book: 206 level-1 pet keys, 38 profitable upgrades. Examples: Pig Common→Uncommon at
1.69M + 327k materials → +1.80M in 1 minute; Armadillo Legendary→Mythic at 13M + 1M + 8.28M
materials → +4.85M in 1 hour. Short, thin upgrades rise to the top of the per-day ranking, which is
why the detail line always shows the pool size (`max 2 listings`).

## Variant keys

A 5★ Heroic Hyperion with Ultimate Wise V is not the same item as a clean 3★ one, so listings are
no longer pooled by name alone. Each listing is keyed by:

| Part | Source |
|---|---|
| base item + tier | `item_name` + `tier` |
| reforge | `ExtraAttributes.modifier`; recent sales use the exact reforge first, then a reforge stone in the same price band |
| stars | `ExtraAttributes.upgrade_level` |
| pet level | `[Lvl N]` in the display name (`petInfo` carries `exp`, not a level) |
| pet tier | `petInfo.tier` — the **actual** rarity, not the auction's displayed one |
| held pet item | `petInfo.heldItem` (for example `PET_ITEM_TIER_BOOST`) |
| enchants | `ExtraAttributes.enchantments`, sorted, FNV-1a hashed |

The reforge does matter, but its value is evidence-based. Recent sales are checked with the **exact
reforge** first. Only when that reforge has no sales does the engine fall back to reforges whose
stone cost is in the same band (Withered/Fabled are close; Lustrous/Gilded are not). The reforge word
is stripped from the display name before pooling, so a Withered, Fabled or Spicy copy with the same
stars and tier still caps the others — that is what killed the Shadow Fury false positive. The detail
line says `(cheap reforge pooled)` when the pool is not a single reforge.

**Recombobulator:** `rarity_upgrades` marks a recombed item. A **non-recombed** listing is capped by
the cheapest recombed copy of the same item, stars and pet level, minus the live Recombobulator ask
(about 10–11M). A recombed listing is not capped. This is why a non-recomb Atomsplit at 50.9M cannot
be sold at 56M when a recombed copy exists at 58M — the recomb makes the non-recomb effectively a
47M item, so the listing is rejected. The recombed Storm's Boots and Primordial Helmet are priced
normally; the cap only applies to the non-recombed versions.

**Wither Impact scrolls:** `ability_scroll` is parsed for Necron blades (`HYPERION`, `ASTRAEA`,
`SCYLLA`, `VALKYRIE`). All three of `IMPLOSION_SCROLL`, `SHADOW_WARP_SCROLL` and
`WITHER_SHIELD_SCROLL` mean **Wither Impact**. A blade missing scrolls is priced against full
Wither Impact listings of the same item/stars/tier, minus the **instant bazaar cost** of every
missing scroll; the bazaar depth must fill all of it in one purchase. If that arithmetic does not
leave the normal profit/margin, the blade is skipped. Scrolls are currently about 509M for all
three; a blade whose full copy is not worth more than the base plus those scrolls is never shown.

Pools widen only when the data forces it, and each step costs confidence:

| Pool | Key | Used when | Multiplier |
|---|---|---|---|
| exact variant | every part above | ≥ `minAuctionListings` listings | ×1.00 |
| same variant, any enchants | drops the enchant hash | exact pool too thin | ×0.92 |
| base item pool | name + tier | both too thin | ×0.80 |

Each flip's detail records which pool priced it, for example
`heroic, 5★, 26 ench [exact variant] next of 41 BINs`. On a live book of 20 auction flips, 15 priced
from an exact pool and 5 from the enchant-agnostic group. Page 0 split 877 listings into 508 exact
pools versus 496 base pools, so the extra dimensions are doing real work rather than collapsing.

Pets need special handling: every pet's NBT id is `PET`, so the id comes from `petInfo.type` and the
level from the display name. Rarity comes from `petInfo.tier`, **not** the auction's displayed
tier: a held **Tier Boost** makes an Epic pet display as Legendary, and is priced as the Epic it
actually is, with `PET_ITEM_TIER_BOOST` kept in the key. Pet pricing stays inside the **same level
bucket**; a Lvl 1 copy is not allowed to cap a Lvl 100 pet, because the level is most of the value.

**Skins, runes, and dyes:** unapplied skin items, **all runes**, and **all dyes** are dropped at parse time
(`isExcludedCosmetic`: name contains `skin`/`rune`, the SkyBlock id contains `SKIN`/`RUNE`, or the
ExtraAttributes carry a `runes` compound). An applied skin adds no value and does not change the
variant key, so the item is priced exactly like the regular version; an item carrying an applied
rune is dropped instead. Armor carrying an applied dye (`dye_item`) is dropped too, so a dyed
chestplate cannot set or inflate the price of the undyed version. Dye sales are excluded from new
sale statistics for the same reason.

## Time to sell

Hypixel gives no way to join a sold auction back to the listing it came from. That was verified
against live data, not assumed:

- `auctions_ended.auction_id` matched **0 of 168** active `uuid`s, even after normalising dashes;
- matching on seller + price produced pairs with **different `item_bytes`** — coincidences, not the
  same listing;
- matching on NBT signature + seller + price hit 8 of 40 sales, only **2 unambiguously**, with
  durations spread from 5 minutes to 14 hours.

That is too noisy to put in front of a player, so time-to-sell comes from throughput instead
(Little's law): **`(listings + 1) / sales per hour`**.

- **Sales per hour** — realized BIN sales with the same variant signature, 24-hour window,
  requiring at least 3 sales.
- **Listings** — the active pool the flip was priced from (exact, group, or base).
- Falls back to the base item's rate when the exact variant is too sparse, and reports nothing when
  there are no recent sales at all.
- Denominator is floored at 1 hour and capped at 24, so a fresh install cannot imply 60× the rate.

Example from a seeded run: 41 listings competing, 6.0 sales/hour → `~7.0h`. The chat line appends
`~7.0h`, the console has a `SELLS IN` column, and the detail reads
`41 listed, 6.0 sales/h -> ~7.0h`.

It is a market average, not a stopwatch on one listing: it counts overpriced listings that will
never sell, which skews slow, and ended sales carry no rarity tier.

## Attribute mispricing

Variant keys say *what* differs; this says what the difference is worth. The rule is that an
attribute is only worth something if this exact item has **realized sales carrying it**:

| Input | Use |
|---|---|
| plain sales (0 stars, no reforge, pet 0, ≤2 enchants, no ultimate) | baseline, needs ≥5 |
| sales with the same stars / reforge / pet level **and the ultimate** | comparable, needs **≥4** and **≥5% of the item's sales** |
| bazaar book price (`ENCHANTMENT_<NAME>_<LEVEL>`) | hard ceiling on an ultimate's value |
| sales per day for the item | liquidity discount: 1.0 / 0.75 / 0.5 / 0.25 |

`premium = median(comparable) − median(plain)`, capped by book cost and scaled by liquidity.

**The Aspect of the Jerry rule:** a Soul Eater V book dropped on an item nobody buys has no
comparable sales, so the comparable set is empty and the valuation is discarded. Value is never
transferred from a book price onto an item that has no market for it. A guard test seeds 10 plain
sales and zero attribute sales — the valuation is `null` and nothing is surfaced.

**The lucky-sales rule:** the comparable set is now only sales that actually carry the attribute
(all of the variant's ultimates, plus the same stars/reforge/pet level), and it needs **more than
three** of them *and* at least **5% of the item's total sales**. Three lucky sales, or four sales on
an item that trades 200 times a week, no longer create a premium. A plain variant (no ultimate,
stars, reforge or pet level) has no attribute to value at all and returns `null`.

A listing becomes an attribute play when all of these hold:

- its price is within 15% of the plain median;
- the evidenced premium is at least 1M and at least 15% of the price;
- it is within budget.

The target becomes `min(comparable, plain + premium)`, the profit is recomputed, and the sanity cap
widens from 60% to 150% **only when that comparable target is the one actually used**. A higher
asking price never qualifies for the wider cap.
Attribute plays need no book at all — if the pool is too thin they are priced from comparables alone
and the detail says `[attribute comparables] no book`.

They show up with a `★` in chat and a second `ATTR` line in the console:

```
ATTR plain 6.19M, similar 19.50M | 5★, ancient, 6 ench [attribute comparables] no book
= 12.85M; 5.0 sells/day (n=10, median 6.19M); 5 listed, 0.4 sales/h -> ~14.4h
```

Limits: only ultimate enchants are valued individually (normal enchants are filler), pet level
comparables use a ±15 level band, and everything is based on the 7-day sale window.

## Why the filters look like this

- **Bazaar:** Hypixel's `buy_summary` is the ask side and `sell_summary` is the bid side (they are
  named from the opposite perspective, which is why `buyPrice` is always above `sellPrice`).
  The engine takes the top of both books, applies the 1.25% tax, and requires a margin between
  0.2% and 15%, 1,000+ units/day of two-sided volume, and ≥50k coins/hour using a 10% capture rate
  capped by how many units your budget can hold.
- **Auctions:** with **5+ sales of the exact reforge**, the target is 98% of the realized median,
  capped by 99% of the next-cheapest BIN. If that reforge has no sales, sales from a reforge stone
  in the same price band are used (Withered/Fabled, for example; never Lustrous vs cheap). With
  **1–4 sales**, the target is the next listing, but only if it is not above
  `maxThinAnchorOverSales` (1.0×) the realized median and the margin clears
  `thinSalesMarginPct` (5%); a next listing many times the sale price is treated as manipulated.
  The listing must be the cheapest, with at least two other listings within 10% of the anchor, at
  least **one** comparable sale and ≥1 sale/day. Margins are capped at 60% (150% only when an
  attribute comparator sets the target).
- **Pets are included.** Rarity is the actual `petInfo.tier`, so a Tier Boosted Epic is never priced
  as Legendary. Held items and pet level are part of the variant; comparisons stay in the same level
  bucket. A Tier Boost is only bought when that actual-tier, tier-boosted pool supports the resale
  price.
- **Skins are never valued.** Unapplied skins are skipped entirely. Applied skins add nothing and
  are priced as the plain item.
- **Dyes are ignored.** Standalone dyes and armor with an applied dye are excluded from listings,
  pools, and new sales evidence, so dye value can never inflate a normal armor flip.
- Percentage margins normally require 8% (5% for a thin sample), but a **≥1M absolute profit**
  bypasses both: a 2% return on a 380M pet is still 5.9M coins and worth taking when sales support it.

A live book after these changes no longer contains the Withered Shadow Fury: cheap-reforge pooling
made it compete with Fabled copies and it did not survive. The same book showed why the thin-sales
guard matters: Pesthunter Artifact had a 7.82M next listing but a 1.10M median across four sales,
so it was rejected rather than shown as +2.66M. The non-recomb Atomsplit was also rejected by the
Recombobulator ceiling, while already-recombed blades are priced normally.

**Plain-item weekly-median guard.** A plain item (no stars, reforge, pet level, ultimate, scroll,
recomb or held pet item) must have a trusted Cofl weekly summary before a BIN flip is shown. It
needs at least **8** weekly sales; the asking price may not exceed **1.15×** the weekly median on
thin local evidence, or **1.25×** when at least **5** recent exact-variant sales support normal
price fluctuation. The projected resale target is capped at **1.40×** the median. This still stops
a 40M Gyrokinetic Wand with a roughly 25M weekly median and no thick local support. The guard
applies to BIN flips universally for plain items and to non-BIN snipes whenever a weekly summary
is available. It can hide a plain BIN while Cofl is still loading; that is safer than turning an
unverified pumped book into a purchase recommendation.


## Sale statistics

There are two layers:

1. **Global item volume** comes from SkyCofl's public 7-day price analysis. This is a market-wide
   summary, so a sale that happened while Minecraft was closed still counts. It supplies sells/day
   and the median (used as the conservative p25 fallback where the summary has no quartile), and it
   prevents "not observed locally" from looking like zero demand. Active auction ids with no local
   sale rows are queued first, then other active listings, then the complete catalog; requests are
   serialized at about one per second and cached in `.minecraft/config/discordia-global-sales.json`.
   Coflnet is the source of these global summaries; the client is not affiliated with Coflnet.
   While a queued item has no answer yet, its stats are **pending**, not zero. A pending book-only
   flip is labeled `[pending]`; it is not promoted to verified sale evidence. A timeout, HTTP 429,
   server error, or malformed/missing-contract response leaves the item pending and retries it
   with exponential backoff (30 seconds up to 30 minutes in production; the test harness can shorten
   this, and honors `Retry-After`); it never clears the item to zero. A permanent item-level 4xx is
   labeled `[unavailable]` instead, which is also unresolved rather than zero demand. Unavailable
   markers expire after 24 hours so a corrected tag can recover, and an active listing's authoritative
   tag (for example `PET_LION`) corrects a stale raw catalog request instead of inheriting its 4xx.
   Unresolved book-only flips can still be surfaced, but they never count as verified sale evidence;
   chat and console rows mark them `[pending]` or `[unavailable]`, and `/discordia status` reports
   how many flips are unresolved.
   Retry deadlines, attempt counts, retry tags, and unavailable deadlines persist in the cache;
   restarting during an outage resumes the same backoff instead of re-queuing the whole catalog.
   The cache is written through a temporary file and atomically moved, and per-item cache expiry
   is jittered by up to an hour so refreshes do not all stampede together. `/discordia status`
   includes the last HTTP/network failure, retry count, next retry, and unavailable count.
   For runtime verification, `/discordia globalfault 429|timeout|500|malformed|404` simulates the
   Cofl failure in-game; `/discordia globalfault off` restores the real source. The fault mode is
   session-only and never persisted.
   If Cofl reports a valid zero but local `/auctions_ended` rows exist, the local observed sales
   win; only a genuine zero from both sources suppresses a book-only flip.
2. **Exact variant evidence** still comes from the local `/auctions_ended` stream. `Nbt` decodes
   `tag.ExtraAttributes.id` (searching for it wherever Hypixel nests it), which gives the SkyBlock
   id of each sold item. These rows carry stars, reforge, pet level, held item, scrolls, and
   recombobulation, so they are what the exact-variant and attribute pricing uses.

The local 7-day window has these properties:

- **sells/day** — `count / observed days`, where observed days is the time since the store was
  created, floored at 1 day and capped at 7 (the window length). A minute of data can never imply
  a daily rate, and a week-old install never divides by more than the window;
- **median and p25 sold price** — what it actually cleared at, not what people are asking;
- **volume gate** — `minAuctionSalesPerDay` (default 1), applied only after 5 observed sales, so a
  fresh install is not blinded by missing data;
- **per-variant rates** — timestamped sales per variant signature over 24 hours, which drive the
  time-to-sell estimate.

Local rows persist to `.minecraft/config/discordia-sales.json`, and the same tick is never counted
twice (de-duplicated by auction id, because the heartbeat repeats it every 5 s). Timestamps use the
ended auction's real `timestamp`, not the moment the client noticed it.

## Known limits (read this)

1. **Time-to-sell is a throughput estimate**, not an observed per-listing measurement, because the
   API exposes no join key between a sale and its listing. It also ignores rarity tier (ended sales
   carry none).
2. **Sale volume stats are keyed by base item id** (pets by pet type), so variants share one volume
   pool. Pricing and sale *rates* are variant-aware; the sells/day figure is not.
3. **Thin exact pools fall back to wider ones.** The detail says which pool was used; a base-pool
   price on a highly enchanted item is a weaker signal, which is why it carries a 0.8 multiplier.
4. **Reference prices can be stale** for up to `fullRefreshMs` (10 min) between full dumps.
5. **No craft flips.** Craft-cost arbitrage needs the recipe tree.
6. **Key polling is global.** `K`/`J`/`C` also fire while a chat screen is open.
7. **Collector filters are synchronized.** The client sends margin, profit, item-cap, and budget
   changes to the collector, which recomputes immediately; lowering a client filter can reveal
   flips from the last fetched book. Ignore entries and the non-BIN show/hide switch are still
   client-side display filters. Book-only flips keep their 8%/1M safety floor.
8. **Plain BINs need Cofl price confirmation.** When the weekly summary is pending, unavailable,
   or has fewer than 8 sales, plain BIN flips stay hidden. This reduces the chance of buying a
   pumped listing, at the cost of a short startup gap and some sparse-item coverage.

Roadmap in order: variant-level sale volume → craft flips. Global item volume is now supplied by
the SkyCofl summary cache; local exact-variant history remains the second layer.

## What was verified

- Offline global-gate probe:
  `java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.GlobalGateProbe`
  proves a 206-sale global summary clears the zero-sale gate for a no-local-sale six-listing
  Titanium book, while an explicit zero global summary leaves the identical book suppressed; a
  synthetic local sale plus a global zero confirms that observed local evidence wins.
- Offline global failure matrix:
  `java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.GlobalFailureProbe`
  covers 429 with and without `Retry-After`, timeout, 5xx, malformed JSON, missing contract
  fields, explicit zero, permanent 4xx, cache reload, persisted retry/unavailable state, the
  in-game fault switch, and stale-tag correction (`ALIAS_RING -> PET_ALIAS_RING`). No request
  leaves loopback.
- Contract/alias probe:
  `java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.CoflContractProbe`
  checks `LION -> PET_LION` and identity tags offline; add `--live` for two deliberately spaced
  public contract requests. This validates the `totalSales`, `medianPrice`, and `salesPerDay`
  fields the client now requires, rather than silently accepting a schema break. The live check
  on 2026-11-24 returned HTTP 200 for `TITANIUM_RING` (199 sales, median 7,999,999, 28.429/day)
  and `PET_LION` (1,867 sales at the second check, median 24,000,000, 266.714/day).
- Full reproducible check: `./verify.sh` runs the build and all three offline probes.
- Pumped-book guard: a synthetic plain Gyrokinetic-like book with a 40M entry, a 45.77M next ask
  and a 25M weekly median is rejected, while the same book is accepted when the synthetic weekly
  median is 50M. A separate thick-evidence control with five recent 40M sales allows a 30M entry
  against the same 25M weekly median. The existing global-gate Titanium control still passes.
- The engine runs against the live API: 38k+ auctions in ~2-3 s, filtered results for both markets.
- The NBT reader parsed **65 of 65** BIN sales from a live `auctions_ended` window.
- Variant keys were checked against live listings: a Heroic 5★ Hyperion with 26 enchants keyed as
  `heroic hyperion|MYTHIC|heroic|5|0|b5cf8c267609fa89`, and engine output showed 15 exact-pool and
  5 group-pool flips out of 20.
- Time-to-sell: a seeded 6.0 sales/hour rate over a 41-listing pool produced `420 min (7.0h)`, and
  the rate maths was checked against a synthetic 12-sales-in-2-hours store (exactly 6.00/h).
- Attribute pricing: a listing at 6.5M against plain sales of 6.19M and comparable sales of 19.5M
  surfaced at a 12.85M target (92% margin), including through a thin book; the same setup with zero
  attribute sales produced a null valuation and no play, which is the Aspect of the Jerry guard.
- Attribute guard: seeded stores asserted a 6-of-100 attribute share prices at a 4.0M premium
  (6 samples), while 3 attribute sales and a 4-of-204 (1.96%) share both returned `null`; a plain
  variant also returned `null`, and the margin-cap rule resolved to 60% for a pool target versus
  150% for a comparable target.
- Auction snipes: a live book had 2,602 non-BIN auctions (21 ending in under 10 minutes, 89 under an
  hour); with a 30-minute window and 3-comparable floor, one passed — Scythe Blade at 5.49M against
  a 6.48M reference, 863k profit, 16% margin. The low count is the data, not a parse failure.
- Deterministic income: `npc_sell_price` covered 2,375 items and a live dump had 17 listings below
  NPC; the forge table loaded from the packaged resource (after fixing resources not being on the
  runtime classpath). After order-book depth and the 150% cap, 3 recipes passed, led by Golden Plate
  at 687.7k per 6h (43%) and Drill Engine at 1.14M per 30h (36%); Refined Mithril was rejected.
- Forge depth: a synthetic book asserted `buy(10)` across two ask levels costs exactly 130,
  `sell(2)` across two bid levels fetches 19, and a fill larger than the book reports incomplete.
  On the live book, 160 Enchanted Mithril filled completely for 224,688 and one Refined Mithril bid
  paid 809,091 — a 256% raw margin, excluded by the cap rather than mispriced by depth.
- Auction recalculation: the previously recommended Lv1 Legendary Ender Dragon carried a Tier Boost
  (displayed Legendary, actually Epic), and is now keyed and priced as Epic with the held item. The
  Withered Shadow Fury false positive was fixed by reforge-band pooling: a Withered and a Fabled
  copy with the same stars/tier now produce the same group key, and the Shadow Fury no longer
  appears as a flip. The non-recomb Fabled Atomsplit at 50.9M is rejected because the cheapest
  recombed copy is 58M and the Recombobulator ask is about 10.2M, giving a 47.2M ceiling; the
  already-recombed Storm's Boots and Ancient Primordial Helmet are not capped. Pesthunter Artifact
  (7.82M anchor vs 1.10M median), Dolphin (11.88M vs 2.50M) and Combat Exp Boost (4.55M vs 2.00M)
  were rejected by the thin-sales manipulation guard. No skins, runes or dyes appeared in the output.
- Wither Impact parsing: 70 Necron blades were inspected and 27 were full Wither Impact; the three
  scrolls cost 509.1M from the bazaar at the time of the check. A partial blade is only considered
  when the full listing minus that instant scroll cost clears the normal filters.
- Kat: the generated table loaded all 209 rows from the jar; a live book had 206 level-1 pet keys
  and 38 profitable upgrades (Armadillo Legendary→Mythic +4.85M, Flying Fish +7.16M, Hermit Crab
  +9.73M), with the `[Lvl 1]` lookup fixed after the first run matched zero keys because base keys
  are lowercased.
- The heartbeat and the auction dump reported the same `lastUpdated` across three polls, and page 0
  was confirmed newest-first against the last page.
- The embedded collector probe accepted a tokenless loopback WebSocket, sent restrictive then broad
  filters, and observed the snapshot change from 12 BIN to 0 and back to 12 (the next market tick
  then produced 2); the Java `CollectorClient` also connected with no token and sent the same filter
  control frames successfully.
- The standalone collector still rejects unauthenticated `/health` with 401 and accepts the bearer
  token with 200.
- The mod jar compiles against the real 26.2 client jar and packages with valid metadata.
- **Not verified:** launching it in a Fabric client. That needs a real 26.2 instance.
