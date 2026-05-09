package com.biteblog.post.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import com.biteblog.post.dto.EsPostDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EsSyncService {

    private final ElasticsearchOperations esOps;

    public void savePostToEs(EsPostDocument doc) {
        esOps.save(doc);
    }

    public void deletePostFromEs(Long noteId) {
        esOps.delete(noteId.toString(), EsPostDocument.class);
    }

    public List<EsPostDocument> search(String keyword, int page, int size) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q
                    .bool(b -> b
                        .must(m -> m
                            .multiMatch(mm -> mm
                                .fields("title", "content", "shopName")
                                .query(keyword)
                                .operator(Operator.Or)
                            )
                        )
                        .filter(f -> f
                            .term(t -> t.field("status").value(1))
                        )
                    )
                )
                .withPageable(PageRequest.of(page - 1, size))
                .build();

        SearchHits<EsPostDocument> hits = esOps.search(query, EsPostDocument.class);
        return hits.stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }

    public long countSearchResults(String keyword) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q
                    .bool(b -> b
                        .must(m -> m
                            .multiMatch(mm -> mm
                                .fields("title", "content", "shopName")
                                .query(keyword)
                                .operator(Operator.Or)
                            )
                        )
                        .filter(f -> f
                            .term(t -> t.field("status").value(1))
                        )
                    )
                )
                .build();

        return esOps.count(query, EsPostDocument.class);
    }
}
