package net.kyle.pop;

import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.events.impl.InteractionEvent;
import net.botwithus.rs3.events.impl.InterfaceOpenedEvent;
import net.botwithus.rs3.events.impl.ServerTickedEvent;
import net.botwithus.rs3.events.impl.VariableUpdateEvent;
import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.queries.builders.objects.SceneObjectQuery;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;
import net.botwithus.rs3.game.scene.entities.object.SceneObject;
import net.botwithus.rs3.script.LoopingScript;
import net.botwithus.rs3.script.config.ScriptConfig;
import net.kyle.pop.data.PortsData;
import net.kyle.pop.game.Ports;
import net.kyle.pop.util.InterfaceDebug;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Player-Owned Ports automation for RuneScape 3 on the (classic) BotWithUs client.
 *
 * Each loop it performs ONE prioritised action: handle a waiting event, make sure
 * the port screen is open, collect a returned voyage, dispatch a new one, or
 * manage resources. When a sweep has nothing left to do it either idles for a
 * configured interval (PERIODIC) or stops (ONE_SHOT).
 *
 * IMPORTANT: interface / component / varbit ids are NOT hard-coded. Discover them
 * with the client debug tools + the "Dump open interfaces" button, then fill in
 * {@link net.kyle.pop.data.PortsData}. Until then the bot safely idles.
 */
public class PortsScript extends LoopingScript {

    public final PortsConfig config = new PortsConfig();

    private final ScriptConfig scriptConfig;
    private final Random random = new Random();
    private final Set<String> warnedOnce = new HashSet<>();

    /** Epoch millis at which PERIODIC mode should resume from idle. */
    private long idleUntilMs = 0L;
    private volatile boolean dumpRequested = false;
    private volatile boolean dumpLocationRequested = false;
    private String status = "Starting";

    // Interactive debug-probe inputs (set from the GUI).
    public int dbgInterface = 950;
    public int dbgComponent = 0;
    public String dbgOption = "";
    private volatile boolean dbgInteractRequested = false;
    private volatile boolean dbgDumpInterfaceRequested = false;
    /** When true, autonomous gameplay is paused; only pop-cmd.txt commands run. */
    private volatile boolean mappingPause = false;
    /** Last verbose-debug snapshot, so we only log when detection state changes. */
    private String lastDebugSnapshot = "";
    private long tickCounter = 0;

    /** Non-blocking pacing: don't act again until this tick. */
    private static final int ACTION_COOLDOWN_TICKS = 3;
    private long cooldownUntilTick = 0;
    /** Multi-step dispatch state machine position (one click per tick). */
    private int dispatchStep = 0;
    /** Which voyage (index into VOYAGE_SELECT_INDICES) the dispatch is currently evaluating. */
    private int dispatchVoyageIdx = 0;
    /** Best voyage found while evaluating all offered voyages this dispatch (send the
     *  best rather than the first that clears the threshold). */
    private int bestVoyageIdx = -1;
    private int bestVoyageSuccess = -1;
    private int bestVoyageReward = -1;
    private boolean bestVoyageMeets = false;
    /** Which voyage tab the current evaluation / best voyage is on (0=Standard, 1=Special). */
    private int dispatchEvalTab = 0;
    private int bestVoyageTab = -1;
    /** Collect state machine: 0=idle, 1=clicked ship (get-results next), 2=(close next). */
    private int collectStep = 0;
    private int collectingSlot = -1;
    private int collectAttempts = 0;
    /** Returned slots that wouldn't clear after repeated collect attempts (skip this sweep). */
    private final java.util.Set<Integer> collectExhausted = new java.util.HashSet<>();
    /** Ready ship slots we couldn't dispatch this sweep (no voyage met the threshold). */
    private final java.util.Set<Integer> exhaustedSlots = new java.util.HashSet<>();
    /** Set true only when a dispatch actually confirmed a send (for the sent-check). */
    private boolean lastActionWasSend = false;
    /** The slot the pending dispatch-sent check is for (so a FAIL can optimize that ship). */
    private int lastDispatchSlot = -1;
    /** Active crew optimization (non-null while running its multi-tick state machine). */
    private net.kyle.pop.game.CrewOptimizer crewOptimizer = null;
    /** Active crew-database scan (non-null while rebuilding crew-db.txt). */
    private net.kyle.pop.game.CrewScanner crewScanner = null;
    /** Loaded crew database for the GUI (lazily loaded / refreshed after a scan). */
    private net.kyle.pop.game.CrewDb crewDb = null;
    /** When true, auto-rescan crew+captains once we're at the port (set on START). */
    private volatile boolean scanOnStart = false;
    /** When true, the next auto-scan reads every crew's stats (not just the count) —
     *  set for the periodic full rescan so same-headcount level-ups are picked up. */
    private volatile boolean nextScanForceFull = false;
    /** Sweeps since the last full crew rescan (drives config.fullRescanSweeps). */
    private int sweepsSinceFullScan = 0;
    /** Black-Market buy state machine: 0=open, 1=buy, 3=close; done-flag reset each sweep. */
    private int bmStep = 0;
    private boolean bmDoneThisSweep = false;
    /** Resource → building upgrade pass (non-null while running); one attempt per sweep. */
    private net.kyle.pop.game.UpgradeManager upgradeManager = null;
    private boolean upgradeDoneThisSweep = false;
    /** Set by tick() when travel is needed; the blocking Traverse.to runs in onLoop()
     *  (the script thread — game queries require it and blocking there is safe). */
    private volatile boolean travelRequested = false;
    private int onLoopBeats = 0;
    /** User-facing Start/Stop. Loads STOPPED — no automation until the user presses Start. */
    private volatile boolean userRunning = false;
    /** Slots we've already tried to crew-optimize this sweep (avoid re-optimizing). */
    private final java.util.Set<Integer> crewOptimizedSlots = new java.util.HashSet<>();

