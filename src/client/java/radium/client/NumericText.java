package radium.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric parsing/formatting shared by Dynamic scoreboard entries. */
final class NumericText {
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)\\s*([A-Za-z]{0,3})"
    );

    /** Ordered largest to smallest for compact output. */
    private static final LinkedHashMap<String, BigDecimal> SUFFIXES = new LinkedHashMap<>();

    static {
        SUFFIXES.put("DC", new BigDecimal("1e33"));
        SUFFIXES.put("NO", new BigDecimal("1e30"));
        SUFFIXES.put("OC", new BigDecimal("1e27"));
        SUFFIXES.put("SP", new BigDecimal("1e24"));
        SUFFIXES.put("SX", new BigDecimal("1e21"));
        SUFFIXES.put("QI", new BigDecimal("1e18"));
        SUFFIXES.put("Q", new BigDecimal("1e15"));
        SUFFIXES.put("T", new BigDecimal("1e12"));
        SUFFIXES.put("B", new BigDecimal("1e9"));
        SUFFIXES.put("M", new BigDecimal("1e6"));
        SUFFIXES.put("K", new BigDecimal("1e3"));
    }

    private NumericText() {
    }

    static ParsedInput parseUserValue(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        Matcher matcher = NUMBER_TOKEN.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        BigDecimal value = parseNumberAndSuffix(matcher.group(1), matcher.group(2));
        if (value == null) {
            return null;
        }
        boolean compact = matcher.group(2) != null && !matcher.group(2).isBlank();
        return new ParsedInput(value, compact);
    }

    static ParsedLineValue parseLineValueAfterTarget(String line, String target) {
        if (line == null || target == null || target.isBlank()) {
            return null;
        }
        int targetStart = indexOfIgnoreCase(line, target);
        if (targetStart < 0) {
            return null;
        }
        int start = skipDecorators(line, targetStart + target.length());
        Matcher matcher = NUMBER_TOKEN.matcher(line);
        matcher.region(start, line.length());
        if (!matcher.find()) {
            return null;
        }

        BigDecimal value = parseNumberAndSuffix(matcher.group(1), matcher.group(2));
        if (value == null) {
            return null;
        }
        String token = line.substring(matcher.start(), matcher.end()).trim();
        return new ParsedLineValue(value, token);
    }

    static String formatDynamic(BigDecimal value, boolean compact) {
        if (value == null) {
            return "";
        }

        // DonutSMP stats are whole-number values. Keep the internal tracker exact,
        // but never render fractional dollars/shards/kills. Compact output truncates
        // downward to tenths (for example, 19.999B -> 19.9B) instead of rounding up.
        BigDecimal wholeValue = value.setScale(0, RoundingMode.DOWN);
        if (!compact) {
            return formatFull(wholeValue);
        }

        BigDecimal absolute = wholeValue.abs();
        Map.Entry<String, BigDecimal> nextLarger = null;
        for (Map.Entry<String, BigDecimal> entry : SUFFIXES.entrySet()) {
            if (absolute.compareTo(entry.getValue()) >= 0) {
                BigDecimal scaled = wholeValue.divide(entry.getValue(), 1, RoundingMode.DOWN);

                // If truncation still produces 1000 of the current suffix, promote it
                // to the next suffix when one exists.
                if (scaled.abs().compareTo(new BigDecimal("1000")) >= 0 && nextLarger != null) {
                    scaled = wholeValue.divide(nextLarger.getValue(), 1, RoundingMode.DOWN);
                    return formatScaledCompact(scaled) + displaySuffix(nextLarger.getKey());
                }

                return formatScaledCompact(scaled) + displaySuffix(entry.getKey());
            }
            nextLarger = entry;
        }
        return formatFull(wholeValue);
    }

    /**
     * Donut-style compact precision: below 100 of a suffix, keep one truncated
     * decimal place; at 100 or above, truncate to a whole number.
     * Examples: 19.999B -> 19.9B, 100.9M -> 100M, 999.9M -> 999M.
     */
    private static String formatScaledCompact(BigDecimal scaled) {
        int scale = scaled.abs().compareTo(new BigDecimal("100")) >= 0 ? 0 : 1;
        return scaled.setScale(scale, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    static String formatRealForGui(BigDecimal value, String originalToken) {
        if (originalToken != null && !originalToken.isBlank()) {
            return originalToken;
        }
        return formatFull(value);
    }

    private static String formatFull(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        DecimalFormat formatter = new DecimalFormat("#,##0.####################", symbols);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(normalized);
    }

    private static BigDecimal parseNumberAndSuffix(String numberText, String suffixText) {
        try {
            BigDecimal base = new BigDecimal(numberText.replace(",", ""));
            String suffix = suffixText == null ? "" : suffixText.trim().toUpperCase(Locale.ROOT);
            BigDecimal resolved;
            if (suffix.isEmpty()) {
                resolved = base;
            } else {
                BigDecimal multiplier = SUFFIXES.get(suffix);
                if (multiplier == null) {
                    return null;
                }
                resolved = base.multiply(multiplier);
            }

            // Server stats represent whole units. Truncate any fractional base-unit input
            // once at parse time so Dynamic never accumulates hidden fractions.
            return resolved.setScale(0, RoundingMode.DOWN);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int skipDecorators(String line, int start) {
        int index = Math.max(0, Math.min(start, line.length()));
        boolean changed;
        do {
            changed = false;
            while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
                index++;
                changed = true;
            }
            if (index < line.length() && isDecorator(line.charAt(index))) {
                index++;
                changed = true;
            }
        } while (changed);
        return index;
    }

    private static boolean isDecorator(char value) {
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

    private static int indexOfIgnoreCase(String text, String search) {
        if (text == null || search == null || search.isEmpty()) {
            return -1;
        }
        return text.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT));
    }

    record ParsedInput(BigDecimal value, boolean compact) {
    }

    record ParsedLineValue(BigDecimal value, String originalToken) {
    }

    private static String displaySuffix(String internal) {
        return switch (internal) {
            case "QI" -> "Qi";
            case "SX" -> "Sx";
            case "SP" -> "Sp";
            case "OC" -> "Oc";
            case "NO" -> "No";
            case "DC" -> "Dc";
            default -> internal;
        };
    }
}
