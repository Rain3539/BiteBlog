package com.biteblog.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.notify.dto.NotificationPreferenceVO;
import com.biteblog.notify.entity.NotificationPreference;
import com.biteblog.notify.mapper.NotificationPreferenceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyPreferenceService {

    public enum CheckResult {
        ALLOW, MUTED, DND
    }

    public static final String PREF_MUTE_TYPE = "mute_type";
    public static final String PREF_MUTE_SENDER = "mute_sender";
    public static final String PREF_DND_TIME = "dnd_time";

    private static final Set<String> MUTABLE_TYPES = Set.of("like", "collect", "comment", "follow_post");
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Redis 偏好缓存：notify:pref:{userId} → JSON 数组，TTL 10 分钟 */
    private static final String PREF_KEY_PREFIX = "notify:pref:";
    private static final long PREF_TTL_MINUTES = 10;

    private final NotificationPreferenceMapper preferenceMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 消费链路前置检查：mute 直接丢弃；dnd 允许写库但不推 WS / 不增未读缓存。
     */
    public CheckResult check(Long receiverId, Long senderId, String type) {
        List<NotificationPreference> prefs = getCachedPreferences(receiverId);
        boolean inDnd = false;
        for (NotificationPreference p : prefs) {
            if (PREF_MUTE_TYPE.equals(p.getPrefType())) {
                if (type.equals(p.getPrefValue())) {
                    return CheckResult.MUTED;
                }
                if ("comment".equals(p.getPrefValue()) && "comment_reply".equals(type)) {
                    return CheckResult.MUTED;
                }
            }
            if (PREF_MUTE_SENDER.equals(p.getPrefType())
                    && String.valueOf(senderId).equals(p.getPrefValue())) {
                return CheckResult.MUTED;
            }
            if (PREF_DND_TIME.equals(p.getPrefType()) && isInDnd(p.getPrefValue())) {
                inDnd = true;
            }
        }
        return inDnd ? CheckResult.DND : CheckResult.ALLOW;
    }

    public List<NotificationPreferenceVO> listPreferences(Long userId) {
        return loadFromDb(userId).stream().map(this::toVO).collect(Collectors.toList());
    }

    public NotificationPreferenceVO muteType(Long userId, String type) {
        String normalized = normalizeType(type);
        NotificationPreference existing = preferenceMapper.selectOne(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .eq(NotificationPreference::getPrefType, PREF_MUTE_TYPE)
                        .eq(NotificationPreference::getPrefValue, normalized)
                        .last("LIMIT 1"));
        if (existing != null) {
            return toVO(existing);
        }
        NotificationPreference p = new NotificationPreference();
        p.setUserId(userId);
        p.setPrefType(PREF_MUTE_TYPE);
        p.setPrefValue(normalized);
        p.setCreatedAt(LocalDateTime.now());
        preferenceMapper.insert(p);
        evictPrefCache(userId);
        return toVO(p);
    }

    public NotificationPreferenceVO muteSender(Long userId, Long senderId) {
        if (senderId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String value = String.valueOf(senderId);
        NotificationPreference existing = preferenceMapper.selectOne(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .eq(NotificationPreference::getPrefType, PREF_MUTE_SENDER)
                        .eq(NotificationPreference::getPrefValue, value)
                        .last("LIMIT 1"));
        if (existing != null) {
            return toVO(existing);
        }
        NotificationPreference p = new NotificationPreference();
        p.setUserId(userId);
        p.setPrefType(PREF_MUTE_SENDER);
        p.setPrefValue(value);
        p.setCreatedAt(LocalDateTime.now());
        preferenceMapper.insert(p);
        evictPrefCache(userId);
        return toVO(p);
    }

    public NotificationPreferenceVO setDndTime(Long userId, String timeRange) {
        validateDndRange(timeRange);
        preferenceMapper.delete(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .eq(NotificationPreference::getPrefType, PREF_DND_TIME));
        NotificationPreference p = new NotificationPreference();
        p.setUserId(userId);
        p.setPrefType(PREF_DND_TIME);
        p.setPrefValue(timeRange.trim());
        p.setCreatedAt(LocalDateTime.now());
        preferenceMapper.insert(p);
        evictPrefCache(userId);
        return toVO(p);
    }

    public void clearDndTime(Long userId) {
        preferenceMapper.delete(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .eq(NotificationPreference::getPrefType, PREF_DND_TIME));
        evictPrefCache(userId);
    }

    public void deletePreference(Long userId, Long preferenceId) {
        NotificationPreference p = preferenceMapper.selectById(preferenceId);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!userId.equals(p.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        preferenceMapper.deleteById(preferenceId);
        evictPrefCache(userId);
    }

    static boolean isInDnd(String timeRange) {
        if (!StringUtils.hasText(timeRange) || !timeRange.contains("-")) {
            return false;
        }
        String[] parts = timeRange.trim().split("-", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim(), TIME_FMT);
            LocalTime end = LocalTime.parse(parts[1].trim(), TIME_FMT);
            LocalTime now = LocalTime.now();
            if (start.equals(end)) {
                return true;
            }
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) {
            return false;
        }
    }

    private List<NotificationPreference> getCachedPreferences(Long userId) {
        String key = prefCacheKey(userId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<NotificationPreference>>() {});
            }
        } catch (Exception e) {
            log.debug("pref cache get failed userId={}", userId, e);
        }
        List<NotificationPreference> prefs = loadFromDb(userId);
        try {
            stringRedisTemplate.opsForValue().set(
                    key, objectMapper.writeValueAsString(prefs), PREF_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("pref cache set failed userId={}", userId, e);
        }
        return prefs;
    }

    private List<NotificationPreference> loadFromDb(Long userId) {
        return preferenceMapper.selectList(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .orderByAsc(NotificationPreference::getId));
    }

    private void evictPrefCache(Long userId) {
        try {
            stringRedisTemplate.delete(prefCacheKey(userId));
        } catch (Exception e) {
            log.debug("pref cache evict failed userId={}", userId, e);
        }
    }

    private static String prefCacheKey(Long userId) {
        return PREF_KEY_PREFIX + userId;
    }

    private static String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String normalized = type.trim().toLowerCase();
        if (!MUTABLE_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return normalized;
    }

    private static void validateDndRange(String timeRange) {
        if (!StringUtils.hasText(timeRange) || !timeRange.contains("-")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String[] parts = timeRange.trim().split("-", 2);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        try {
            LocalTime.parse(parts[0].trim(), TIME_FMT);
            LocalTime.parse(parts[1].trim(), TIME_FMT);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
    }

    private NotificationPreferenceVO toVO(NotificationPreference p) {
        NotificationPreferenceVO vo = new NotificationPreferenceVO();
        vo.setId(p.getId());
        vo.setPrefType(p.getPrefType());
        vo.setPrefValue(p.getPrefValue());
        vo.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().format(ISO_DT) : null);
        return vo;
    }
}
