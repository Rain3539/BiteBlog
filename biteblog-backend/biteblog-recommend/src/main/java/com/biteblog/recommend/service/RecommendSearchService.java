package com.biteblog.recommend.service;

import com.biteblog.recommend.entity.Note;
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

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendSearchService {

    private static final String POST_INDEX = "post_index";

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
            log.warn("ES recommend recall failed, keyword={}, city={}, reason={}",
                    keyword, city, e.getMessage());
            throw new IllegalStateException("ES post_index unavailable", e);
        }
    }

    public boolean indexPost(Note note, List<String> imageUrls) {
        if (note == null || note.getId() == null) {
            return false;
        }
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("postId", note.getId().toString());
            source.put("user_id", note.getAuthorId() == null ? null : note.getAuthorId().toString());
            source.put("title", note.getTitle());
            source.put("content", note.getContent());
            source.put("shopName", note.getShopName());
            source.put("store_name", note.getShopName());
            source.put("imageUrls", imageUrls == null ? List.of() : imageUrls);
            source.put("tags", buildTags(note));
            source.put("score_color", note.getScoreColor());
            source.put("score_smell", note.getScoreSmell());
            source.put("score_taste", note.getScoreTaste());
            source.put("like_count", note.getLikeCount());
            source.put("collect_count", note.getCollectCount());
            source.put("comment_count", note.getCommentCount());
            source.put("status", note.getStatus());
            source.put("created_at", note.getCreatedAt() == null ? null : note.getCreatedAt().toString());

            IndexQuery query = new IndexQueryBuilder()
                    .withId(note.getId().toString())
                    .withObject(source)
                    .build();
            elasticsearchOperations.index(query, IndexCoordinates.of(POST_INDEX));
            return true;
        } catch (Exception e) {
            log.warn("Index recommend post to ES failed, noteId={}, reason={}", note.getId(), e.getMessage());
            return false;
        }
    }

    public boolean deletePostFromIndex(Long noteId) {
        if (noteId == null) {
            return false;
        }
        try {
            elasticsearchOperations.delete(noteId.toString(), IndexCoordinates.of(POST_INDEX));
            return true;
        } catch (Exception e) {
            log.warn("Delete recommend post from ES failed, noteId={}, reason={}", noteId, e.getMessage());
            return false;
        }
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

    private List<String> buildTags(Note note) {
        List<String> tags = new ArrayList<>();
        addKeyword(tags, note.getTitle());
        addKeyword(tags, note.getContent());
        addKeyword(tags, note.getShopName());
        addKeyword(tags, note.getAddress());
        return tags;
    }

    private void addKeyword(List<String> tags, String text) {
        if (isBlank(text)) {
            return;
        }
        String normalized = text.trim();
        if (!tags.contains(normalized)) {
            tags.add(normalized);
        }
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
