package com.biteblog.recommend.service;

import com.biteblog.recommend.dto.RecommendItemVO;
import com.biteblog.recommend.dto.RecommendResponse;
import com.biteblog.recommend.entity.Note;
import com.biteblog.recommend.entity.UserBehavior;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final String HOT_POOL_KEY = "recommend:hot:pool";
    private static final String EXPOSURE_KEY_PREFIX = "exposure:";
    private static final Duration EXPOSURE_TTL = Duration.ofDays(7);
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MIN_BEHAVIOR_COUNT = 5;
    private static final int RECALL_MULTIPLIER = 4;
    private static final String COLD_START_REASON = "近期热门";
    private static final String TAG_REASON = "兴趣标签相关";
    private static final String ITEM_CF_REASON = "相似用户喜欢";

    private final RecommendDataService recommendDataService;
    private final RedisTemplate<String, Object> redisTemplate;

    public RecommendResponse discover(Long userId, Long cursor, int size, String tag, String city) {
        int safeSize = normalizeSize(size);
        int offset = normalizeCursor(cursor);
        Set<Long> excludedNoteIds = getExposureIds(userId);

        long behaviorCount = recommendDataService.countUserBehaviors(userId);
        if (behaviorCount < MIN_BEHAVIOR_COUNT && isBlank(tag)) {
            return coldStart(offset, safeSize, excludedNoteIds);
        }

        List<RecommendItemVO> ranked = hybridRecommend(userId, tag, city, safeSize, offset, excludedNoteIds);
        if (ranked.isEmpty()) {
            return coldStart(offset, safeSize, excludedNoteIds);
        }
        boolean hasMore = ranked.size() > safeSize;
        List<RecommendItemVO> page = ranked.stream().limit(safeSize).toList();
        return new RecommendResponse(page, hasMore ? (long) offset + page.size() : null, hasMore);
    }

    public Map<String, Object> saveExposures(Long userId, Collection<Long> postIds) {
        List<Long> ids = distinctIds(postIds).stream()
                .limit(100)
                .toList();
        if (userId == null || ids.isEmpty()) {
            return Map.of("saved", false, "count", 0);
        }
        try {
            String key = exposureKey(userId);
            redisTemplate.opsForSet().add(key, ids.toArray());
            redisTemplate.expire(key, EXPOSURE_TTL);
            return Map.of("saved", true, "count", ids.size());
        } catch (Exception e) {
            log.warn("Save recommend exposures failed, userId={}, count={}, reason={}", userId, ids.size(), e.getMessage());
            return Map.of("saved", false, "count", ids.size());
        }
    }

    private List<RecommendItemVO> hybridRecommend(Long userId, String tag, String city, int size, int offset, Set<Long> excludedNoteIds) {
        int recallSize = (offset + size + 1) * RECALL_MULTIPLIER;
        Map<Long, Candidate> candidates = new LinkedHashMap<>();

        recallByTag(tag, city, recallSize, excludedNoteIds, candidates);
        recallByItemCf(userId, recallSize, excludedNoteIds, candidates);
        supplementByHot(recallSize, excludedNoteIds, candidates);

        List<Candidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::finalScore).reversed()
                        .thenComparing(candidate -> candidate.note.getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .skip(offset)
                .limit(size + 1L)
                .toList();
        return toItems(ranked);
    }

    private void recallByTag(String tag, String city, int recallSize, Set<Long> excludedNoteIds, Map<Long, Candidate> candidates) {
        if (isBlank(tag) && isBlank(city)) {
            return;
        }
        List<Note> notes = recommendDataService.searchNormalNotes(tag, city, recallSize, excludedNoteIds);
        for (Note note : notes) {
            Candidate candidate = candidates.computeIfAbsent(note.getId(), id -> new Candidate(note));
            candidate.tagScore = Math.max(candidate.tagScore, calculateScore(note));
            candidate.reason = TAG_REASON;
        }
    }

    private void recallByItemCf(Long userId, int recallSize, Set<Long> excludedNoteIds, Map<Long, Candidate> candidates) {
        List<UserBehavior> ownBehaviors = recommendDataService.listRecentUserBehaviors(userId, 50);
        Set<Long> interactedNoteIds = ownBehaviors.stream()
                .map(UserBehavior::getNoteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (interactedNoteIds.isEmpty()) {
            return;
        }

        Set<Long> neighborUserIds = recommendDataService.listBehaviorsByNoteIds(interactedNoteIds, 200)
                .stream()
                .map(UserBehavior::getUserId)
                .filter(id -> id != null && !id.equals(userId))
                .collect(Collectors.toSet());
        if (neighborUserIds.isEmpty()) {
            return;
        }

        Map<Long, Double> itemCfScores = new HashMap<>();
        for (UserBehavior behavior : recommendDataService.listBehaviorsByUserIds(neighborUserIds, 400)) {
            Long noteId = behavior.getNoteId();
            if (noteId == null || interactedNoteIds.contains(noteId) || excludedNoteIds.contains(noteId)) {
                continue;
            }
            itemCfScores.merge(noteId, behaviorWeight(behavior), Double::sum);
        }
        if (itemCfScores.isEmpty()) {
            return;
        }

        List<Long> orderedIds = itemCfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(recallSize)
                .map(Map.Entry::getKey)
                .toList();
        Map<Long, Note> noteMap = recommendDataService.getNormalNotesByIds(orderedIds);
        for (Long noteId : orderedIds) {
            Note note = noteMap.get(noteId);
            if (note == null) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(noteId, id -> new Candidate(note));
            candidate.itemCfScore = Math.max(candidate.itemCfScore, itemCfScores.getOrDefault(noteId, 0.0));
            if (candidate.reason == null) {
                candidate.reason = ITEM_CF_REASON;
            }
        }
    }

    private void supplementByHot(int recallSize, Set<Long> excludedNoteIds, Map<Long, Candidate> candidates) {
        if (candidates.size() >= recallSize) {
            return;
        }
        Set<Long> excluded = new HashSet<>(excludedNoteIds);
        excluded.addAll(candidates.keySet());
        for (Note note : recommendDataService.listHotNotes(0, recallSize - candidates.size(), excluded)) {
            Candidate candidate = candidates.computeIfAbsent(note.getId(), id -> new Candidate(note));
            candidate.hotScore = Math.max(candidate.hotScore, calculateScore(note));
            if (candidate.reason == null) {
                candidate.reason = COLD_START_REASON;
            }
        }
    }

    private RecommendResponse coldStart(int offset, int size, Set<Long> excludedNoteIds) {
        RecommendResponse fromRedis = coldStartFromRedis(offset, size, excludedNoteIds);
        if (fromRedis != null && !fromRedis.getList().isEmpty()) {
            return fromRedis;
        }
        return coldStartFromMysql(offset, size, excludedNoteIds);
    }

    private RecommendResponse coldStartFromRedis(int offset, int size, Set<Long> excludedNoteIds) {
        try {
            long start = offset;
            long end = offset + (long) size * RECALL_MULTIPLIER;
            Set<ZSetOperations.TypedTuple<Object>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(HOT_POOL_KEY, start, end);
            Long total = redisTemplate.opsForZSet().zCard(HOT_POOL_KEY);
            if (tuples == null || tuples.isEmpty()) {
                return null;
            }

            List<Long> noteIds = tuples.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .map(this::toLong)
                    .filter(id -> id != null && !excludedNoteIds.contains(id))
                    .distinct()
                    .toList();
            Map<Long, Note> noteMap = recommendDataService.getNormalNotesByIds(noteIds);
            Map<Long, String> coverMap = recommendDataService.getCoverUrls(noteIds);

            List<RecommendItemVO> items = new ArrayList<>();
            for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                Long noteId = toLong(tuple.getValue());
                if (noteId == null || excludedNoteIds.contains(noteId)) {
                    continue;
                }
                Note note = noteMap.get(noteId);
                if (note == null) {
                    continue;
                }
                items.add(toItem(note, coverMap.get(noteId),
                        tuple.getScore() == null ? calculateScore(note) : tuple.getScore(),
                        COLD_START_REASON));
                if (items.size() >= size) {
                    break;
                }
            }

            long safeTotal = total == null ? offset + items.size() : total;
            return new RecommendResponse(items, nextCursor(offset, items, safeTotal), offset + items.size() < safeTotal);
        } catch (Exception e) {
            log.warn("Recommend hot pool unavailable, fallback to MySQL: {}", e.getMessage());
            return null;
        }
    }

    private RecommendResponse coldStartFromMysql(int offset, int size, Set<Long> excludedNoteIds) {
        List<Note> notes = recommendDataService.listHotNotes(offset, size + 1, excludedNoteIds);
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

    private List<RecommendItemVO> toItems(List<Candidate> candidates) {
        List<Long> noteIds = candidates.stream().map(candidate -> candidate.note.getId()).toList();
        Map<Long, String> coverMap = recommendDataService.getCoverUrls(noteIds);
        return candidates.stream()
                .map(candidate -> toItem(candidate.note, coverMap.get(candidate.note.getId()),
                        candidate.finalScore(), candidate.reason == null ? COLD_START_REASON : candidate.reason))
                .toList();
    }

    private Set<Long> getExposureIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        try {
            Set<Object> members = redisTemplate.opsForSet().members(exposureKey(userId));
            if (members == null || members.isEmpty()) {
                return Set.of();
            }
            return members.stream()
                    .map(this::toLong)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Read recommend exposures failed, userId={}, reason={}", userId, e.getMessage());
            return Set.of();
        }
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

    private double behaviorWeight(UserBehavior behavior) {
        if (behavior.getWeight() != null && behavior.getWeight() > 0) {
            return behavior.getWeight();
        }
        String type = behavior.getBehaviorType();
        if ("comment".equalsIgnoreCase(type)) {
            return 10;
        }
        if ("collect".equalsIgnoreCase(type) || "favorite".equalsIgnoreCase(type)) {
            return 8;
        }
        if ("like".equalsIgnoreCase(type)) {
            return 5;
        }
        if ("dwell".equalsIgnoreCase(type)) {
            return 3;
        }
        return 1;
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

    private String exposureKey(Long userId) {
        return EXPOSURE_KEY_PREFIX + userId;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

    private static class Candidate {
        private final Note note;
        private double tagScore;
        private double itemCfScore;
        private double hotScore;
        private String reason;

        private Candidate(Note note) {
            this.note = note;
        }

        private double finalScore() {
            return tagScore * 0.6 + itemCfScore * 0.4 + hotScore;
        }
    }
}
