package radium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Scoreboard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flicker-resistant client-side spoofer for plugin-driven sidebar lines.
 *
 * <p>Every configured entry keeps its own scoreboard-team lock and refresh
 * grace state. Incoming server prefixes are cached untouched before Radium
 * substitutes the local display text, so Dynamic mode can still observe the
 * real server-sent value underneath a fixed spoof.</p>
 */
public final class LineSpoofer {
    /** Two seconds at 20 client ticks/sec is long enough to ride out refreshes. */
    private static final int LOCK_GRACE_TICKS = 40;

    /** Radium-owned writes must bypass PlayerTeamMixin. */
    private static final ThreadLocal<Integer> PREFIX_WRITE_BYPASS = ThreadLocal.withInitial(() -> 0);

    /** Raw server-owned prefixes captured before Radium substitutes display text. */
    private static final Map<String, Component> RAW_PREFIXES_BY_TEAM = new java.util.HashMap<>();
    private static final Map<Object, Component> RAW_PREFIXES_BY_OBJECT = new IdentityHashMap<>();

    /** Runtime lock state is deliberately separate from editable GUI configuration. */
    private static final Map<SpoofConfig.LineEntry, LockState> LOCKS = new IdentityHashMap<>();

    private static Scoreboard lockedScoreboard;

    private LineSpoofer() {
    }

    /**
     * Called synchronously from PlayerTeam#setPlayerPrefix (or its alias).
     * The incoming component is cached untouched first. If the team belongs to
     * one of Radium's active entries, a spoofed copy is returned immediately so
     * the real refreshed value never becomes renderable for a frame.
     */
    public static Component interceptPrefixWrite(Object team, Component incomingPrefix) {
        if (incomingPrefix == null || isPrefixWriteBypassed()) {
            return incomingPrefix;
        }

        rememberRawPrefix(team, incomingPrefix);

        if (!SpoofConfig.lineEnabled) {
            return incomingPrefix;
        }

        for (SpoofConfig.LineEntry entry : snapshotEntries()) {
            String target = safeTrim(entry.targetLine);
            String replacement = safeTrim(entry.fakeValue);
            if (target.isBlank() || replacement.isBlank()) {
                continue;
            }

            LockState state = stateFor(entry);

            // A GUI edit is reconciled by tick(), where the old team can first
            // be restored safely. Do not silently retarget a stale lock here.
            if (state.hasLock() && !state.lockedTarget.equalsIgnoreCase(target)) {
                continue;
            }

            if (!state.hasLock()
                    && containsIgnoreCase(incomingPrefix.getString(), target)
                    && !isTeamClaimedByAnother(entry, team)) {
                acquireLock(entry, state, team, incomingPrefix, target);
            }

            if (!matchesLockedTeam(state, team)) {
                continue;
            }

            Component source;
            if (containsIgnoreCase(incomingPrefix.getString(), target)) {
                source = incomingPrefix;
                rememberStableSource(entry, state, incomingPrefix);
                state.missingTargetTicks = 0;
            } else if (state.lastStableSource != null && state.missingTargetTicks <= LOCK_GRACE_TICKS) {
                // DonutScoreboard can briefly blank/rebuild a team. Keep the
                // last stable label structure during that transient refresh.
                source = state.lastStableSource;
            } else {
                continue;
            }

            Component spoofed = replaceValueAfterMatch(source, target, replacement);
            if (spoofed != null) {
                return spoofed;
            }
        }

        return incomingPrefix;
    }

