package lk.auradot.wms.expression;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Value rules shared by the parser, the evaluator and the workflow engine. */
public final class Values {

    private Values() {
    }

    /** Resolves a dotted path through nested maps; anything missing or not a map on the way yields null. */
    public static Object resolve(Map<String, ?> variables, String path) {
        Object current = variables;
        for (String segment : path.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return normalize(current);
    }

    /** Numbers become BigDecimal (non-finite doubles become null); lists are normalised element-wise. */
    public static Object normalize(Object value) {
        if (value instanceof Number number) {
            return toBigDecimal(number);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Values::normalize).toList();
        }
        return value;
    }

    public static boolean equalValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if ((a instanceof String && b instanceof String) || (a instanceof Boolean && b instanceof Boolean)) {
            return a.equals(b);
        }
        return false;
    }

    /** Both values are numbers, both strings, or both booleans. */
    public static boolean sameKind(Object a, Object b) {
        return (a instanceof BigDecimal && b instanceof BigDecimal)
                || (a instanceof String && b instanceof String)
                || (a instanceof Boolean && b instanceof Boolean);
    }

    /** ISO date (midnight UTC) or ISO date-time with offset; anything else is empty. */
    public static Optional<Instant> toInstant(Object value) {
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException notADate) {
            try {
                return Optional.of(OffsetDateTime.parse(text).toInstant());
            } catch (DateTimeParseException notADateTime) {
                return Optional.empty();
            }
        }
    }

    /** null, blank text, and empty lists or maps are empty. */
    public static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String text && isBlank(text))
                || (value instanceof Collection<?> collection && collection.isEmpty())
                || (value instanceof Map<?, ?> map && map.isEmpty());
    }

    /**
     * Blank means only JavaScript white space (what {@code String.prototype.trim} removes), so the browser and
     * the server agree: tab, line breaks, vertical tab, form feed, every space separator including the
     * non-breaking space, U+2028/U+2029 and the byte-order mark.
     */
    private static boolean isBlank(String text) {
        return text.chars().allMatch(c -> (c >= 0x09 && c <= 0x0D) || c == 0xFEFF || c == 0x2028 || c == 0x2029
                || Character.getType(c) == Character.SPACE_SEPARATOR);
    }

    private static BigDecimal toBigDecimal(Number number) {
        return switch (number) {
            case BigDecimal decimal -> decimal;
            case BigInteger integer -> new BigDecimal(integer);
            case Double d when d.isNaN() || d.isInfinite() -> null;
            case Float f when f.isNaN() || f.isInfinite() -> null;
            case Double d -> new BigDecimal(d.toString());
            case Float f -> new BigDecimal(f.toString());
            default -> BigDecimal.valueOf(number.longValue());
        };
    }
}
