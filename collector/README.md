# Discordia private collector

One small service that polls the public Hypixel AH API, computes the auction flips with
Discordia's existing engine, and pushes snapshots over WebSocket. It does not buy, bid, open the
game, or read game packets. The normal scan follows Hypixel's ~60-second market tick; an optional
SkyCofl newest-auction lane queues eligible new BINs and drains them between ticks under the
shared Cofl request budget.

**Normal users do not need this service.** The Discordia mod starts an equivalent collector inside
the game on an ephemeral loopback port with no token. This standalone service is for an optional
shared/remote feed.

## Run

```bash
./build.sh
COLLECTOR_TOKEN='replace-with-a-long-random-token' ./run.sh
```

`COLLECTOR_TOKEN` is required for this standalone service. The default bind is `127.0.0.1:8787`, so
it is not exposed to the network unless you change it deliberately.

```text
WebSocket  ws://127.0.0.1:8787/ws
Health     http://127.0.0.1:8787/health
Auth       Authorization: Bearer <COLLECTOR_TOKEN>
```

PowerShell:

```powershell
$env:COLLECTOR_TOKEN = "replace-with-a-long-random-token"
.\build.sh
.\run.sh
```

## Configuration

All settings are environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `COLLECTOR_TOKEN` | required | Bearer token for `/ws` and `/health` |
| `COLLECTOR_HOST` | `127.0.0.1` | Bind address; use a firewall/reverse proxy for remote access |
| `COLLECTOR_PORT` | `8787` | HTTP/WebSocket port |
| `COLLECTOR_BUDGET` | `10b` | Upper budget used by the server engine |
| `COLLECTOR_MARGIN` | `8` | Server-side minimum margin percent |
| `COLLECTOR_MIN_PROFIT` | `400k` | Server-side minimum BIN profit |
| `COLLECTOR_MIN_SNIPE_PROFIT` | `100k` | Server-side minimum non-BIN profit |
| `COLLECTOR_MAX_COST` | `0` | Absolute item cap; `0` means budget-derived |
| `COLLECTOR_SCAN_INTERVAL_MS` | `3000` | Heartbeat polling interval |
| `COLLECTOR_FULL_REFRESH_MS` | `600000` | Full auction-book rebuild interval |
| `COLLECTOR_STATE_DIR` | `collector-state` | Sales/Cofl cache directory |

Amounts accept `k`, `m`, and `b` suffixes. The initial environment values are the starting broad
feed. Once a client connects, it sends its current margin, profit, item cap, and budget over the
WebSocket; the server applies those values and recomputes immediately from the last fetched book.
Lowering a filter can therefore reveal more flips, subject to the engine's book-only safety floor
(8% and 1M). Ignore entries and the non-BIN show/hide switch remain client-side display filters.

## Client

The mod's default collector is automatic; no command is needed. For this standalone service only:

```text
/discordia collector ws://127.0.0.1:8787/ws <COLLECTOR_TOKEN>
/discordia collector
/discordia collector off
```

The URL and token for an external collector are stored in `config/discordia-config.json` for
reconnects. Keep that file private. The client reconnects with backoff; `/discordia status` shows
the collector connection, the last scan/push age, and the market tick age separately. The mod stops
its local scanner while collector mode is active. `/discordia collector off` stops an embedded
collector and resumes the local scanner.

The collector token is a secret. Do not put it in a public repository, a chat message, or a URL
that will be logged. Prefer `Authorization: Bearer` over a `?token=` query parameter. You can also
supply the connection through `DISCORDIA_COLLECTOR_URL` and `DISCORDIA_COLLECTOR_TOKEN` instead of
saving it in the config.

## Protocol

The WebSocket sends a JSON snapshot whenever the market tick produces a new result, a tiny status
heartbeat on every scan even when the market tick has not moved, and immediate state when a client
connects. A heartbeat is a `status` frame with `lastUpdated` and looks like:

```json
{ "type": "status", "generatedAt": 1795549458346, "status": "watching", "lastUpdated": 1795549396346 }
```

Snapshots look like:

```json
{
  "type": "snapshot",
  "generatedAt": 1795549396346,
  "lastUpdated": 1795549396346,
  "status": "ok",
  "pending": 2,
  "budget": 10000000000,
  "bin": [ { "kind": "AUCTION", "name": "...", "buyPrice": 1.0, "profit": 2.0, "command": "/viewauction ..." } ],
  "nonBin": []
}
```

`type: "status"` messages carry connection or scanner errors without clearing the last snapshot.

Clients may send this control frame after connecting and whenever a local filter changes:

```json
{
  "type": "filters",
  "budget": 10000000000,
  "minMarginPct": 8,
  "minSnipeMarginPct": 8,
  "minProfit": 400000,
  "minSnipeProfit": 100000,
  "maxItemCost": 0,
  "includeNonBin": false
}
```

The server recomputes the current book immediately and broadcasts a new snapshot. `includeNonBin`
is informational; the server always sends both lists and the client controls visibility.

## Remote use

For a VPS, bind the service behind a firewall and put it behind Caddy/nginx with TLS, then use
`wss://your-host/ws` in the client. The collector itself speaks plain HTTP/WebSocket by design.
Do not expose it directly to the public internet without TLS and a firewall.

A public API that proxies Hypixel's auction API is against Hypixel's developer policy. This service
is intended for a private feed used by you (or a small private group that you control), not as a
public API.

## What it does

- Polls `auctions_ended` every 3 seconds by default and broadcasts a status heartbeat each scan.
- Fetches page 0 only when the market `lastUpdated` tick changes.
- Watches SkyCofl's newest-active-auction feed every 2 seconds (cache-busted) and scores eligible new BINs against the cached book; failures fall back to the normal scan.
- Rebuilds the full book every 10 minutes.
- Computes the same auction and non-BIN flips as the client engine.
- Broadcasts a JSON snapshot after each successful update and on every new connection.
- Persists local sale history and Cofl summaries under `COLLECTOR_STATE_DIR`.

The normal page-0 path still cannot make listings appear before Hypixel publishes a new auction
snapshot. The fast lane uses SkyCofl's active feed and detail rows to fill that gap when possible;
it is best-effort and does not guarantee every new auction. A collector reduces duplicate
bandwidth and gives every consumer one low-latency source.
