package radium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Scoreboard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Numeric line spoofer that follows changes in Radium's untouched raw scoreboard text.
 *
 * <p>Example: if the server line moves from 10.3M to 10.2M while a Dynamic entry
 * started at 20M, the fake value becomes 19.9M. The current fake amount is kept as
 * a BigDecimal, so formatting/rounding never becomes the source for the next delta.</p>
 */
public final class DynamicSpoofer {
    private static final int LOCK_GRACE_TICKS = 40;

    private static final Map<SpoofConfig.DynamicEntry, DynamicState> STATES = new IdentityHashMap<>();
    private static Scoreboard lockedScoreboard;

    private DynamicSpoofer() {
    }

    /**
     * Runs after LineSpoofer has captured the untouched incoming prefix. Fixed Lines
     * intentionally take precedence if both systems target the same scoreboard team.
     */
    public static Component interceptPrefixWrite(Object team, Component incomingAfterFixedLine) {
        if (team == null || incomingAfterFixedLine == null || !SpoofConfig.dynamicEnabled) {
            return incomingAfterFixedLine;
        }

        if (LineSpoofer.isTeamClaimedByFixedLine(team)) {
            return incomingAfterFixedLine;
        }

        Component raw = LineSpoofer.getRawPrefixForTeam(team);
        if (raw == null) {
            raw = incomingAfterFixedLine;
        }

        for (SpoofConfig.DynamicEntry entry : snapshotEntries()) {
            String target = safeTrim(entry.targetLine);
            NumericText.ParsedInput startingFake = NumericText.parseUserValue(entry.startingFakeValue);
            if (target.isBlank() || startingFake == null) {
                continue;
            }

            DynamicState state = stateFor(entry);
            if (state.hasLock() && (!state.lockedTarget.equalsIgnoreCase(target)
                    || !state.configuredStartText.equals(safeTrim(entry.startingFakeValue)))) {
                // tick() safely reconciles edits and restores the old team first.
                continue;
            }

            if (!state.hasLock()
                    && containsIgnoreCase(raw.getString(), target)
                    && !isTeamClaimedByAnother(entry, team)) {
                acquireLock(state, team, target, entry.startingFakeValue);
            }

            if (!matchesLockedTeam(state, team)) {
                continue;
            }

            Component source;
            if (containsIgnoreCase(raw.getString(), target)) {
                source = raw;
                state.lastStableSource = raw;
                state.missingTargetTicks = 0;
                entry.lastMatchedLine = raw.getString();
                if (!updateTracking(entry, state, raw, target)) {
                    return incomingAfterFixedLine;
                }
            } else if (state.lastStableSource != null && state.missingTargetTicks <= LOCK_GRACE_TICKS) {
                source = state.lastStableSource;
            } else {
                continue;
            }

            if (state.currentFake == null) {
                continue;
            }
            String display = NumericText.formatDynamic(state.currentFake, state.compactDisplay);
            entry.currentFakeDisplay = display;
            Component spoofed = LineSpoofer.replaceValueAfterMatch(source, target, display);
            if (spoofed != null) {
                return spoofed;
            }
        }

        return incomingAfterFixedLine;
    }

