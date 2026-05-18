package com.biteblog.rank.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biteblog.rank.dto.RankItemVO;
import com.biteblog.rank.entity.Note;
import com.biteblog.rank.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {
    private final NoteMapper noteMapper;
    private final StringRedisTemplate redisTemplate;

    private static final DateTimeFormatter DATE_KEY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String DAILY_KEY_PREFIX = "rank:daily:";
    private static final String WEEKLY_KEY = "rank:weekly";
    private static final String ALL_KEY = "rank:all";
    private static final Set<String> TYPES = Set.of("daily", "weekly", "all");
    private static final int MAX_CACHE_SIZE = 200;
    private static final int MAX_REBUILD_CANDIDATES = 1000;

    public Map<String, Object> getTop10(String type) {
        return getRankList(type, 1, 10);
    }

    public Map<String, Object> getRankList(String type, int page, int size) {
        String safeType = normalizeType(type);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        ensureCache(safeType);

        String key = rankKey(safeType);
        long start = (long) (safePage - 1) * safeSize;
        long end = start + safeSize - 1;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        Long total = redisTemplate.opsForZSet().zCard(key);

        List<RankItemVO> list = toRankItems(key, tuples, (int) start + 1);
        Map<String, Object> result = new HashMap<>();
        result.put("type", safeType);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total == null ? 0 : total);
        result.put("list", list);
        return result;
    }

    public void rebuild(String type) {
        String safeType = normalizeType(type);
        String key = rankKey(safeType);
        redisTemplate.delete(key);

        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getStatus, 1);
        if ("daily".equals(safeType)) {
            wrapper.ge(Note::getCreatedAt, LocalDateTime.now().minusDays(1));
        } else if ("weekly".equals(safeType)) {
            wrapper.ge(Note::getCreatedAt, LocalDateTime.now().minusDays(7));
        }
        wrapper.last("ORDER BY (" +
                "COALESCE(like_count, 0) * 3 + " +
                "COALESCE(collect_count, 0) * 5 + " +
                "COALESCE(comment_count, 0) * 4 + " +
                "(COALESCE(score_taste, 0) + COALESCE(score_smell, 0) + COALESCE(score_color, 0)) / 3" +
                ") DESC LIMIT " + MAX_REBUILD_CANDIDATES);

        List<Note> notes = noteMapper.selectList(wrapper);
        for (Note note : notes) {
            putScore(key, note.getId(), calculateScore(note));
        }
        trim(key);
        log.info("Rank cache rebuilt: type={}, count={}", safeType, notes.size());
    }

    public void addInitialScore(Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !Objects.equals(note.getStatus(), 1)) {
            return;
        }
        double score = calculateScore(note);
        for (String type : TYPES) {
            if (shouldJoin(type, note.getCreatedAt())) {
                String key = rankKey(type);
                putScore(key, noteId, score);
                trim(key);
            }
        }
    }

    public void removeNote(Long noteId) {
        if (noteId == null) {
            return;
        }
        for (String type : TYPES) {
            removeScore(rankKey(type), noteId);
        }
    }

    public void increaseByInteraction(Long noteId, String interactionType) {
        if (noteId == null) {
            return;
        }
        Note note = noteMapper.selectById(noteId);
        if (note == null || !Objects.equals(note.getStatus(), 1)) {
            removeNote(noteId);
            return;
        }
        double score = calculateScore(note);
        for (String type : TYPES) {
            String key = rankKey(type);
            if (shouldJoin(type, note.getCreatedAt())) {
                putScore(key, noteId, score);
                trim(key);
            } else {
                removeScore(key, noteId);
            }
        }
        log.info("Rank score refreshed by interaction: noteId={}, type={}", noteId, interactionType);
    }

    @Scheduled(cron = "0 0/10 * * * ?")
    public void scheduledRefresh() {
        for (String type : TYPES) {
            rebuild(type);
        }
    }

    private void ensureCache(String type) {
        Long size = redisTemplate.opsForZSet().zCard(rankKey(type));
        if (size == null || size == 0) {
            rebuild(type);
        }
    }

    private List<RankItemVO> toRankItems(String key, Set<ZSetOperations.TypedTuple<String>> tuples, int startRank) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tuples.stream()
                .map(t -> parseNoteId(t.getValue()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Note> noteMap = noteMapper.selectBatchIds(ids).stream()
                .filter(n -> Objects.equals(n.getStatus(), 1))
                .collect(Collectors.toMap(Note::getId, n -> n));

        List<RankItemVO> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int rank = startRank;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long id = parseNoteId(tuple.getValue());
            if (id == null || !seen.add(id)) {
                redisTemplate.opsForZSet().remove(key, tuple.getValue());
                continue;
            }
            Note note = noteMap.get(id);
            if (note == null) {
                removeNote(id);
                continue;
            }
            RankItemVO vo = new RankItemVO();
            vo.setRankNo(rank++);
            vo.setPostId(note.getId());
            vo.setAuthorId(note.getAuthorId());
            vo.setTitle(note.getTitle());
            vo.setShopName(note.getShopName());
            vo.setLikeCount(defaultZero(note.getLikeCount()));
            vo.setCollectCount(defaultZero(note.getCollectCount()));
            vo.setCommentCount(defaultZero(note.getCommentCount()));
            vo.setHotScore(tuple.getScore());
            vo.setCreatedAt(note.getCreatedAt());
            result.add(vo);
        }
        return result;
    }

    private double calculateScore(Note note) {
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
        long hours = Math.max(1, java.time.Duration.between(note.getCreatedAt(), LocalDateTime.now()).toHours());
        return base + 24.0 / Math.sqrt(hours);
    }

    private boolean shouldJoin(String type, LocalDateTime createdAt) {
        if (createdAt == null || "all".equals(type)) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        return ("daily".equals(type) && createdAt.isAfter(now.minusDays(1)))
                || ("weekly".equals(type) && createdAt.isAfter(now.minusDays(7)));
    }

    private void trim(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size != null && size > MAX_CACHE_SIZE) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - MAX_CACHE_SIZE - 1);
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "daily";
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return TYPES.contains(lower) ? lower : "daily";
    }

    private String rankKey(String type) {
        return switch (type) {
            case "daily" -> DAILY_KEY_PREFIX + LocalDate.now().format(DATE_KEY_FORMATTER);
            case "weekly" -> WEEKLY_KEY;
            case "all" -> ALL_KEY;
            default -> DAILY_KEY_PREFIX + LocalDate.now().format(DATE_KEY_FORMATTER);
        };
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void putScore(String key, Long noteId, double score) {
        String member = noteId.toString();
        redisTemplate.opsForZSet().remove(key, legacyJsonStringMember(member));
        redisTemplate.opsForZSet().add(key, member, score);
    }

    private void removeScore(String key, Long noteId) {
        String member = noteId.toString();
        redisTemplate.opsForZSet().remove(key, member, legacyJsonStringMember(member));
    }

    private String legacyJsonStringMember(String member) {
        return "\"" + member + "\"";
    }

    private Long parseNoteId(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            log.warn("Invalid rank member found in Redis: {}", value);
            return null;
        }
    }
}
