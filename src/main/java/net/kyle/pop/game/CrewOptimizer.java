package net.kyle.pop.game;

import net.kyle.pop.PortsScript;
import net.kyle.pop.data.PortsData;
import net.kyle.pop.game.Ports.Crew;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Predictive, non-blocking crew optimizer for Player-Owned Ports.
 *
 * Planning uses REAL per-unit crew stats. It first tries to read each individual's
 * stats LIVE by selecting them in the ship editor (the same select-then-read trick
 * the captain optimizer uses) — this distinguishes same-name crew that differ by
 * level. If the client doesn't populate the stat block for roster crew, it falls
 * back to the recorded per-name averages in {@link CrewDb}. The chosen mode is
 * learned once and cached across runs so we don't waste clicks re-probing.
 *
 * With real stats in hand it computes the ship's base (hull) stats, runs a greedy
 * over the available pool (current crew + this ship's roster) to fill the slots so
 * the worst required stat is maximised, forecasts whether 100% is reachable, then
 * executes the plan with single clicks paced by the caller's tick cooldown. The
 * live ship-stat totals still verify each swap.
 *
 * Swap/assign mechanic (verified live): Select an on-ship slot ([916,182,sub])
 * — filled OR empty — then click a roster crew ([916,199,sub]); they trade
 * places (assigning into an empty slot removes nothing).
 */
public final class CrewOptimizer {

    private static final String[] STAT = { "Morale", "Combat", "Seafaring" };

    /** Learned once: does selecting a roster crew populate the live stat block?
     *  null = unknown (probe this run), TRUE = read live, FALSE = use DB averages. */
    private static Boolean liveReadSupported = null;

    private enum Phase { READ_ADV, OPEN_SHIP, OPEN_GRID,
                         CAPT_ENUM, CAPT_SWAP,
                         SCAN_ENUM, SCAN_SELECT, SCAN_READ,
                         BASELINE, EXECUTE, MEASURE, CLOSE, DONE, FAILED }

    private final PortsScript script;
    private final CrewDb db;
    private final int startSuccess;

    private Phase phase = Phase.READ_ADV;
    private int[] required;      // [M,C,S]
    private long[] base;         // ship base (hull/parts) [M,C,S]
    private int[] totals;        // live totals [M,C,S]
    private int openGridTries = 0;

    // Captain optimization (active captain [916,186] + candidates [916,191]).
    // Names are read via crewNameAt (reliable off the UI thread); stats come from the
    // recorded crew DB, because the SEL-block stat reads return blank/zero in the
    // autonomous loop — relying on them made every captain "unavailable".
    private int captSlotSub = -1;
    private final List<Crew> captPool = new ArrayList<>();       // active (comp 186) + candidates (comp 191)

    // Live per-unit crew read (select each crew, read its stat block).
    private final List<int[]> scanList = new ArrayList<>();      // {comp, sub} to read
    private int scanCursor = 0;
    private int scanComp = -1, scanSub = -1;
    private final List<Crew> liveCrew = new ArrayList<>();       // individuals read this run

    /** Planned assign ops: each = {slotSub(Integer), crew(Crew)} — put crew into that slot. */
    private final List<Object[]> ops = new ArrayList<>();
    private int opIdx = 0;
    private final Set<Integer> usedRosterSubs = new HashSet<>();
    private int[] preOpTotals;

    public CrewOptimizer(PortsScript script) {
        this.script = script;
        this.db = CrewDb.load();
        this.startSuccess = Ports.selectedSuccessChance();
    }

    public boolean isDone()    { return phase == Phase.DONE || phase == Phase.FAILED; }
    public boolean succeeded() { return phase == Phase.DONE; }

    public boolean step() {
        switch (phase) {
            case READ_ADV:  return readAdversity();
            case OPEN_SHIP: return openShip();
            case OPEN_GRID:   return openGrid();
            case CAPT_ENUM:   return captEnum();
            case CAPT_SWAP:   return captSwap();
            case SCAN_ENUM:   return scanEnum();
            case SCAN_SELECT: return scanSelect();
            case SCAN_READ:   return scanRead();
            case BASELINE:  return baseline();
            case EXECUTE:   return execute();
            case MEASURE:   return measure();
            case CLOSE:     return close();
            default:        return false;
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private boolean readAdversity() {
        if (!Ports.isPortOpen()) { fail("voyage screen not open for adversity read"); return false; }
        required = Ports.adversityRequired();
        if (db.isEmpty()) script.log("[CREW] crew DB empty (run 'scancrew') — planning without stats.");
        script.debug("[CREW] required M" + required[0] + "/C" + required[1] + "/S" + required[2]
                + " startSuccess=" + startSuccess + "%");
        phase = Phase.OPEN_SHIP;
        return false;
    }

    private boolean openShip() {
        boolean clicked = Ports.clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.EDIT_SHIP_INDEX, PortsData.SELECT_OPTION);
        script.debug("[CREW] Edit Ship -> " + clicked);
        phase = Phase.OPEN_GRID; openGridTries = 0;
        return clicked;
    }

    private boolean openGrid() {
        if (Ports.isCrewGridVisible()) { phase = Phase.CAPT_ENUM; return false; }
        if (openGridTries++ > 8) { fail("crew grid never populated"); return false; }
        boolean clicked = Ports.clickComponent(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_EDIT_CREW_BUTTON_INDEX, PortsData.SELECT_OPTION);
        script.debug("[CREW] Edit Crew (open grid) try " + openGridTries + " -> " + clicked);
        return clicked;
    }

    // ── Captain optimization (active [916,186] + candidates [916,191]) ───────

    private boolean captEnum() {
        captPool.clear(); captSlotSub = -1;

        // Active captain slot (comp 186) — read its NAME from the slot itself (reliable),
        // and if occupied look its stats up in the DB.
        java.util.List<Integer> slotSubs = Ports.subsWithOption(PortsData.SHIP_CAPTAIN_SLOT_COMP, PortsData.SELECT_OPTION);
        if (!slotSubs.isEmpty()) captSlotSub = slotSubs.get(0);
        String activeName = captSlotSub >= 0
                ? Ports.crewNameAt(PortsData.SHIP_CAPTAIN_SLOT_COMP, captSlotSub) : "";
        if (isRealCaptain(activeName)) captPool.add(dbCrew(PortsData.SHIP_CAPTAIN_SLOT_COMP, captSlotSub, activeName.trim()));

        // Candidate captains (comp 191) — names via crewNameAt, stats via the DB (the
        // SEL-block stat reads are unreliable off the UI thread, so we do NOT click-read them).
        java.util.List<Integer> candSubs = Ports.subsWithOption(PortsData.SHIP_CAPTAIN_CAND_COMP, PortsData.CREW_ASSIGN_OPTION);
        StringBuilder cand = new StringBuilder();
        for (int sub : candSubs) {
            String name = Ports.crewNameAt(PortsData.SHIP_CAPTAIN_CAND_COMP, sub);
            captPool.add(dbCrew(PortsData.SHIP_CAPTAIN_CAND_COMP, sub, name == null ? "" : name.trim()));
            cand.append(name == null || name.isBlank() ? "?" : name.trim()).append(",");
        }
        script.log("[CREW] captain enum: slot=" + captSlotSub + " active='" + activeName.trim()
                + "' candidates=" + candSubs.size() + (cand.length() > 0 ? " [" + cand + "]" : ""));
        // One-shot discovery: the sub text is a generic 'Captain' label — dump the
        // slot + first candidate's params so we can locate where the real name/stats live.
        if (!candSubs.isEmpty()) {
            script.log("[CREW] captain slot info: " + Ports.subComponentInfo(PortsData.SHIP_CAPTAIN_SLOT_COMP, captSlotSub));
            script.log("[CREW] captain cand[0] info: " + Ports.subComponentInfo(PortsData.SHIP_CAPTAIN_CAND_COMP, candSubs.get(0)));
        }

        if (captSlotSub < 0) { phase = Phase.SCAN_ENUM; return false; } // no captain slot on this ship
        phase = Phase.CAPT_SWAP;
        return false;
    }

    private static boolean isRealCaptain(String name) {
        if (name == null) return false;
        String n = name.trim();
        return !n.isBlank() && !n.equalsIgnoreCase("None") && !n.equalsIgnoreCase("Captain");
    }

    /** Ensure the ship has a captain (mandatory to sail), and swap in a better one
     *  for the worst deficient stat when it helps. */
    private boolean captSwap() {
        if (captSlotSub < 0) { phase = Phase.SCAN_ENUM; return false; }
        Crew active = null;
        for (Crew c : captPool) if (c.comp() == PortsData.SHIP_CAPTAIN_SLOT_COMP) active = c;

        // MANDATORY: an empty captain slot blocks sailing. Assign the strongest
        // candidate (by DB stats; ties/unknowns → first) — any captain beats none,
        // so this must NOT depend on readable stats.
        if (active == null) {
            Crew best = null;
            for (Crew c : captPool) {
                if (c.comp() != PortsData.SHIP_CAPTAIN_CAND_COMP) continue;
                if (best == null || total(c) > total(best)) best = c;
            }
            if (best == null) {
                script.log("[CREW] captain: empty slot but NO candidate captains found (check comp 191/'Assign').");
                phase = Phase.SCAN_ENUM; return false;
            }
            script.log("[CREW] captain: ship had NONE — assigning " + captLabel(best));
            Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_CAPTAIN_SLOT_COMP, captSlotSub);
            boolean c1 = Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_CAPTAIN_CAND_COMP, best.sub());
            phase = Phase.SCAN_ENUM;
            return c1;
        }

        // Otherwise optimise for the worst deficient stat (DB stats).
        int[] now = Ports.shipTotals();
        int d = -1; double worst = 1.0;
        for (int s = 0; s < 3; s++) {
            if (required[s] <= 0) continue;
            double r = Math.min(1.0, (double) now[s] / required[s]);
            if (r < 1.0 && r < worst) { worst = r; d = s; }
        }
        if (d < 0) { phase = Phase.SCAN_ENUM; return false; }
        Crew best = null;
        for (Crew c : captPool) {
            if (c.comp() != PortsData.SHIP_CAPTAIN_CAND_COMP) continue;
            if (best == null || c.stat(d) > best.stat(d)) best = c;
        }
        if (best == null || best.stat(d) <= active.stat(d)) {
            script.debug("[CREW] captain: keeping " + active.name() + " (" + STAT[d] + " already best)");
            phase = Phase.SCAN_ENUM;
            return false;
        }
        script.log("[CREW] captain: swap in " + captLabel(best) + " (" + STAT[d] + " " + best.stat(d)
                + " vs " + active.stat(d) + ")");
        Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_CAPTAIN_SLOT_COMP, captSlotSub);
        boolean clicked = Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_CAPTAIN_CAND_COMP, best.sub());
        phase = Phase.SCAN_ENUM;
        return clicked;
    }

    private static String captLabel(Crew c) {
        String nm = c.name() == null || c.name().isBlank() ? "(captain sub " + c.sub() + ")" : c.name();
        return nm + " (M" + c.morale() + "/C" + c.combat() + "/S" + c.seafaring() + ")";
    }

    private static int total(Crew c) { return c.morale() + c.combat() + c.seafaring(); }

    // ── Live per-unit read (select each crew, read its real stats) ───────────

    private boolean scanEnum() {
        scanList.clear(); liveCrew.clear(); scanCursor = 0;
        // We already learned this client doesn't expose live roster stats — skip the
        // probe entirely and plan from the recorded DB averages.
        if (Boolean.FALSE.equals(liveReadSupported)) { phase = Phase.BASELINE; return false; }
        for (int sub : Ports.shipCrewSlotSubs()) {
            String name = Ports.crewNameAt(PortsData.SHIP_ASSIGNED_CREW_COMP, sub);
            if (name != null && !name.isBlank() && !name.equalsIgnoreCase("None"))
                scanList.add(new int[]{ PortsData.SHIP_ASSIGNED_CREW_COMP, sub });
        }
        for (Crew c : Ports.rosterCrew())
            scanList.add(new int[]{ PortsData.SHIP_ROSTER_CREW_COMP, c.sub() });
        if (scanList.isEmpty()) { phase = Phase.BASELINE; return false; }
        phase = Phase.SCAN_SELECT;
        return false;
    }

    private boolean scanSelect() {
        if (scanCursor >= scanList.size()) { finishScan(); return false; }
        int[] cs = scanList.get(scanCursor);
        scanComp = cs[0]; scanSub = cs[1];
        boolean clicked = Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, cs[0], cs[1]);
        phase = Phase.SCAN_READ;
        return clicked;
    }

    private boolean scanRead() {
        Crew read = Ports.readSelectedCrew(scanComp, scanSub);
        // Canonical name comes from the crew's own sub (the shared SEL name can lag).
        String name = Ports.crewNameAt(scanComp, scanSub);
        if (name == null || name.isBlank()) name = read.name();
        liveCrew.add(new Crew(scanComp, scanSub, name == null ? "" : name.trim(),
                read.morale(), read.combat(), read.seafaring(), read.speed()));
        scanCursor++;
        phase = Phase.SCAN_SELECT;
        return false;
    }

    private void finishScan() {
        int statSum = 0;
        for (Crew c : liveCrew) statSum += c.morale() + c.combat() + c.seafaring();
        boolean live = statSum > 0;
        liveReadSupported = live; // learn for subsequent runs
        script.log("[CREW] per-unit read: " + (live
                ? "LIVE stats from " + liveCrew.size() + " crew"
                : "roster stats not exposed — using recorded DB averages"));
        phase = Phase.BASELINE;
    }

    // ── Plan from real per-unit stats (live if available, else DB average) ────

    private boolean baseline() {
        totals = Ports.shipTotals();
        boolean live = Boolean.TRUE.equals(liveReadSupported) && !liveCrew.isEmpty();

        // Slots (filled + empty) and this ship's roster.
        List<Integer> slotSubs = Ports.shipCrewSlotSubs();
        List<Crew> onship = new ArrayList<>();   // filled slots
        List<Integer> emptySlots = new ArrayList<>();
        for (int sub : slotSubs) {
            String name = Ports.crewNameAt(PortsData.SHIP_ASSIGNED_CREW_COMP, sub);
            if (name == null || name.isBlank() || name.equalsIgnoreCase("None")) { emptySlots.add(sub); continue; }
            onship.add(crewFor(PortsData.SHIP_ASSIGNED_CREW_COMP, sub, name, live));
        }
        List<Crew> roster = new ArrayList<>();
        for (Crew c : Ports.rosterCrew()) roster.add(crewFor(PortsData.SHIP_ROSTER_CREW_COMP, c.sub(), c.name(), live));

        // base = live totals − Σ(current on-ship crew stats)
        base = new long[3];
        for (int s = 0; s < 3; s++) {
            long sum = 0; for (Crew c : onship) sum += c.stat(s);
            base[s] = totals[s] - sum;
        }
        script.log("[CREW] req M" + required[0] + "/C" + required[1] + "/S" + required[2]
                + " | ship base M" + base[0] + "/C" + base[1] + "/S" + base[2]
                + " | slots=" + slotSubs.size() + " (empty " + emptySlots.size() + ") roster=" + roster.size()
                + " | " + (live ? "per-unit" : "avg"));

        planLoadout(slotSubs, onship, emptySlots, roster);
        return false;
    }

    /** Greedy-optimal loadout from base + available pool, then a forecast + op list. */
    private void planLoadout(List<Integer> slotSubs, List<Crew> onship, List<Integer> emptySlots, List<Crew> roster) {
        int n = slotSubs.size();
        List<Crew> pool = new ArrayList<>(); pool.addAll(onship); pool.addAll(roster);

        // Greedy: repeatedly add the crew that most raises the worst required-stat ratio.
        // With per-unit stats this naturally prefers the strongest individual of a name.
        List<Crew> chosen = new ArrayList<>();
        long[] cur = base.clone();
        Set<Integer> usedPool = new HashSet<>();
        for (int k = 0; k < n; k++) {
            int bi = -1; double best = minRatio(cur);
            for (int i = 0; i < pool.size(); i++) {
                if (usedPool.contains(i)) continue;
                double r = minRatio(add(cur, pool.get(i)));
                if (r > best + 1e-9) { best = r; bi = i; }
            }
            // Tie-break at the ceiling (all required met): keep adding the highest
            // total-stat crew so we don't leave slots emptier than the current loadout.
            if (bi < 0) {
                for (int i = 0; i < pool.size(); i++) {
                    if (usedPool.contains(i)) continue;
                    if (bi < 0 || total(pool.get(i)) > total(pool.get(bi))) bi = i;
                }
                if (bi < 0) break;
                // Only take a "free" crew if it doesn't displace an already-chosen one
                // and there's an empty/available slot for it — the op builder handles fit.
            }
            usedPool.add(bi); chosen.add(pool.get(bi)); cur = add(cur, pool.get(bi));
        }

        // Forecast.
        boolean feasible = true;
        StringBuilder pred = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            if (required[s] <= 0) continue;
            long v = cur[s];
            boolean met = v >= required[s];
            feasible &= met;
            pred.append(STAT[s]).append(" ").append(v).append("/").append(required[s]).append(met ? "✓ " : "✗ ");
        }
        script.log("[CREW] PLAN: " + pred + "→ " + (feasible ? "CAN reach 100%" : "max ~" + Math.round(minRatio(cur) * 100) + "%")
                + " | loadout: " + names(chosen));

        // Build assign ops: chosen crew that are in the ROSTER must be brought in;
        // chosen crew already on-ship stay; their slots are protected.
        Set<Integer> keptSlots = new HashSet<>();
        List<Crew> toAdd = new ArrayList<>();
        for (Crew c : chosen) {
            if (c.comp() == PortsData.SHIP_ASSIGNED_CREW_COMP) keptSlots.add(c.sub());
            else toAdd.add(c);
        }
        List<Integer> freeSlots = new ArrayList<>(emptySlots);
        for (int sub : slotSubs) if (!keptSlots.contains(sub) && !emptySlots.contains(sub)) freeSlots.add(sub);

        ops.clear();
        for (int i = 0; i < toAdd.size() && i < freeSlots.size(); i++) {
            ops.add(new Object[]{ freeSlots.get(i), toAdd.get(i) });
        }
        opIdx = 0;
        if (ops.isEmpty()) { script.log("[CREW] already optimal — no crew changes needed."); phase = Phase.CLOSE; return; }
        phase = Phase.EXECUTE;
    }

    // ── Execute the plan ─────────────────────────────────────────────────────

    private boolean execute() {
        if (opIdx >= ops.size()) { finishOk(); return false; }
        int slotSub = (int) ops.get(opIdx)[0];
        Crew crew = (Crew) ops.get(opIdx)[1];
        int rosterSub = resolveRosterSub(crew);
        if (rosterSub < 0) { script.debug("[CREW] '" + crew.name() + "' no longer in roster; skipping"); opIdx++; return false; }
        preOpTotals = Ports.shipTotals();
        // Select the target slot, then click the roster crew → assign/swap.
        Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_ASSIGNED_CREW_COMP, slotSub);
        boolean clicked = Ports.clickSub(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_ROSTER_CREW_COMP, rosterSub);
        usedRosterSubs.add(rosterSub);
        script.debug("[CREW] assign '" + crew.name() + "' (roster sub " + rosterSub + ") -> slot " + slotSub);
        phase = Phase.MEASURE;
        return clicked;
    }

    private boolean measure() {
        int[] now = Ports.shipTotals();
        script.debug("[CREW] after op " + (opIdx + 1) + "/" + ops.size()
                + ": M" + now[0] + "/C" + now[1] + "/S" + now[2]);
        totals = now;
        opIdx++;
        phase = Phase.EXECUTE;
        return false;
    }

    private boolean close() {
        // Fully close the ship editor (not just the crew grid) so we don't leave the
        // editor window open behind the voyage screen.
        boolean clicked = Ports.clickComponent(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_EDITOR_CLOSE_INDEX, PortsData.SELECT_OPTION);
        script.debug("[CREW] close ship editor -> " + clicked);
        phase = Phase.DONE;
        return clicked;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Per-unit stats for a crew: prefer the live-read individual (this comp+sub),
     *  else fall back to the recorded per-name average. */
    private Crew crewFor(int comp, int sub, String name, boolean live) {
        if (live) {
            for (Crew c : liveCrew) {
                if (c.comp() == comp && c.sub() == sub
                        && (c.morale() + c.combat() + c.seafaring()) > 0) return c;
            }
        }
        return dbCrew(comp, sub, name);
    }

    private Crew dbCrew(int comp, int sub, String name) {
        int[] s = db.stats(name);
        return new Crew(comp, sub, name, s[0], s[1], s[2], s[3]);
    }

    /** Find the roster sub to click for a planned crew. Prefer the exact captured sub
     *  if it still holds the same crew (roster slots are fixed positions); otherwise
     *  match by name among unused subs (handles reindexing / DB-average mode). */
    private int resolveRosterSub(Crew want) {
        if (want.comp() == PortsData.SHIP_ROSTER_CREW_COMP && !usedRosterSubs.contains(want.sub())) {
            String n = Ports.crewNameAt(PortsData.SHIP_ROSTER_CREW_COMP, want.sub());
            if (n != null && n.equalsIgnoreCase(want.name())) return want.sub();
        }
        return findRosterSub(want.name());
    }

    private long[] add(long[] cur, Crew c) {
        return new long[]{ cur[0] + c.stat(0), cur[1] + c.stat(1), cur[2] + c.stat(2) };
    }

    /** Worst required-stat ratio (capped at 1.0); 1.0 if nothing required. */
    private double minRatio(long[] cur) {
        double worst = Double.MAX_VALUE;
        for (int s = 0; s < 3; s++) {
            if (required[s] <= 0) continue;
            double r = Math.min(1.0, (double) cur[s] / required[s]);
            if (r < worst) worst = r;
        }
        return worst == Double.MAX_VALUE ? 1.0 : worst;
    }

    private int findRosterSub(String name) {
        for (Crew c : Ports.rosterCrew()) {
            if (!usedRosterSubs.contains(c.sub()) && c.name().equalsIgnoreCase(name)) return c.sub();
        }
        return -1;
    }

    private static String names(List<Crew> cs) {
        if (cs.isEmpty()) return "(none)";
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Crew c : cs) counts.merge(c.name(), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (var e : counts.entrySet()) sb.append(e.getValue()).append("x ").append(e.getKey()).append(", ");
        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : sb.toString();
    }

    private void finishOk() {
        script.log("[CREW] done: final M" + totals[0] + "/C" + totals[1] + "/S" + totals[2]
                + " after " + ops.size() + " assign(s)");
        phase = Phase.CLOSE;
    }

    private void fail(String why) { script.log("[CREW] aborted: " + why); phase = Phase.FAILED; }
}
