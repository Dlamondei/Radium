package radium.client;

import java.util.ArrayList;
import java.util.List;

public final class SpoofConfig {
    public static final int MAX_LINE_ENTRIES = 50;
    public static final int MAX_DYNAMIC_ENTRIES = 50;

    private SpoofConfig() {
    }

    /** Enables the original objective/player-score display spoof. */
    public static boolean enabled = true;

    /**
     * Internal scoreboard objective name to target.
     * Blank means "whatever objective is currently displayed in the sidebar".
     */
    public static String targetObjective = "";

    /**
     * Display-only replacement for the local player's sidebar score.
     * This is text rather than an int, so values such as 4M, 2.5B, 1T,
     * or 1000000000000 can be shown without the vanilla scoreboard int limit.
     */
    public static String fakeValue = "2.3M";

    /** Last objective observed in the vanilla sidebar, used by the GUI for feedback. */
    public static String lastSidebarObjective = "";

    /** Short live status message for the Variables tab. */
    public static String statusMessage = "Ready";

    /** Enables all fixed team-prefix/sidebar-line spoof entries. */
    public static boolean lineEnabled = false;

    /** Ordered fixed line spoof definitions. */
    public static final List<LineEntry> lineEntries = new ArrayList<>();

    /** Enables Dynamic entries that follow raw numeric scoreboard changes. */
    public static boolean dynamicEnabled = false;

    /** Ordered Dynamic definitions. Each one tracks one numeric sidebar line. */
    public static final List<DynamicEntry> dynamicEntries = new ArrayList<>();

    static {
        lineEntries.add(new LineEntry("Money", "4.5T"));
        dynamicEntries.add(new DynamicEntry("Money", "20M"));
    }

    public static LineEntry addLineEntry() {
        if (lineEntries.size() >= MAX_LINE_ENTRIES) {
            return null;
        }
        LineEntry entry = new LineEntry("", "");
        lineEntries.add(entry);
        return entry;
    }

    public static boolean removeLineEntry(LineEntry entry) {
        if (entry == null) {
            return false;
        }
        return lineEntries.remove(entry);
    }

    public static DynamicEntry addDynamicEntry() {
        if (dynamicEntries.size() >= MAX_DYNAMIC_ENTRIES) {
            return null;
        }
        DynamicEntry entry = new DynamicEntry("", "");
        dynamicEntries.add(entry);
        return entry;
    }

    public static boolean removeDynamicEntry(DynamicEntry entry) {
        if (entry == null) {
            return false;
        }
        return dynamicEntries.remove(entry);
    }

    /** Clears world-specific feedback without changing the user's configured spoof values. */
    public static void resetRuntimeFeedback() {
        lastSidebarObjective = "";
        statusMessage = enabled ? "Waiting for a world" : "Spoofing disabled";

        for (LineEntry entry : lineEntries) {
            entry.lastMatchedLine = "";
            entry.statusMessage = lineEnabled ? "Waiting for a world" : "Disabled";
        }

        for (DynamicEntry entry : dynamicEntries) {
            entry.lastMatchedLine = "";
            entry.currentRealDisplay = "";
            entry.currentFakeDisplay = "";
            entry.statusMessage = dynamicEnabled ? "Waiting for a world" : "Disabled";
        }
    }

    public static final class LineEntry {
        /** Case-insensitive text used to identify the visible sidebar line. */
        public String targetLine;

        /** Text that replaces the value portion of the matched line. */
        public String fakeValue;

        /** Last untouched server line matched by this entry. */
        public String lastMatchedLine = "";

        /** Live status shown under the entry in the Lines tab. */
        public String statusMessage = "Ready";

        public LineEntry(String targetLine, String fakeValue) {
            this.targetLine = targetLine == null ? "" : targetLine;
            this.fakeValue = fakeValue == null ? "" : fakeValue;
        }
    }

    public static final class DynamicEntry {
        /** Case-insensitive text used to identify the numeric sidebar line. */
        public String targetLine;

        /**
         * Fake numeric baseline entered by the user. Supports commas and compact
         * suffixes such as K, M, B, T, Q, Qi, Sx, Sp, Oc, No and Dc.
         */
        public String startingFakeValue;

        /** Last untouched server line matched by this entry. */
        public String lastMatchedLine = "";

        /** Most recently parsed raw server value, formatted for GUI feedback. */
        public String currentRealDisplay = "";

        /** Current fake value after applying all detected server-side deltas. */
        public String currentFakeDisplay = "";

        /** Live status shown under the entry in the Dynamic tab. */
        public String statusMessage = "Ready";

        public DynamicEntry(String targetLine, String startingFakeValue) {
            this.targetLine = targetLine == null ? "" : targetLine;
            this.startingFakeValue = startingFakeValue == null ? "" : startingFakeValue;
        }
    }
}
