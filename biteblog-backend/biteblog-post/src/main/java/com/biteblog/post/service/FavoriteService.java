package com.biteblog.post.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.post.entity.Note;
import com.biteblog.post.entity.NoteFavorite;
import com.biteblog.post.mapper.NoteFavoriteMapper;
import com.biteblog.post.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final NoteFavoriteMapper favoriteMapper;
    private final NoteMapper noteMapper;
    private final PostEventPublisher eventPublisher;

    @Transactional
    public boolean toggleFavorite(Long noteId, Long userId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || note.getStatus() == 0) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        NoteFavorite existing = favoriteMapper.selectOne(
                new LambdaQueryWrapper<NoteFavorite>()
                        .eq(NoteFavorite::getNoteId, noteId)
                        .eq(NoteFavorite::getUserId, userId)
        );

        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            noteMapper.update(null,
                    new LambdaUpdateWrapper<Note>()
                            .eq(Note::getId, noteId)
                            .set(Note::getUpdatedAt, LocalDateTime.now())
                            .setSql("collect_count = collect_count - 1"));
            eventPublisher.publishInteraction(noteId, userId, note.getAuthorId(), "collect", "remove");
            return false;
        }

        NoteFavorite favorite = new NoteFavorite();
        favorite.setNoteId(noteId);
        favorite.setUserId(userId);
        favorite.setCreatedAt(LocalDateTime.now());
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            return true;
        }
        noteMapper.update(null,
                new LambdaUpdateWrapper<Note>()
                        .eq(Note::getId, noteId)
                        .set(Note::getUpdatedAt, LocalDateTime.now())
                        .setSql("collect_count = collect_count + 1"));
        eventPublisher.publishInteraction(noteId, userId, note.getAuthorId(), "collect", "add");
        return true;
    }
}
