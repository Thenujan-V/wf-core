package lk.auradot.wms.document.search;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * MySQL FULLTEXT (ngram) over name and metadata, and over the current version's extracted text. A written document
 * appears once it has an approved version.
 */
@Repository
public class MySqlDocumentSearch implements DocumentSearchPort {

    private static final String MATCHING = """
            FROM document d LEFT JOIN document_text t ON t.version_id = d.current_version_id
            WHERE d.folder_id IN (:folders) AND (:type IS NULL OR d.type_id = :type)
              AND (d.kind = 'FILE' OR d.current_version_id IS NOT NULL)
              AND (MATCH(d.search_text) AGAINST (:q IN BOOLEAN MODE) OR MATCH(t.content) AGAINST (:q IN BOOLEAN MODE))""";

    private final JdbcClient jdbc;

    public MySqlDocumentSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SearchPage search(SearchQuery query, Set<String> folderIds) {
        if (folderIds.isEmpty()) {
            return new SearchPage(List.of(), 0);
        }
        long total = jdbc.sql("SELECT COUNT(*) " + MATCHING)
                .param("folders", folderIds).param("type", query.typeId()).param("q", query.booleanQuery())
                .query(Long.class).single();
        List<String> ids = jdbc.sql("SELECT d.id " + MATCHING + """
                         ORDER BY MATCH(d.search_text) AGAINST (:q IN BOOLEAN MODE) * 2
                                  + COALESCE(MATCH(t.content) AGAINST (:q IN BOOLEAN MODE), 0) DESC, d.updated_at DESC, d.id
                         LIMIT :limit OFFSET :offset""")
                .param("folders", folderIds).param("type", query.typeId()).param("q", query.booleanQuery())
                .param("limit", query.size()).param("offset", query.page() * query.size())
                .query(String.class).list();
        return new SearchPage(ids, total);
    }
}
