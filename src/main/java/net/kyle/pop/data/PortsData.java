package net.kyle.pop.data;

/**
 * Player-Owned Ports UI ids, discovered from the client via the debug dump
 * (see pop-dump.txt). Values are real where confirmed; a few are still TODO and
 * require one more dump (crew roster + the reward popup shown after collecting).
 *
 * Discovered layout of the voyages screen (interface 950):
 *   - "Voyages available: N"           -> component 22 (text)
 *   - Active ship slots (your voyages) -> each slot has a Select option; the
 *     slot's STATUS text sits 4 indices after its Select. A slot whose status is
 *     "Returned" is ready to COLLECT. Observed slot Select indices: 181,187,193,199.
 *   - Send Voyage button               -> component 305 (option "Select")
 *   - Confirm / Cancel                 -> components 383 / 389
 *   - Edit Crew / Edit Ship            -> components 218 / 230
 *   - Voyage list entries              -> name text, Select option at name+2
 *                                         (e.g. 85 "Howdy Pilgrims" -> 87 Select)
 * Port hub (interface 905): Chimes value text at component 79 ("x 236").
 */
public final class PortsData {

    private PortsData() {}

    // ── Interfaces ──────────────────────────────────────────────────────────────
    /** The voyages screen — where collecting and dispatching happen. */
    public static final int VOYAGE_INTERFACE = 950;
    /** The port hub screen (chimes / resources / trade goods). */
    public static final int HUB_INTERFACE = 905;
    /** Crew assignment panel on the voyage (Select/Unassign per crew). */
    public static final int CREW_INTERFACE = 916;
    /** Interface behind the top-menu crew-roster ICON (from hover). To OPEN the
     *  roster we capture the click (op/p1/p2/p3) like the Voyages open. */
    public static final int CREW_ROSTER_ICON_INTERFACE = 1486;
    /** Captured MiniMenu args to open the crew roster (from a real click). */
    public static final int CREW_OPEN_OP = -1;
    public static final int CREW_OPEN_P1 = 0;
    public static final int CREW_OPEN_P2 = 0;
    public static final int CREW_OPEN_P3 = 0;
    /** Crew Roster / recruitment screen (opened from hub 905 comp 50, or voyage
     *  Edit Crew). "Your Crew" list = comp 7 sub-components ([Info, Dismiss]);
     *  clicking one (Info) fills the detail panel below. */
    public static final int CREW_FOR_HIRE_INTERFACE = 1276;
    public static final int CREW_ROSTER_INTERFACE = 1276;
    public static final int HUB_CREW_ROSTER_OPEN_INDEX = 50;   // hub 905 "Open" for the roster
    public static final int ROSTER_CREW_LIST_COMP = 7;         // "Your Crew" list (sub = each crew)
    public static final int ROSTER_DETAIL_NAME_INDEX = 138;
    public static final int ROSTER_DETAIL_LEVEL_INDEX = 109;
    public static final int ROSTER_DETAIL_COMBAT_INDEX = 129;
    public static final int ROSTER_DETAIL_MORALE_INDEX = 130;
    public static final int ROSTER_DETAIL_SEAFARING_INDEX = 133;
    public static final int ROSTER_DETAIL_SPEED_INDEX = 134;
    public static final String ROSTER_INFO_OPTION = "Info";
    /** Ship selection screen. */
    public static final int SHIP_INTERFACE = 1275;
    /** Upgrade Buildings screen (resource spending). VERIFIED: hub 905,53 -> 1373.
     *  Title text [1373,150]='Upgrade Buildings'. Detection-only ("Just detect,
     *  don't spend") — we read what's buildable, we never click Build. */
    public static final int UPGRADE_INTERFACE = 1373;
    /** Upgrade Buildings components (real indices from the 1373 dump). */
    public static final int UPGRADE_TITLE_INDEX = 150;        // 'Upgrade Buildings'
    public static final int UPGRADE_CURRENT_LABEL_INDEX = 35; // 'Currently built:'
    public static final int UPGRADE_BAR_TITLE_INDEX = 147;    // e.g. 'Upgrade Bar'
    public static final int UPGRADE_BUILD_INDEX = 143;        // 'Build' button (clicked only when auto-upgrade is on AND dry-run off)
    public static final int UPGRADE_PREV_INDEX = 144;         // Previous-building
    public static final int UPGRADE_NEXT_INDEX = 145;         // Next-building
    public static final int UPGRADE_CLOSE_INDEX = 151;        // Close
    /** Hub button that opens the Upgrade Buildings screen (VERIFIED: 905,53 -> 1373). */
    public static final int UPGRADE_OPEN_INDEX = 53;

