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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final NoteLikeMapper likeMapper;
    private final NoteMapper noteMapper;
    private final PostEventPublisher eventPublisher;

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
            likeMapper.deleteById(existing.getId());
            noteMapper.update(null,
                    new LambdaUpdateWrapper<Note>()
                            .eq(Note::getId, noteId)
                            .set(Note::getUpdatedAt, LocalDateTime.now())
                            .setSql("like_count = like_count - 1"));
            eventPublisher.publishInteraction(noteId, userId, note.getAuthorId(), "like", "remove");
            return false;
        }

        NoteLike like = new NoteLike();
        like.setNoteId(noteId);
        like.setUserId(userId);
        like.setCreatedAt(LocalDateTime.now());
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            return true;
        }
        noteMapper.update(null,
                new LambdaUpdateWrapper<Note>()
                        .eq(Note::getId, noteId)
                        .set(Note::getUpdatedAt, LocalDateTime.now())
                        .setSql("like_count = like_count + 1"));
        eventPublisher.publishInteraction(noteId, userId, note.getAuthorId(), "like", "add");
        return true;
    }
}
