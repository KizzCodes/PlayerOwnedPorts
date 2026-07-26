package net.kyle.pop.util;

import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.Coordinate;
import net.botwithus.rs3.game.hud.interfaces.Component;
import net.botwithus.rs3.game.hud.interfaces.Interfaces;
import net.botwithus.rs3.game.queries.builders.components.ComponentQuery;
import net.botwithus.rs3.game.queries.builders.objects.SceneObjectQuery;
import net.botwithus.rs3.game.queries.results.ResultSet;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;
import net.botwithus.rs3.game.scene.entities.object.SceneObject;
import net.kyle.pop.PortsScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot discovery aid. With the port screen open, trigger this from the config
 * tab ("Dump open interfaces") and it prints every currently-open interface plus
 * each meaningful component's index, text and menu options to the script console.
 *
 * Cross-check it with the client's built-in widget/varbit debug tools to fill in
 * {@link net.kyle.pop.data.PortsData}.
 */
public final class InterfaceDebug {

    private InterfaceDebug() {}

    /** Highest interface id to probe. 2000+ is plenty for RS3 interfaces. */
    private static final int MAX_INTERFACE_ID = 2200;
    /** Cap components logged per interface so the console stays readable. */
    private static final int MAX_COMPONENTS = 120;

    /** File the dump is written to (in addition to the on-screen console). */
    public static final Path DUMP_FILE =
            Paths.get(System.getProperty("user.home"), "BotWithUs", "scripts", "pop-dump.txt");

    public static void dumpOpenInterfaces(PortsScript script) {
        List<String> lines = new ArrayList<>();
        lines.add("=== BEGIN open-interface dump ===");
        int openCount = 0;
        for (int id = 0; id <= MAX_INTERFACE_ID; id++) {
            if (!Interfaces.isOpen(id)) continue;
            openCount++;

            ResultSet<Component> comps = ComponentQuery.newQuery(id).results();
            int size = comps == null ? 0 : comps.size();
            lines.add("Interface " + id + " — " + size + " components");
            if (comps == null) continue;

            int shown = 0;
            for (Component c : comps) {
                if (c == null || shown >= MAX_COMPONENTS) continue;

                // Report the REAL componentIndex/subComponentIndex (what
                // ComponentQuery.componentIndex()/subComponentIndex() match on),
                // not iteration position.
                int ci = c.getComponentIndex();
                int sub = c.getSubComponentIndex();

                String text = safe(c.getText());
                List<String> options = c.getOptions();
                boolean hasOptions = options != null && options.stream().anyMatch(o -> o != null && !o.isBlank());
                int itemId = c.getItemId();

                if (text.isBlank() && !hasOptions && itemId <= 0) continue;

                StringBuilder sb = new StringBuilder();
                sb.append("   [").append(id).append(",").append(ci);
                if (sub >= 0) sb.append(",sub=").append(sub);
                sb.append("]");
                if (!text.isBlank()) sb.append(" text='").append(text).append('\'');
                if (itemId > 0)      sb.append(" item=").append(itemId).append(" x").append(c.getItemAmount());
                if (hasOptions)      sb.append(" options=").append(cleanOptions(options));
                lines.add(sb.toString());
                shown++;
            }
        }
        lines.add("=== END dump — " + openCount + " open interface(s) ===");

        // Full dump to file only; summary to console (avoid flooding the ImGui console).
        writeToFile(script, lines);
        script.println("Dumped " + openCount + " open interface(s) to pop-dump.txt");
    }