    // ── Voyages screen (950) components — REAL componentIndex values ─────────────
    /** "Voyages available: N" text component (also read by text scan). */
    public static final int AVAILABLE_COUNT_INDEX = 11;
    /** Almost every clickable on this screen uses the single option "Select". */
    public static final String SELECT_OPTION = "Select";

    /** Select-option indices of your active ship slots (top row of the screen). */
    public static final int[] SLOT_SELECT_INDICES = { 83, 89, 95, 101 };
    /** A slot's status text sits this many indices after its Select component. */
    public static final int SLOT_STATUS_OFFSET = 4;
    /** To COLLECT a returned voyage, click the slot's ship component (Select+2),
     *  NOT the Select — verified from the user's real collect click (comp 91 = 89+2). */
    public static final int SLOT_SHIP_OFFSET = 2;
    /** Status text meaning "voyage finished, ready to collect". */
    public static final String STATUS_RETURNED = "Returned";
    /** Status text meaning "idle ship, ready to be sent on a new voyage". */
    public static final String STATUS_READY = "Ready";
    /** Status text meaning "no ship assigned to this slot". */
    public static final String STATUS_NO_SHIP = "No Ship";

    /** "Send Voyage" button. */
    public static final int SEND_VOYAGE_INDEX = 163;
    /** Confirm / Cancel buttons on the send/confirm dialog. */
    public static final int CONFIRM_INDEX = 193;
    public static final int CANCEL_INDEX = 194;
    /** Opens the crew roster / ship editors. */
    public static final int EDIT_CREW_INDEX = 108;
    public static final int EDIT_SHIP_INDEX = 109;
    /** "Overall success chance:" value text, e.g. "0%" / "100%". */
    public static final int SUCCESS_CHANCE_INDEX = 160;
    /** Close [X] buttons — used to exit POP screens after a sweep so the bot doesn't
     *  idle with panels open. Voyage screen 950, ship editor 916, crew roster 1276. */
    public static final int VOYAGE_CLOSE_INDEX = 218;
    public static final int SHIP_EDITOR_CLOSE_INDEX = 381;
    public static final int ROSTER_CLOSE_INDEX = 167;
    /** "A ship needs a captain in order to sail." prompt — visible (not hidden) only
     *  when the selected ship has NO captain. Detected via Component.isHidden(). */
    public static final int VOYAGE_NEEDS_CAPTAIN_INDEX = 206;

    // ── Adversity panel on the voyage screen (950) — for crew optimization ───────
    // Three stats: Morale / Combat / Seafaring. Each row shows the Current-Ship
    // value, a % bar, and the Selected-Voyage required value. Overall success is
    // bottlenecked by the worst stat. (Speed is not part of adversity.)
    public static final int ADV_MORALE_CURRENT_INDEX = 118;
    public static final int ADV_COMBAT_CURRENT_INDEX = 132;
    public static final int ADV_SEAFARING_CURRENT_INDEX = 146;
    public static final int ADV_MORALE_REQUIRED_INDEX = 130;
    public static final int ADV_COMBAT_REQUIRED_INDEX = 144;
    public static final int ADV_SEAFARING_REQUIRED_INDEX = 158;

