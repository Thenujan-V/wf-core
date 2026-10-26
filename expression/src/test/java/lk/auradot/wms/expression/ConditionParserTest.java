package lk.auradot.wms.expression;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionParserTest {

    @Test
    void parsesTheSpecExample() {
        Object json = Map.of("all", List.of(
                Map.of("var", "amount", "op", "gt", "value", 10000),
                Map.of("any", List.of(
                        Map.of("var", "department", "op", "eq", "value", "Finance"),
                        Map.of("var", "priority", "op", "in", "value", List.of("HIGH", "URGENT"))))));

        Condition condition = ConditionParser.parse(json);

        assertThat(condition).isInstanceOf(Condition.All.class);
        Condition.All all = (Condition.All) condition;
        assertThat(all.conditions().getFirst())
                .isEqualTo(new Condition.Compare("amount", Operator.GT, new BigDecimal("10000")));
        assertThat(all.conditions().get(1)).isInstanceOf(Condition.Any.class);
    }

    @Test
    void normalisesNumbersInsideLists() {
        Condition condition = ConditionParser.parse(Map.of("var", "n", "op", "in", "value", List.of(1, 2.5)));

        assertThat(((Condition.Compare) condition).value())
                .isEqualTo(List.of(new BigDecimal("1"), new BigDecimal("2.5")));
    }

    @Test
    void reportsWhereTheProblemIs() {
        Object json = Map.of("all", List.of(Map.of("var", "a", "op", "bogus", "value", 1)));

        assertThatThrownBy(() -> ConditionParser.parse(json))
                .isInstanceOf(ConditionParseException.class)
                .hasMessageContaining("$.all[0].op")
                .hasMessageContaining("bogus");
    }

    @Test
    void rejectsExcessiveNesting() {
        Object json = Map.of("var", "a", "op", "isEmpty");
        for (int i = 0; i < 40; i++) {
            json = Map.of("not", json);
        }
        Object deep = json;

        assertThatThrownBy(() -> ConditionParser.parse(deep)).isInstanceOf(ConditionParseException.class)
                .hasMessageContaining("nested too deeply");
    }

    @Test
    void allowsExplicitNullLiteral() {
        var map = new java.util.HashMap<String, Object>();
        map.put("var", "a");
        map.put("op", "eq");
        map.put("value", null);

        assertThat(ConditionParser.parse(map)).isEqualTo(new Condition.Compare("a", Operator.EQ, null));
    }

    @Test
    void copiesListsDefensively() {
        List<Object> items = new ArrayList<>(List.of(Map.of("var", "a", "op", "isEmpty")));
        Condition.All all = (Condition.All) ConditionParser.parse(Map.of("all", items));
        items.clear();

        assertThat(all.conditions()).hasSize(1);
    }
}