    /** Maintains all entry locks and acquires lines that predate Radium being enabled. */
    public static void tick(Minecraft client) {
        pruneRemovedEntries();

        if (client.level == null) {
            clearAllState();
            for (SpoofConfig.LineEntry entry : snapshotEntries()) {
                entry.lastMatchedLine = "";
                entry.statusMessage = SpoofConfig.lineEnabled ? "Waiting for a world" : "Disabled";
            }
            return;
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        if (lockedScoreboard != null && lockedScoreboard != scoreboard) {
            clearAllState();
        }
        lockedScoreboard = scoreboard;

        if (!SpoofConfig.lineEnabled) {
            restoreAllLocks();
            LOCKS.clear();
            for (SpoofConfig.LineEntry entry : snapshotEntries()) {
                entry.lastMatchedLine = "";
                entry.statusMessage = "Disabled";
            }
            return;
        }

        Collection<?> teams = getTeams(scoreboard);
        if (teams == null) {
            for (SpoofConfig.LineEntry entry : snapshotEntries()) {
                entry.statusMessage = "Could not read scoreboard teams";
            }
            return;
        }

        for (SpoofConfig.LineEntry entry : snapshotEntries()) {
            tickEntry(teams, entry);
        }
    }

    private static void tickEntry(Collection<?> teams, SpoofConfig.LineEntry entry) {
        LockState state = stateFor(entry);
        String target = safeTrim(entry.targetLine);
        String replacement = safeTrim(entry.fakeValue);

        if (target.isBlank()) {
            restoreLock(state);
            state.clearLock();
            entry.lastMatchedLine = "";
            entry.statusMessage = "Enter line text to find";
            return;
        }

        if (replacement.isBlank()) {
            restoreLock(state);
            state.clearLock();
            entry.lastMatchedLine = "";
            entry.statusMessage = "Enter a fake value";
            return;
        }

        if (state.hasLock() && !state.lockedTarget.equalsIgnoreCase(target)) {
            restoreLock(state);
            state.clearLock();
        }
        state.lockedTarget = target;

        Object currentLockedTeam = findLockedTeam(teams, state);
        if (currentLockedTeam != null) {
            state.lockedTeam = currentLockedTeam;
            Component rawPrefix = getRememberedRawPrefix(currentLockedTeam);
            if (rawPrefix == null) {
                rawPrefix = getCurrentPrefix(currentLockedTeam);
                rememberRawPrefix(currentLockedTeam, rawPrefix);
            }

            if (rawPrefix != null && containsIgnoreCase(rawPrefix.getString(), target)) {
                rememberStableSource(entry, state, rawPrefix);
                state.missingTargetTicks = 0;
                applySpoofToTeam(currentLockedTeam, rawPrefix, target, replacement);
                entry.statusMessage = "Locked: " + displayTeamName(currentLockedTeam);
                return;
            }

            state.missingTargetTicks++;
            if (state.missingTargetTicks <= LOCK_GRACE_TICKS && state.lastStableSource != null) {
                applySpoofToTeam(currentLockedTeam, state.lastStableSource, target, replacement);
                entry.statusMessage = "Locked - scoreboard refreshing";
                return;
            }

            restoreLock(state);
            state.clearLock();
            state.lockedTarget = target;
        } else if (!state.lockedTeamName.isBlank()) {
            state.missingTargetTicks++;
            if (state.missingTargetTicks <= LOCK_GRACE_TICKS) {
                entry.statusMessage = "Waiting for locked team refresh";
                return;
            }
            state.clearLock();
            state.lockedTarget = target;
        }

        Object match = findTeamContaining(teams, target, entry);
        if (match == null) {
            entry.lastMatchedLine = "";
            entry.statusMessage = "Waiting for: " + target;
            return;
        }

        if (isTeamClaimedByAnother(entry, match)) {
            entry.statusMessage = "That line is already used by another entry";
            return;
        }

        Component rawPrefix = getRememberedRawPrefix(match);
        if (rawPrefix == null) {
            rawPrefix = getCurrentPrefix(match);
            rememberRawPrefix(match, rawPrefix);
        }

        acquireLock(entry, state, match, rawPrefix, target);
        applySpoofToTeam(match, rawPrefix, target, replacement);
        entry.statusMessage = "Locked: " + displayTeamName(match);
    }

    /** Future Dynamic mode can inspect the exact untouched server line for an entry. */
    public static Component getRawPrefixForEntry(SpoofConfig.LineEntry entry) {
        LockState state = LOCKS.get(entry);
        if (state == null || state.lockedTeam == null) {
            return null;
        }
        return getRememberedRawPrefix(state.lockedTeam);
    }

    /** Compatibility helper retained for future code that already has a team object. */
    public static Component getRawPrefixForTeam(Object team) {
        return getRememberedRawPrefix(team);
    }

    /** Lines tab wins if a fixed and Dynamic entry try to own the same team. */
    static boolean isTeamClaimedByFixedLine(Object team) {
        if (team == null) {
            return false;
        }
        String name = getTeamName(team);
        for (LockState state : LOCKS.values()) {
            if (state.lockedTeam == team) {
                return true;
            }
            if (!name.isBlank() && name.equals(state.lockedTeamName)) {
                return true;
            }
        }
        return false;
    }

    /** Immediately restores and forgets a GUI entry before it is removed. */
    public static void removeEntry(SpoofConfig.LineEntry entry) {
        LockState state = LOCKS.remove(entry);
        if (state != null) {
            restoreLock(state);
        }
    }

    public static void restoreAll() {
        restoreAllLocks();
        clearAllState();
    }

    private static void acquireLock(
            SpoofConfig.LineEntry entry,
            LockState state,
            Object team,
            Component rawPrefix,
            String target
    ) {
        state.lockedTeam = team;
        state.lockedTeamName = getTeamName(team);
        state.lockedTarget = target;
        state.missingTargetTicks = 0;
        if (rawPrefix != null && containsIgnoreCase(rawPrefix.getString(), target)) {
            rememberStableSource(entry, state, rawPrefix);
        }
    }

    private static void rememberStableSource(
            SpoofConfig.LineEntry entry,
            LockState state,
            Component rawPrefix
    ) {
        state.lastStableSource = rawPrefix;
        entry.lastMatchedLine = rawPrefix.getString();
    }

    private static LockState stateFor(SpoofConfig.LineEntry entry) {
        return LOCKS.computeIfAbsent(entry, ignored -> new LockState());
    }

    private static boolean matchesLockedTeam(LockState state, Object team) {
        if (team == null) {
            return false;
        }
        if (team == state.lockedTeam) {
            return true;
        }
        if (!state.lockedTeamName.isBlank()) {
            String name = getTeamName(team);
            if (!name.isBlank() && state.lockedTeamName.equals(name)) {
                state.lockedTeam = team;
                return true;
            }
        }
        return false;
    }

    private static Object findLockedTeam(Collection<?> teams, LockState state) {
        if (state.lockedTeam != null && teams.contains(state.lockedTeam)) {
            return state.lockedTeam;
        }
        if (!state.lockedTeamName.isBlank()) {
            for (Object team : teams) {
                if (state.lockedTeamName.equals(getTeamName(team))) {
                    return team;
                }
            }
        }
        return null;
    }

    private static Object findTeamContaining(
            Collection<?> teams,
            String target,
            SpoofConfig.LineEntry requestingEntry
    ) {
        for (Object team : teams) {
            if (team == null || isTeamClaimedByAnother(requestingEntry, team)) {
                continue;
            }
            Component prefix = getRememberedRawPrefix(team);
            if (prefix == null) {
                prefix = getCurrentPrefix(team);
            }
            if (prefix != null && containsIgnoreCase(prefix.getString(), target)) {
                rememberRawPrefix(team, prefix);
                return team;
            }
        }
        return null;
    }

    private static boolean isTeamClaimedByAnother(SpoofConfig.LineEntry entry, Object team) {
        if (team == null) {
            return false;
        }
        String teamName = getTeamName(team);
        for (Map.Entry<SpoofConfig.LineEntry, LockState> runtime : LOCKS.entrySet()) {
            if (runtime.getKey() == entry) {
                continue;
            }
            LockState other = runtime.getValue();
            if (other.lockedTeam == team) {
                return true;
            }
            if (!teamName.isBlank() && teamName.equals(other.lockedTeamName)) {
                return true;
            }
        }
        return false;
    }

    private static void restoreLock(LockState state) {
        if (state == null || state.lockedTeam == null) {
            return;
        }
        Component raw = getRememberedRawPrefix(state.lockedTeam);
        if (raw != null) {
            setCurrentPrefix(state.lockedTeam, raw);
        }
    }

    private static void restoreAllLocks() {
        for (LockState state : new ArrayList<>(LOCKS.values())) {
            restoreLock(state);
        }
    }

    private static void pruneRemovedEntries() {
        List<SpoofConfig.LineEntry> current = snapshotEntries();
        var iterator = LOCKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SpoofConfig.LineEntry, LockState> runtime = iterator.next();
            if (!current.contains(runtime.getKey())) {
                restoreLock(runtime.getValue());
                iterator.remove();
            }
        }
    }