    /** Dump ALL components of one interface by id (even if it isn't "open"). */
    public static void dumpInterface(PortsScript script, int interfaceId) {
        List<String> lines = new ArrayList<>();
        lines.add("=== BEGIN interface dump " + interfaceId + " (isOpen=" + Interfaces.isOpen(interfaceId) + ") ===");
        int shown = 0;
        try {
            for (Component c : ComponentQuery.newQuery(interfaceId).results()) {
                if (c == null || shown >= 600) continue;
                String text = safe(c.getText());
                List<String> options = c.getOptions();
                boolean hasOptions = options != null && options.stream().anyMatch(o -> o != null && !o.isBlank());
                int itemId = c.getItemId();
                int sprite = safeSprite(c);
                String params = safeParams(c);
                // Include components that carry a sprite or params too (Black Market
                // items / crew / captains have no text but DO have sprite/params).
                if (text.isBlank() && !hasOptions && itemId <= 0 && sprite <= 0 && params.isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                sb.append("   [").append(interfaceId).append(",").append(c.getComponentIndex());
                if (c.getSubComponentIndex() >= 0) sb.append(",sub=").append(c.getSubComponentIndex());
                sb.append("]");
                if (!text.isBlank()) sb.append(" text='").append(text).append('\'');
                if (itemId > 0)      sb.append(" item=").append(itemId).append(" x").append(c.getItemAmount());
                if (sprite > 0)      sb.append(" sprite=").append(sprite);
                if (hasOptions)      sb.append(" options=").append(cleanOptions(options));
                if (!params.isEmpty()) sb.append(" params=").append(params);
                lines.add(sb.toString());
                shown++;
            }
        } catch (Throwable t) {
            lines.add("!! error: " + t);
        }
        lines.add("=== END interface dump " + interfaceId + " — " + shown + " labelled components ===");
        // Write the full dump to the FILE, but only a summary to the in-client console
        // — printing 1000+ lines to the ImGui console freezes the client.
        writeToFile(script, lines);
        script.println("Dumped interface " + interfaceId + " (" + shown + " components) to pop-dump.txt");
    }

    /**
     * Interactive probe: click [interface, component] (optionally by a specific
     * option label), then dump the resulting open interfaces so we can see what it
     * did. This is the "click things and report back as we go" tool.
     */
    public static void testInteract(PortsScript script, int interfaceId, int component, String option) {
        List<String> lines = new ArrayList<>();
        boolean useOption = option != null && !option.isBlank();
        lines.add("=== TEST INTERACT [" + interfaceId + "," + component + "] option="
                + (useOption ? "'" + option + "'" : "(default)") + " ===");

        Component c = ComponentQuery.newQuery(interfaceId).componentIndex(component).results().first();
        if (c == null) {
            lines.add("  component not found (is that interface open?)");
            for (String l : lines) script.println(l);
            writeToFile(script, lines);
            return;
        }
        lines.add("  before: text='" + safe(c.getText()) + "' options=" + cleanOptions(c.getOptions()));
        boolean result = useOption ? c.interact(option) : c.interact();
        lines.add("  interact() returned " + result);
        for (String l : lines) script.println(l);
        writeToFile(script, lines);

        // Dump the resulting state immediately (non-blocking). If you need to see
        // the UI a moment after the click, just press "Dump open interfaces" again.
        dumpOpenInterfaces(script);
    }

    /** Log the player's tile + nearby interactable objects, to find the port entry.
     *  Hardened: any failure is written into the dump file with a stack trace, and
     *  individual bad objects are skipped, so we always get diagnostics. */
    public static void dumpLocation(PortsScript script) {
        List<String> lines = new ArrayList<>();
        lines.add("=== BEGIN location dump ===");
        try {
            LocalPlayer me = Client.getLocalPlayer();
            Coordinate here = me == null ? null : me.getCoordinate();
            if (here == null) {
                lines.add("Player/coordinate unavailable (not logged in?)");
            } else {
                lines.add("Player tile: x=" + here.getX() + " y=" + here.getY() + " z=" + here.getZ()
                        + "  (region=" + here.getRegionId() + ")");
                lines.add("Nearby interactable objects (dist | name | options | tile):");

                List<String> entries = new ArrayList<>();
                int scanned = 0;
                for (SceneObject o : SceneObjectQuery.newQuery().results()) {
                    scanned++;
                    try {
                        if (o == null) continue;
                        List<String> opts = o.getOptions();
                        boolean hasOptions = opts != null && opts.stream().anyMatch(s -> s != null && !s.isBlank());
                        if (!hasOptions) continue;
                        String name = o.getName();
                        if (name == null || name.isBlank() || "null".equals(name)) continue;
                        Coordinate c = o.getCoordinate();
                        int d = (c == null) ? 99999
                                : Math.max(Math.abs(c.getX() - here.getX()), Math.abs(c.getY() - here.getY()));
                        String tile = (c == null) ? "?" : (c.getX() + "," + c.getY() + "," + c.getZ());
                        entries.add(String.format("%05d | %s | %s | %s", d, name, cleanOptions(opts), tile));
                    } catch (Throwable objErr) {
                        entries.add("99998 | <error reading object: " + objErr + ">");
                    }
                }
                entries.sort(String::compareTo);
                int shown = 0;
                for (String e : entries) {
                    if (shown++ >= 40) break;
                    lines.add("   " + e);
                }
                lines.add("scanned " + scanned + " scene objects, " + entries.size() + " interactable");
                lines.add("For the port: set PORT_ENTRY_OBJECT=name, PORT_ENTRY_OPTION=the open option, "
                        + "PORT_ENTRY_X/Y = that object's tile.");
            }
        } catch (Throwable t) {
            lines.add("!! location dump ERROR: " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) lines.add("    at " + st[i]);
        }
        lines.add("=== END location dump ===");

        for (String l : lines) script.println(l);
        writeToFile(script, lines);
    }

    private static void writeToFile(PortsScript script, List<String> lines) {
        try {
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line).append(System.lineSeparator());
            }
            out.append(System.lineSeparator());
            Files.write(DUMP_FILE, out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            script.println("Dump also written to: " + DUMP_FILE);
        } catch (IOException e) {
            script.println("Could not write dump file (" + DUMP_FILE + "): " + e.getMessage());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** getSpriteId() guarded — may identify Black Market items / crew portraits. */
    private static int safeSprite(Component c) {
        try { return c.getSpriteId(); } catch (Throwable t) { return -1; }
    }

    /** getParams() guarded — structured data (item ids, names) not in getText().
     *  Prints real id=value / id='text' pairs (ComponentParam.getId/getValue/getText),
     *  skipping the noisy zero-value entries. */
    private static String safeParams(Component c) {
        try {
            java.util.List<net.botwithus.rs3.game.hud.interfaces.ComponentParam> ps = c.getParams();
            if (ps == null || ps.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (net.botwithus.rs3.game.hud.interfaces.ComponentParam p : ps) {
                if (p == null) continue;
                String t = p.getText();
                long v = p.getValue();
                if ((t == null || t.isBlank()) && v == 0) continue; // skip empty params
                if (shown++ > 0) sb.append(",");
                if (shown > 8) { sb.append("…"); break; }
                sb.append(p.getId()).append('=');
                sb.append((t != null && !t.isBlank()) ? "'" + t + "'" : String.valueOf(v));
            }
            return sb.length() == 0 ? "" : "{" + sb + "}";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String cleanOptions(List<String> options) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String o : options) {
            if (o == null || o.isBlank()) continue;
            if (!first) sb.append(", ");
            sb.append(o.trim());
            first = false;
        }
        return sb.append(']').toString();
    }
}
