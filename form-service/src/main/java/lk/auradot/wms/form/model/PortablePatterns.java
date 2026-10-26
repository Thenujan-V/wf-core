package lk.auradot.wms.form.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Field patterns run on the server (Java) and in the browser (JavaScript, as {@code ^(?:pattern)$}). Only syntax that
 * means the same in both is allowed, repeats that can backtrack catastrophically are refused at publish, and the
 * server gives every match a deadline as a last line of defence.
 */
public final class PortablePatterns {

    /** Longest value a patterned field may accept; bounds the work a match can do. */
    public static final int MAX_LENGTH = 1000;

    private static final long BUDGET_NANOS = 100_000_000L;
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private PortablePatterns() {
    }

    /** What would behave differently in JavaScript, or empty when the pattern is portable. */
    public static Optional<String> notPortable(String pattern) {
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            char next = i + 1 < pattern.length() ? pattern.charAt(i + 1) : 0;
            if (c == '\\') {
                if ("AZzhHRGQEXpP".indexOf(next) >= 0 && next != 0) {
                    return Optional.of("\\" + next + " is not supported in browsers");
                }
                i++;
                continue;
            }
            if (inClass) {
                if (c == '&' && next == '&') {
                    return Optional.of("character-class intersection (&&) is not supported in browsers");
                }
                if (c == '[') {
                    return Optional.of("nested character classes are not supported in browsers");
                }
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == '(' && next == '?') {
                char kind = i + 2 < pattern.length() ? pattern.charAt(i + 2) : 0;
                char after = i + 3 < pattern.length() ? pattern.charAt(i + 3) : 0;
                boolean allowed = kind == ':' || kind == '=' || kind == '!'
                        || (kind == '<' && (after == '=' || after == '!' || Character.isLetter(after)));
                if (!allowed) {
                    return Optional.of("(?" + kind + " groups and inline flags are not supported in browsers");
                }
                i++; // the '?' opens the group; it is not a quantifier
            } else if ((c == '*' || c == '+' || c == '?' || c == '}') && next == '+') {
                return Optional.of("possessive repeats (" + c + "+) are not supported in browsers");
            }
        }
        return Optional.empty();
    }

    /**
     * True when a pattern can take exponential time on a short value:
     * <ul>
     *   <li>a group that contains an unbounded repeat ({@code *}, {@code +}, {@code {n,}}) is itself repeated,
     *       e.g. {@code (a+)+} or {@code (.*a){12}};</li>
     *   <li>a group of alternatives is repeated without a bound, e.g. {@code (a|aa)*}.</li>
     * </ul>
     * Deliberately conservative: browsers have no deadline, so doubtful patterns are refused at publish.
     */
    public static boolean unsafe(String pattern) {
        Deque<boolean[]> groups = new ArrayDeque<>(); // per group: [repeats inside, has alternatives]
        groups.push(new boolean[2]);
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (inClass) {
                inClass = c != ']';
                continue;
            }
            switch (c) {
                case '[' -> inClass = true;
                case '(' -> groups.push(new boolean[2]);
                case ')' -> {
                    boolean[] group = groups.size() > 1 ? groups.pop() : new boolean[2];
                    char next = i + 1 < pattern.length() ? pattern.charAt(i + 1) : 0;
                    boolean repeated = next == '*' || next == '+' || next == '{';
                    boolean unbounded = next == '*' || next == '+' || (next == '{' && unboundedBrace(pattern, i + 1));
                    if ((repeated && group[0]) || (unbounded && group[1])) {
                        return true;
                    }
                    if (group[0]) {
                        groups.peek()[0] = true;
                    }
                    if (group[1]) {
                        groups.peek()[1] = true; // ((a|aa)) is as dangerous as (a|aa) when repeated
                    }
                }
                case '|' -> groups.peek()[1] = true;
                case '*', '+' -> groups.peek()[0] = true;
                case '{' -> {
                    if (unboundedBrace(pattern, i)) {
                        groups.peek()[0] = true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** A brace repeat at {@code open} with no upper bound, or one above 10: treated as unbounded. */
    private static boolean unboundedBrace(String pattern, int open) {
        int close = pattern.indexOf('}', open);
        if (close <= open) {
            return false;
        }
        String[] bounds = pattern.substring(open + 1, close).split(",", -1);
        String upper = (bounds.length > 1 ? bounds[1] : bounds[0]).trim();
        if (upper.isEmpty()) {
            return bounds.length > 1; // {n,}
        }
        try {
            return Integer.parseInt(upper) > 10;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Whole-value match; a match that runs past its deadline counts as no match. */
    public static boolean matches(String pattern, String value) {
        Pattern compiled = COMPILED.computeIfAbsent(pattern, Pattern::compile);
        try {
            return compiled.matcher(new Deadline(value, System.nanoTime() + BUDGET_NANOS)).matches();
        } catch (Deadline.Exceeded e) {
            return false;
        }
    }

    /** Lets the regex engine read the value only until the deadline. */
    private static final class Deadline implements CharSequence {

        static final class Exceeded extends RuntimeException {
            Exceeded() {
                super(null, null, false, false);
            }
        }

        private final CharSequence text;
        private final long until;
        private int reads;

        Deadline(CharSequence text, long until) {
            this.text = text;
            this.until = until;
        }

        @Override
        public char charAt(int index) {
            if (++reads % 1024 == 0 && System.nanoTime() > until) {
                throw new Exceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), until);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
