package net.kyle.pop;

import net.botwithus.rs3.imgui.ImGui;
import net.botwithus.rs3.imgui.ImGuiWindowFlag;
import net.botwithus.rs3.script.ScriptConsole;
import net.botwithus.rs3.script.ScriptGraphicsContext;

/**
 * The script window. Layout:
 *   • Fixed header — Start/Stop + one-line status (always visible).
 *   • Tabs, each with its OWN scrollable body so content never overflows/clips:
 *       Status   — read-only: what the bot is doing + detected port/crew state.
 *       Crew     — recorded crew/captain stat table.
 *       Settings — pure configuration, grouped by feature.
 *       Debug    — logging toggles, dumps, interact probe, and the LIVE log.
 *
 * Each tab body is a BeginChild region, so a long section (e.g. Settings) scrolls
 * inside its tab instead of pushing other controls off-screen.
 */
public class PortsGraphicsContext extends ScriptGraphicsContext {

    private static final String[] RUN_MODES = { "PERIODIC", "ONE_SHOT" };
    private static final int WFLAGS = ImGuiWindowFlag.None.getValue();
    private static final int LOG_FLAGS = ImGuiWindowFlag.AlwaysVerticalScrollbar.getValue();

    private final PortsScript script;
    private boolean logAutoScroll = true;

    public PortsGraphicsContext(ScriptConsole console, PortsScript script) {
        super(console);
        this.script = script;
    }

    @Override
    public void drawSettings() {
        if (!ImGui.Begin("Player-Owned Ports", WFLAGS)) { ImGui.End(); return; }

        drawHeader();

        if (ImGui.BeginTabBar("pop_tabs", WFLAGS)) {
            drawStatusTab();
            drawCrewTab();
            drawSettingsTab();
            drawDebugTab();
            ImGui.EndTabBar();
        }
        ImGui.End();
    }

    // ── Fixed header ──────────────────────────────────────────────────────────

    private void drawHeader() {
        boolean running = script.isRunning();
        if (ImGui.Button(running ? "[]  STOP" : ">  START")) {
            script.setRunning(!running);
        }
        ImGui.SameLine();
        text((running ? "● RUNNING" : "○ stopped") + "   —   " + nz(script.getStatus()));
        ImGui.Separator();
    }

    // ── Status ───────────────────────────────────────────────────────────────

    private void drawStatusTab() {
        if (!ImGui.BeginTabItem("Status", WFLAGS)) return;
        if (ImGui.BeginChild("status_body", 0f, 0f, false, WFLAGS)) {
            PortsConfig cfg = script.config;

            ImGui.SeparatorText("Now");
            text("State:  " + nz(script.getStatus()));
            text("Mode:   " + cfg.runMode.name()
                    + (cfg.runMode == PortsConfig.RunMode.PERIODIC ? "  (every " + cfg.intervalMinutes + " min)" : ""));

            ImGui.SeparatorText("Port (detection only)");
            text(nz(script.lastPortStatus));

            ImGui.SeparatorText("Crew database");
            int n = script.crewDbCount();
            text(n > 0 ? (n + " crew + captains recorded — used for predictive planning")
                       : "No crew recorded yet — press Start at the port, or use Crew ▸ Rescan.");

            ImGui.SeparatorText("Enabled features");
            text(bullet(cfg.collectFinishedVoyages) + "Collect   "
                    + bullet(cfg.autoDispatchVoyages) + "Dispatch   "
                    + bullet(cfg.optimizeCrew) + "Crew opt");
            text(bullet(cfg.includeSpecialVoyages) + "Special voyages   "
                    + bullet(cfg.blackMarketBuy) + "Black Market   "
                    + bullet(cfg.autoUpgrade) + "Upgrades");
        }
        ImGui.EndChild();
        ImGui.EndTabItem();
    }

    // ── Crew (recorded stat table) ───────────────────────────────────────────

