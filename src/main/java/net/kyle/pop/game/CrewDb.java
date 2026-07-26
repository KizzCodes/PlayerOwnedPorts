package net.kyle.pop.game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent per-crew-member stat database for predictive crew solving.
 *
 * The client won't let the ship-editor roster be read (roster crew expose only a
 * name), so we record every owned crew's stats once from the Crew Roster screen
 * (1276) into a pipe-delimited file — one line per crew: {@code name|Morale|Combat|Seafaring|Speed}.
 * The optimizer loads this to look up a crew's stats BY NAME and plan the optimal
 * loadout up front, instead of trial-and-error swapping.
 *
 * Same-name crew can differ slightly by level, and execution can only target crew
 * by name, so we key on name and use the AVERAGE stats of that name — the expected
 * value when an arbitrary crew of that name is assigned. The live ship-stat totals
 * still verify each swap, so small per-level variance can't cause a bad result.
 */
public final class CrewDb {

    public static final Path FILE =
            Paths.get(System.getProperty("user.home"), "BotWithUs", "scripts", "crew-db.txt");

    private final Map<String, int[]> avgByName = new HashMap<>(); // name -> [M,C,S,Speed]
    private final Map<String, Integer> countByName = new HashMap<>();

    /** One owned unit as recorded (for the GUI table). */
    public record Entry(String name, int morale, int combat, int seafaring, int speed) {}
    private final List<Entry> entries = new ArrayList<>();

    /** Per-unit rows in file order (for display). */
    public List<Entry> entries() { return entries; }

    private CrewDb() {}

    public boolean isEmpty() { return avgByName.isEmpty(); }
    public int ownedCount(String name) { return countByName.getOrDefault(key(name), 0); }
    public int totalCount() { int n = 0; for (int c : countByName.values()) n += c; return n; }
    public int distinctTypes() { return avgByName.size(); }

    /** Average [Morale, Combat, Seafaring, Speed] for a crew name (zeros if unknown). */
    public int[] stats(String name) {
        int[] s = avgByName.get(key(name));
        return s == null ? new int[]{0, 0, 0, 0} : s;
    }

    public boolean knows(String name) { return avgByName.containsKey(key(name)); }

    /** Load the DB from {@link #FILE}; returns an empty DB if the file is absent/unreadable. */
    public static CrewDb load() {
        CrewDb db = new CrewDb();
        try {
            if (!Files.exists(FILE)) return db;
            Map<String, long[]> sums = new HashMap<>(); // name -> [sumM,sumC,sumS,sumSpd,count]
            for (String raw : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                if (p.length < 5) continue;
                String name = key(p[0]);
                long[] acc = sums.computeIfAbsent(name, k -> new long[5]);
                int m = (int) parse(p[1]), c = (int) parse(p[2]), s = (int) parse(p[3]), sp = (int) parse(p[4]);
                acc[0] += m; acc[1] += c; acc[2] += s; acc[3] += sp; acc[4]++;
                db.entries.add(new Entry(p[0].trim(), m, c, s, sp));
            }
            for (Map.Entry<String, long[]> e : sums.entrySet()) {
                long[] a = e.getValue();
                int n = (int) a[4];
                if (n == 0) continue;
                db.avgByName.put(e.getKey(), new int[]{
                        (int) (a[0] / n), (int) (a[1] / n), (int) (a[2] / n), (int) (a[3] / n) });
                db.countByName.put(e.getKey(), n);
            }
        } catch (Exception ignored) {
        }
        return db;
    }

    /** Write a freshly-scanned crew list to {@link #FILE} (pipe format). */
    public static void save(List<String[]> rows) {
        try {
            List<String> lines = new ArrayList<>();
            for (String[] r : rows) {
                // r = {name, morale, combat, seafaring, speed}
                lines.add(r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4]);
            }
            Files.write(FILE, String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(); }
    private static long parse(String s) {
        try { return Long.parseLong(s.trim().replaceAll("[^0-9-]", "")); }
        catch (Exception e) { return 0; }
    }
}
