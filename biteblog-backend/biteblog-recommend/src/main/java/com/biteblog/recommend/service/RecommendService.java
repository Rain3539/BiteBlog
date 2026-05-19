package com.biteblog.recommend.service;

import com.biteblog.common.result.Result;
import com.biteblog.recommend.client.PostClient;
import com.biteblog.recommend.client.UserClient;
import com.biteblog.recommend.client.dto.PostDetailDTO;
import com.biteblog.recommend.client.dto.UserProfileDTO;
import com.biteblog.recommend.dto.RecommendItemVO;
import com.biteblog.recommend.dto.RecommendResponse;
import com.biteblog.recommend.entity.Note;
import com.biteblog.recommend.entity.UserBehavior;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private static final String RANK_DAILY_KEY_PREFIX = "rank:daily:";
    private static final String BEHAVIOR_KEY_PREFIX = "behavior:";
    private static final String EXPOSURE_KEY_PREFIX = "exposure:";
    private static final String ITEM_SIM_KEY_PREFIX = "recommend:itemcf:similar:";
    private static final Duration EXPOSURE_TTL = Duration.ofDays(7);
    private static final Duration BEHAVIOR_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MIN_BEHAVIOR_COUNT = 5;
    private static final int RECALL_MULTIPLIER = 4;
    private static final String COLD_START_REASON = "近期热门";
    private static final String TAG_REASON = "兴趣标签相关";
    private static final String ITEM_CF_REASON = "相似用户喜欢";

    private final RecommendDataService recommendDataService;
    private final RecommendSearchService recommendSearchService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final PostClient postClient;
    private final UserClient userClient;

    public RecommendResponse discover(Long userId, Long cursor, int size, String tag, String city) {
        int safeSize = normalizeSize(size);
        int offset = normalizeCursor(cursor);

        long behaviorCount = recommendDataService.countUserBehaviors(userId);
        if (behaviorCount < MIN_BEHAVIOR_COUNT && isBlank(tag)) {
            return coldStart(userId, offset, safeSize);
        }

        RecommendResponse response = hybridRecommend(userId, tag, city, safeSize, offset);
        if (response.getList().isEmpty()) {
            return coldStart(userId, offset, safeSize);
        }
        return response;
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

    private RecommendResponse hybridRecommend(Long userId, String tag, String city, int size, int offset) {
        int recallSize = (offset + size + 1) * RECALL_MULTIPLIER;
        Map<Long, Candidate> candidates = new LinkedHashMap<>();

        try {
            recallByTag(userId, tag, city, recallSize, candidates);
        } catch (IllegalStateException e) {
            if (!isBlank(tag) || !isBlank(city)) {
                log.warn("ES recall unavailable, fallback to Redis rank hot pool, userId={}, tag={}, city={}",
                        userId, tag, city);
                return coldStart(userId, offset, size);
            }
            throw e;
        }
        recallByItemCf(userId, recallSize, candidates);
        supplementByHot(userId, recallSize, candidates);

        List<Candidate> ranked = candidates.values().stream()
                .filter(candidate -> !isOwnNote(userId, candidate.note))
                .sorted(Comparator.comparingDouble(Candidate::finalScore).reversed()
                        .thenComparing(candidate -> candidate.note.getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .skip(offset)
                .toList();
        List<Long> selectedIds = selectAndReserveWithRefill(userId,
                ranked.stream().map(candidate -> candidate.note.getId()).toList(), size);
        Map<Long, Candidate> candidateMap = ranked.stream()
                .collect(Collectors.toMap(candidate -> candidate.note.getId(), candidate -> candidate, (left, right) -> left, LinkedHashMap::new));
        List<Candidate> selected = selectedIds.stream()
                .map(candidateMap::get)
                .filter(Objects::nonNull)
                .toList();
        boolean hasMore = ranked.size() > selectedIds.size();
        List<RecommendItemVO> page = toItems(diversifyCandidates(selected));
        return new RecommendResponse(page, hasMore ? (long) offset + Math.min(size, ranked.size()) : null, hasMore);
    }

    private void recallByTag(Long userId, String tag, String city, int recallSize, Map<Long, Candidate> candidates) {
        if (isBlank(tag) && isBlank(city)) {
            return;
        }
        List<Long> esNoteIds = recommendSearchService.searchPostIds(tag, city, recallSize);
        List<Note> notes = esNoteIds.isEmpty()
                ? recommendDataService.searchNormalNotes(tag, city, recallSize, List.of())
                : new ArrayList<>(recommendDataService.getNormalNotesByIds(esNoteIds).values());
        for (Note note : notes) {
            if (isOwnNote(userId, note)) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(note.getId(), id -> new Candidate(note));
            candidate.tagScore = Math.max(candidate.tagScore, calculateScore(note));
            candidate.reason = TAG_REASON;
        }
    }

    private void recallByItemCf(Long userId, int recallSize, Map<Long, Candidate> candidates) {
        List<UserBehavior> ownBehaviors = listRecentUserBehaviorsCached(userId, 50);
        Set<Long> interactedNoteIds = ownBehaviors.stream()
                .map(UserBehavior::getNoteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (interactedNoteIds.isEmpty()) {
            return;
        }

        if (recallByEsItemCf(userId, interactedNoteIds, recallSize, candidates)) {
            return;
        }

        if (recallByRedisItemCf(userId, interactedNoteIds, recallSize, candidates)) {
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
            if (noteId == null || interactedNoteIds.contains(noteId)) {
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
            if (note == null || isOwnNote(userId, note)) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(noteId, id -> new Candidate(note));
            candidate.itemCfScore = Math.max(candidate.itemCfScore, itemCfScores.getOrDefault(noteId, 0.0));
            if (candidate.reason == null) {
                candidate.reason = ITEM_CF_REASON;
            }
        }
    }

    private boolean recallByEsItemCf(Long userId, Set<Long> interactedNoteIds, int recallSize, Map<Long, Candidate> candidates) {
        Map<Long, Double> itemCfScores = recommendSearchService.searchSimilarPostScores(interactedNoteIds, recallSize);
        return fillItemCfCandidates(userId, itemCfScores, recallSize, candidates);
    }

    private boolean recallByRedisItemCf(Long userId, Set<Long> interactedNoteIds, int recallSize, Map<Long, Candidate> candidates) {
        Map<Long, Double> itemCfScores = new HashMap<>();
        try {
            for (Long noteId : interactedNoteIds) {
                Set<ZSetOperations.TypedTuple<Object>> tuples =
                        redisTemplate.opsForZSet().reverseRangeWithScores(ITEM_SIM_KEY_PREFIX + noteId, 0, recallSize - 1L);
                if (tuples == null) {
                    continue;
                }
                for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                    Long relatedId = toLong(tuple.getValue());
                    if (relatedId == null || interactedNoteIds.contains(relatedId)) {
                        continue;
                    }
                    itemCfScores.merge(relatedId, tuple.getScore() == null ? 1.0 : tuple.getScore(), Double::sum);
                }
            }
        } catch (Exception e) {
            log.warn("Read precomputed ItemCF failed, fallback to online behavior scan: {}", e.getMessage());
            return false;
        }
        if (itemCfScores.isEmpty()) {
            return false;
        }
        return fillItemCfCandidates(userId, itemCfScores, recallSize, candidates);
    }

    private boolean fillItemCfCandidates(Long userId, Map<Long, Double> itemCfScores, int recallSize, Map<Long, Candidate> candidates) {
        if (itemCfScores == null || itemCfScores.isEmpty()) {
            return false;
        }
        List<Long> orderedIds = itemCfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(recallSize)
                .map(Map.Entry::getKey)
                .toList();
        Map<Long, Note> noteMap = recommendDataService.getNormalNotesByIds(orderedIds);
        for (Long noteId : orderedIds) {
            Note note = noteMap.get(noteId);
            if (note == null || isOwnNote(userId, note)) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(noteId, id -> new Candidate(note));
            candidate.itemCfScore = Math.max(candidate.itemCfScore, itemCfScores.getOrDefault(noteId, 0.0));
            if (candidate.reason == null) {
                candidate.reason = ITEM_CF_REASON;
            }
        }
        return true;
    }

    private void supplementByHot(Long userId, int recallSize, Map<Long, Candidate> candidates) {
        if (candidates.size() >= recallSize) {
            return;
        }
        Set<Long> excluded = new HashSet<>(candidates.keySet());
        int requestSize = (recallSize - candidates.size()) * 2;
        for (Note note : recommendDataService.listHotNotes(0, requestSize, excluded)) {
            if (isOwnNote(userId, note)) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(note.getId(), id -> new Candidate(note));
            if (candidate.reason == null) {
                candidate.reason = COLD_START_REASON;
            }
            if (candidates.size() >= recallSize) {
                break;
            }
        }
    }

    private RecommendResponse coldStart(Long userId, int offset, int size) {
        RecommendResponse fromRedis = coldStartFromRedis(userId, offset, size);
        if (fromRedis != null && !fromRedis.getList().isEmpty()) {
            return fromRedis;
        }
        return coldStartFromMysql(userId, offset, size);
    }

    private RecommendResponse coldStartFromRedis(Long userId, int offset, int size) {
        try {
            long end = Math.max(offset + (long) size * RECALL_MULTIPLIER, MAX_SIZE * (long) RECALL_MULTIPLIER);
            String hotKey = resolveHotPoolKey();
            Set<ZSetOperations.TypedTuple<Object>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(hotKey, 0, end);
            if (tuples == null || tuples.isEmpty()) {
                return null;
            }

            List<Long> orderedIds = tuples.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .map(this::toLong)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<Long, Note> orderedNoteMap = recommendDataService.getNormalNotesByIds(orderedIds);
            List<Long> filteredIds = orderedIds.stream()
                    .filter(id -> {
                        Note note = orderedNoteMap.get(id);
                        return note != null && !isOwnNote(userId, note);
                    })
                    .skip(offset)
                    .toList();
            List<Long> noteIds = selectAndReserveWithRefill(userId, filteredIds, size);
            Map<Long, Note> noteMap = recommendDataService.getNormalNotesByIds(noteIds);
            Map<Long, String> coverMap = recommendDataService.getCoverUrls(noteIds);

            List<RecommendItemVO> items = new ArrayList<>();
            for (Long noteId : noteIds) {
                Note note = noteMap.get(noteId);
                if (note == null) {
                    continue;
                }
                items.add(toItem(note, coverMap.get(noteId), COLD_START_REASON));
                if (items.size() >= size) {
                    break;
                }
            }

            long nextOffset = offset + noteIds.size();
            boolean hasMore = filteredIds.size() > noteIds.size();
            return new RecommendResponse(diversifyItems(items), hasMore ? nextOffset : null, hasMore);
        } catch (Exception e) {
            log.warn("Recommend hot pool unavailable, fallback to MySQL: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<UserBehavior> listRecentUserBehaviorsCached(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        String key = behaviorKey(userId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List<?> list && !list.isEmpty()) {
                return list.stream()
                        .filter(UserBehavior.class::isInstance)
                        .map(UserBehavior.class::cast)
                        .limit(limit)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Read behavior profile cache failed, userId={}, reason={}", userId, e.getMessage());
        }

        List<UserBehavior> behaviors = recommendDataService.listRecentUserBehaviors(userId, limit);
        try {
            redisTemplate.opsForValue().set(key, behaviors, BEHAVIOR_TTL);
        } catch (Exception e) {
            log.warn("Write behavior profile cache failed, userId={}, reason={}", userId, e.getMessage());
        }
        return behaviors;
    }

    private String resolveHotPoolKey() {
        String rankKey = rankDailyKey();
        try {
            Long rankSize = redisTemplate.opsForZSet().zCard(rankKey);
            if (rankSize != null && rankSize > 0) {
                return rankKey;
            }
        } catch (Exception e) {
            log.warn("Read rank daily hot pool failed, fallback to recommend hot pool: {}", e.getMessage());
        }
        return HOT_POOL_KEY;
    }

    private RecommendResponse coldStartFromMysql(Long userId, int offset, int size) {
        List<Note> notes = recommendDataService.listHotNotes(offset, (size + 1) * RECALL_MULTIPLIER, List.of());
        List<Note> ranked = notes.stream()
                .filter(note -> !isOwnNote(userId, note))
                .sorted(Comparator.comparingDouble(this::calculateScore).reversed())
                .toList();
        List<Long> selectedIds = selectAndReserveWithRefill(userId, ranked.stream().map(Note::getId).toList(), size);
        Map<Long, Note> noteMap = ranked.stream()
                .collect(Collectors.toMap(Note::getId, note -> note, (left, right) -> left, LinkedHashMap::new));
        List<Note> pageNotes = selectedIds.stream()
                .map(noteMap::get)
                .filter(Objects::nonNull)
                .toList();
        boolean hasMore = notes.size() > selectedIds.size();
        Map<Long, String> coverMap = recommendDataService.getCoverUrls(pageNotes.stream().map(Note::getId).toList());

        List<RecommendItemVO> items = diversifyNotes(pageNotes).stream()
                .map(note -> toItem(note, coverMap.get(note.getId()), COLD_START_REASON))
                .toList();
        return new RecommendResponse(items, hasMore ? (long) offset + Math.min(size, notes.size()) : null, hasMore);
    }

    private List<Candidate> diversifyCandidates(List<Candidate> candidates) {
        return diversify(candidates, candidate -> candidate.note.getAuthorId());
    }

    private List<Note> diversifyNotes(List<Note> notes) {
        return diversify(notes, Note::getAuthorId);
    }

    private List<RecommendItemVO> diversifyItems(List<RecommendItemVO> items) {
        return diversify(items, RecommendItemVO::getAuthorId);
    }

    private <T> List<T> diversify(List<T> orderedItems, java.util.function.Function<T, Long> authorExtractor) {
        if (orderedItems == null || orderedItems.size() <= 1) {
            return orderedItems == null ? List.of() : orderedItems;
        }
        List<T> remaining = new ArrayList<>(orderedItems);
        List<T> diversified = new ArrayList<>(orderedItems.size());
        Long lastAuthorId = null;

        while (!remaining.isEmpty()) {
            int selectedIndex = 0;
            if (lastAuthorId != null) {
                for (int i = 0; i < remaining.size(); i++) {
                    Long authorId = authorExtractor.apply(remaining.get(i));
                    if (!lastAuthorId.equals(authorId)) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
            T selected = remaining.remove(selectedIndex);
            diversified.add(selected);
            lastAuthorId = authorExtractor.apply(selected);
        }
        return diversified;
    }

    private List<RecommendItemVO> toItems(List<Candidate> candidates) {
        List<Long> noteIds = candidates.stream().map(candidate -> candidate.note.getId()).toList();
        Map<Long, String> coverMap = recommendDataService.getCoverUrls(noteIds);
        return candidates.stream()
                .map(candidate -> toItem(candidate.note, coverMap.get(candidate.note.getId()),
                        candidate.reason == null ? COLD_START_REASON : candidate.reason))
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

    private RecommendItemVO toItem(Note note, String coverUrl, String reason) {
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
        item.setReason(reason);
        item.setCreatedAt(note.getCreatedAt());
        enrichFromPostService(item);
        enrichFromUserService(item);
        return item;
    }

    private void enrichFromPostService(RecommendItemVO item) {
        try {
            Result<PostDetailDTO> result = postClient.getPostDetail(item.getPostId());
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                return;
            }
            PostDetailDTO detail = result.getData();
            item.setAuthorId(detail.getAuthorId() == null ? item.getAuthorId() : detail.getAuthorId());
            item.setAuthorName(firstNonBlank(detail.getAuthorName(), item.getAuthorName()));
            item.setAuthorAvatar(firstNonBlank(detail.getAuthorAvatar(), item.getAuthorAvatar()));
            item.setTitle(firstNonBlank(detail.getTitle(), item.getTitle()));
            item.setShopName(firstNonBlank(detail.getShopName(), item.getShopName()));
            if (detail.getImageUrls() != null && !detail.getImageUrls().isEmpty()) {
                item.setCoverUrl(detail.getImageUrls().get(0));
            }
            if (detail.getLikeCount() != null) {
                item.setLikeCount(toLong(detail.getLikeCount()));
            }
            if (detail.getCollectCount() != null) {
                item.setCollectCount(toLong(detail.getCollectCount()));
            }
            if (detail.getCommentCount() != null) {
                item.setCommentCount(toLong(detail.getCommentCount()));
            }
            if (detail.getCreatedAt() != null) {
                item.setCreatedAt(detail.getCreatedAt());
            }
        } catch (Exception e) {
            log.warn("Feign post-service enrich failed, postId={}, reason={}", item.getPostId(), e.getMessage());
        }
    }

    private void enrichFromUserService(RecommendItemVO item) {
        if (item.getAuthorId() == null) {
            return;
        }
        try {
            Result<UserProfileDTO> result = userClient.getUserProfile(item.getAuthorId());
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                return;
            }
            UserProfileDTO profile = result.getData();
            item.setAuthorName(firstNonBlank(profile.getUsername(), item.getAuthorName()));
            item.setAuthorAvatar(firstNonBlank(profile.getAvatar(), item.getAuthorAvatar()));
        } catch (Exception e) {
            log.warn("Feign user-service enrich failed, authorId={}, reason={}", item.getAuthorId(), e.getMessage());
        }
    }

    private List<Long> selectAndReserveWithRefill(Long userId, List<Long> orderedIds, int limit) {
        List<Long> reserved = selectAndReserveUnexposed(userId, orderedIds, limit);
        if (reserved.size() >= limit || orderedIds == null || orderedIds.isEmpty()) {
            return reserved;
        }

        Set<Long> selected = new HashSet<>(reserved);
        List<Long> result = new ArrayList<>(reserved);
        for (Long id : orderedIds) {
            if (id == null || selected.contains(id)) {
                continue;
            }
            result.add(id);
            selected.add(id);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Long> selectAndReserveUnexposed(Long userId, List<Long> orderedIds, int limit) {
        if (userId == null || orderedIds == null || orderedIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        String scriptText = """
                local key = KEYS[1]
                local limit = tonumber(ARGV[1])
                local ttl = tonumber(ARGV[2])
                local selected = {}
                for i = 3, #ARGV do
                    local id = ARGV[i]
                    if redis.call('SISMEMBER', key, id) == 0 then
                        redis.call('SADD', key, id)
                        table.insert(selected, id)
                        if #selected >= limit then
                            break
                        end
                    end
                end
                if #selected > 0 then
                    redis.call('EXPIRE', key, ttl)
                end
                return selected
                """;
        try {
            DefaultRedisScript<List> script = new DefaultRedisScript<>(scriptText, List.class);
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(limit));
            args.add(String.valueOf(EXPOSURE_TTL.toSeconds()));
            orderedIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .forEach(args::add);
            List<Object> selected = redisTemplate.execute(script, Collections.singletonList(exposureKey(userId)),
                    args.toArray());
            if (selected == null || selected.isEmpty()) {
                return List.of();
            }
            return selected.stream()
                    .map(this::toLong)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Reserve recommend exposures failed, fallback to local exposure filtering, userId={}, reason={}",
                    userId, e.getMessage());
            Set<Long> exposed = getExposureIds(userId);
            return orderedIds.stream()
                    .filter(id -> id != null && !exposed.contains(id))
                    .limit(limit)
                    .toList();
        }
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

    private String behaviorKey(Long userId) {
        return BEHAVIOR_KEY_PREFIX + userId;
    }

    private String rankDailyKey() {
        return RANK_DAILY_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
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
        private String reason;

        private Candidate(Note note) {
            this.note = note;
        }

        private double finalScore() {
            return tagScore * 0.6 + itemCfScore * 0.4;
        }
    }

    private boolean isOwnNote(Long userId, Note note) {
        return userId != null && note != null && userId.equals(note.getAuthorId());
    }
}
