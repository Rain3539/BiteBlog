package com.biteblog.recommend.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendSearchService {

    private static final String POST_INDEX = "post_index";
    private static final String ITEM_SIM_INDEX = "item_sim_index";

    private final ElasticsearchOperations elasticsearchOperations;

    public List<Long> searchPostIds(String keyword, String city, int size) {
        if (size <= 0 || isBlank(keyword) && isBlank(city)) {
            return List.of();
        }
        try {
            String queryText = buildQueryText(keyword, city);
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm
                                    .fields("title^3", "content", "shopName^2", "store_name^2", "tags")
                                    .query(queryText)))
                            .filter(f -> f.term(t -> t.field("status").value(1)))))
                    .withPageable(PageRequest.of(0, size))
                    .build();

            SearchHits<Map> hits = elasticsearchOperations.search(query, Map.class,
                    IndexCoordinates.of(POST_INDEX));
            return hits.stream()
                    .map(SearchHit::getContent)
                    .map(this::extractPostId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("ES recommend recall failed, fallback to MySQL, keyword={}, city={}, reason={}",
                    keyword, city, e.getMessage());
            return List.of();
        }
    }

    public Map<Long, Double> searchSimilarPostScores(Collection<Long> itemIds, int size) {
        if (itemIds == null || itemIds.isEmpty() || size <= 0) {
            return Map.of();
        }
        try {
            List<FieldValue> values = itemIds.stream()
                    .filter(Objects::nonNull)
                    .map(FieldValue::of)
                    .toList();
            if (values.isEmpty()) {
                return Map.of();
            }

            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .filter(f -> f.terms(t -> t
                                    .field("item_id")
                                    .terms(terms -> terms.value(values))))))
                    .withSort(s -> s.field(f -> f.field("score").order(SortOrder.Desc)))
                    .withPageable(PageRequest.of(0, size))
                    .build();

            SearchHits<Map> hits = elasticsearchOperations.search(query, Map.class,
                    IndexCoordinates.of(ITEM_SIM_INDEX));
            Map<Long, Double> scores = new LinkedHashMap<>();
            for (SearchHit<Map> hit : hits) {
                Map<?, ?> document = hit.getContent();
                Long similarId = toLong(firstNonNull(document.get("similar_item_id"),
                        document.get("similarItemId"), document.get("similar_id")));
                if (similarId == null || itemIds.contains(similarId)) {
                    continue;
                }
                scores.merge(similarId, toDouble(document.get("score")), Double::sum);
            }
            return scores;
        } catch (Exception e) {
            log.warn("ES ItemCF recall failed, fallback to Redis/MySQL, itemIds={}, reason={}",
                    itemIds, e.getMessage());
            return Map.of();
        }
    }

    public int rebuildItemSimilarityIndex(Collection<ItemSimilarityDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        IndexCoordinates index = IndexCoordinates.of(ITEM_SIM_INDEX);
        try {
            elasticsearchOperations.indexOps(index).delete();
        } catch (Exception e) {
            log.debug("Delete old item_sim_index skipped: {}", e.getMessage());
        }

        int saved = 0;
        for (ItemSimilarityDocument document : documents) {
            try {
                Map<String, Object> source = Map.of(
                        "item_id", document.itemId(),
                        "similar_item_id", document.similarItemId(),
                        "score", document.score()
                );
                IndexQuery query = new IndexQueryBuilder()
                        .withId(document.itemId() + "_" + document.similarItemId())
                        .withObject(source)
                        .build();
                elasticsearchOperations.index(query, index);
                saved++;
            } catch (Exception e) {
                log.warn("Save item similarity to ES failed, itemId={}, similarItemId={}, reason={}",
                        document.itemId(), document.similarItemId(), e.getMessage());
            }
        }
        return saved;
    }

    public record ItemSimilarityDocument(Long itemId, Long similarItemId, Double score) {
    }

    private String buildQueryText(String keyword, String city) {
        StringBuilder builder = new StringBuilder();
        if (!isBlank(keyword)) {
            builder.append(keyword.trim());
        }
        if (!isBlank(city)) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(city.trim());
        }
        return builder.toString();
    }

    private Long extractPostId(Map<?, ?> document) {
        Object value = firstNonNull(document.get("postId"), document.get("post_id"), document.get("id"));
        return toLong(value);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 1.0;
            }
        }
        return 1.0;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