    // ── Ship / crew editor (interface 916, PERSISTENT — never Interfaces.isOpen) ──
    /** Reached from the voyage screen via Edit Ship (950,109) → 916, then the
     *  in-ship "Edit Crew" button shows the crew grid; "Close Crew" hides it. */
    public static final int SHIP_EDITOR_INTERFACE = 916;
    public static final int SHIP_EDIT_CREW_BUTTON_INDEX = 322;   // "Edit Crew"
    public static final int SHIP_CLOSE_CREW_BUTTON_INDEX = 321;  // "Close Crew"
    public static final int SHIP_PREV_INDEX = 317;
    public static final int SHIP_NEXT_INDEX = 319;
    /** Assigned crew live on comp 182 as sub-components (occupant of each fixed
     *  slot; options [Select, Unassign]). Roster (assignable) crew = comp 199 subs
     *  (options [Assign]; a 'None' sub is an empty placeholder). */
    public static final int SHIP_ASSIGNED_CREW_COMP = 182;
    public static final int SHIP_ROSTER_CREW_COMP = 199;
    /** Captains live in the left column: active captain slot = comp 186 ([Select]),
     *  candidate captains = comp 191 ([Assign]). Swap = select the slot, then click a
     *  candidate. Both are readable by selecting (name=150, stats=157-160). */
    public static final int SHIP_CAPTAIN_SLOT_COMP = 186;
    public static final int SHIP_CAPTAIN_CAND_COMP = 191;
    public static final String CREW_ASSIGN_OPTION = "Assign";
    public static final String CREW_UNASSIGN_OPTION = "Unassign";
    /** Selected-crew stat block (updates whenever any crew is Selected). */
    public static final int SEL_CREW_NAME_INDEX = 150;
    public static final int SEL_CREW_LEVEL_INDEX = 152;
    public static final int SEL_CREW_MORALE_INDEX = 157;
    public static final int SEL_CREW_COMBAT_INDEX = 158;
    public static final int SEL_CREW_SEAFARING_INDEX = 159;
    public static final int SEL_CREW_SPEED_INDEX = 160;
    /** Sub-component click: MiniMenu.interact(14, 1, subIndex, (iface<<16)|comp).
     *  (Verified live — p2 carries the sub index; top-level clicks use p2=-1.) */
    public static final int SUBCLICK_OP = 14;
    public static final int SUBCLICK_P1 = 1;
    /** Live ship stat TOTALS shown in the crew grid (the swap oracle). Labels are
     *  Speed/Morale/Combat/Seafaring at 326-329; values at 330-333. */
    public static final int SHIP_TOTAL_SPEED_INDEX = 330;
    public static final int SHIP_TOTAL_MORALE_INDEX = 331;
    public static final int SHIP_TOTAL_COMBAT_INDEX = 332;
    public static final int SHIP_TOTAL_SEAFARING_INDEX = 333;

    /** Voyage RESULTS report opens when you click a returned ship (interface 916).
     *  Multi-step claim: click "Get results" (244) THEN "Close" (277) — both verified
     *  from the user's real clicks (p3 60031220=916:244, 60031253=916:277). */
    public static final int RESULTS_INTERFACE = 916;
    public static final int GET_RESULTS_INDEX = 244;
    public static final int CLOSE_RESULTS_INDEX = 277;

    /** Select-option indices for entries in the voyage list (name + 2). Same in
     *  both the Special and Standard tabs — switching tabs repopulates these. */
    public static final int[] VOYAGE_SELECT_INDICES = { 36, 56, 76 };
    /** Tab buttons for the voyage list. */
    public static final int SPECIAL_VOYAGES_TAB_INDEX = 220;
    public static final int STANDARD_VOYAGES_TAB_INDEX = 222;

