package com.biteblog.recommend.service;

import com.biteblog.recommend.dto.RecommendItemVO;
import com.biteblog.recommend.dto.RecommendResponse;
import com.biteblog.recommend.entity.Note;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final String HOT_POOL_KEY = "recommend:hot:pool";
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MIN_BEHAVIOR_COUNT = 5;
    private static final String COLD_START_REASON = "近期热门";

    private final RecommendDataService recommendDataService;
    private final RedisTemplate<String, Object> redisTemplate;

    public RecommendResponse discover(Long userId, Long cursor, int size) {
        int safeSize = normalizeSize(size);
        int offset = normalizeCursor(cursor);

        long behaviorCount = recommendDataService.countUserBehaviors(userId);
        if (behaviorCount < MIN_BEHAVIOR_COUNT) {
            return coldStart(offset, safeSize);
        }

        // Tag recall and ItemCF will be added in later steps. Until then, keep the API useful.
        return coldStart(offset, safeSize);
    }

    private RecommendResponse coldStart(int offset, int size) {
        RecommendResponse fromRedis = coldStartFromRedis(offset, size);
        if (fromRedis != null && !fromRedis.getList().isEmpty()) {
            return fromRedis;
        }
        return coldStartFromMysql(offset, size);
    }

    private RecommendResponse coldStartFromRedis(int offset, int size) {
        try {
            long start = offset;
            long end = offset + size - 1L;
            Set<ZSetOperations.TypedTuple<Object>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(HOT_POOL_KEY, start, end);
            Long total = redisTemplate.opsForZSet().zCard(HOT_POOL_KEY);
            if (tuples == null || tuples.isEmpty()) {
                return null;
            }

            List<Long> noteIds = tuples.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .map(this::toLong)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<Long, Note> noteMap = recommendDataService.getNormalNotesByIds(noteIds);
            Map<Long, String> coverMap = recommendDataService.getCoverUrls(noteIds);

            List<RecommendItemVO> items = new ArrayList<>();
            for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                Long noteId = toLong(tuple.getValue());
                if (noteId == null) {
                    continue;
                }
                Note note = noteMap.get(noteId);
                if (note == null) {
                    continue;
                }
                items.add(toItem(note, coverMap.get(noteId),
                        tuple.getScore() == null ? calculateScore(note) : tuple.getScore(),
                        COLD_START_REASON));
            }

            long safeTotal = total == null ? offset + items.size() : total;
            return new RecommendResponse(items, nextCursor(offset, items, safeTotal), offset + items.size() < safeTotal);
        } catch (Exception e) {
            log.warn("Recommend hot pool unavailable, fallback to MySQL: {}", e.getMessage());
            return null;
        }
    }

    private RecommendResponse coldStartFromMysql(int offset, int size) {
        List<Note> notes = recommendDataService.listHotNotes(offset, size + 1);
        boolean hasMore = notes.size() > size;
        List<Note> pageNotes = notes.stream()
                .limit(size)
                .sorted(Comparator.comparingDouble(this::calculateScore).reversed())
                .toList();
        Map<Long, String> coverMap = recommendDataService.getCoverUrls(pageNotes.stream().map(Note::getId).toList());

        List<RecommendItemVO> items = pageNotes.stream()
                .map(note -> toItem(note, coverMap.get(note.getId()), calculateScore(note), COLD_START_REASON))
                .toList();
        return new RecommendResponse(items, nextCursor(offset, items, offset + notes.size()), hasMore);
    }

    private RecommendItemVO toItem(Note note, String coverUrl, Double score, String reason) {
        RecommendItemVO item = new RecommendItemVO();
        item.setPostId(note.getId());
        item.setAuthorId(note.getAuthorId());
        item.setTitle(note.getTitle());
        item.setCoverUrl(coverUrl);
        item.setShopName(note.getShopName());
        item.setTags(List.of());
        item.setLikeCount(toLong(defaultZero(note.getLikeCount())));
        item.setCollectCount(toLong(defaultZero(note.getCollectCount())));
        item.setCommentCount(toLong(defaultZero(note.getCommentCount())));
        item.setScore(score);
        item.setReason(reason);
        item.setCreatedAt(note.getCreatedAt());
        return item;
    }

    private Double calculateScore(Note note) {
        int like = defaultZero(note.getLikeCount());
        int collect = defaultZero(note.getCollectCount());
        int comment = defaultZero(note.getCommentCount());
        int taste = defaultZero(note.getScoreTaste());
        int smell = defaultZero(note.getScoreSmell());
        int color = defaultZero(note.getScoreColor());
        double quality = (taste + smell + color) / 3.0;
        double base = like * 3.0 + collect * 5.0 + comment * 4.0 + quality;
        if (note.getCreatedAt() == null) {
            return base;
        }
        long hours = Math.max(1, Duration.between(note.getCreatedAt(), LocalDateTime.now()).toHours());
        return base + 24.0 / Math.sqrt(hours);
    }

    private Long nextCursor(int offset, Collection<?> items, long total) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        long next = offset + items.size();
        return next < total ? next : null;
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private int normalizeCursor(Long cursor) {
        if (cursor == null || cursor < 0) {
            return 0;
        }
        return Math.toIntExact(Math.min(cursor, Integer.MAX_VALUE));
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

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