    /** Automated post-action verification: no new action until the last one is checked. */
    private String checkName = null;
    private long checkDeadlineTick = 0;
    private java.util.concurrent.Callable<Boolean> checkCond = null;
    /** Consecutive-FAIL backoff so a stuck action can't loop forever. */
    private String lastFailedCheck = null;
    private int consecutiveFails = 0;
    private static final int MAX_CONSECUTIVE_FAILS = 4;
    /** Backoffs before a hard auto-stop (so we don't idle-loop forever on an
     *  unrecoverable problem instead of surfacing it). */
    private int backoffCount = 0;
    private static final int MAX_BACKOFFS = 3;
    /** Why the script auto-stopped (shown in the GUI header). Empty = running/manual. */
    public volatile String stopReason = "";

    public PortsScript(String name, ScriptConfig scriptConfig, ScriptDefinition definition) {
        super(name, scriptConfig, definition);
        this.scriptConfig = scriptConfig;
    }

    @Override
    public boolean initialize() {
        this.sgc = new PortsGraphicsContext(getConsole(), this);
        this.loopDelay = 600;
        this.config.load(scriptConfig);
        // Drive logic from the server-tick event (the reliable execution hook in
        // this client), rather than onLoop.
        subscribe(ServerTickedEvent.class, e -> {
            try {
                tick();
            } catch (Throwable t) {
                emit("[ERR] tick: " + t);
            }
        });

        // Passive event tracing (gated by Verbose): logs what YOU click and what
        // opens, so we can discover interfaces/components by just interacting.
        subscribe(InterfaceOpenedEvent.class, e -> {
            if (config.verboseDebug) emit("[EVT] interfaceOpened id=" + e.getInterfaceId());
        });
        subscribe(InteractionEvent.class, e -> {
            if (config.verboseDebug) emit("[EVT] interaction op=" + e.getOpcode()
                    + " p1=" + e.getParam1() + " p2=" + e.getParam2() + " p3=" + e.getParam3());
        });
        subscribe(VariableUpdateEvent.class, e -> {
            if (config.traceVars) emit("[EVT] " + (e.isVarbit() ? "varbit" : "varp")
                    + " id=" + e.getId() + " val=" + e.getValue());
        });

        emit("[INIT] Player-Owned Ports initialized — tick + event tracing subscribed");
        return super.initialize();
    }

    /** Fast per-tick logic runs in tick() via ServerTickedEvent. onLoop() (the script's
     *  own loop thread) is reserved for BLOCKING calls that require the script thread —
     *  currently the port traversal (Traverse.to blocks and its queries throw off-thread). */
    @Override
    public void onLoop() {
        if (onLoopBeats < 3) { onLoopBeats++; emit("[NAV] onLoop alive on script thread #" + onLoopBeats); }
        if (!userRunning || mappingPause || !travelRequested) return;
        travelRequested = false;
        if (Ports.isPortOpen() || Ports.isHubOpen()) return;               // already there
        if (Client.getLocalPlayer() == null || Client.getGameState() != Client.GameState.LOGGED_IN) return;
        try {
            emit("[NAV] onLoop Traverse.to port entry " + PortsData.PORT_ENTRY_X + "," + PortsData.PORT_ENTRY_Y);
            boolean ok = net.botwithus.api.game.world.Traverse.to(new net.botwithus.rs3.game.Coordinate(
                    PortsData.PORT_ENTRY_X, PortsData.PORT_ENTRY_Y, PortsData.PORT_ENTRY_Z));
            int reg = playerRegionId();
            emit("[NAV] onLoop traverse returned " + ok + " region=" + reg
                    + (reg == PortsData.PORT_ENTRY_REGION ? " (arrived)" : " (not port region)"));
        } catch (Throwable t) {
            emit("[NAV][ERR] onLoop " + t);
        }
    }

