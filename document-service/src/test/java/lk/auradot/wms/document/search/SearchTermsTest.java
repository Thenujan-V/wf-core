package lk.auradot.wms.document.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTermsTest {

    @Test
    void requiresEveryWordAsAPhrase() {
        assertThat(SearchTerms.booleanQuery("  Budget   review ")).contains("+\"Budget\" +\"review\"");
        assertThat(SearchTerms.booleanQuery("Relatório 2026")).contains("+\"Relatório\" +\"2026\"");
    }

    @Test
    void operatorsAndQuotesCannotReachMySql() {
        assertThat(SearchTerms.booleanQuery("inv+oice (draft) \"x")).contains("+\"inv\" +\"oice\" +\"draft\"");
        assertThat(SearchTerms.booleanQuery("a*b @c ~d <e> -f")).isEmpty();
    }

    @Test
    void nothingSearchableLeftMeansNoQuery() {
        assertThat(SearchTerms.booleanQuery(null)).isEmpty();
        assertThat(SearchTerms.booleanQuery("")).isEmpty();
        assertThat(SearchTerms.booleanQuery("a")).isEmpty();
        assertThat(SearchTerms.booleanQuery("%% -- ++")).isEmpty();
    }

    @Test
    void keepsAtMostTenDistinctWords() {
        assertThat(SearchTerms.booleanQuery("aa bb cc dd ee ff gg hh ii jj kk ll aa").orElseThrow().split(" ")).hasSize(10);
    }

    @Test
    void keepsWordsOfScriptsWithVowelSignsWhole() {
        assertThat(SearchTerms.booleanQuery("ලංකාව")).contains("+\"ලංකාව\"");
        assertThat(SearchTerms.booleanQuery("சென்னை அலுவலகம்")).contains("+\"சென்னை\" +\"அலுவலகம்\"");
        assertThat(SearchTerms.booleanQuery("ශ්‍රී")).contains("+\"ශ්‍රී\"");
    }
}