    private void drawCrewTab() {
        if (!ImGui.BeginTabItem("Crew", WFLAGS)) return;
        if (ImGui.BeginChild("crew_body", 0f, 0f, false, WFLAGS)) {
            var entries = script.crewDbEntries();
            text(entries.size() + " recorded (crew + captains) — auto-rescans on Start");
            if (ImGui.Button("Rescan now")) {
                script.requestCrewScan();
                script.setStatus("Scanning crew…");
            }
            ImGui.Separator();
            if (entries.isEmpty()) {
                text("No crew recorded yet — press Start (or Rescan) at the port.");
            } else if (ImGui.BeginTable("pop_crew_tbl", 5, 0)) {
                ImGui.TableSetupColumn("Name", 0);
                ImGui.TableSetupColumn("Morale", 0);
                ImGui.TableSetupColumn("Combat", 0);
                ImGui.TableSetupColumn("Seafaring", 0);
                ImGui.TableSetupColumn("Speed", 0);
                ImGui.TableHeadersRow();
                for (net.kyle.pop.game.CrewDb.Entry e : entries) {
                    ImGui.TableNextRow();
                    ImGui.TableNextColumn(); text(e.name());
                    ImGui.TableNextColumn(); text(Integer.toString(e.morale()));
                    ImGui.TableNextColumn(); text(Integer.toString(e.combat()));
                    ImGui.TableNextColumn(); text(Integer.toString(e.seafaring()));
                    ImGui.TableNextColumn(); text(Integer.toString(e.speed()));
                }
                ImGui.EndTable();
            }
        }
        ImGui.EndChild();
        ImGui.EndTabItem();
    }

    // ── Settings (pure config) ───────────────────────────────────────────────

    private void drawSettingsTab() {
        if (!ImGui.BeginTabItem("Settings", WFLAGS)) return;
        if (ImGui.BeginChild("settings_body", 0f, 0f, false, WFLAGS)) {
            PortsConfig cfg = script.config;
            boolean changed = false;

            ImGui.SeparatorText("Automation");
            boolean collect = ImGui.Checkbox("Collect finished voyages", cfg.collectFinishedVoyages);
            if (collect != cfg.collectFinishedVoyages) { cfg.collectFinishedVoyages = collect; changed = true; }
            boolean dispatch = ImGui.Checkbox("Auto-dispatch voyages", cfg.autoDispatchVoyages);
            if (dispatch != cfg.autoDispatchVoyages) { cfg.autoDispatchVoyages = dispatch; changed = true; }
            boolean events = ImGui.Checkbox("Handle events / adventurers", cfg.handleEvents);
            if (events != cfg.handleEvents) { cfg.handleEvents = events; changed = true; }
            boolean resources = ImGui.Checkbox("Manage resources / bazaar", cfg.manageResources);
            if (resources != cfg.manageResources) { cfg.manageResources = resources; changed = true; }

            if (cfg.autoDispatchVoyages) {
                ImGui.SeparatorText("Dispatch");
                int pct = ImGui.InputInt("Min success % to send", cfg.minSuccessPercent);
                pct = Math.max(0, Math.min(pct, 100));
                if (pct != cfg.minSuccessPercent) { cfg.minSuccessPercent = pct; changed = true; }
                boolean opt = ImGui.Checkbox("Optimize crew/captain to meet the threshold", cfg.optimizeCrew);
                if (opt != cfg.optimizeCrew) { cfg.optimizeCrew = opt; changed = true; }
                boolean spec = ImGui.Checkbox("Also consider Special/adventurer voyages", cfg.includeSpecialVoyages);
                if (spec != cfg.includeSpecialVoyages) { cfg.includeSpecialVoyages = spec; changed = true; }
                text("Sends the BEST offered voyage (reward, then success%), not the");
                text("first over the threshold. Special voyages still need to meet it.");
            }

            ImGui.SeparatorText("Travel");
            boolean travel = ImGui.Checkbox("Auto-travel to port & open screen", cfg.autoTravel);
            if (travel != cfg.autoTravel) { cfg.autoTravel = travel; changed = true; }
            boolean lode = ImGui.Checkbox("Allow lodestone teleport", cfg.allowLodestone);
            if (lode != cfg.allowLodestone) { cfg.allowLodestone = lode; changed = true; }

            ImGui.SeparatorText("Black Market");
            boolean bm = ImGui.Checkbox("Auto-buy from Black Market", cfg.blackMarketBuy);
            if (bm != cfg.blackMarketBuy) { cfg.blackMarketBuy = bm; changed = true; }
            if (cfg.blackMarketBuy) {
                int floor = ImGui.InputInt("Min chimes to keep (floor)", cfg.minChimesFloor);
                floor = Math.max(0, floor);
                if (floor != cfg.minChimesFloor) { cfg.minChimesFloor = floor; changed = true; }
                int cap = ImGui.InputInt("Max spend per visit", cfg.blackMarketMaxSpend);
                cap = Math.max(0, cap);
                if (cap != cfg.blackMarketMaxSpend) { cfg.blackMarketMaxSpend = cap; changed = true; }
                text("Buys the biggest tier affordable within (chimes − floor), capped");
                text("by max-spend. Never drops below the floor.");
            }

            ImGui.SeparatorText("Building upgrades");
            boolean up = ImGui.Checkbox("Auto-spend resources on upgrades", cfg.autoUpgrade);
            if (up != cfg.autoUpgrade) { cfg.autoUpgrade = up; changed = true; }
            if (cfg.autoUpgrade) {
                boolean dry = ImGui.Checkbox("Dry-run (report only, don't spend)", cfg.upgradeDryRun);
                if (dry != cfg.upgradeDryRun) { cfg.upgradeDryRun = dry; changed = true; }
                text(cfg.upgradeDryRun
                        ? "Reports the first affordable upgrade each sweep — nothing spent."
                        : "SPENDS resources on the first affordable upgrade each sweep.");
            }

            ImGui.SeparatorText("Run cadence");
            int modeIdx = ImGui.Combo("Run mode", cfg.runMode.ordinal(), RUN_MODES);
            modeIdx = Math.max(0, Math.min(modeIdx, RUN_MODES.length - 1));
            PortsConfig.RunMode newMode = PortsConfig.RunMode.values()[modeIdx];
            if (newMode != cfg.runMode) { cfg.runMode = newMode; changed = true; }
            if (cfg.runMode == PortsConfig.RunMode.PERIODIC) {
                int minutes = ImGui.InputInt("Idle between sweeps (min)", cfg.intervalMinutes);
                minutes = Math.max(1, Math.min(minutes, 240));
                if (minutes != cfg.intervalMinutes) { cfg.intervalMinutes = minutes; changed = true; }
                int fr = ImGui.InputInt("Full crew rescan every N sweeps (0=off)", cfg.fullRescanSweeps);
                fr = Math.max(0, Math.min(fr, 500));
                if (fr != cfg.fullRescanSweeps) { cfg.fullRescanSweeps = fr; changed = true; }
                text("Full rescan catches same-headcount level-ups the fast scan misses.");
            }

            ImGui.Separator();
            if (ImGui.Button("Save settings")) {
                script.persist();
                script.setStatus("Settings saved");
            }
            if (changed) script.persist();
        }
        ImGui.EndChild();
        ImGui.EndTabItem();
    }