    private void tick() {
        tickCounter++;
        LocalPlayer player = Client.getLocalPlayer();
        Client.GameState gameState = Client.getGameState();

        // Debug/mapping scaffolding (pop-cmd.txt command file + interface-dump tools)
        // runs only when the debug switch is on — off for normal use / submission.
        if (config.debug) {
            handleCommandFile();

            // While paused (for mapping), only commands run — no autonomous gameplay.
            if (mappingPause) {
                setStatus("Paused (mapping)");
                return;
            }

            if (dumpRequested) {
                dumpRequested = false;
                try { InterfaceDebug.dumpOpenInterfaces(this); }
                catch (Exception e) { emit("[ERR] Debug dump failed: " + e.getMessage()); }
            }
            if (dumpLocationRequested) {
                dumpLocationRequested = false;
                try { InterfaceDebug.dumpLocation(this); }
                catch (Exception e) { emit("[ERR] Location dump failed: " + e.getMessage()); }
            }
            if (dbgInteractRequested) {
                dbgInteractRequested = false;
                try { InterfaceDebug.testInteract(this, dbgInterface, dbgComponent, dbgOption); }
                catch (Exception e) { emit("[ERR] Test interact failed: " + e.getMessage()); }
            }
            if (dbgDumpInterfaceRequested) {
                dbgDumpInterfaceRequested = false;
                try { InterfaceDebug.dumpInterface(this, dbgInterface); }
                catch (Exception e) { emit("[ERR] Dump interface failed: " + e.getMessage()); }
            }
        }

        // User Start/Stop gate — no automation until the user presses Start (loads
        // stopped). Debug tools + the command file above still work when stopped.
        if (!userRunning) {
            setStatus("Stopped — press Start");
            return;
        }

        // Gameplay logic requires being logged in. NON-BLOCKING: just return; the
        // next server tick re-checks (never sleep on the tick thread).
        if (player == null || gameState != Client.GameState.LOGGED_IN) {
            setStatus("Waiting for login (" + gameState + ")");
            return;
        }

        // Non-blocking pacing between actions (replaces Execution.delay).
        if (tickCounter < cooldownUntilTick) {
            return;
        }

        // PERIODIC idle between sweeps (non-blocking).
        long now = System.currentTimeMillis();
        if (now < idleUntilMs) {
            setStatus("Idle — next sweep in " + formatDuration(idleUntilMs - now));
            return;
        }

        // Verify the previous action succeeded before taking another (checks in place).
        if (evaluatePendingCheck()) {
            return;
        }

        // Auto-rescan crew + captains once we're at the port (set on START), so the
        // stat database + UI table are fresh.
        if (scanOnStart && crewScanner == null && Ports.isHubOpen()) {
            scanOnStart = false;
            boolean full = nextScanForceFull;
            nextScanForceFull = false;
            if (full) sweepsSinceFullScan = 0;
            crewScanner = new net.kyle.pop.game.CrewScanner(this, full);
            emit("[SCAN] auto-rescan crew + captains " + (full ? "(full)" : "(smart)"));
        }

        // Crew-database scan runs as its own multi-tick state machine (owns the loop).
        if (crewScanner != null) {
            if (crewScanner.isDone()) {
                emit("[SCAN] crew database scan finished");
                crewScanner = null;
                refreshCrewDbCount();
                cooldownUntilTick = tickCounter + cooldownTicks();
                return;
            }
            boolean actedScan = crewScanner.step();
            if (actedScan) cooldownUntilTick = tickCounter + cooldownTicks();
            setStatus("Scanning crew…");
            return;
        }

        // Crew optimization runs as its own multi-tick state machine; while active it
        // owns the loop (navigates the ship editor), then hands control back.
        if (crewOptimizer != null) {
            if (crewOptimizer.isDone()) {
                emit("[CREW] optimization finished (" + (crewOptimizer.succeeded() ? "ok" : "aborted")
                        + ") — reopening voyages to re-evaluate");
                crewOptimizer = null;
                // Force a fresh dispatch attempt on the retried slot(s).
                exhaustedSlots.clear();
                dispatchStep = 0;
                cooldownUntilTick = tickCounter + cooldownTicks();
                return;
            }
            boolean actedCrew = crewOptimizer.step();
            if (actedCrew) cooldownUntilTick = tickCounter + cooldownTicks();
            setStatus("Optimizing crew…");
            return;
        }

        // Resource → building upgrade pass runs as its own multi-tick state machine
        // (navigates the Upgrade Buildings screen), then hands control back.
        if (upgradeManager != null) {
            if (upgradeManager.isDone()) {
                upgradeManager = null;
                upgradeDoneThisSweep = true;
                cooldownUntilTick = tickCounter + cooldownTicks();
                return;
            }
            boolean actedUp = upgradeManager.step();
            if (actedUp) cooldownUntilTick = tickCounter + cooldownTicks();
            setStatus(config.upgradeDryRun ? "Checking upgrades…" : "Upgrading buildings…");
            return;
        }

        // Gather detection state once.
        boolean portOpen         = Ports.isPortOpen();
        boolean hubOpen          = Ports.isHubOpen();
        boolean eventOpen        = Ports.isEventOpen();
        int     returnedSlot     = portOpen ? Ports.returnedSlotSelectIndexExcluding(collectExhausted) : -1;
        boolean finishedVoyage   = returnedSlot != -1;
        int     readySlot        = portOpen ? Ports.readySlotSelectIndexExcluding(exhaustedSlots) : -1;
        int     voyagesAvailable = portOpen ? Ports.availableVoyageCount() : -1;
        int     chimes           = hubOpen ? Ports.chimes() : -1;

        logDetection(portOpen, hubOpen, eventOpen, finishedVoyage, returnedSlot, readySlot, voyagesAvailable, chimes);

        // ── At most ONE click per tick, then cooldown + verification ─────────────
        boolean acted;
        if (config.handleEvents && eventOpen) {
            debug("action: advancing dialog/event");
            acted = Ports.advanceDialog();
            if (acted) armCheck("advance-dialog", 6, () -> !Ports.isEventOpen());
            dispatchStep = 0;
        } else if (config.collectFinishedVoyages && collectStep > 0) {
            // Mid-collect: finish the results report sequence (get-results -> close).
            // Runs regardless of portOpen since clicking the ship closes 950.
            acted = collectStepAdvance();
        } else if (!portOpen) {
            acted = openPort(); // arms its own check (open-voyages-950 or entered-port)
        } else if (config.collectFinishedVoyages && finishedVoyage) {
            // Start collect: click the returned ship (Select+2) to open its results report.
            final int slot = returnedSlot;
            if (slot == collectingSlot) {
                collectAttempts++;
            } else {
                collectingSlot = slot;
                collectAttempts = 1;
            }
            if (collectAttempts > 3) {
                warnOnce("Returned slot " + slot + " not clearing after collect attempts — skipping this sweep.");
                collectExhausted.add(slot);
                acted = false;
            } else {
                int shipComp = slot + PortsData.SLOT_SHIP_OFFSET;
                debug("action: collect — click ship comp=" + shipComp + " (slot Select=" + slot + ", attempt " + collectAttempts + ")");
                acted = Ports.clickComponent(PortsData.VOYAGE_INTERFACE, shipComp, PortsData.SELECT_OPTION);
                if (acted) collectStep = 1;
            }
            dispatchStep = 0;
        } else if (config.autoDispatchVoyages && voyagesAvailable > 0 && readySlot != -1) {
            final int availBefore = voyagesAvailable;
            final int slot = readySlot;
            lastActionWasSend = false;
            acted = dispatchStep(slot);
            // Only verify a send after an actual confirm (not a skip/next-voyage step).
            if (acted && lastActionWasSend) {
                lastDispatchSlot = slot;
                armCheck("dispatch-sent", 10,
                        () -> Ports.availableVoyageCount() < availBefore
                                || Ports.readySlotSelectIndexExcluding(exhaustedSlots) != slot);
            }
        } else if (config.blackMarketBuy && !bmDoneThisSweep && hubOpen
                && Ports.chimes() > config.minChimesFloor && Ports.blackMarketeer() != null) {
            acted = blackMarketStep();
        } else if (config.autoUpgrade && !upgradeDoneThisSweep && hubOpen) {
            // Kick off the resource → upgrade pass; the top-level block drives it.
            upgradeManager = new net.kyle.pop.game.UpgradeManager(this, config.upgradeDryRun);
            debug("action: starting upgrade pass (dryRun=" + config.upgradeDryRun + ")");
            acted = true;
        } else {
            debug("action: nothing to do — sweep complete");
            exhaustedSlots.clear();
            collectExhausted.clear();
            crewOptimizedSlots.clear();
            bmDoneThisSweep = false;
            bmStep = 0;
            upgradeDoneThisSweep = false;
            collectStep = 0;
            collectingSlot = -1;
            onSweepComplete();
            return;
        }

        if (acted) {
            cooldownUntilTick = tickCounter + cooldownTicks();
        }
    }

