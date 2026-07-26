package net.kyle.pop;

import net.botwithus.rs3.script.config.ScriptConfig;

/**
 * Runtime configuration for the Player-Owned Ports script.
 * Persisted through the client's {@link ScriptConfig} (simple string properties).
 */
public class PortsConfig {

    /** How the script behaves over time. Voyages take real-world hours, so the
     *  two modes are: keep running and re-check on an interval, or do a single
     *  collect+dispatch sweep and stop. */
    public enum RunMode {
        PERIODIC,   // stay running; sweep, idle for `intervalMinutes`, repeat
        ONE_SHOT    // sweep once, then stop the script
    }

    // ── Scope toggles (map 1:1 to the feature checkboxes) ──────────────────────
    public boolean collectFinishedVoyages = true;
    public boolean autoDispatchVoyages    = true;
    public boolean handleEvents           = true;
    /** Navigate to the port and open the port screen if needed. */
    public boolean autoTravel             = true;

    // ── Run cadence ────────────────────────────────────────────────────────────
    public RunMode runMode = RunMode.PERIODIC;
    /** Minutes to idle between sweeps in PERIODIC mode. */
    public int intervalMinutes = 30;
    /** Force a FULL crew rescan (reads every crew's stats, not just the count) every
     *  this many sweeps, so a same-headcount level-up doesn't leave the DB stale.
     *  0 = never (rely on count-change smart scan + manual rescan only). */
    public int fullRescanSweeps = 25;

    // ── Black Market auto-buy ────────────────────────────────────────────────────
    /** Enable buying from the Black Market. */
    public boolean blackMarketBuy = false;
    /** Never spend below this many chimes (hard floor — safety). */
    public int minChimesFloor = 0;
    /** Hard cap on chimes spent per Black-Market visit (safety, so a mis-read can't overspend). */
    public int blackMarketMaxSpend = 5000;

    // ── Dispatch safety ──────────────────────────────────────────────────────────
    /** Only send a voyage whose "Overall success chance" is at least this %.
     *  100 = only send guaranteed voyages. */
    public int minSuccessPercent = 100;
    /** When a slot has no voyage meeting the threshold, try swapping crew on its
     *  ship to raise the deficient adversity stat before giving up on the slot.
     *  Validated live (fills empty slots + swaps by name, keep/revert via ship
     *  totals); on by default. Toggle with the GUI checkbox or pop-cmd "opt on|off". */
    public boolean optimizeCrew = true;
    /** Also consider Special/adventurer voyages during dispatch (not just Standard).
     *  Off by default — special voyages are often unreachable and you may want to
     *  hand-pick story/adventurer ones. When on, dispatch evaluates both tabs and
     *  sends the best voyage that meets the threshold across them. */
    public boolean includeSpecialVoyages = false;

    // ── Resource → building upgrades (opt-in; spends port resources) ─────────────
    /** Auto-spend port resources on affordable building upgrades. Off by default —
     *  spending is irreversible. */
    public boolean autoUpgrade = false;
    /** When on, the upgrade pass only REPORTS what it would build (no spend). Defaults
     *  on so you can verify the detection before letting it spend for real. */
    public boolean upgradeDryRun = true;

    // ── Debug ──────────────────────────────────────────────────────────────────
    /** Master debug switch: enables the pop-cmd.txt command file + interface-dump
     *  tools (mapping scaffolding). Off for normal use / submission. */
    public boolean debug = false;
    /** When on, logs every detection result + action + interface-opens + clicks. */
    public boolean verboseDebug = false;
    /** Trace varp/varbit changes too (very noisy — thousands per login). Off by default. */
    public boolean traceVars = false;

    public void load(ScriptConfig cfg) {
        if (cfg == null) return;
        collectFinishedVoyages = bool(cfg, "collectFinishedVoyages", collectFinishedVoyages);
        autoDispatchVoyages    = bool(cfg, "autoDispatchVoyages", autoDispatchVoyages);
        handleEvents           = bool(cfg, "handleEvents", handleEvents);
        autoTravel             = bool(cfg, "autoTravel", autoTravel);
        debug                  = bool(cfg, "debug", debug);
        verboseDebug           = bool(cfg, "verboseDebug", verboseDebug);
        traceVars              = bool(cfg, "traceVars", traceVars);
        blackMarketBuy         = bool(cfg, "blackMarketBuy", blackMarketBuy);
        optimizeCrew           = bool(cfg, "optimizeCrew", optimizeCrew);
        includeSpecialVoyages  = bool(cfg, "includeSpecialVoyages", includeSpecialVoyages);
        autoUpgrade            = bool(cfg, "autoUpgrade", autoUpgrade);
        upgradeDryRun          = bool(cfg, "upgradeDryRun", upgradeDryRun);
        intervalMinutes        = intVal(cfg, "intervalMinutes", intervalMinutes);
        fullRescanSweeps       = intVal(cfg, "fullRescanSweeps", fullRescanSweeps);
        minSuccessPercent      = intVal(cfg, "minSuccessPercent", minSuccessPercent);
        minChimesFloor         = intVal(cfg, "minChimesFloor", minChimesFloor);
        blackMarketMaxSpend    = intVal(cfg, "blackMarketMaxSpend", blackMarketMaxSpend);
        if (cfg.containsKey("runMode")) {
            try { runMode = RunMode.valueOf(cfg.getProperty("runMode")); }
            catch (IllegalArgumentException ignored) { runMode = RunMode.PERIODIC; }
        }
    }

    public void save(ScriptConfig cfg) {
        if (cfg == null) return;
        cfg.addProperty("collectFinishedVoyages", Boolean.toString(collectFinishedVoyages));
        cfg.addProperty("autoDispatchVoyages", Boolean.toString(autoDispatchVoyages));
        cfg.addProperty("handleEvents", Boolean.toString(handleEvents));
        cfg.addProperty("autoTravel", Boolean.toString(autoTravel));
        cfg.addProperty("debug", Boolean.toString(debug));
        cfg.addProperty("verboseDebug", Boolean.toString(verboseDebug));
        cfg.addProperty("traceVars", Boolean.toString(traceVars));
        cfg.addProperty("blackMarketBuy", Boolean.toString(blackMarketBuy));
        cfg.addProperty("optimizeCrew", Boolean.toString(optimizeCrew));
        cfg.addProperty("includeSpecialVoyages", Boolean.toString(includeSpecialVoyages));
        cfg.addProperty("autoUpgrade", Boolean.toString(autoUpgrade));
        cfg.addProperty("upgradeDryRun", Boolean.toString(upgradeDryRun));
        cfg.addProperty("intervalMinutes", Integer.toString(intervalMinutes));
        cfg.addProperty("fullRescanSweeps", Integer.toString(fullRescanSweeps));
        cfg.addProperty("minSuccessPercent", Integer.toString(minSuccessPercent));
        cfg.addProperty("minChimesFloor", Integer.toString(minChimesFloor));
        cfg.addProperty("blackMarketMaxSpend", Integer.toString(blackMarketMaxSpend));
        cfg.addProperty("runMode", runMode.name());
        try { cfg.save(); } catch (Exception ignored) { }
    }

    private static boolean bool(ScriptConfig cfg, String key, boolean def) {
        return cfg.containsKey(key) ? Boolean.parseBoolean(cfg.getProperty(key)) : def;
    }

    private static int intVal(ScriptConfig cfg, String key, int def) {
        if (!cfg.containsKey(key)) return def;
        try { return Integer.parseInt(cfg.getProperty(key)); }
        catch (NumberFormatException e) { return def; }
    }
}
