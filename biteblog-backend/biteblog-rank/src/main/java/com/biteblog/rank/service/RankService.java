package com.biteblog.rank.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biteblog.rank.dto.RankItemVO;
import com.biteblog.rank.entity.Note;
import com.biteblog.rank.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final RedisTemplate<String, Object> redisTemplate;

    private static final DateTimeFormatter DATE_KEY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String DAILY_KEY_PREFIX = "rank:daily:";
    private static final String WEEKLY_KEY = "rank:weekly";
    private static final String ALL_KEY = "rank:all";
    private static final Set<String> TYPES = Set.of("daily", "weekly", "all");
    private static final int MAX_CACHE_SIZE = 200;

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
        Set<ZSetOperations.TypedTuple<Object>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        Long total = redisTemplate.opsForZSet().zCard(key);

        List<RankItemVO> list = toRankItems(tuples, (int) start + 1);
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
                .eq(Note::getStatus, 1)
                .orderByDesc(Note::getLikeCount)
                .last("LIMIT " + MAX_CACHE_SIZE);
        if ("daily".equals(safeType)) {
            wrapper.ge(Note::getCreatedAt, LocalDateTime.now().minusDays(1));
        } else if ("weekly".equals(safeType)) {
            wrapper.ge(Note::getCreatedAt, LocalDateTime.now().minusDays(7));
        }

        List<Note> notes = noteMapper.selectList(wrapper);
        for (Note note : notes) {
            redisTemplate.opsForZSet().add(key, note.getId().toString(), calculateScore(note));
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
                redisTemplate.opsForZSet().add(rankKey(type), noteId.toString(), score);
                trim(rankKey(type));
            }
        }
    }

    public void removeNote(Long noteId) {
        if (noteId == null) {
            return;
        }
        for (String type : TYPES) {
            redisTemplate.opsForZSet().remove(rankKey(type), noteId.toString());
        }
    }

    public void increaseByInteraction(Long noteId, String interactionType) {
        if (noteId == null) {
            return;
        }
        double delta = switch (String.valueOf(interactionType)) {
            case "like" -> 3.0;
            case "collect" -> 5.0;
            case "comment" -> 4.0;
            case "view" -> 1.0;
            default -> 1.0;
        };
        Note note = noteMapper.selectById(noteId);
        if (note == null || !Objects.equals(note.getStatus(), 1)) {
            removeNote(noteId);
            return;
        }
        for (String type : TYPES) {
            if (shouldJoin(type, note.getCreatedAt())) {
                redisTemplate.opsForZSet().incrementScore(rankKey(type), noteId.toString(), delta);
                trim(rankKey(type));
            }
        }
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

    private List<RankItemVO> toRankItems(Set<ZSetOperations.TypedTuple<Object>> tuples, int startRank) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tuples.stream()
                .map(t -> Long.valueOf(String.valueOf(t.getValue())))
                .collect(Collectors.toList());
        Map<Long, Note> noteMap = noteMapper.selectBatchIds(ids).stream()
                .filter(n -> Objects.equals(n.getStatus(), 1))
                .collect(Collectors.toMap(Note::getId, n -> n));

        List<RankItemVO> result = new ArrayList<>();
        int rank = startRank;
        for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
            Long id = Long.valueOf(String.valueOf(tuple.getValue()));
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
}
