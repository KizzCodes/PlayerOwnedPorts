package net.kyle.pop.game;

import net.kyle.pop.PortsScript;
import net.kyle.pop.data.PortsData;

/**
 * Non-blocking resource → building upgrade automation for Player-Owned Ports.
 *
 * Opens the Upgrade Buildings screen (1373), cycles through the buildings looking
 * for one whose upgrade is currently affordable (its "Build" button is enabled),
 * and — when a buildable one is found — either logs it ({@code dryRun}) or clicks
 * Build to spend the port's resources on it. One build per run, then it closes the
 * screen. This is the port's main resource sink, so it keeps the port progressing
 * unattended instead of letting resources cap and go to waste.
 *
 * SAFETY: spending is irreversible, so the whole feature is opt-in and defaults to
 * dry-run — it reports what it WOULD build until the user confirms the detection is
 * right and turns dry-run off. It never touches ship/crew resources or chimes.
 */
public final class UpgradeManager {

    /** Max buildings to cycle before giving up (carousel guard). */
    private static final int MAX_CYCLE = 16;

    private enum Phase { OPEN, SCAN, ADVANCE, BUILD, CLOSE, DONE, FAILED }

    private final PortsScript script;
    private final boolean dryRun;

    private Phase phase = Phase.OPEN;
    private int openTries = 0;
    private int cycled = 0;

    public UpgradeManager(PortsScript script, boolean dryRun) {
        this.script = script;
        this.dryRun = dryRun;
    }

    public boolean isDone() { return phase == Phase.DONE || phase == Phase.FAILED; }

    public boolean step() {
        switch (phase) {
            case OPEN:    return open();
            case SCAN:    return scan();
            case ADVANCE: return advance();
            case BUILD:   return build();
            case CLOSE:   return close();
            default:      return false;
        }
    }

    private boolean open() {
        if (Ports.isUpgradesOpen()) { phase = Phase.SCAN; cycled = 0; return false; }
        if (openTries++ > 8) { fail("Upgrade Buildings (1373) never opened"); return false; }
        boolean clicked = Ports.openUpgrades();
        script.debug("[UPGRADE] open try " + openTries + " -> " + clicked);
        return clicked;
    }

    /** Look at the building currently shown; build it if affordable, else advance. */
    private boolean scan() {
        if (!Ports.isUpgradesOpen()) { phase = Phase.DONE; return false; }
        if (cycled >= MAX_CYCLE) {
            script.log("[UPGRADE] no affordable upgrade found after " + cycled + " buildings — closing.");
            phase = Phase.CLOSE;
            return false;
        }
        if (Ports.upgradeBuildable()) {
            phase = Phase.BUILD;
            return false;
        }
        phase = Phase.ADVANCE;
        return false;
    }

    private boolean advance() {
        cycled++;
        boolean clicked = Ports.upgradeNext();
        phase = Phase.SCAN;
        return clicked;
    }

    private boolean build() {
        String what = Ports.upgradeCurrentTitle();
        if (dryRun) {
            script.log("[UPGRADE] would build: " + (what.isEmpty() ? "(building " + cycled + ")" : what)
                    + " — dry-run on, not spending. Turn off 'Dry-run' to build.");
            phase = Phase.CLOSE;
            return false;
        }
        boolean clicked = Ports.upgradeBuild();
        script.log("[UPGRADE] building: " + (what.isEmpty() ? "(building " + cycled + ")" : what) + " -> " + clicked);
        phase = Phase.CLOSE;
        return clicked;
    }

    private boolean close() {
        boolean clicked = Ports.closeUpgrades();
        script.debug("[UPGRADE] close -> " + clicked);
        phase = Phase.DONE;
        return clicked;
    }

    private void fail(String why) { script.log("[UPGRADE] aborted: " + why); phase = Phase.FAILED; }
}
