# Player-Owned Ports (BotWithUs, RS3)

An autonomous Player-Owned Ports bot for the **classic BotWithUs client** (Java 20).
It travels to the port, opens the voyages screen, collects returned voyages,
re-dispatches ships (success-gated), and reports port status — all self-verified
with automated checks and safe pacing (no blocking, no infinite loops).

---

## Status

| Feature | State |
|---|---|
| Travel to port (portal "Enter") + open voyages (950) | ✅ working, self-checked |
| Collect returned voyages (ship → results 916 → Get results) | ✅ working |
| Auto-dispatch (cycles voyages, sends first ≥ success threshold, skips un-sendable ships) | ✅ working |
| Handle dialogs/events (adventurers, prompts) | ✅ via Dialog API |
| Detection-only Port Status report (chimes, ships, voyages, adventurers, captain) | ✅ working |
| Per-action automated checks + consecutive-fail backoff | ✅ working |
| Black Market **auto-buy** | ⏳ config ready; buy flow needs the 1373/759/941 dumps |
| Labeled detection: trade goods / building upgrades / crew-for-hire | ⏳ needs 1276/1486/trader dumps |

---

## Build & run

Direct build with the client's bundled JDK 20 (no install, no Gradle needed):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

It auto-detects the JDK bundled with the client; set `$env:BWU_JDK` to a JDK 20
home to override.

`build.ps1` compiles against local jars in `libs/`, which are **not** committed —
drop `botwithus-api.jar` and `xapi-public.jar` in there first, or build with
Gradle (`.\gradlew jar`), which pulls both from the BotWithUs nexus.

It compiles, bundles the `xapi.public` API into the jar, and deploys to
`…\BotWithUs\scripts\local\PlayerOwnedPorts.jar`. **Fully restart the client** to
load a new jar (it does not hot-reload). Then start "Player-Owned Ports".

---

## How it works (architecture)

- **Execution:** event-driven — logic runs on `ServerTickedEvent` (subscribed in
  `initialize()`); `onLoop()` is empty. (Plain `onLoop` did not drive this client.)
- **Clicks:** every UI click goes through `MiniMenu.interact(14, 1, -1, (iface<<16)|comp)`.
  `Component.interact("option")` is a silent no-op in this client — MiniMenu is the
  reliable path.
- **Non-blocking:** at most ONE click per tick, paced by a tick-count cooldown.
  Never calls `Execution.delay` on the tick thread (that froze the game).
- **Checks:** after each action it arms a `[CHECK]` and won't act again until the
  action is verified (PASS) or times out (FAIL). 4 consecutive FAILs → 2-min backoff.
- **Logging:** everything is written to `…\BotWithUs\scripts\pop-log.txt` (verbose)
  so it can be monitored outside the client. Dumps go to `pop-dump.txt`.

---

## Config (GUI → Config tab; all settings persist)

- **Tasks:** collect finished voyages · auto-dispatch · manage resources · handle events
- **Travel:** auto-travel to port · allow lodestone teleport
- **Min success % to send** (dispatch only sends voyages at/above this)
- **Black Market:** auto-buy · item component (in 1373) · buy quantity · **min chimes floor** (never spends below)
- **Run cadence:** PERIODIC (idle N min between sweeps) or ONE_SHOT
- **Debug:** verbose logging · trace varbits/varps (noisy) · dump tools · **Save settings**

---

## Discovered IDs (interface 950 = voyages, 905 = hub)

- Open voyages: `MiniMenu.interact(14,1,-1,59310129)` (hub Voyages button, 905:49)
- Voyages available text: `950:11` · Overall success chance: `950:160`
- Ship slots (Select): `83, 89, 95, 101`; status text = Select+4 ("Ready"/"Returned"/"No Ship")
- **Collect** a returned ship: click ship comp = Select+2 → opens **results 916** → **Get results 916:244**
- Voyage-list selects: `36, 56, 76` · Send `163` · Confirm `193` · Special Voyages label `950:219` (subcomponent)
- Hub 905: chimes `37`, captain-for-hire name `40` / status `43`
- Port entry portal: object **3219**, option "Enter"
- Black Market shop **1373**; buy dialogs **759** / **941**; item buttons seen at 1373:33/144/145/151

---

## Adding the remaining features (the dump workflow)

Each unlabeled screen is one dump away. With the screen open:
1. Config → Debug → set **interface id** and click **"Dump interface [id above]"** (writes to `pop-dump.txt`).
2. To capture a specific button, just click it once — `InteractionEvent` logs
   `op/p1/p2/p3`; the click is `MiniMenu.interact(op,p1,p2,p3)` and `p3 = (iface<<16)|comp`.

Screens still needed:
- **Black Market buy:** dump **1373** (items) and **759**/**941** (quantity/confirm), set item/qty/floor → wire guarded buy.
- **Detection (detect-only):** dump **1276** (crew-for-hire), **1486** (upgrades), trader/black-market for labeled reporting.

---

## Notes

- All spending is gated (min chimes floor) and success-gated (dispatch threshold).
