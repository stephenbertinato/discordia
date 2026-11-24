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
`ahflip/build/libs/discordia-1.0.0.jar`.

Install it in the `.minecraft/mods` directory of a Fabric 26.2 profile. There is no Fabric API
dependency.

## Verify

```bash
cd ahflip
./verify.sh
```

This builds the mod and runs the deterministic offline probes. The algorithm brief requires new
regression probes to be added to this command.

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
/discordia maxcost 100m        maximum item cost
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
