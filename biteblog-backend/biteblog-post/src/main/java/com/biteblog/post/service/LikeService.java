package com.biteblog.post.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.post.entity.Note;
import com.biteblog.post.entity.NoteLike;
import com.biteblog.post.mapper.NoteLikeMapper;
import com.biteblog.post.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final NoteLikeMapper likeMapper;
    private final NoteMapper noteMapper;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public boolean toggleLike(Long noteId, Long userId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || note.getStatus() == 0) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        NoteLike existing = likeMapper.selectOne(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getNoteId, noteId)
                        .eq(NoteLike::getUserId, userId)
        );

        if (existing != null) {
            // 取消点赞
            likeMapper.deleteById(existing.getId());
            noteMapper.update(null,
                    new LambdaUpdateWrapper<Note>()
                            .eq(Note::getId, noteId)
                            .setSql("like_count = like_count - 1"));
            return false;
        } else {
            // 点赞（唯一约束保证并发安全）
            NoteLike like = new NoteLike();
            like.setNoteId(noteId);
            like.setUserId(userId);
            try {
                likeMapper.insert(like);
            } catch (DuplicateKeyException e) {
                // 并发重复点赞，幂等返回已赞
                return true;
            }
            noteMapper.update(null,
                    new LambdaUpdateWrapper<Note>()
                            .eq(Note::getId, noteId)
                            .setSql("like_count = like_count + 1"));
            // 异步通知排行 + 通知服务
            Map<String, Object> event = Map.of(
                    "noteId", noteId,
                    "userId", userId,
                    "authorId", note.getAuthorId(),
                    "type", "like"
            );
            rabbitTemplate.convertAndSend("biteblog.interaction", "interaction.like", event);
            return true;
        }
    }
}