    private static void applySpoofToTeam(Object team, Component rawSource, String target, String replacement) {
        if (team == null || rawSource == null) {
            return;
        }
        Component spoofed = replaceValueAfterMatch(rawSource, target, replacement);
        if (spoofed != null) {
            setCurrentPrefix(team, spoofed);
        }
    }

    private static void clearAllState() {
        lockedScoreboard = null;
        LOCKS.clear();
        // Raw prefix data belongs to one client scoreboard/session only.
        RAW_PREFIXES_BY_OBJECT.clear();
        RAW_PREFIXES_BY_TEAM.clear();
    }

    private static String displayTeamName(Object team) {
        String name = getTeamName(team);
        return name.isBlank() ? "matched line" : name;
    }

    private static List<SpoofConfig.LineEntry> snapshotEntries() {
        return new ArrayList<>(SpoofConfig.lineEntries);
    }

    /** Shared styled-line replacement used by both fixed Lines and Dynamic mode. */
    static Component replaceValueAfterMatch(Component original, String target, String replacement) {
        String plain = original.getString();
        int matchStart = indexOfIgnoreCase(plain, target);
        if (matchStart < 0) {
            return null;
        }

        int matchEnd = matchStart + target.length();
        int valueStart = findValueStart(plain, matchEnd);
        String replacementText = replacement;

        if (valueStart == plain.length() && matchEnd == plain.length()) {
            replacementText = " " + replacement;
        }

        return rebuildWithReplacement(original, valueStart, replacementText);
    }

