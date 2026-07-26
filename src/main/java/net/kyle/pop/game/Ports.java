package net.kyle.pop.game;

import net.botwithus.api.game.hud.Dialog;
import net.botwithus.rs3.game.hud.interfaces.Component;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.queries.builders.components.ComponentQuery;
import net.kyle.pop.data.PortsData;

import static net.kyle.pop.data.PortsData.set;

/**
 * Non-blocking helper for Player-Owned Ports. Every method does at most ONE quick
 * game read or ONE click and returns immediately — there are NO Execution.delay
 * calls here. Pacing between actions is handled by the tick cooldown in
 * {@link PortsScript}, so nothing ever blocks the client's tick thread.
 */
public final class Ports {

    private Ports() {}

    // ── State checks ───────────────────────────────────────────────────────────

    public static boolean isPortOpen() {
        return Interfaces.isOpen(PortsData.VOYAGE_INTERFACE);
    }

    public static boolean isHubOpen() {
        return Interfaces.isOpen(PortsData.HUB_INTERFACE);
    }

    public static boolean isDialogOpen() {
        return Dialog.isOpen();
    }

    public static boolean isEventOpen() {
        if (Dialog.isOpen()) return true;
        return set(PortsData.EVENT_INTERFACE) && Interfaces.isOpen(PortsData.EVENT_INTERFACE);
    }

    /** The voyage-results report (opens when a returned ship is clicked). */
    public static boolean isResultsOpen() {
        return Interfaces.isOpen(PortsData.RESULTS_INTERFACE);
    }

    /** Close any open POP panel (voyage screen / ship editor / crew roster) so the bot
     *  doesn't idle with a screen open. Returns true if it clicked a close. */
    public static boolean closePortScreens() {
        boolean did = false;
        if (Interfaces.isOpen(PortsData.VOYAGE_INTERFACE)) {
            clickComponent(PortsData.VOYAGE_INTERFACE, PortsData.VOYAGE_CLOSE_INDEX, PortsData.SELECT_OPTION);
            did = true;
        }
        // Ship editor + roster: close only if their close button is actually present
        // (916 is a persistent container, so don't rely on isOpen for it).
        if (clickComponentDefault(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_EDITOR_CLOSE_INDEX, PortsData.SELECT_OPTION)) did = true;
        if (clickComponentDefault(PortsData.CREW_ROSTER_INTERFACE, PortsData.ROSTER_CLOSE_INDEX, PortsData.SELECT_OPTION)) did = true;
        return did;
    }

    public static boolean hasFinishedVoyage() {
        return returnedSlotSelectIndex() != -1;
    }

    public static boolean hasAvailableVoyage() {
        return availableVoyageCount() > 0 && readySlotSelectIndex() != -1;
    }

    public static int availableVoyageCount() {
        if (!isPortOpen()) return 0;
        // Text-based (robust to index numbering): find the "Voyages available: N" label.
        String t = scanTextContains(PortsData.VOYAGE_INTERFACE, "available");
        return extractInt(t);
    }

    /** True if any component on the voyages screen currently reads "Returned". */
    public static boolean returnedVoyagePresentByText() {
        return isPortOpen() && scanTextEquals(PortsData.VOYAGE_INTERFACE, PortsData.STATUS_RETURNED) != null;
    }

    // ── Black Market auto-buy (Black Marketeer NPC → buy dialog 941) ─────────────

    /** The Black Marketeer NPC (nearest), or null if not in range. */
    public static net.botwithus.rs3.game.scene.entities.characters.npc.Npc blackMarketeer() {
        return net.botwithus.rs3.game.queries.builders.characters.NpcQuery.newQuery()
                .id(PortsData.BLACK_MARKETEER_NPC_ID).results().nearest();
    }

    /** Open the Black Market buy dialog (941) by interacting "View goods". */
    public static boolean openBlackMarket() {
        var npc = blackMarketeer();
        if (npc == null) return false;
        return npc.interact(PortsData.BM_VIEW_OPTION);
    }