    /** Arm a post-action check that must pass within `withinTicks`, else logs FAIL. */
    private void armCheck(String name, int withinTicks, java.util.concurrent.Callable<Boolean> cond) {
        checkName = name;
        checkCond = cond;
        checkDeadlineTick = tickCounter + withinTicks;
        emit("[CHECK] armed '" + name + "'");
    }

    /** Returns true while a check is still pending (caller should wait, not act). */
    private boolean evaluatePendingCheck() {
        if (checkCond == null) return false;
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(checkCond.call());
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            emit("[CHECK] PASS " + checkName);
            consecutiveFails = 0;
            lastFailedCheck = null;
            backoffCount = 0;
            checkCond = null;
            return false;
        }
        if (tickCounter >= checkDeadlineTick) {
            emit("[CHECK] FAIL " + checkName + " — expected change didn't happen");
            // A send that didn't take is usually a ship with no captain (can't sail) or
            // a voyage that slipped the success gate — optimize that ship's crew+captain
            // once, then let dispatch retry it.
            if ("dispatch-sent".equals(checkName) && config.optimizeCrew
                    && lastDispatchSlot != -1 && !crewOptimizedSlots.contains(lastDispatchSlot)) {
                emit("[CREW] send failed for slot " + lastDispatchSlot
                        + " (likely no captain) — running crew/captain optimization");
                crewOptimizedSlots.add(lastDispatchSlot);
                crewOptimizer = new net.kyle.pop.game.CrewOptimizer(this);
                dispatchStep = 0;
                checkCond = null;
                return false;
            }
            // Backoff: if the same action keeps failing, stop retrying and idle briefly
            // so we can never spin in an infinite FAIL loop.
            if (checkName != null && checkName.equals(lastFailedCheck)) {
                consecutiveFails++;
            } else {
                lastFailedCheck = checkName;
                consecutiveFails = 1;
            }
            if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                consecutiveFails = 0;
                lastFailedCheck = null;
                dispatchStep = 0;
                if (++backoffCount >= MAX_BACKOFFS) {
                    // Don't idle-loop forever — hard-stop and surface why.
                    autoStop("repeated failures on '" + checkName + "'");
                } else {
                    emit("[CHECK] backoff " + backoffCount + "/" + MAX_BACKOFFS + " — '" + checkName
                            + "' failing; idling 2 min then retrying fresh.");
                    setStatus("Backoff: " + checkName + " failing — idling");
                    idleUntilMs = System.currentTimeMillis() + 120_000L;
                }
            }
            checkCond = null;
            return false;
        }
        return true;
    }

    /**
     * Non-blocking two-step open (one click per tick):
     *   - If inside the port, click the object that opens the voyages screen (950).
     *   - Otherwise enter via the portal (id 3219, option "Enter").
     * Whichever object is nearby decides which step runs.
     */
    private boolean openPort() {
        // Step 2a (preferred): replay the captured MiniMenu action from the user's
        // real Voyages click — but only when we're actually inside the port (the hub
        // menu is present), else this would short-circuit the portal-entry step.
        if (PortsData.MENU_OPEN_OP > -1 && Ports.isHubOpen()) {
            boolean clicked = MiniMenu.interact(PortsData.MENU_OPEN_OP, PortsData.MENU_OPEN_P1,
                    PortsData.MENU_OPEN_P2, PortsData.MENU_OPEN_P3);
            if (clicked) {
                debug("action: opening voyages via captured MiniMenu ("
                        + PortsData.MENU_OPEN_OP + "," + PortsData.MENU_OPEN_P1 + ","
                        + PortsData.MENU_OPEN_P2 + "," + PortsData.MENU_OPEN_P3 + ")");
                armCheck("open-voyages-950", 8, Ports::isPortOpen);
                return true;
            }
        }

        // Step 2b: component-based menu click (if configured).
        if (PortsData.OPEN_VOYAGES_MENU_INTERFACE > -1) {
            boolean clicked = Ports.clickComponentDefault(
                    PortsData.OPEN_VOYAGES_MENU_INTERFACE,
                    PortsData.OPEN_VOYAGES_MENU_INDEX,
                    PortsData.OPEN_VOYAGES_MENU_OPTION);
            if (clicked) {
                debug("action: opening voyages screen via top-menu ["
                        + PortsData.OPEN_VOYAGES_MENU_INTERFACE + "," + PortsData.OPEN_VOYAGES_MENU_INDEX + "]");
                return true;
            }
        }

        // Step 1: outside — enter the port via the portal.
        SceneObject portal = null;
        if (PortsData.PORT_ENTRY_OBJECT_ID > -1) {
            portal = SceneObjectQuery.newQuery().id(PortsData.PORT_ENTRY_OBJECT_ID).results().nearest();
        }
        if (portal == null && PortsData.set(PortsData.PORT_ENTRY_OBJECT)) {
            portal = SceneObjectQuery.newQuery().name(PortsData.PORT_ENTRY_OBJECT).results().nearest();
        }
        if (portal != null) {
            // Self-learn the entry coordinate (for logging / future precise travel).
            try {
                net.botwithus.rs3.game.Coordinate pc = portal.getCoordinate();
                if (pc != null) debug("port portal in scene at " + pc.getX() + "," + pc.getY() + "," + pc.getZ());
            } catch (Throwable ignored) {}
            debug("action: entering port via portal (option '" + PortsData.PORT_ENTRY_OPTION + "')");
            boolean clicked = PortsData.set(PortsData.PORT_ENTRY_OPTION)
                    ? portal.interact(PortsData.PORT_ENTRY_OPTION)
                    : portal.interact(1);
            if (clicked) armCheck("entered-port", 12, Ports::isHubOpen);
            return clicked;
        }

        // Step 0: not in the scene → travel to the port entry coordinate. Traverse.to
        // picks a teleport BY COORDINATE (we avoid Lodestone.PORT_SARIM.teleport() — its
        // enum is misaligned in this client and lands at Seers Village). Traverse.to
        // BLOCKS (Execution.delayUntil) and its game queries require the SCRIPT thread,
        // so we only flag the request here; onLoop() (the script thread) runs the actual
        // travel. Never call Traverse.to on this (ServerTickedEvent) thread.
        if (!config.autoTravel) {
            warnOnce("Not at the port and auto-travel is off — enable 'Auto-travel to port'.");
            setStatus("Not at port (auto-travel off)");
            return false;
        }
        travelRequested = true;
        setStatus("Travelling to port…");
        // VERIFY arrival — Traverse.to can mis-teleport, so confirm we reached the port
        // region / hub within the deadline instead of assuming it worked.
        armCheck("travelled-to-port", 50, () ->
                playerRegionId() == PortsData.PORT_ENTRY_REGION || Ports.isHubOpen() || Ports.isPortOpen());
        return false;
    }

    /** Finish the results-report sequence: get-results (244) then close (277).
     *  Non-blocking, one click per tick; verified by the returned voyage clearing. */
    private boolean collectStepAdvance() {
        switch (collectStep) {
            case 1:
                debug("collect: click Get results [" + PortsData.RESULTS_INTERFACE + "," + PortsData.GET_RESULTS_INDEX + "]");
                collectStep = 2;
                return Ports.clickComponent(PortsData.RESULTS_INTERFACE, PortsData.GET_RESULTS_INDEX, PortsData.SELECT_OPTION);
            case 2:
                debug("collect: click Close [" + PortsData.RESULTS_INTERFACE + "," + PortsData.CLOSE_RESULTS_INDEX + "]");
                collectStep = 0;
                return Ports.clickComponent(PortsData.RESULTS_INTERFACE, PortsData.CLOSE_RESULTS_INDEX, PortsData.SELECT_OPTION);
            default:
                collectStep = 0;
                return false;
        }
    }

    /** One dispatch sub-step per tick (paced by the cooldown). Cycles through voyages
     *  to find one meeting the success threshold; if none do, exhausts the slot so we
     *  don't loop forever. Returns true if it took an action this tick. */
    private boolean dispatchStep(int slot) {
        switch (dispatchStep) {
            case 0:
                // Start on the STANDARD tab (the core loop). If includeSpecialVoyages is
                // on, the evaluator will also sweep the Special/adventurer tab (step 1).
                Ports.clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.STANDARD_VOYAGES_TAB_INDEX, PortsData.SELECT_OPTION);
                debug("dispatch[0]: standard tab + select Ready ship slot " + slot);
                dispatchStep = 1;
                dispatchVoyageIdx = 0;
                dispatchEvalTab = 0;
                bestVoyageIdx = -1; bestVoyageTab = -1; bestVoyageSuccess = -1; bestVoyageReward = -1; bestVoyageMeets = false;
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, slot, PortsData.SELECT_OPTION);
            case 1: {
                // EVALUATE: walk every offered voyage on the current tab, selecting each
                // so its success% and reward can be read (step 2). When the tab is done,
                // optionally switch to the Special tab; then → CHOOSE (3).
                if (dispatchVoyageIdx >= PortsData.VOYAGE_SELECT_INDICES.length) {
                    if (config.includeSpecialVoyages && dispatchEvalTab == 0) {
                        dispatchEvalTab = 1;
                        dispatchVoyageIdx = 0;
                        debug("dispatch[1]: switching to Special voyages tab");
                        return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.SPECIAL_VOYAGES_TAB_INDEX, PortsData.SELECT_OPTION);
                    }
                    dispatchStep = 3;
                    return true;
                }
                int v = PortsData.VOYAGE_SELECT_INDICES[dispatchVoyageIdx];
                if (!Ports.isVoyageSelectable(v)) { // empty list row — skip
                    dispatchVoyageIdx++;
                    return true;
                }
                debug("dispatch[1]: evaluate tab " + dispatchEvalTab + " voyage idx " + dispatchVoyageIdx + " (comp " + v + ")");
                dispatchStep = 2;
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, v, PortsData.SELECT_OPTION);
            }
            case 2: {
                // READ the voyage just selected in step 1 and keep it if it's the best
                // so far (prefer meeting the threshold, then higher reward, then higher %).
                int success = Ports.selectedSuccessChance();
                int reward = Ports.selectedVoyageRewardScore();
                boolean meets = success >= config.minSuccessPercent;
                if (isBetterVoyage(meets, reward, success)) {
                    bestVoyageIdx = dispatchVoyageIdx;
                    bestVoyageTab = dispatchEvalTab;
                    bestVoyageMeets = meets; bestVoyageReward = reward; bestVoyageSuccess = success;
                }
                debug("dispatch[2]: tab " + dispatchEvalTab + " voyage idx " + dispatchVoyageIdx
                        + " success=" + success + "% reward=" + reward + " meets=" + meets);
                dispatchVoyageIdx++;
                dispatchStep = 1;
                return true;
            }
            case 3: {
                // CHOOSE the best voyage: make its tab active, then select it (step 4).
                if (bestVoyageIdx < 0) {
                    warnOnce("No selectable voyage for ready slot " + slot + " — skipping this sweep.");
                    setStatus("Slot " + slot + ": no voyages offered");
                    exhaustedSlots.add(slot);
                    dispatchStep = 0;
                    return true;
                }
                dispatchStep = 4;
                int tab = bestVoyageTab == 1 ? PortsData.SPECIAL_VOYAGES_TAB_INDEX : PortsData.STANDARD_VOYAGES_TAB_INDEX;
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, tab, PortsData.SELECT_OPTION);
            }
            case 4: {
                // Winning tab is active — select the chosen voyage. If it meets the
                // threshold → send flow (5); else try crew optimization once.
                int chosen = PortsData.VOYAGE_SELECT_INDICES[bestVoyageIdx];
                if (!bestVoyageMeets) {
                    if (config.optimizeCrew && !crewOptimizedSlots.contains(slot)) {
                        crewOptimizedSlots.add(slot);
                        // Select the best (closest) voyage so the optimizer reads its adversity.
                        Ports.clickComponent(PortsData.VOYAGE_INTERFACE, chosen, PortsData.SELECT_OPTION);
                        emit("[CREW] slot " + slot + ": best voyage only " + bestVoyageSuccess + "% (< "
                                + config.minSuccessPercent + "%) — attempting crew optimization");
                        crewOptimizer = new net.kyle.pop.game.CrewOptimizer(this);
                        dispatchStep = 0;
                        return true;
                    }
                    warnOnce("No voyage >= " + config.minSuccessPercent + "% for ready slot " + slot
                            + " — skipping it (crew can't reach the threshold; lower it or hire better crew).");
                    setStatus("Slot " + slot + ": no voyage meets " + config.minSuccessPercent + "%");
                    exhaustedSlots.add(slot);
                    dispatchStep = 0;
                    return true;
                }
                emit("[DISPATCH] slot " + slot + ": best voyage = " + (bestVoyageTab == 1 ? "special" : "standard")
                        + " idx " + bestVoyageIdx + " (" + bestVoyageSuccess + "%"
                        + (bestVoyageReward > 0 ? ", reward " + bestVoyageReward : "") + ")");
                dispatchStep = 5;
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, chosen, PortsData.SELECT_OPTION);
            }
            case 5: {
                // Proactive: if this ship has no captain it can't sail — assign one
                // (crew/captain optimization) before wasting a send.
                if (config.optimizeCrew && Ports.shipNeedsCaptain() && !crewOptimizedSlots.contains(slot)) {
                    crewOptimizedSlots.add(slot);
                    emit("[CREW] slot " + slot + " has no captain — optimizing before send");
                    crewOptimizer = new net.kyle.pop.game.CrewOptimizer(this);
                    dispatchStep = 0;
                    return true;
                }
                debug("dispatch[5]: sending best voyage");
                dispatchStep = 6;
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.SEND_VOYAGE_INDEX, PortsData.SELECT_OPTION);
            }
            case 6:
                debug("dispatch[6]: confirm send");
                dispatchStep = 0;
                lastActionWasSend = true;      // a real send happened → verify it
                exhaustedSlots.clear();        // state changed; re-evaluate all slots
                crewOptimizedSlots.clear();
                return Ports.clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.CONFIRM_INDEX, PortsData.SELECT_OPTION);
            default:
                dispatchStep = 0;
                return false;
        }
    }

    /** Voyage ranking used during dispatch evaluation: prefer one that MEETS the
     *  success threshold, then higher reward, then higher success%. */
    private boolean isBetterVoyage(boolean meets, int reward, int success) {
        if (bestVoyageIdx < 0) return true;
        if (meets != bestVoyageMeets) return meets;
        if (reward != bestVoyageReward) return reward > bestVoyageReward;
        return success > bestVoyageSuccess;
    }

    /** Non-blocking Black-Market auto-buy: open the buy dialog (via the Black Marketeer
     *  "View goods"), buy the largest quantity tier affordable within
     *  (chimes − minChimesFloor) and capped by blackMarketMaxSpend, then close. One
     *  purchase per sweep. "Buy whatever's offered" policy. */
    private boolean blackMarketStep() {
        switch (bmStep) {
            case 0: // open the buy dialog
                if (Ports.isBlackMarketOpen()) { bmStep = 1; return false; }
                boolean opened = Ports.openBlackMarket();
                debug("[BM] opening buy dialog (View goods) -> " + opened);
                if (opened) { armCheck("bm-open", 8, Ports::isBlackMarketOpen); bmStep = 1; }
                else { warnOnce("Black Market: couldn't open (Black Marketeer not interactable)."); bmDoneThisSweep = true; }
                return opened;
            case 1: { // decide + buy
                if (!Ports.isBlackMarketOpen()) { bmStep = 0; return false; }
                int budget = Math.min(Ports.chimes() - config.minChimesFloor, config.blackMarketMaxSpend);
                int bestTier = -1, bestCost = -1, bestQty = 0;
                for (int i = 0; i < Ports.blackMarketTierCount(); i++) {
                    int cost = Ports.blackMarketTierCost(i);
                    if (cost > 0 && cost <= budget && cost > bestCost) {
                        bestCost = cost; bestTier = i; bestQty = Ports.blackMarketTierQty(i);
                    }
                }
                if (bestTier < 0) {
                    emit("[BM] " + Ports.blackMarketItem() + ": nothing affordable within budget " + budget + " — skipping.");
                    bmDoneThisSweep = true; bmStep = 3; return false;
                }
                emit("[BM] buying " + bestQty + "x (" + Ports.blackMarketItem() + ") for " + bestCost
                        + " [budget " + budget + "]");
                boolean bought = Ports.blackMarketBuyTier(bestTier);
                bmDoneThisSweep = true;
                bmStep = 3;
                return bought;
            }
            case 3: // close
                bmStep = 0;
                bmDoneThisSweep = true;
                return Ports.closeBlackMarket();
            default:
                bmStep = 0;
                return false;
        }
    }

    private void onSweepComplete() {
        // Detection-only status report each sweep (read, never spend).
        try {
            lastPortStatus = Ports.portStatusSummary();
            emit("[STATUS] " + lastPortStatus);
        } catch (Throwable t) {
            emit("[STATUS] error: " + t);
        }
        // Exit any open POP panel so the bot doesn't idle with the voyage/editor
        // screen open (it reopens next sweep).
        try {
            if (Ports.closePortScreens()) emit("[NAV] closed port screens for idle");
        } catch (Throwable ignored) {
        }
        if (config.runMode == PortsConfig.RunMode.ONE_SHOT) {
            emit("One-shot sweep complete — stopping script.");
            setStatus("Done (one-shot)");
            setActive(false);
            return;
        }
        // Periodic FULL rescan so a same-headcount level-up doesn't leave the DB stale
        // (the on-start smart scan only rescans when the crew COUNT changes).
        sweepsSinceFullScan++;
        if (config.fullRescanSweeps > 0 && sweepsSinceFullScan >= config.fullRescanSweeps) {
            nextScanForceFull = true;
            scanOnStart = true; // fires next sweep once the hub is open again
            sweepsSinceFullScan = 0;
            emit("[SCAN] periodic full crew rescan due (every " + config.fullRescanSweeps + " sweeps)");
        }

        idleUntilMs = System.currentTimeMillis()
                + (long) config.intervalMinutes * 60_000L + random.nextInt(120_000); // +0-2min jitter
        setStatus("Sweep complete — idling " + config.intervalMinutes + " min");
        emit("Sweep complete — idling for " + config.intervalMinutes + " min.");
    }

    /** Latest detection-only port status summary (shown in the GUI). */
    public String lastPortStatus = "(not yet read)";

    // ── Helpers used by Ports / GUI ──────────────────────────────────────────────

    /** Action cooldown in ticks with light jitter, so pacing isn't perfectly regular. */
    private long cooldownTicks() {
        return ACTION_COOLDOWN_TICKS + random.nextInt(3);   // 3-5 ticks
    }

    private int playerRegionId() {
        LocalPlayer p = Client.getLocalPlayer();
        return (p != null && p.getCoordinate() != null) ? p.getCoordinate().getRegionId() : -1;
    }

    /** Hard-stop the script and record why (surfaced in the GUI header + logged). */
    private void autoStop(String reason) {
        stopReason = reason;
        emit("[STOP] " + reason);
        setRunning(false);
    }

    public void warnOnce(String message) {
        if (warnedOnce.add(message)) {
            emit("[WARN] " + message);
        }
    }

    /** In-memory ring buffer of recent log lines, shown live in the GUI Debug tab.
     *  Written on the script thread, read on the render thread → guard with its lock. */
    private final java.util.ArrayDeque<String> logBuffer = new java.util.ArrayDeque<>();
    private static final int LOG_BUFFER_MAX = 400;

    private void pushLog(String line) {
        synchronized (logBuffer) {
            logBuffer.addLast(line);
            while (logBuffer.size() > LOG_BUFFER_MAX) logBuffer.removeFirst();
        }
    }

    /** Snapshot of recent log lines (oldest first) for the GUI. */
    public java.util.List<String> recentLog() {
        synchronized (logBuffer) { return new java.util.ArrayList<>(logBuffer); }
    }

    /** Clear the in-GUI log buffer (does not touch pop-log.txt). */
    public void clearLog() {
        synchronized (logBuffer) { logBuffer.clear(); }
    }

    /** Print to the in-client console AND append to pop-log.txt (which is
     *  background-monitored), so live output is visible outside the client. */
    private void emit(String line) {
        println(line);
        pushLog(line);
        try {
            java.nio.file.Files.write(
                    java.nio.file.Paths.get(System.getProperty("user.home"), "BotWithUs", "scripts", "pop-log.txt"),
                    (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** Always-on log (used by the crew optimizer for milestone reporting). */
    public void log(String message) {
        emit(message);
    }

    /** Verbose log — only prints when the "Verbose debug" toggle is on. */
    public void debug(String message) {
        if (config.verboseDebug) {
            emit("[DEBUG] " + message);
        }
    }

    /** Log the full detection snapshot, but only when it changes (avoids flooding). */
    private void logDetection(boolean portOpen, boolean hubOpen, boolean eventOpen,
                              boolean finishedVoyage, int returnedSlot, int readySlot,
                              int voyagesAvailable, int chimes) {
        if (!config.verboseDebug) return;
        String snap = "detect | portOpen(950)=" + portOpen
                + " hubOpen(905)=" + hubOpen
                + " eventOpen=" + eventOpen
                + " returnedVoyage=" + finishedVoyage + " (slotSelect=" + returnedSlot + ")"
                + " readyShipSlot=" + readySlot
                + " voyagesAvailable=" + voyagesAvailable
                + " chimes=" + chimes;
        if (!snap.equals(lastDebugSnapshot)) {
            emit("[DEBUG] " + snap);
            lastDebugSnapshot = snap;
        }
    }

    /**
     * External control: reads and executes commands from pop-cmd.txt (one per line),
     * then deletes the file. Lets the operator drive mapping/clicks by writing a file.
     * Commands: dumpall | dump <id> | dumploc | interact <iface> <comp>
     *           | mini <op> <p1> <p2> <p3> | pause | resume | shot(marker only)
     */
    private void handleCommandFile() {
        java.nio.file.Path f = java.nio.file.Paths.get(
                System.getProperty("user.home"), "BotWithUs", "scripts", "pop-cmd.txt");
        try {
            if (!java.nio.file.Files.exists(f)) return;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
            java.nio.file.Files.deleteIfExists(f); // consume once
            for (String raw : lines) {
                String cmd = raw.trim();
                if (cmd.isEmpty() || cmd.startsWith("#")) continue;
                String[] t = cmd.split("\\s+");
                emit("[CMD] " + cmd);
                try {
                    switch (t[0].toLowerCase()) {
                        case "dumpall":  InterfaceDebug.dumpOpenInterfaces(this); break;
                        case "dump":     InterfaceDebug.dumpInterface(this, Integer.parseInt(t[1])); break;
                        case "dumploc":  InterfaceDebug.dumpLocation(this); break;
                        case "interact": emit("[CMD] interact " + t[1] + "," + t[2] + " -> "
                                + Ports.clickComponent(Integer.parseInt(t[1]), Integer.parseInt(t[2]), PortsData.SELECT_OPTION)); break;
                        case "mini":     emit("[CMD] mini -> " + MiniMenu.interact(
                                Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]), Integer.parseInt(t[4]))); break;
                        case "pause":    mappingPause = true;  emit("[CMD] paused"); break;
                        case "resume":   mappingPause = false; emit("[CMD] resumed"); break;
                        case "opt":      config.optimizeCrew = t.length > 1 && t[1].equalsIgnoreCase("on");
                                         emit("[CMD] optimizeCrew=" + config.optimizeCrew); break;
                        case "scancrew": crewScanner = new net.kyle.pop.game.CrewScanner(this);
                                         mappingPause = false; // scan runs in the normal loop
                                         emit("[CMD] scancrew started"); break;
                        case "sub":      emit("[CMD] sub " + t[1] + "," + t[2] + " -> " + Ports.clickSub(
                                Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]))); break;
                        case "varsnap": { // snapshot non-zero varbits in a range to pop-vars.txt (no console flood)
                                int a = Integer.parseInt(t[1]), b = Integer.parseInt(t[2]);
                                StringBuilder sb = new StringBuilder("=== varsnap " + a + "-" + b + " ===" + System.lineSeparator());
                                int cnt = 0;
                                for (int i = a; i <= b && (i - a) < 30000; i++) {
                                    int v;
                                    try { v = net.botwithus.rs3.game.vars.VarManager.getVarbitValue(i); } catch (Throwable e) { continue; }
                                    if (v != 0) { sb.append("vb").append(i).append('=').append(v).append(System.lineSeparator()); cnt++; }
                                }
                                java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("user.home"), "BotWithUs", "scripts", "pop-vars.txt"),
                                        sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                emit("[VARSNAP] " + a + "-" + b + " -> pop-vars.txt (" + cnt + " non-zero)");
                                break; }
                        case "invvar":   emit("[INVVAR] inv=" + t[1] + " slot=" + t[2] + " vb=" + t[3] + " -> "
                                + net.botwithus.rs3.game.vars.VarManager.getInvVarbit(
                                        Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]))); break;
                        default:         emit("[CMD] unknown: " + cmd);
                    }
                } catch (Exception e) {
                    emit("[CMD] error on '" + cmd + "': " + e);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void requestDebugDump() {
        dumpRequested = true;
    }

    public void requestLocationDump() {
        dumpLocationRequested = true;
    }

    public void requestDebugInteract() {
        dbgInteractRequested = true;
    }

    public void requestDumpInterface() {
        dbgDumpInterfaceRequested = true;
    }

    /** Start an in-game crew-database rescan (reads the roster into crew-db.txt). */
    public void requestCrewScan() {
        if (crewScanner == null) {
            crewScanner = new net.kyle.pop.game.CrewScanner(this, true); // manual = force full rescan
            mappingPause = false;
        }
    }

    /** Crew count currently loaded in the stat database (for the GUI). */
    public int crewDbCount() {
        if (crewDb == null) refreshCrewDbCount();
        return crewDb == null ? 0 : crewDb.totalCount();
    }

    /** Recorded crew+captain rows for the GUI table. */
    public java.util.List<net.kyle.pop.game.CrewDb.Entry> crewDbEntries() {
        if (crewDb == null) refreshCrewDbCount();
        return crewDb == null ? java.util.Collections.emptyList() : crewDb.entries();
    }

    void refreshCrewDbCount() {
        try { crewDb = net.kyle.pop.game.CrewDb.load(); }
        catch (Throwable t) { crewDb = null; }
    }

    public void persist() {
        config.save(scriptConfig);
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    /** User Start/Stop (GUI). Loads stopped; automation runs only when started. */
    public boolean isRunning() {
        return userRunning;
    }

    public void setRunning(boolean run) {
        if (userRunning == run) return;
        userRunning = run;
        travelRequested = false;
        if (run) { scanOnStart = true; stopReason = ""; backoffCount = 0; } // fresh start
        setStatus(run ? "Started" : "Stopped — press Start");
        emit("[CTRL] " + (run ? "STARTED" : "STOPPED") + " by user");
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format("%dm %02ds", m, s);
    }
}
