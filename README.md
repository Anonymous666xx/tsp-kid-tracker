# TSP - Kid Location Tracker

Real-time kid location tracking. The kid runs the APK, the parent watches on a website.

- **Parent Website:** https://tsp-tracker.pages.dev
- **API Backend:** https://tsp.omaromartest12.workers.dev

## How It Works

```
Kid's Phone (APK) --> Cloudflare Worker (D1) --> Parent's Browser (Map)
     |                    |                         |
  GPS every 1s        Stores 24h              Dark map, live update
```

## Quick Start

1. **Kid's phone:** Download APK from [Releases](../../releases), install it
2. **Open the app**, enter the API URL: `https://tsp.omaromartest12.workers.dev`
3. Set a 6-digit code (e.g. `123456`)
4. **Parent's browser:** Open https://tsp-tracker.pages.dev
5. **Enter the same 6-digit code** - see the kid's location live

## Files

| Folder | Description |
|--------|-------------|
| `worker/` | Cloudflare Worker API (D1 storage) |
| `kid-app/` | Android APK source (Kotlin) |
| `website/` | Parent map website (Cloudflare Pages) |