    // ── Port hub (905) components — VERIFIED from the 905 dump ───────────────────
    /** Chimes amount text on the hub (comp 37) — UNRELIABLE (read 132 while the real
     *  chimes were 2898); prefer the currency-pouch item below. */
    public static final int HUB_CHIMES_TEXT_INDEX = 37;
    /** Reliable chimes source: the Chimes currency ITEM (37753) in the currency
     *  pouch (interface 1473). Read via getItemAmount(). */
    public static final int CURRENCY_POUCH_INTERFACE = 1473;
    public static final int CHIMES_ITEM_ID = 37753;
    /** Captain-for-hire label (39) / name (40) / status (43, e.g. "In Port"). */
    public static final int HUB_CAPTAIN_LABEL_INDEX = 39;
    public static final int HUB_CAPTAIN_NAME_INDEX = 40;
    public static final int HUB_CAPTAIN_STATUS_INDEX = 43;
    /** Chef ("cook" building resident) label/status. */
    public static final int HUB_CHEF_LABEL_INDEX = 42;
    public static final int HUB_CHEF_STATUS_INDEX = 43;
    /** Architect label/status + its "Open" (upgrade buildings) button. */
    public static final int HUB_ARCHITECT_LABEL_INDEX = 69;
    public static final int HUB_ARCHITECT_STATUS_INDEX = 70;
    public static final int HUB_ARCHITECT_OPEN_INDEX = 71;
    /** Port Resources: 'Resources'(59) 'Port Resources'(60); the two stockpile
     *  totals sit at 62 and 64 (e.g. woodplanks / bamboo). Detect-only. */
    public static final int HUB_RESOURCES_LABEL_INDEX = 59;
    public static final int HUB_RESOURCE1_INDEX = 62;
    public static final int HUB_RESOURCE2_INDEX = 64;
    /** Trade Goods label (86); the quantities follow in the 88..103 block. */
    public static final int HUB_TRADE_GOODS_LABEL_INDEX = 86;
    /** Trader panel: 'Trader'(105) 'Has:'(106) 'Wants:'(108). Detect-only. */
    public static final int HUB_TRADER_LABEL_INDEX = 105;
    public static final int HUB_TRADER_HAS_INDEX = 106;
    public static final int HUB_TRADER_WANTS_INDEX = 108;

    // ── Black Market (lives ON the hub 905, not a separate interface) ────────────
    /** 'Black Market' panel label. VERIFIED: the market is rendered on 905 itself. */
    public static final int HUB_BLACK_MARKET_LABEL_INDEX = 34;
    /** The two purchasable item slots — option "Select" buys the item. */
    public static final int[] HUB_BLACK_MARKET_ITEM_INDICES = { 9, 12 };
    /** Reroll buttons for the market offer (Reroll x1 / Reroll x0 uses). */
    public static final int HUB_BLACK_MARKET_REROLL1_INDEX = 0;
    public static final int HUB_BLACK_MARKET_REROLL2_INDEX = 3;
    public static final String BLACK_MARKET_BUY_OPTION = "Select";
    public static final String BLACK_MARKET_REROLL_OPTION = "Reroll";

    // ── Detection-only (report, don't act) ──────────────────────────────────────
    /** "Special Voyages (N)" label on the voyages screen — N = adventurer voyages. */
    public static final int SPECIAL_VOYAGES_TEXT_INDEX = 219;

    // ── Opening the port screen / navigation ────────────────────────────────────
    /** The portal object used to enter the port (object id 3219, from the dump). */
    public static final int PORT_ENTRY_OBJECT_ID = 3219;
    /** Optional name-based fallback if the id isn't found. */
    public static final String PORT_ENTRY_OBJECT = "";
    /** Menu option on the portal — "Enter" takes you inside the port area. */
    public static final String PORT_ENTRY_OPTION = "Enter";
    /** Step 2 (inside the port): the top-menu Voyages button that opens screen 950.
     *  DISABLED (-1): [1486,1] was verified NOT to open 950. Will be replaced by a
     *  captured MiniMenu interaction from the user's real click (see MENU_OPEN_* below). */
    public static final int OPEN_VOYAGES_MENU_INTERFACE = -1;
    public static final int OPEN_VOYAGES_MENU_INDEX = 1;
    public static final String OPEN_VOYAGES_MENU_OPTION = "";

