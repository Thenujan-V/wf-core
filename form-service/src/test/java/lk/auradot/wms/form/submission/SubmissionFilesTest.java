package lk.auradot.wms.form.submission;

import lk.auradot.wms.form.FakeFiles;
import lk.auradot.wms.form.FormIntegrationTest;
import lk.auradot.wms.form.TestDb;
import lk.auradot.wms.form.TestForms;
import lk.auradot.wms.form.TestJwt;
import lk.auradot.wms.form.definition.FormStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §2.2 "form → document (verify file references)": files are checked before a submission is stored. */
@FormIntegrationTest
class SubmissionFilesTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired FormStore forms;
    @Autowired FakeFiles files;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        files.reset();
        forms.saveDraft("receipt-claim", "Receipt claim", TestForms.load("receipt-claim.json"), "bob");
        forms.publish("receipt-claim", "bob");
    }

    static Map<String, Object> ref(String documentId, String name) {
        return Map.of("documentId", documentId, "name", name);
    }

    ResultActions submit(Map<String, Object> data, Map<String, Object> access, Map<String, Object> baseline) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("formKey", "receipt-claim");
        request.put("version", 1);
        request.put("data", data);
        request.put("access", access);
        request.put("baseline", baseline);
        request.put("submittedBy", "carol");
        request.put("contextRef", "start:receipt-review");
        return mvc.perform(post("/submissions").with(TestJwt.user("service-account-wms-workflow", "wms-service"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)));
    }

    int stored() {
        return jdbc.sql("SELECT COUNT(*) FROM form_submission").query(Integer.class).single();
    }

    @Test
    void theSubmittersStagedFilesAreStoredUnderTheirRealNames() throws Exception {
        files.stage("carol", "d1", "Taxi receipt.pdf");

        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1", "renamed-in-browser.pdf"))), Map.of(), Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipts[0].documentId").value("d1"))
                .andExpect(jsonPath("$.data.receipts[0].name").value("Taxi receipt.pdf"));
        assertThat(files.asked()).containsExactly(List.of("d1"));
    }

    @Test
    void aFileThatIsNotTheSubmittersIsAFieldErrorAndNothingIsStored() throws Exception {
        files.stage("bob", "d2", "Someone else's.pdf");

        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d2", "x.pdf"))), Map.of(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("receipts"))
                .andExpect(jsonPath("$.errors[0].code").value("FILE_NOT_AVAILABLE"));
        assertThat(stored()).isZero();
    }

    @Test
    void filesAlreadyOnTheRequestAreNotCheckedAgain() throws Exception {
        List<Map<String, Object>> earlier = List.of(ref("d1", "Taxi receipt.pdf"));
        files.stage("carol", "d3", "Hotel.pdf");

        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1", "Taxi receipt.pdf"))), Map.of(),
                Map.of("purpose", "Taxi", "receipts", earlier))
                .andExpect(status().isCreated());
        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1", "Taxi receipt.pdf"), ref("d3", "h.pdf"))), Map.of(),
                Map.of("purpose", "Taxi", "receipts", earlier))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipts[1].name").value("Hotel.pdf"));
        submit(Map.of("purpose", "Other"), Map.of("receipts", "READONLY"), Map.of("purpose", "Taxi", "receipts", earlier))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipts[0].documentId").value("d1"));

        assertThat(files.asked()).containsExactly(List.of("d3"));
    }

    @Test
    void whenDocumentServiceCannotAnswerNothingIsStored() throws Exception {
        files.fail();

        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1", "a.pdf"))), Map.of(), Map.of())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FORM_FILES_UNAVAILABLE"));
        assertThat(stored()).isZero();
    }

    @Test
    void aFileAlreadyOnTheRequestKeepsItsRecordedName() throws Exception {
        List<Map<String, Object>> earlier = List.of(ref("d1", "Taxi receipt.pdf"));

        submit(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1", "Approved-by-CFO.pdf"))), Map.of(),
                Map.of("purpose", "Taxi", "receipts", earlier))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipts[0].name").value("Taxi receipt.pdf"));
        assertThat(files.asked()).isEmpty();
    }
}