    /** Keeps the label/separators after it, then replaces the remaining value. */
    private static int findValueStart(String line, int start) {
        int index = Math.max(0, Math.min(start, line.length()));

        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }

        boolean consumedSeparator;
        do {
            consumedSeparator = false;
            if (index < line.length() && isPreservedSeparator(line.charAt(index))) {
                index++;
                consumedSeparator = true;
                while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
                    index++;
                }
            }
        } while (consumedSeparator);

        return index;
    }

    private static boolean isPreservedSeparator(char value) {
        return value == ':'
                || value == '='
                || value == '|'
                || value == '»'
                || value == '›'
                || value == '→'
                || value == '$'
                || value == '€'
                || value == '£'
                || value == '¥';
    }

    /** Rebuilds flattened styled components when available, preserving label style. */
    private static Component rebuildWithReplacement(Component original, int keepCharacters, String replacement) {
        List<Component> flat = flatten(original);
        if (flat.isEmpty()) {
            return Component.literal(original.getString().substring(0, keepCharacters) + replacement)
                    .withStyle(original.getStyle());
        }

        var rebuilt = Component.literal("");
        int remaining = keepCharacters;
        Component replacementStyleSource = original;

        for (Component part : flat) {
            String text = part.getString();
            if (text.isEmpty()) {
                continue;
            }

            if (remaining > 0) {
                int take = Math.min(remaining, text.length());
                if (take > 0) {
                    rebuilt.append(Component.literal(text.substring(0, take)).withStyle(part.getStyle()));
                    replacementStyleSource = part;
                    remaining -= take;
                }

                if (take < text.length()) {
                    replacementStyleSource = part;
                    break;
                }
            } else {
                replacementStyleSource = part;
                break;
            }
        }

        rebuilt.append(Component.literal(replacement).withStyle(replacementStyleSource.getStyle()));
        return rebuilt;
    }

    @SuppressWarnings("unchecked")
    private static List<Component> flatten(Component component) {
        try {
            Method method = Component.class.getMethod("toFlatList");
            Object result = method.invoke(component);
            if (result instanceof List<?> list) {
                List<Component> components = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Component part) {
                        components.add(part);
                    }
                }
                return components;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the root-style fallback.
        }
        return List.of();
    }

    /** Package-private bridge so Dynamic mode can reuse the proven team discovery path. */
    static Collection<?> getTeamsForScoreboard(Scoreboard scoreboard) {
        return getTeams(scoreboard);
    }

    private static Collection<?> getTeams(Scoreboard scoreboard) {
        Object result = invokeNoArg(scoreboard, "getPlayerTeams", "getTeams");
        if (result instanceof Collection<?> collection) {
            return collection;
        }
        for (Method method : scoreboard.getClass().getMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (method.getParameterCount() == 0
                    && Collection.class.isAssignableFrom(method.getReturnType())
                    && name.contains("team")) {
                try {
                    Object value = method.invoke(scoreboard);
                    if (value instanceof Collection<?> collection) {
                        return collection;
                    }
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }

    /** Reads the current client-side prefix (which may be spoofed). */
    static Component getClientPrefix(Object team) {
        return getCurrentPrefix(team);
    }

    private static Component getCurrentPrefix(Object team) {
        Object result = invokeNoArg(team, "getPlayerPrefix", "getPrefix");
        if (result instanceof Component component) {
            return component;
        }
        for (Method method : team.getClass().getMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (method.getParameterCount() == 0
                    && Component.class.isAssignableFrom(method.getReturnType())
                    && name.contains("prefix")) {
                try {
                    Object value = method.invoke(team);
                    if (value instanceof Component component) {
                        return component;
                    }
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }

    /** Writes a Radium-owned prefix while bypassing the incoming-server mixin. */
    static void setClientPrefix(Object team, Component prefix) {
        setCurrentPrefix(team, prefix);
    }

    private static void setCurrentPrefix(Object team, Component prefix) {
        if (team == null || prefix == null) {
            return;
        }
        pushPrefixWriteBypass();
        try {
            if (invokeComponentSetter(team, prefix, "setPlayerPrefix", "setPrefix")) {
                return;
            }
            for (Method method : team.getClass().getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(Component.class)
                        && name.contains("prefix")) {
                    try {
                        method.invoke(team, prefix);
                        return;
                    } catch (IllegalAccessException | InvocationTargetException ignored) {
                        // Try the next candidate.
                    }
                }
            }
        } finally {
            popPrefixWriteBypass();
        }
    }

    private static void rememberRawPrefix(Object team, Component prefix) {
        if (team == null || prefix == null) {
            return;
        }
        RAW_PREFIXES_BY_OBJECT.put(team, prefix);
        String name = getTeamName(team);
        if (!name.isBlank()) {
            RAW_PREFIXES_BY_TEAM.put(name, prefix);
        }
    }

    private static Component getRememberedRawPrefix(Object team) {
        if (team == null) {
            return null;
        }
        Component byObject = RAW_PREFIXES_BY_OBJECT.get(team);
        if (byObject != null) {
            return byObject;
        }
        String name = getTeamName(team);
        return name.isBlank() ? null : RAW_PREFIXES_BY_TEAM.get(name);
    }

    static String getTeamNameForInternalUse(Object team) {
        return getTeamName(team);
    }

    private static String getTeamName(Object team) {
        if (team == null) {
            return "";
        }
        Object value = invokeNoArg(team, "getName", "getTeamName");
        if (value instanceof String string) {
            return string;
        }
        for (Method method : team.getClass().getMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (method.getParameterCount() == 0
                    && method.getReturnType() == String.class
                    && name.contains("name")) {
                try {
                    Object result = method.invoke(team);
                    if (result instanceof String string) {
                        return string;
                    }
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return "";
    }

    private static boolean isPrefixWriteBypassed() {
        return PREFIX_WRITE_BYPASS.get() > 0;
    }

    private static void pushPrefixWriteBypass() {
        PREFIX_WRITE_BYPASS.set(PREFIX_WRITE_BYPASS.get() + 1);
    }

    private static void popPrefixWriteBypass() {
        int depth = PREFIX_WRITE_BYPASS.get() - 1;
        if (depth <= 0) {
            PREFIX_WRITE_BYPASS.remove();
        } else {
            PREFIX_WRITE_BYPASS.set(depth);
        }
    }

    private static Object invokeNoArg(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the next name.
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean invokeComponentSetter(Object target, Component value, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name, Component.class);
                method.invoke(target, value);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Try the next name.
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String text, String search) {
        return indexOfIgnoreCase(text, search) >= 0;
    }

    private static int indexOfIgnoreCase(String text, String search) {
        if (text == null || search == null || search.isEmpty()) {
            return -1;
        }
        return text.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class LockState {
        Object lockedTeam;
        String lockedTeamName = "";
        String lockedTarget = "";
        Component lastStableSource;
        int missingTargetTicks;

        boolean hasLock() {
            return lockedTeam != null || !lockedTeamName.isBlank();
        }

        void clearLock() {
            lockedTeam = null;
            lockedTeamName = "";
            lockedTarget = "";
            lastStableSource = null;
            missingTargetTicks = 0;
        }
    }
}
