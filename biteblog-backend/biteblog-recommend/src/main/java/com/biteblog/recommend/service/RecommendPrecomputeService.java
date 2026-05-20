package com.biteblog.recommend.service;

import com.biteblog.recommend.entity.Note;
import com.biteblog.recommend.entity.UserBehavior;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendPrecomputeService {

    private static final String HOT_POOL_KEY = "recommend:hot:pool";
    private static final String RANK_DAILY_KEY_PREFIX = "rank:daily:";
    private static final String BEHAVIOR_KEY_PREFIX = "behavior:";
    private static final String ITEM_SIM_KEY_PREFIX = "recommend:itemcf:similar:";
    private static final int MAX_BEHAVIOR_SCAN = 2000;
    private static final int MAX_HOT_POOL_SIZE = 200;
    private static final int MAX_ITEMS_PER_USER = 30;
    private static final int MAX_SIMILAR_PER_ITEM = 20;

    private final RecommendDataService recommendDataService;
    private final RecommendSearchService recommendSearchService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(initialDelay = 60_000, fixedDelay = 10 * 60_000)
    public void scheduledPrecompute() {
        try {
            precompute();
        } catch (Exception e) {
            log.warn("Scheduled recommend precompute failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> precompute() {
        long started = System.currentTimeMillis();
        int hotCount = rebuildHotPool();
        int itemSimCount = rebuildItemSimilarities();
        long elapsed = System.currentTimeMillis() - started;
        return Map.of(
                "hotPoolCount", hotCount,
                "itemSimilarityCount", itemSimCount,
                "elapsedMs", elapsed
        );
    }

    public Map<String, Object> refreshNote(Long noteId) {
        long started = System.currentTimeMillis();
        if (noteId == null) {
            return Map.of(
                    "updated", false,
                    "reason", "missing noteId",
                    "elapsedMs", System.currentTimeMillis() - started
            );
        }
        Note note = recommendDataService.getNormalNoteById(noteId);
        if (note == null) {
            return removeNote(noteId);
        }

        List<String> coverUrls = new ArrayList<>(recommendDataService.getCoverUrls(List.of(noteId)).values());
        boolean esSaved = recommendSearchService.indexPost(note, coverUrls);
        double score = calculateHotScore(note);
        redisTemplate.opsForZSet().add(HOT_POOL_KEY, note.getId(), score);
        if (shouldJoinDailyRank(note)) {
            redisTemplate.opsForZSet().add(rankDailyKey(), note.getId().toString(), score);
        }
        return Map.of(
                "noteId", noteId,
                "esPostIndexed", esSaved,
                "hotPoolUpdated", true,
                "elapsedMs", System.currentTimeMillis() - started
        );
    }

    public Map<String, Object> removeNote(Long noteId) {
        long started = System.currentTimeMillis();
        if (noteId == null) {
            return Map.of(
                    "removed", false,
                    "reason", "missing noteId",
                    "elapsedMs", System.currentTimeMillis() - started
            );
        }
        boolean esDeleted = recommendSearchService.deletePostFromIndex(noteId);
        Long removed = redisTemplate.opsForZSet().remove(HOT_POOL_KEY, noteId);
        Long rankRemoved = redisTemplate.opsForZSet().remove(rankDailyKey(), noteId.toString(), noteId);
        int itemSimCount = rebuildItemSimilarities();
        return Map.of(
                "noteId", noteId,
                "esPostDeleted", esDeleted,
                "hotPoolRemoved", removed == null ? 0L : removed,
                "rankDailyRemoved", rankRemoved == null ? 0L : rankRemoved,
                "itemSimilarityCount", itemSimCount,
                "elapsedMs", System.currentTimeMillis() - started
        );
    }

    public Map<String, Object> refreshAfterInteraction(Long noteId) {
        long started = System.currentTimeMillis();
        Map<String, Object> noteResult = refreshNote(noteId);
        clearBehaviorCache();
        int itemSimCount = rebuildItemSimilarities();
        return Map.of(
                "noteId", noteId,
                "noteRefresh", noteResult,
                "itemSimilarityCount", itemSimCount,
                "elapsedMs", System.currentTimeMillis() - started
        );
    }

    private int rebuildHotPool() {
        List<Note> notes = recommendDataService.listNormalNotesForPrecompute(MAX_HOT_POOL_SIZE);
        redisTemplate.delete(HOT_POOL_KEY);
        String rankKey = rankDailyKey();
        redisTemplate.delete(rankKey);
        int saved = 0;
        for (Note note : notes) {
            double score = calculateHotScore(note);
            redisTemplate.opsForZSet().add(HOT_POOL_KEY, note.getId(), score);
            if (shouldJoinDailyRank(note)) {
                redisTemplate.opsForZSet().add(rankKey, note.getId().toString(), score);
            }
            saved++;
        }
        return saved;
    }

    private int rebuildItemSimilarities() {
        List<UserBehavior> behaviors = recommendDataService.listRecentBehaviorsForPrecompute(MAX_BEHAVIOR_SCAN);
        Map<Long, Map<Long, Double>> userItemWeights = new HashMap<>();
        for (UserBehavior behavior : behaviors) {
            if (behavior.getUserId() == null || behavior.getNoteId() == null) {
                continue;
            }
            userItemWeights
                    .computeIfAbsent(behavior.getUserId(), id -> new LinkedHashMap<>())
                    .merge(behavior.getNoteId(), behaviorWeight(behavior), Math::max);
        }

        Map<Long, Map<Long, Double>> itemSimilarities = new HashMap<>();
        for (Map<Long, Double> itemWeights : userItemWeights.values()) {
            List<Map.Entry<Long, Double>> items = itemWeights.entrySet().stream()
                    .limit(MAX_ITEMS_PER_USER)
                    .toList();
            for (int i = 0; i < items.size(); i++) {
                for (int j = 0; j < items.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    Long itemId = items.get(i).getKey();
                    Long similarItemId = items.get(j).getKey();
                    double score = Math.sqrt(items.get(i).getValue() * items.get(j).getValue());
                    itemSimilarities
                            .computeIfAbsent(itemId, id -> new HashMap<>())
                            .merge(similarItemId, score, Double::sum);
                }
            }
        }

        Map<Long, List<Map.Entry<Long, Double>>> topSimilarities = new HashMap<>();
        for (Map.Entry<Long, Map<Long, Double>> entry : itemSimilarities.entrySet()) {
            List<Map.Entry<Long, Double>> topItems = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(MAX_SIMILAR_PER_ITEM)
                    .map(similar -> Map.entry(similar.getKey(), roundScore(similar.getValue())))
                    .toList();
            if (!topItems.isEmpty()) {
                topSimilarities.put(entry.getKey(), topItems);
            }
        }
        return saveItemSimilaritiesToRedis(topSimilarities);
    }

    private int saveItemSimilaritiesToRedis(Map<Long, List<Map.Entry<Long, Double>>> topSimilarities) {
        int saved = 0;
        try {
            Set<String> oldKeys = redisTemplate.keys(ITEM_SIM_KEY_PREFIX + "*");
            if (oldKeys != null && !oldKeys.isEmpty()) {
                redisTemplate.delete(oldKeys);
            }
        } catch (Exception e) {
            log.warn("Clean old Redis ItemCF similarities failed: {}", e.getMessage());
        }

        for (Map.Entry<Long, List<Map.Entry<Long, Double>>> entry : topSimilarities.entrySet()) {
            String key = ITEM_SIM_KEY_PREFIX + entry.getKey();
            for (Map.Entry<Long, Double> similar : entry.getValue()) {
                redisTemplate.opsForZSet().add(key, similar.getKey(), similar.getValue());
                saved++;
            }
        }
        return saved;
    }

    private double calculateHotScore(Note note) {
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
        return roundScore(base + 24.0 / Math.sqrt(hours));
    }

    private double behaviorWeight(UserBehavior behavior) {
        if (behavior.getWeight() != null && behavior.getWeight() > 0) {
            return behavior.getWeight();
        }
        String type = behavior.getBehaviorType();
        if ("comment".equalsIgnoreCase(type)) {
            return 10.0;
        }
        if ("collect".equalsIgnoreCase(type) || "favorite".equalsIgnoreCase(type)) {
            return 8.0;
        }
        if ("like".equalsIgnoreCase(type)) {
            return 5.0;
        }
        if ("dwell".equalsIgnoreCase(type)) {
            return 3.0;
        }
        return 1.0;
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private double roundScore(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private boolean shouldJoinDailyRank(Note note) {
        return note != null && note.getCreatedAt() != null
                && note.getCreatedAt().isAfter(LocalDateTime.now().minusDays(1));
    }

    private String rankDailyKey() {
        return RANK_DAILY_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private void clearBehaviorCache() {
        try {
            redisTemplate.delete(Objects.requireNonNull(redisTemplate.keys(BEHAVIOR_KEY_PREFIX + "*")));
        } catch (Exception e) {
            log.warn("Clear recommend behavior cache failed: {}", e.getMessage());
        }
    }
}