    public static boolean isBlackMarketOpen() {
        return Interfaces.isOpen(PortsData.BLACK_MARKET_BUY_INTERFACE)
                && !textAt(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_ITEM_NAME_INDEX).isBlank();
    }

    /** The offered item text, e.g. "132 Black slate". */
    public static String blackMarketItem() {
        return textAt(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_ITEM_NAME_INDEX).trim();
    }

    /** Cost of buy-tier i (the number after "for" in "Buy N for COST"), or -1. */
    public static int blackMarketTierCost(int i) {
        if (i < 0 || i >= PortsData.BM_BUY_LABEL_INDICES.length) return -1;
        String t = textAt(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_BUY_LABEL_INDICES[i]);
        return lastInt(t);
    }

    /** Quantity of buy-tier i (the first number in "Buy N for COST"), or -1. */
    public static int blackMarketTierQty(int i) {
        if (i < 0 || i >= PortsData.BM_BUY_LABEL_INDICES.length) return -1;
        String t = textAt(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_BUY_LABEL_INDICES[i]);
        return t.isBlank() ? -1 : extractInt(t);
    }

    public static int blackMarketTierCount() { return PortsData.BM_BUY_BUTTON_INDICES.length; }

    public static boolean blackMarketBuyTier(int i) {
        return clickComponent(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_BUY_BUTTON_INDICES[i], PortsData.SELECT_OPTION);
    }

    public static boolean closeBlackMarket() {
        return clickComponent(PortsData.BLACK_MARKET_BUY_INTERFACE, PortsData.BM_CLOSE_INDEX, PortsData.SELECT_OPTION);
    }

    /** Last integer in a string like "Buy 5 for 2500" -> 2500. */
    private static int lastInt(String s) {
        if (s == null) return -1;
        int last = -1, i = 0, n = s.length();
        while (i < n) {
            if (Character.isDigit(s.charAt(i))) {
                int v = 0;
                while (i < n && Character.isDigit(s.charAt(i))) { v = v * 10 + (s.charAt(i) - '0'); i++; }
                last = v;
            } else i++;
        }
        return last;
    }

    public static int chimes() {
        // Preferred: the Chimes currency ITEM (37753) in the currency pouch (1473) —
        // the hub text (comp 37) proved unreliable (read 132 vs a real 2898).
        for (Component c : ComponentQuery.newQuery(PortsData.CURRENCY_POUCH_INTERFACE).results()) {
            if (c != null && c.getItemId() == PortsData.CHIMES_ITEM_ID) {
                int amt = c.getItemAmount();
                if (amt > 0) return amt;
            }
        }
        if (!isHubOpen()) return -1;
        return extractInt(textAt(PortsData.HUB_INTERFACE, PortsData.HUB_CHIMES_TEXT_INDEX));
    }

    /** True when the voyage screen's "needs a captain" prompt is VISIBLE — i.e. the
     *  selected ship has no captain and can't sail. Uses Component.isHidden() so it
     *  only reports when the prompt is actually shown (proactive, before a failed send). */
    public static boolean shipNeedsCaptain() {
        if (!isPortOpen()) return false;
        Component c = comp(PortsData.VOYAGE_INTERFACE, PortsData.VOYAGE_NEEDS_CAPTAIN_INDEX);
        if (c == null) return false;
        try { if (c.isHidden()) return false; } catch (Throwable t) { return false; }
        String t = c.getText();
        return t != null && t.toLowerCase().contains("captain");
    }