    /** VERIFIED working: MiniMenu.interact(14,1,-1,59310129) opens the voyages screen
     *  (950). p3=59310129 = (905<<16)|49 — the hub's Voyages "Open" button.
     *  (The earlier 1486 opens were the separate, now-disabled [1486,1] approach.) */
    public static final int MENU_OPEN_OP = 14;
    public static final int MENU_OPEN_P1 = 1;
    public static final int MENU_OPEN_P2 = -1;
    public static final int MENU_OPEN_P3 = 59310129;

    /** MiniMenu action args for a normal component left-click (op 14, option 1).
     *  Component hash = (interfaceId<<16)|componentIndex. Verified via the open. */
    public static final int CLICK_OP = 14;
    public static final int CLICK_P1 = 1;
    public static final int CLICK_P2 = -1;
    /** Tile of / next to the entry object (from a dumploc while standing by the
     *  portal): Port Sarim, region 12082. The bot walks here after the lodestone. */
    public static final int PORT_ENTRY_X = 3034;
    public static final int PORT_ENTRY_Y = 3246;
    public static final int PORT_ENTRY_Z = 0;
    /** Region of the port entry tile (Port Sarim). Used to VERIFY travel actually
     *  arrived — Traverse.to picks a teleport by coordinate and can mis-teleport. */
    public static final int PORT_ENTRY_REGION = 12082;

    // ── Reward popup ────────────────────────────────────────────────────────────
    // Collecting a returned voyage credits rewards directly (chat: "Congratulations!
    // You have completed:") with no dedicated confirm popup observed. If your client
    // shows a report screen, dump it and set these to auto-confirm it.
    public static final int REWARD_INTERFACE = -1;       // optional
    public static final int REWARD_CONFIRM_INDEX = -1;   // optional

    // ── Black Market BUY DIALOG = interface 941 (opens at the port when the market
    //    is viewed; the backing hub 905 slots are opaque, but 941 is fully readable) ─
    public static final int BLACK_MARKET_BUY_INTERFACE = 941;
    /** Offered item + its stack, e.g. "132 Black slate" (the trade good on sale). */
    public static final int BM_ITEM_NAME_INDEX = 1;
    /** "Available at N coins each" — per-unit cost. */
    public static final int BM_COST_EACH_INDEX = 2;
    /** "(You currently own N)". */
    public static final int BM_OWNED_INDEX = 5;
    /** "New stock available in ..." restock timer. */
    public static final int BM_RESTOCK_INDEX = 16;
    public static final int BM_CLOSE_INDEX = 27;
    /** The 5 quantity buy buttons (Select) and their labels ("Buy N for COST").
     *  buy button [6+i] ↔ label [11+i]: 1, 5, 10, 50, max. */
    public static final int[] BM_BUY_BUTTON_INDICES = { 6, 7, 8, 9, 10 };
    public static final int[] BM_BUY_LABEL_INDICES  = { 11, 12, 13, 14, 15 };
    /** Black Marketeer NPC (type id) — stand next to them; "View goods" opens 941. */
    public static final int BLACK_MARKETEER_NPC_ID = 16552;
    public static final String BM_VIEW_OPTION = "View goods";

    // ── Events / resources (optional, not yet discovered) ───────────────────────
    public static final int EVENT_INTERFACE = -1;        // TODO
    public static final int BAZAAR_INTERFACE = -1;       // TODO

    public static boolean set(int id) {
        return id > -1;
    }

    public static boolean set(String s) {
        return s != null && !s.isBlank();
    }
}
