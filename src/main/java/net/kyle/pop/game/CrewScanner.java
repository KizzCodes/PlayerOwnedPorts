package net.kyle.pop.game;

import net.botwithus.rs3.game.hud.interfaces.Component;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.queries.builders.components.ComponentQuery;
import net.kyle.pop.PortsScript;
import net.kyle.pop.data.PortsData;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking scanner that rebuilds the per-crew stat database ({@link CrewDb})
 * by reading every owned crew from the Crew Roster (1276): it opens the roster,
 * clicks each crew in "Your Crew" (Info) to populate the detail panel, reads the
 * stats, and writes {@code crew-db.txt}. One click/read per {@link #step()}.
 *
 * Run it after crew change (recruit / level up) via the pop-cmd "scancrew" command;
 * the optimizer auto-suggests it when the DB is empty.
 */
public final class CrewScanner {

    private enum Phase { OPEN, ENUM, SELECT, READ, SAVE, CLOSE, DONE, FAILED }

    private final PortsScript script;
    private final boolean forceFull;
    private Phase phase = Phase.OPEN;
    private int openTries = 0;
    private List<Integer> subs = new ArrayList<>();
    private int cursor = 0;
    private int pendingSub = -1;
    private final List<String[]> rows = new ArrayList<>();

    public CrewScanner(PortsScript script) { this(script, false); }
    public CrewScanner(PortsScript script, boolean forceFull) { this.script = script; this.forceFull = forceFull; }

    public boolean isDone() { return phase == Phase.DONE || phase == Phase.FAILED; }

    public boolean step() {
        switch (phase) {
            case OPEN:   return open();
            case ENUM:   return enumerate();
            case SELECT: return select();
            case READ:   return read();
            case SAVE:   return save();
            case CLOSE:  return close();
            default:     return false;
        }
    }

    private boolean open() {
        if (rosterListSubs().size() > 0) { phase = Phase.ENUM; return false; }
        if (openTries++ > 8) { fail("crew roster (1276) never opened"); return false; }
        // Open from the hub's roster "Open" button.
        boolean clicked = Ports.clickComponent(PortsData.HUB_INTERFACE, PortsData.HUB_CREW_ROSTER_OPEN_INDEX, PortsData.SELECT_OPTION);
        script.debug("[SCAN] open crew roster try " + openTries + " -> " + clicked);
        return clicked;
    }

    private boolean enumerate() {
        subs = rosterListSubs();
        if (subs.isEmpty()) { fail("no crew in roster list"); return false; }
        // Smart scan: if the roster size matches the cached DB, crew are unchanged —
        // skip the slow per-crew read loop (catches recruit/dismiss; a level-up with
        // the same count needs a manual Rescan).
        int cached = CrewDb.load().totalCount();
        if (!forceFull && cached > 0 && cached == subs.size()) {
            script.log("[SCAN] crew unchanged (" + cached + ") — using cached DB, skipping full scan");
            phase = Phase.CLOSE;
            return false;
        }
        script.log("[SCAN] scanning " + subs.size() + " crew into the database…");
        cursor = 0; rows.clear();
        phase = Phase.SELECT;
        return false;
    }

    private boolean select() {
        if (cursor >= subs.size()) { phase = Phase.SAVE; return false; }
        pendingSub = subs.get(cursor);
        boolean clicked = Ports.clickSub(PortsData.CREW_ROSTER_INTERFACE, PortsData.ROSTER_CREW_LIST_COMP, pendingSub);
        phase = Phase.READ;
        return clicked;
    }

    private boolean read() {
        String name = text(PortsData.ROSTER_DETAIL_NAME_INDEX);
        if (name != null && !name.isBlank()) {
            rows.add(new String[]{
                    name.trim(),
                    num(PortsData.ROSTER_DETAIL_MORALE_INDEX),
                    num(PortsData.ROSTER_DETAIL_COMBAT_INDEX),
                    num(PortsData.ROSTER_DETAIL_SEAFARING_INDEX),
                    num(PortsData.ROSTER_DETAIL_SPEED_INDEX) });
        }
        cursor++;
        phase = Phase.SELECT;
        return false;
    }

    private boolean save() {
        CrewDb.save(rows);
        script.log("[SCAN] wrote " + rows.size() + " crew to " + CrewDb.FILE);
        phase = Phase.CLOSE;
        return false;
    }

    /** Close the crew roster we opened so it isn't left on-screen. */
    private boolean close() {
        boolean clicked = Ports.clickComponent(PortsData.CREW_ROSTER_INTERFACE, PortsData.ROSTER_CLOSE_INDEX, PortsData.SELECT_OPTION);
        script.debug("[SCAN] close crew roster -> " + clicked);
        phase = Phase.DONE;
        return clicked;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Integer> rosterListSubs() {
        List<Integer> out = new ArrayList<>();
        for (Component c : ComponentQuery.newQuery(PortsData.CREW_ROSTER_INTERFACE).results()) {
            if (c == null || c.getComponentIndex() != PortsData.ROSTER_CREW_LIST_COMP) continue;
            if (c.getSubComponentIndex() < 0) continue;
            List<String> opts = c.getOptions();
            if (opts != null && opts.stream().anyMatch(o -> PortsData.ROSTER_INFO_OPTION.equalsIgnoreCase(o))) {
                out.add(c.getSubComponentIndex());
            }
        }
        return out;
    }

    private String text(int idx) {
        Component c = ComponentQuery.newQuery(PortsData.CREW_ROSTER_INTERFACE).componentIndex(idx).results().first();
        return c == null ? null : c.getText();
    }

    private String num(int idx) {
        String t = text(idx);
        if (t == null) return "0";
        String d = t.replaceAll("[^0-9]", "");
        return d.isEmpty() ? "0" : d;
    }

    private void fail(String why) { script.log("[SCAN] aborted: " + why); phase = Phase.FAILED; }
}
