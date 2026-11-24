# Discordia

A client-side Hypixel SkyBlock auction flip finder for Minecraft 26.2 (Fabric). Discordia reads
public auction and bazaar data, values listings against realized sales and the active order book,
and prints clickable flip candidates in chat.

It **never buys, bids, clicks, or confirms anything**. Opening a notification only runs
`/viewauction <uuid>`; you still decide whether to buy. It does not read the in-game auction-house
UI or packets, and it does not automate gameplay.

> A flip finder can reduce risk and reject unsupported prices. It cannot guarantee a profit in a
> player-driven market. Discordia's goal is a strict no-bad-alert policy: only alert when the
> conservative, fee-inclusive exit value is profitable and the supporting evidence is present.

## Repository layout

| Path | Purpose |
|---|---|
| `ahflip/` | The Fabric client mod and the shared plain-Java market engine |
| `collector/` | Optional standalone collector server for a shared private feed |
| `ALGORITHM_IMPROVEMENT_PROMPT.md` | Detailed engineering brief for hardening the flip algorithm |

The client reuses the collector sources at build time, so the standalone collector is not required
for normal play.

## Requirements

- Java 21
- Minecraft 26.2 with Fabric Loader 0.20.1 or newer
- Internet access to the public Hypixel and SkyCofl APIs

## Build

```bash
cd ahflip
./build.sh
```

The first build downloads the Minecraft client and the required compile-time libraries into
`.tooling/` and `libs/`; those directories are gitignored. The finished mod is written to
`ahflip/build/libs/discordia-1.3.0.jar`.

Install it in the `.minecraft/mods` directory of a Fabric 26.2 profile. There is no Fabric API
dependency.

## Verify

```bash
cd ahflip
./verify.sh
```

This builds the mod and runs the deterministic offline probes: the global-sales failure matrix, the
global zero/alias gate, the Cofl contract check, and `AlgorithmGuardProbe` — 67 assertions covering
the fee brackets, the anchor and support rules, page-0/full-scan parity, the thin-sample manipulation guard, the
plain-item weekly-median gate, the recombobulator and Wither-scroll ceilings, the conservative exit
floor, the hold horizon and the rejection reason codes.

## How a flip is allowed to be announced

Discordia refuses far more than it announces, on purpose. Profit is booked at a **conservative
exit** — the ask after a 1.5% drift/relist reserve, and never above the realized p25 once there are
five sales to measure one — and an item whose expected hold exceeds the horizon (7 days, 3 for a
book-only flip) is suppressed as a capital trap rather than shown with a large headline.
`/discordia status` reports a `why` row with the top rejection reasons for the last scan, so an
empty feed can be told apart from a broken one, and a `tick` row with the measured tick period,
the drift and the poll counts.

## Latency

The auction dump republishes on a **60,000 ms** tick, and every auction created in a tick lands on
page 0 (over one tick: 130 of 130 new listings). `MarketTicker` measures that period and polls the
172 KB heartbeat hard only in the ~1.2 s around the predicted boundary, so a new listing reaches
chat **148–167 ms** after the tick, at about 130 MB/hour instead of 300 MB/hour.

Craft flips are priced the same way: the recipe table is bundled (2,002 outputs from
NotEnoughUpdates), every ingredient is bought at a provable live price, and the finished item still
has to clear the same conservative exit. The resulting replacement cost also caps what an auction
may be priced at, so a book promising a 900M resale on something craftable for 300M is refused.

See [`ahflip/README.md`](ahflip/README.md#conservative-exit-hold-horizon-and-why-nothing-was-shown)
for the full model, the reason codes and the remaining limits.

## Standalone collector

```bash
cd collector
./build.sh
COLLECTOR_TOKEN='change-me' ./run.sh
```

See `collector/README.md` for the client connection command and protocol details. The normal
in-game install starts an embedded loopback collector automatically; the standalone server is only
for advanced/shared setups.

## Commands

```text
/discordia                     toggle live notifications
/discordia toggle nonbin       show/hide non-BIN ending auctions
/discordia list                print current BIN flips
/discordia open                open the best flip
/discordia copy                copy the best flip's command
/discordia margin 10%          minimum auction/snipe margin
/discordia minprofit 500k      minimum auction/snipe profit
/discordia status              show active values and source health
/discordia collector           show the automatic collector
/discordia collector off       return to the local scanner
/discordia help                compact command list
```

Full behaviour, filters, and known limits are documented in [`ahflip/README.md`](ahflip/README.md).

## Contributing

Read [`ALGORITHM_IMPROVEMENT_PROMPT.md`](ALGORITHM_IMPROVEMENT_PROMPT.md) before changing the
valuation engine. Preserve the API-only, no-automation constraint, do not commit build artifacts or
secrets, and run the build/verification commands before pushing.
