package com.biteblog.feed.service;

import com.biteblog.feed.config.FeedRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedEventListener {

    private final StringRedisTemplate redisTemplate;

    private static final String INBOX_PREFIX = "feed:inbox:";
    private static final String DELETED_KEY = "feed:deleted";
    private static final String BIG_V_KEY = "feed:bigv";
    private static final int BIG_V_THRESHOLD = 50;

    @RabbitListener(queues = FeedRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Map<String, Object> event) {
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
    }

    @RabbitListener(queues = FeedRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Map<String, Object> event) {
        Long noteId = Long.valueOf(event.get("noteId").toString());
        log.info("Feed received note.deleted: noteId={}", noteId);
        redisTemplate.opsForSet().add(DELETED_KEY, noteId.toString());
    }
}