    /** "Overall success chance" of the currently selected voyage (0 if none). */
    public static int selectedSuccessChance() {
        if (!isPortOpen()) return 0;
        return extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.SUCCESS_CHANCE_INDEX));
    }

    /** True if the given voyage-list component currently exposes a Select option
     *  (i.e. it's a real, pickable voyage and not an empty list row). */
    public static boolean isVoyageSelectable(int index) {
        return componentHasOption(PortsData.VOYAGE_INTERFACE, index, PortsData.SELECT_OPTION);
    }

    /** True if the component at (interface,index) exposes the given menu option. */
    public static boolean componentHasOption(int interfaceId, int index, String option) {
        Component c = comp(interfaceId, index);
        return c != null && hasOption(c, option);
    }

    /** Best-effort reward score for the currently-selected voyage, used to rank which
     *  voyage to dispatch when several meet the success threshold. Voyages reward
     *  chimes (the universal POP currency), so we sum any "N chimes" numbers shown on
     *  the voyage screen for the selection. Returns 0 when no chime reward text is
     *  present — callers then fall back to ranking by success%. (Reward-component ids
     *  aren't mapped yet; this text scan is a safe proxy that never mis-ranks below
     *  success-only.) */
    public static int selectedVoyageRewardScore() {
        if (!isPortOpen()) return 0;
        int score = 0;
        for (Component c : ComponentQuery.newQuery(PortsData.VOYAGE_INTERFACE).results()) {
            if (c == null) continue;
            String t = c.getText();
            if (t != null && t.toLowerCase().contains("chime")) score += extractInt(t);
        }
        return score;
    }

    /** Detection-only port status summary (read, never act). Best with 950 + 905 open. */
    public static String portStatusSummary() {
        int ready = 0, returned = 0, empty = 0, sailing = 0;
        if (isPortOpen()) {
            for (int sel : PortsData.SLOT_SELECT_INDICES) {
                String st = textAt(PortsData.VOYAGE_INTERFACE, sel + PortsData.SLOT_STATUS_OFFSET).trim();
                if (st.isEmpty()) continue;
                if (PortsData.STATUS_READY.equalsIgnoreCase(st)) ready++;
                else if (PortsData.STATUS_RETURNED.equalsIgnoreCase(st)) returned++;
                else if (PortsData.STATUS_NO_SHIP.equalsIgnoreCase(st)) empty++;
                else sailing++; // "Sailing" / timer / anything else
            }
        }
        String capName = isHubOpen() ? textAt(PortsData.HUB_INTERFACE, PortsData.HUB_CAPTAIN_NAME_INDEX) : "";
        String capStatus = isHubOpen() ? textAt(PortsData.HUB_INTERFACE, PortsData.HUB_CAPTAIN_STATUS_INDEX) : "";
        // "Special Voyages (N)" text sits on a subcomponent — find it by text scan.
        int special = isPortOpen() ? extractInt(scanTextContains(PortsData.VOYAGE_INTERFACE, "Special Voyages")) : -1;

        return "chimes=" + chimes()
                + " | ships ready=" + ready + " returned=" + returned + " sailing=" + sailing + " empty=" + empty
                + " | voyagesAvail=" + availableVoyageCount()
                + " | specialVoyages=" + special
                + " | captainForHire=" + capName.replace("<br>", " ").trim()
                + (capStatus.isBlank() ? "" : " (" + capStatus.trim() + ")")
                + hubExtrasSummary();
    }

    /** Detection-only report of the hub's resource / trade / market panels (read,
     *  never spend). Empty string when the hub isn't backing state right now. */
    private static String hubExtrasSummary() {
        if (!isHubOpen()) return "";
        String res1 = textAt(PortsData.HUB_INTERFACE, PortsData.HUB_RESOURCE1_INDEX).trim();
        String res2 = textAt(PortsData.HUB_INTERFACE, PortsData.HUB_RESOURCE2_INDEX).trim();
        String has  = textAt(PortsData.HUB_INTERFACE, PortsData.HUB_TRADER_HAS_INDEX).trim();
        String wants = textAt(PortsData.HUB_INTERFACE, PortsData.HUB_TRADER_WANTS_INDEX).trim();
        String arch = textAt(PortsData.HUB_INTERFACE, PortsData.HUB_ARCHITECT_STATUS_INDEX).trim();
        return " | resources=" + res1 + "/" + res2
                + " | blackMarketItems=" + blackMarketItemCount()
                + " | trader(has=" + has + ",wants=" + wants + ")"
                + " | architect=" + (arch.isEmpty() ? "?" : arch)
                + " | upgradeBuildable=" + upgradeBuildable();
    }

    /** How many Black-Market item slots currently offer a Select (0..2). Detect-only. */
    public static int blackMarketItemCount() {
        if (!isHubOpen()) return 0;
        int n = 0;
        for (int idx : PortsData.HUB_BLACK_MARKET_ITEM_INDICES) {
            Component c = comp(PortsData.HUB_INTERFACE, idx);
            if (c != null && c.getOptions() != null && !c.getOptions().isEmpty()) n++;
        }
        return n;
    }

    /** True if the Upgrade Buildings screen currently exposes a Build button
     *  (i.e. the shown building's upgrade is affordable/available right now). */
    public static boolean upgradeBuildable() {
        if (!Interfaces.isOpen(PortsData.UPGRADE_INTERFACE)) return false;
        Component c = comp(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_BUILD_INDEX);
        return c != null && c.getOptions() != null && !c.getOptions().isEmpty();
    }

    // ── Upgrade Buildings automation (interface 1373) ────────────────────────────

    public static boolean isUpgradesOpen() {
        return Interfaces.isOpen(PortsData.UPGRADE_INTERFACE);
    }

    /** Open the Upgrade Buildings screen from the hub (905,53 → 1373). */
    public static boolean openUpgrades() {
        return clickComponent(PortsData.HUB_INTERFACE, PortsData.UPGRADE_OPEN_INDEX, PortsData.SELECT_OPTION);
    }

    /** Advance to the next building in the Upgrade Buildings carousel. */
    public static boolean upgradeNext() {
        return clickComponent(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_NEXT_INDEX, PortsData.SELECT_OPTION);
    }

    /** Click Build for the building currently shown (spends resources — callers must
     *  gate this behind the auto-upgrade opt-in and honour dry-run). */
    public static boolean upgradeBuild() {
        return clickComponent(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_BUILD_INDEX, PortsData.SELECT_OPTION);
    }

    public static boolean closeUpgrades() {
        return clickComponent(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_CLOSE_INDEX, PortsData.SELECT_OPTION);
    }

    /** Name/title of the building currently shown in the Upgrade Buildings screen. */
    public static String upgradeCurrentTitle() {
        String t = textAt(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_BAR_TITLE_INDEX).trim();
        if (t.isEmpty()) t = textAt(PortsData.UPGRADE_INTERFACE, PortsData.UPGRADE_TITLE_INDEX).trim();
        return t;
    }

    /** Select-option index of the first ship slot whose status is "Returned". */
    public static int returnedSlotSelectIndex() {
        return slotSelectIndexByStatus(PortsData.STATUS_RETURNED);
    }

    /** Select-option index of the first idle ("Ready") ship slot. */
    public static int readySlotSelectIndex() {
        return slotSelectIndexByStatus(PortsData.STATUS_READY);
    }

    /** First "Returned" ship slot NOT in the exclude set. */
    public static int returnedSlotSelectIndexExcluding(java.util.Set<Integer> exclude) {
        if (!isPortOpen()) return -1;
        for (int selectIdx : PortsData.SLOT_SELECT_INDICES) {
            if (exclude.contains(selectIdx)) continue;
            String status = textAt(PortsData.VOYAGE_INTERFACE, selectIdx + PortsData.SLOT_STATUS_OFFSET);
            if (PortsData.STATUS_RETURNED.equalsIgnoreCase(status.trim())) return selectIdx;
        }
        return -1;
    }

    /** First Ready ship slot NOT in the exclude set (slots we couldn't dispatch this sweep). */
    public static int readySlotSelectIndexExcluding(java.util.Set<Integer> exclude) {
        if (!isPortOpen()) return -1;
        for (int selectIdx : PortsData.SLOT_SELECT_INDICES) {
            if (exclude.contains(selectIdx)) continue;
            String status = textAt(PortsData.VOYAGE_INTERFACE, selectIdx + PortsData.SLOT_STATUS_OFFSET);
            if (PortsData.STATUS_READY.equalsIgnoreCase(status.trim())) return selectIdx;
        }
        return -1;
    }

    /** Select-option index of the first selectable voyage in the list. */
    public static int firstAvailableVoyageSelectIndex() {
        if (!isPortOpen()) return -1;
        for (int idx : PortsData.VOYAGE_SELECT_INDICES) {
            Component c = comp(PortsData.VOYAGE_INTERFACE, idx);
            if (c != null && c.getOptions() != null && !c.getOptions().isEmpty()) {
                return idx;
            }
        }
        return -1;
    }

    // ── Single-click primitives (non-blocking) ──────────────────────────────────

    /** Click a component via MiniMenu (op 14, option 1) — the method proven to work
     *  (Component.interact(String) was a silent no-op). hash = (iface<<16)|component. */
    public static boolean clickComponent(int interfaceId, int index, String option) {
        int hash = (interfaceId << 16) | index;
        return MiniMenu.interact(PortsData.CLICK_OP, PortsData.CLICK_P1, PortsData.CLICK_P2, hash);
    }

    /** MiniMenu component click by hash (interface<<16 | component). */
    public static boolean clickHash(int interfaceId, int index) {
        return MiniMenu.interact(PortsData.CLICK_OP, PortsData.CLICK_P1, PortsData.CLICK_P2,
                (interfaceId << 16) | index);
    }

    /** Click a SUB-component via MiniMenu. Verified format:
     *  interact(14, 1, subIndex, (iface<<16)|comp). Used for crew grids in 916. */
    public static boolean clickSub(int interfaceId, int comp, int sub) {
        return MiniMenu.interact(PortsData.SUBCLICK_OP, PortsData.SUBCLICK_P1, sub,
                (interfaceId << 16) | comp);
    }

    // ── Crew optimization reads (ship/crew editor 916 + adversity on 950) ────────

    /** Immutable crew snapshot. loc = SHIP_ASSIGNED_CREW_COMP (on the ship) or
     *  SHIP_ROSTER_CREW_COMP (available to assign); sub = its sub-component index. */
    public record Crew(int comp, int sub, String name, int morale, int combat,
                       int seafaring, int speed) {
        /** stat by adversity index: 0=Morale, 1=Combat, 2=Seafaring. */
        public int stat(int idx) {
            switch (idx) {
                case 0:  return morale;
                case 1:  return combat;
                case 2:  return seafaring;
                default: return 0;
            }
        }
    }

    /** True only when the crew GRID is open. The assignable roster (comp 199) is
     *  populated only in grid mode — in the ship "overview" it's empty. Roster crew
     *  expose NO menu option (only a name), so we detect by name via rosterCrew(),
     *  NOT by an option (the assigned comp 182 has subs in both modes and would
     *  false-positive; keying off an "Assign" option is always empty here). */
    public static boolean isCrewGridVisible() {
        return !rosterCrew().isEmpty();
    }

    /** Sub-indices of the given component that expose the given option. */
    public static java.util.List<Integer> subsWithOption(int comp, String option) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Component c : ComponentQuery.newQuery(PortsData.SHIP_EDITOR_INTERFACE).results()) {
            if (c == null || c.getComponentIndex() != comp) continue;
            if (c.getSubComponentIndex() < 0) continue;
            if (hasOption(c, option)) {
                String t = c.getText();
                if (t != null && t.equalsIgnoreCase("None")) continue; // empty placeholder
                out.add(c.getSubComponentIndex());
            }
        }
        return out;
    }

    /** ALL ship crew-slot sub-indices (comp 182) that expose the Select option,
     *  INCLUDING empty 'None' slots (which subsWithOption deliberately skips). An
     *  empty slot can be filled by assigning a roster crew — no crew removed. */
    public static java.util.List<Integer> shipCrewSlotSubs() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Component c : ComponentQuery.newQuery(PortsData.SHIP_EDITOR_INTERFACE).results()) {
            if (c == null || c.getComponentIndex() != PortsData.SHIP_ASSIGNED_CREW_COMP) continue;
            if (c.getSubComponentIndex() < 0) continue;
            if (hasOption(c, PortsData.SELECT_OPTION)) out.add(c.getSubComponentIndex());
        }
        return out;
    }

    /** Roster (assignable) crew as (sub, name) — comp 199 sub-components that carry a
     *  crew name. Roster crew expose NO menu option (you swap by arming an on-ship
     *  crew then clicking one), so we enumerate by name, skipping the 'None' slot. */
    public static java.util.List<Crew> rosterCrew() {
        java.util.List<Crew> out = new java.util.ArrayList<>();
        for (Component c : ComponentQuery.newQuery(PortsData.SHIP_EDITOR_INTERFACE).results()) {
            if (c == null || c.getComponentIndex() != PortsData.SHIP_ROSTER_CREW_COMP) continue;
            if (c.getSubComponentIndex() < 0) continue;
            String t = c.getText();
            if (t == null || t.isBlank() || t.equalsIgnoreCase("None")) continue;
            out.add(new Crew(PortsData.SHIP_ROSTER_CREW_COMP, c.getSubComponentIndex(), t.trim(), 0, 0, 0, 0));
        }
        return out;
    }

    /** Diagnostic: full info (text/itemId/params) for one ship-editor sub-component —
     *  used to locate where captain names/stats actually live (the sub's text is a
     *  generic 'Captain' label). */
    public static String subComponentInfo(int comp, int sub) {
        for (Component c : ComponentQuery.newQuery(PortsData.SHIP_EDITOR_INTERFACE).results()) {
            if (c == null || c.getComponentIndex() != comp || c.getSubComponentIndex() != sub) continue;
            StringBuilder sb = new StringBuilder("text='" + c.getText() + "' itemId=" + c.getItemId()
                    + " itemAmt=" + c.getItemAmount() + " sprite=" + c.getSpriteId());
            try {
                java.util.List<net.botwithus.rs3.game.hud.interfaces.ComponentParam> ps = c.getParams();
                if (ps != null) for (var p : ps) {
                    if (p == null) continue;
                    String pt = p.getText();
                    sb.append(" p").append(p.getId()).append('=')
                      .append(pt != null && !pt.isBlank() ? "'" + pt + "'" : Long.toString(p.getValue()));
                }
            } catch (Throwable ignored) {}
            return sb.toString();
        }
        return "(comp " + comp + " sub " + sub + " not found)";
    }

    /** First sub-index of a ship-editor component exposing the given option (or -1). */
    public static int firstSubWithOption(int comp, String option) {
        java.util.List<Integer> subs = subsWithOption(comp, option);
        return subs.isEmpty() ? -1 : subs.get(0);
    }

    /** Text (crew name) of a specific sub-component in the ship editor. */
    public static String crewNameAt(int comp, int sub) {
        for (Component c : ComponentQuery.newQuery(PortsData.SHIP_EDITOR_INTERFACE).results()) {
            if (c != null && c.getComponentIndex() == comp && c.getSubComponentIndex() == sub) {
                String t = c.getText();
                return t == null ? "" : t.trim();
            }
        }
        return "";
    }

    /** Read the currently-Selected crew's stat block (916,150/157-160). */
    public static Crew readSelectedCrew(int comp, int sub) {
        String name = textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SEL_CREW_NAME_INDEX);
        int mor = extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SEL_CREW_MORALE_INDEX));
        int com = extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SEL_CREW_COMBAT_INDEX));
        int sea = extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SEL_CREW_SEAFARING_INDEX));
        int spd = extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SEL_CREW_SPEED_INDEX));
        return new Crew(comp, sub, name == null ? "" : name.trim(), mor, com, sea, spd);
    }

    /** Adversity current-ship totals on the voyage screen [Morale, Combat, Seafaring]. */
    public static int[] adversityCurrent() {
        return new int[]{
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_MORALE_CURRENT_INDEX)),
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_COMBAT_CURRENT_INDEX)),
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_SEAFARING_CURRENT_INDEX)),
        };
    }

    /** Live ship stat TOTALS from the crew grid [Morale, Combat, Seafaring] — the
     *  oracle used to keep/revert crew swaps. Only valid with the crew grid open. */
    public static int[] shipTotals() {
        return new int[]{
                extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_TOTAL_MORALE_INDEX)),
                extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_TOTAL_COMBAT_INDEX)),
                extractInt(textAt(PortsData.SHIP_EDITOR_INTERFACE, PortsData.SHIP_TOTAL_SEAFARING_INDEX)),
        };
    }

    /** Adversity required values on the voyage screen [Morale, Combat, Seafaring]. */
    public static int[] adversityRequired() {
        return new int[]{
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_MORALE_REQUIRED_INDEX)),
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_COMBAT_REQUIRED_INDEX)),
                extractInt(textAt(PortsData.VOYAGE_INTERFACE, PortsData.ADV_SEAFARING_REQUIRED_INDEX)),
        };
    }

    private static boolean hasOption(Component c, String option) {
        java.util.List<String> opts = c.getOptions();
        if (opts == null) return false;
        for (String o : opts) {
            if (o != null && o.equalsIgnoreCase(option)) return true;
        }
        return false;
    }

    /** Advance/clear an open dialog (event / adventurer / voyage report). */
    public static boolean advanceDialog() {
        if (!Dialog.isOpen()) return false;
        return Dialog.select();
    }

    /** Click a component's default action via MiniMenu, only if it exists on screen. */
    public static boolean clickComponentDefault(int interfaceId, int index, String option) {
        Component c = ComponentQuery.newQuery(interfaceId).componentIndex(index).results().first();
        if (c == null) return false; // not present (e.g. menu not on screen)
        return clickHash(interfaceId, index);
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private static int slotSelectIndexByStatus(String wantedStatus) {
        if (!isPortOpen()) return -1;
        for (int selectIdx : PortsData.SLOT_SELECT_INDICES) {
            String status = textAt(PortsData.VOYAGE_INTERFACE, selectIdx + PortsData.SLOT_STATUS_OFFSET);
            if (wantedStatus.equalsIgnoreCase(status.trim())) {
                return selectIdx;
            }
        }
        return -1;
    }

    private static Component comp(int interfaceId, int index) {
        return ComponentQuery.newQuery(interfaceId).componentIndex(index).results().first();
    }

    /** First component whose text contains needle (case-insensitive); returns its text. */
    private static String scanTextContains(int interfaceId, String needle) {
        String n = needle.toLowerCase();
        for (Component c : ComponentQuery.newQuery(interfaceId).results()) {
            if (c == null) continue;
            String t = c.getText();
            if (t != null && t.toLowerCase().contains(n)) return t;
        }
        return null;
    }

    /** First component whose trimmed text equals value (case-insensitive); returns its text. */
    private static String scanTextEquals(int interfaceId, String value) {
        for (Component c : ComponentQuery.newQuery(interfaceId).results()) {
            if (c == null) continue;
            String t = c.getText();
            if (t != null && value.equalsIgnoreCase(t.trim())) return t;
        }
        return null;
    }

    private static String textAt(int interfaceId, int index) {
        Component c = comp(interfaceId, index);
        String t = c == null ? null : c.getText();
        return t == null ? "" : t;
    }

    /** First integer in a string like "Voyages available: 18" / "x 236" / "100%". */
    private static int extractInt(String s) {
        if (s == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
    }
}
