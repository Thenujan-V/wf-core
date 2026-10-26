package lk.auradot.wms.document.search;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns what a person typed into a MySQL boolean-mode query that requires every word. Only letters, combining marks
 * (the vowel signs and virama of Sinhala and Tamil), zero-width joiners and digits
 * survive, so no operator, quote or bracket from the input can change the query's meaning or break its syntax.
 */
public final class SearchTerms {

    static final int MAX_TERMS = 10;
    static final int MAX_TERM_LENGTH = 100;

    private SearchTerms() {
    }

    public static Optional<String> booleanQuery(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String query = Arrays.stream(raw.split("[^\\p{L}\\p{M}\\p{N}\\u200C\\u200D]+"))
                .filter(term -> term.codePointCount(0, term.length()) >= 2 && term.length() <= MAX_TERM_LENGTH)
                .distinct()
                .limit(MAX_TERMS)
                .map(term -> "+\"" + term + "\"")
                .collect(Collectors.joining(" "));
        return query.isEmpty() ? Optional.empty() : Optional.of(query);
    }
}
