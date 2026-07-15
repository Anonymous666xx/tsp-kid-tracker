# TSP - Kid Location Tracker

Real-time kid location tracking. The kid runs the APK, the parent watches on a website.

**Live:** https://tsp.omaromartest12.workers.dev

## How It Works

```
Kid's Phone (APK) --> Cloudflare Worker (D1) --> Parent's Browser (Map)
     |                    |                         |
  GPS every 1s        Stores 24h              Dark map, live update
```

## Quick Start

1. **Kid's phone:** Download APK from [Releases](../../releases), install it
2. **Open the app**, enter the API URL and set a 6-digit code (e.g. `123456`)
3. **Parent's browser:** Open https://tsp.omaromartest12.workers.dev
4. **Enter the same 6-digit code** - see the kid's location live

## Files

| Folder | Description |
|--------|-------------|
| `worker/` | Cloudflare Worker (API + parent website) |
| `kid-app/` | Android APK source (Kotlin) |

## Tech Stack

- **Backend:** Cloudflare Workers + D1 (auto-cleanup after 24h)
- **APK:** Android Kotlin, foreground service, GPS every 1s, survives reboot
- **Website:** Dark theme, Leaflet + OpenStreetMap, 1s refresh
- **Auth:** 6-digit numeric code shared between kid and parent
