package com.biteblog.feed.service;

import com.biteblog.feed.config.FeedRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedEventListener {

    private final StringRedisTemplate redisTemplate;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INBOX_PREFIX = "feed:inbox:";
    private static final String DELETED_KEY = "feed:deleted";
    private static final String BIG_V_KEY = "feed:bigv";
    private static final int BIG_V_THRESHOLD = 50;

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = FeedRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Message message) {
        Map<String, Object> event;
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            event = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse note.published message", e);
            throw new RuntimeException(e);
        }

        Long noteId = Long.valueOf(event.get("noteId").toString());
        Long authorId = Long.valueOf(event.get("authorId").toString());
        Object ts = event.get("timestamp");
        long score = ts != null ? Long.parseLong(ts.toString()) : System.currentTimeMillis();

        log.info("Feed received note.published: noteId={}, authorId={}", noteId, authorId);

        // 检查粉丝数，判断是否为大V
        int followerCount = 0;
        try {
            Object cached = redisTemplate.opsForHash().get("cache:user:" + authorId, "followerCount");
            if (cached != null) {
                followerCount = Integer.parseInt(cached.toString());
            }
        } catch (Exception ignored) {}

        if (followerCount >= BIG_V_THRESHOLD) {
            // 大V：标记到 bigv 集合，不推送
            redisTemplate.opsForSet().add(BIG_V_KEY, authorId.toString());
            // 仍然写入作者自己的inbox（作为数据源，timeline拉取时用）
            redisTemplate.opsForZSet().add(INBOX_PREFIX + authorId, noteId.toString(), score);
            log.info("Author {} is big-V ({} followers), skip fanout", authorId, followerCount);
            return;
        }

        // 普通用户：推送到所有粉丝的inbox
        Set<String> fans = redisTemplate.opsForSet().members("fans:" + authorId);
        if (fans != null && !fans.isEmpty()) {
            for (String fanId : fans) {
                redisTemplate.opsForZSet().add(INBOX_PREFIX + fanId, noteId.toString(), score);
            }
            log.info("Pushed note {} to {} fans' inbox", noteId, fans.size());
        }

        // 也写入作者自己的inbox，保证作者能在feed中看到自己的帖子
        redisTemplate.opsForZSet().add(INBOX_PREFIX + authorId, noteId.toString(), score);
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = FeedRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Message message) {
        Map<String, Object> event;
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            event = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse note.deleted message", e);
            throw new RuntimeException(e);
        }

        Long noteId = Long.valueOf(event.get("noteId").toString());
        log.info("Feed received note.deleted: noteId={}", noteId);
        redisTemplate.opsForSet().add(DELETED_KEY, noteId.toString());
    }
}
