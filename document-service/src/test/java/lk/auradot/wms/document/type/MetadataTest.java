package lk.auradot.wms.document.type;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeForms;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.forms.FormRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DocumentIntegrationTest
class MetadataTest {

    @Autowired JdbcClient jdbc;
    @Autowired FakeForms forms;
    @Autowired Metadata metadata;

    final Caller carol = new Caller("carol", Set.of("user"), Set.of(), "t");

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        forms.reset();
        forms.publish(new FormRef("invoice-meta", 1), Set.of("supplier", "amount"), Set.of("supplier"));
        jdbc.sql("""
                        INSERT INTO document_type (id, name, form_key, form_version, created_at) VALUES
                        ('t-invoice', 'Invoice', 'invoice-meta', 1, NOW(6)), ('t-plain', 'Plain', NULL, NULL, NOW(6))""")
                .update();
    }

    @Test
    void validatesMetadataAgainstTheTypesForm() {
        assertThat(metadata.check("t-invoice", Map.of("supplier", "Acme", "amount", 12), carol))
                .containsEntry("supplier", "Acme");
        assertThatThrownBy(() -> metadata.check("t-invoice", Map.of("amount", 12), carol))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_METADATA_INVALID"));
    }

    @Test
    void metadataNeedsATypeWithAForm() {
        assertThat(metadata.check(null, null, carol)).isEmpty();
        assertThat(metadata.check("t-plain", Map.of(), carol)).isEmpty();
        assertThatThrownBy(() -> metadata.check(null, Map.of("x", 1), carol))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_METADATA_WITHOUT_TYPE"));
        assertThatThrownBy(() -> metadata.check("t-plain", Map.of("x", 1), carol))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_METADATA_WITHOUT_TYPE"));
        assertThatThrownBy(() -> metadata.check("t-nope", Map.of(), carol))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_TYPE_NOT_FOUND"));
    }

    @Test
    void searchTextHoldsTheNameAndEveryMetadataValue() {
        String text = Metadata.searchText("Invoice March.pdf",
                Map.of("supplier", "Acme Ltd", "amount", 1250.5, "lines", List.of(Map.of("item", "Paper")), "paid", true));

        assertThat(text).startsWith("Invoice March.pdf").contains("Acme Ltd", "1250.5", "Paper", "true");
    }
}