    public static void tick(Minecraft client) {
        pruneRemovedEntries();

        if (client.level == null) {
            clearAllState();
            for (SpoofConfig.DynamicEntry entry : snapshotEntries()) {
                clearFeedback(entry);
                entry.statusMessage = SpoofConfig.dynamicEnabled ? "Waiting for a world" : "Disabled";
            }
            return;
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        if (lockedScoreboard != null && lockedScoreboard != scoreboard) {
            clearAllState();
        }
        lockedScoreboard = scoreboard;

        if (!SpoofConfig.dynamicEnabled) {
            restoreAllLocks();
            STATES.clear();
            for (SpoofConfig.DynamicEntry entry : snapshotEntries()) {
                clearFeedback(entry);
                entry.statusMessage = "Disabled";
            }
            return;
        }

        Collection<?> teams = LineSpoofer.getTeamsForScoreboard(scoreboard);
        if (teams == null) {
            for (SpoofConfig.DynamicEntry entry : snapshotEntries()) {
                entry.statusMessage = "Could not read scoreboard teams";
            }
            return;
        }

        for (SpoofConfig.DynamicEntry entry : snapshotEntries()) {
            tickEntry(teams, entry);
        }
    }

    private static void tickEntry(Collection<?> teams, SpoofConfig.DynamicEntry entry) {
        DynamicState state = stateFor(entry);
        String target = safeTrim(entry.targetLine);
        String startText = safeTrim(entry.startingFakeValue);
        NumericText.ParsedInput startingFake = NumericText.parseUserValue(startText);

        if (target.isBlank()) {
            restoreLock(state);
            state.clearLockAndTracking();
            clearFeedback(entry);
            entry.statusMessage = "Enter line text to find";
            return;
        }

        if (startingFake == null) {
            restoreLock(state);
            state.clearLockAndTracking();
            clearFeedback(entry);
            entry.statusMessage = "Enter a numeric fake value (20M, 4.5T, 2000)";
            return;
        }

        if (state.hasLock() && (!state.lockedTarget.equalsIgnoreCase(target)
                || !state.configuredStartText.equals(startText))) {
            restoreLock(state);
            state.clearLockAndTracking();
        }
        state.lockedTarget = target;
        state.configuredStartText = startText;

        Object currentLockedTeam = findLockedTeam(teams, state);
        if (currentLockedTeam != null) {
            if (LineSpoofer.isTeamClaimedByFixedLine(currentLockedTeam)) {
                // The Lines tab deliberately has priority. Do not restore here because
                // that would overwrite the fixed spoof that was just applied.
                state.clearLockAndTracking();
                clearFeedback(entry);
                entry.statusMessage = "This line is currently owned by the Lines tab";
                return;
            }

            state.lockedTeam = currentLockedTeam;
            Component raw = LineSpoofer.getRawPrefixForTeam(currentLockedTeam);
            if (raw == null) {
                raw = LineSpoofer.getClientPrefix(currentLockedTeam);
            }

            if (raw != null && containsIgnoreCase(raw.getString(), target)) {
                state.lastStableSource = raw;
                state.missingTargetTicks = 0;
                entry.lastMatchedLine = raw.getString();
                if (!updateTracking(entry, state, raw, target)) {
                    restoreLock(state);
                    entry.statusMessage = "No numeric value found after: " + target;
                    return;
                }
                applySpoof(currentLockedTeam, raw, target, state, entry);
                entry.statusMessage = trackingStatus(currentLockedTeam, entry);
                return;
            }

            state.missingTargetTicks++;
            if (state.missingTargetTicks <= LOCK_GRACE_TICKS && state.lastStableSource != null
                    && state.currentFake != null) {
                applySpoof(currentLockedTeam, state.lastStableSource, target, state, entry);
                entry.statusMessage = "Tracking - scoreboard refreshing";
                return;
            }

            restoreLock(state);
            state.clearLockAndTracking();
            state.lockedTarget = target;
            state.configuredStartText = startText;
        } else if (!state.lockedTeamName.isBlank()) {
            state.missingTargetTicks++;
            if (state.missingTargetTicks <= LOCK_GRACE_TICKS) {
                entry.statusMessage = "Waiting for locked team refresh";
                return;
            }
            state.clearLockAndTracking();
            state.lockedTarget = target;
            state.configuredStartText = startText;
        }

        Object match = findTeamContaining(teams, target, entry);
        if (match == null) {
            clearFeedback(entry);
            entry.statusMessage = "Waiting for: " + target;
            return;
        }

        if (LineSpoofer.isTeamClaimedByFixedLine(match)) {
            clearFeedback(entry);
            entry.statusMessage = "This line is currently owned by the Lines tab";
            return;
        }

        Component raw = LineSpoofer.getRawPrefixForTeam(match);
        if (raw == null) {
            raw = LineSpoofer.getClientPrefix(match);
        }

        acquireLock(state, match, target, startText);
        state.lastStableSource = raw;
        entry.lastMatchedLine = raw == null ? "" : raw.getString();
        if (raw == null || !updateTracking(entry, state, raw, target)) {
            restoreLock(state);
            state.clearLockAndTracking();
            entry.statusMessage = "No numeric value found after: " + target;
            return;
        }

        applySpoof(match, raw, target, state, entry);
        entry.statusMessage = trackingStatus(match, entry);
    }

    private static boolean updateTracking(
            SpoofConfig.DynamicEntry entry,
            DynamicState state,
            Component raw,
            String target
    ) {
        NumericText.ParsedLineValue parsed = NumericText.parseLineValueAfterTarget(raw.getString(), target);
        if (parsed == null) {
            return false;
        }

        NumericText.ParsedInput starting = NumericText.parseUserValue(entry.startingFakeValue);
        if (starting == null) {
            return false;
        }

        if (state.lastReal == null || state.currentFake == null) {
            state.lastReal = parsed.value();
            state.currentFake = starting.value();
            state.compactDisplay = starting.compact();
        } else {
            BigDecimal delta = parsed.value().subtract(state.lastReal);
            if (delta.signum() != 0) {
                state.currentFake = state.currentFake.add(delta);
                state.lastReal = parsed.value();
            }
        }

        entry.currentRealDisplay = NumericText.formatRealForGui(parsed.value(), parsed.originalToken());
        entry.currentFakeDisplay = NumericText.formatDynamic(state.currentFake, state.compactDisplay);
        return true;
    }

    private static void applySpoof(
            Object team,
            Component rawSource,
            String target,
            DynamicState state,
            SpoofConfig.DynamicEntry entry
    ) {
        if (team == null || rawSource == null || state.currentFake == null) {
            return;
        }
        String display = NumericText.formatDynamic(state.currentFake, state.compactDisplay);
        entry.currentFakeDisplay = display;
        Component spoofed = LineSpoofer.replaceValueAfterMatch(rawSource, target, display);
        if (spoofed != null) {
            LineSpoofer.setClientPrefix(team, spoofed);
        }
    }

    public static void removeEntry(SpoofConfig.DynamicEntry entry) {
        DynamicState state = STATES.remove(entry);
        if (state != null) {
            restoreLock(state);
        }
    }

    public static void resetEntry(SpoofConfig.DynamicEntry entry) {
        DynamicState state = STATES.get(entry);
        if (state == null) {
            return;
        }
        restoreLock(state);
        state.clearLockAndTracking();
        clearFeedback(entry);
        entry.statusMessage = "Baseline reset";
    }

    public static void restoreAll() {
        restoreAllLocks();
        clearAllState();
    }

    private static String trackingStatus(Object team, SpoofConfig.DynamicEntry entry) {
        String name = LineSpoofer.getTeamNameForInternalUse(team);
        String values = entry.currentRealDisplay + " -> " + entry.currentFakeDisplay;
        if (name == null || name.isBlank()) {
            return "Tracking: " + values;
        }
        return "Tracking " + name + ": " + values;
    }

    private static void acquireLock(DynamicState state, Object team, String target, String startText) {
        state.lockedTeam = team;
        state.lockedTeamName = LineSpoofer.getTeamNameForInternalUse(team);
        state.lockedTarget = target;
        state.configuredStartText = safeTrim(startText);
        state.missingTargetTicks = 0;
    }

    private static DynamicState stateFor(SpoofConfig.DynamicEntry entry) {
        return STATES.computeIfAbsent(entry, ignored -> new DynamicState());
    }

    private static Object findLockedTeam(Collection<?> teams, DynamicState state) {
        if (state.lockedTeam != null && teams.contains(state.lockedTeam)) {
            return state.lockedTeam;
        }
        if (!state.lockedTeamName.isBlank()) {
            for (Object team : teams) {
                if (state.lockedTeamName.equals(LineSpoofer.getTeamNameForInternalUse(team))) {
                    return team;
                }
            }
        }
        return null;
    }

    private static boolean matchesLockedTeam(DynamicState state, Object team) {
        if (team == state.lockedTeam) {
            return true;
        }
        if (!state.lockedTeamName.isBlank()) {
            String name = LineSpoofer.getTeamNameForInternalUse(team);
            if (state.lockedTeamName.equals(name)) {
                state.lockedTeam = team;
                return true;
            }
        }
        return false;
    }

    private static Object findTeamContaining(
            Collection<?> teams,
            String target,
            SpoofConfig.DynamicEntry requestingEntry
    ) {
        for (Object team : teams) {
            if (team == null
                    || LineSpoofer.isTeamClaimedByFixedLine(team)
                    || isTeamClaimedByAnother(requestingEntry, team)) {
                continue;
            }
            Component raw = LineSpoofer.getRawPrefixForTeam(team);
            if (raw == null) {
                raw = LineSpoofer.getClientPrefix(team);
            }
            if (raw != null && containsIgnoreCase(raw.getString(), target)) {
                return team;
            }
        }
        return null;
    }

    private static boolean isTeamClaimedByAnother(SpoofConfig.DynamicEntry entry, Object team) {
        String teamName = LineSpoofer.getTeamNameForInternalUse(team);
        for (Map.Entry<SpoofConfig.DynamicEntry, DynamicState> runtime : STATES.entrySet()) {
            if (runtime.getKey() == entry) {
                continue;
            }
            DynamicState other = runtime.getValue();
            if (other.lockedTeam == team) {
                return true;
            }
            if (!teamName.isBlank() && teamName.equals(other.lockedTeamName)) {
                return true;
            }
        }
        return false;
    }

    private static void restoreLock(DynamicState state) {
        if (state == null || state.lockedTeam == null) {
            return;
        }
        // Never overwrite an active fixed Lines spoof; it has deliberate priority.
        if (LineSpoofer.isTeamClaimedByFixedLine(state.lockedTeam)) {
            return;
        }
        Component raw = LineSpoofer.getRawPrefixForTeam(state.lockedTeam);
        if (raw != null) {
            LineSpoofer.setClientPrefix(state.lockedTeam, raw);
        }
    }

    private static void restoreAllLocks() {
        for (DynamicState state : new ArrayList<>(STATES.values())) {
            restoreLock(state);
        }
    }

    private static void pruneRemovedEntries() {
        List<SpoofConfig.DynamicEntry> current = snapshotEntries();
        var iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SpoofConfig.DynamicEntry, DynamicState> runtime = iterator.next();
            if (!current.contains(runtime.getKey())) {
                restoreLock(runtime.getValue());
                iterator.remove();
            }
        }
    }

    private static void clearAllState() {
        lockedScoreboard = null;
        STATES.clear();
    }

    private static void clearFeedback(SpoofConfig.DynamicEntry entry) {
        entry.lastMatchedLine = "";
        entry.currentRealDisplay = "";
        entry.currentFakeDisplay = "";
    }

    private static List<SpoofConfig.DynamicEntry> snapshotEntries() {
        return new ArrayList<>(SpoofConfig.dynamicEntries);
    }

    private static boolean containsIgnoreCase(String text, String search) {
        if (text == null || search == null || search.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class DynamicState {
        Object lockedTeam;
        String lockedTeamName = "";
        String lockedTarget = "";
        String configuredStartText = "";
        Component lastStableSource;
        int missingTargetTicks;

        BigDecimal lastReal;
        BigDecimal currentFake;
        boolean compactDisplay;

        boolean hasLock() {
            return lockedTeam != null || !lockedTeamName.isBlank();
        }

        void clearLockAndTracking() {
            lockedTeam = null;
            lockedTeamName = "";
            lockedTarget = "";
            configuredStartText = "";
            lastStableSource = null;
            missingTargetTicks = 0;
            lastReal = null;
            currentFake = null;
            compactDisplay = false;
        }
    }
}