    // ── Debug (tools + live log) ─────────────────────────────────────────────

    private void drawDebugTab() {
        if (!ImGui.BeginTabItem("Debug", WFLAGS)) return;
        if (ImGui.BeginChild("debug_body", 0f, 0f, false, WFLAGS)) {
            PortsConfig cfg = script.config;
            boolean changed = false;

            ImGui.SeparatorText("Logging");
            boolean verbose = ImGui.Checkbox("Verbose debug logging", cfg.verboseDebug);
            if (verbose != cfg.verboseDebug) { cfg.verboseDebug = verbose; changed = true; }
            boolean tv = ImGui.Checkbox("Trace varbits/varps (very noisy)", cfg.traceVars);
            if (tv != cfg.traceVars) { cfg.traceVars = tv; changed = true; }

            ImGui.SeparatorText("Dumps & probes");
            if (ImGui.Button("Dump open interfaces")) script.requestDebugDump();
            ImGui.SameLine();
            if (ImGui.Button("Dump location + objects")) script.requestLocationDump();
            script.dbgInterface = ImGui.InputInt("interface id", script.dbgInterface);
            script.dbgComponent = ImGui.InputInt("component index", script.dbgComponent);
            script.dbgOption = ImGui.InputText("option (blank = default)", script.dbgOption);
            if (ImGui.Button("Interact + dump result")) script.requestDebugInteract();
            ImGui.SameLine();
            if (ImGui.Button("Dump interface [id above]")) script.requestDumpInterface();

            ImGui.SeparatorText("Live log");
            logAutoScroll = ImGui.Checkbox("Auto-scroll", logAutoScroll);
            ImGui.SameLine();
            if (ImGui.Button("Clear")) script.clearLog();
            ImGui.SameLine();
            text("(also saved to pop-log.txt)");

            if (ImGui.BeginChild("pop_log", 0f, 0f, true, LOG_FLAGS)) {
                for (String line : script.recentLog()) text(line);
                if (logAutoScroll) ImGui.SetScrollHereY(1.0f);
            }
            ImGui.EndChild();

            if (changed) script.persist();
        }
        ImGui.EndChild();
        ImGui.EndTabItem();
    }

    @Override
    public void drawOverlay() {
        super.drawOverlay();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Safe Text: escape % (ImGui.Text is printf-style) and null-guard. */
    private static void text(String s) {
        ImGui.Text(s == null ? "" : s.replace("%", "%%"));
    }

    private static String bullet(boolean on) { return on ? "[x] " : "[ ] "; }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
