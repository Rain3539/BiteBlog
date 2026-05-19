package com.biteblog.post.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.biteblog.common.exception.BusinessException;
import com.biteblog.common.result.ErrorCode;
import com.biteblog.post.dto.CommentVO;
import com.biteblog.post.entity.Comment;
import com.biteblog.post.entity.Note;
import com.biteblog.post.entity.UserInfo;
import com.biteblog.post.mapper.CommentMapper;
import com.biteblog.post.mapper.NoteMapper;
import com.biteblog.post.mapper.UserInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final NoteMapper noteMapper;
    private final UserInfoMapper userInfoMapper;
    private final PostEventPublisher eventPublisher;

    @Transactional
    public Long publishComment(Long noteId, Long userId, String content, Long parentId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || note.getStatus() == 0) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        Comment comment = new Comment();
        comment.setNoteId(noteId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setParentId(parentId);
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        noteMapper.update(null,
                new LambdaUpdateWrapper<Note>()
                        .eq(Note::getId, noteId)
                        .set(Note::getUpdatedAt, LocalDateTime.now())
                        .setSql("comment_count = comment_count + 1"));

        eventPublisher.publishInteraction(noteId, userId, note.getAuthorId(), "comment", "add");

        return comment.getId();
    }

    public Map<String, Object> getComments(Long noteId, int page, int size) {
        Page<Comment> pageParam = new Page<>(page, size);
        Page<Comment> commentPage = commentMapper.selectPage(pageParam,
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getNoteId, noteId)
                        .isNull(Comment::getParentId)
                        .eq(Comment::getStatus, 1)
                        .orderByDesc(Comment::getCreatedAt)
        );

        List<CommentVO> list = commentPage.getRecords().stream().map(c -> {
            CommentVO vo = new CommentVO();
            vo.setCommentId(c.getId());
            vo.setUserId(c.getUserId());
            vo.setContent(c.getContent());
            vo.setParentId(c.getParentId());
            vo.setCreatedAt(c.getCreatedAt());

            // 查询二级回复
            List<Comment> replies = commentMapper.selectList(
                    new LambdaQueryWrapper<Comment>()
                            .eq(Comment::getParentId, c.getId())
                            .eq(Comment::getStatus, 1)
                            .orderByAsc(Comment::getCreatedAt)
            );
            List<CommentVO> replyVOs = replies.stream().map(r -> {
                CommentVO rvo = new CommentVO();
                rvo.setCommentId(r.getId());
                rvo.setUserId(r.getUserId());
                rvo.setContent(r.getContent());
                rvo.setParentId(r.getParentId());
                rvo.setCreatedAt(r.getCreatedAt());
                return rvo;
            }).collect(Collectors.toList());
            vo.setReplies(replyVOs);

            return vo;
        }).collect(Collectors.toList());

        fillCommentUsernames(list);

        return Map.of("list", list, "total", commentPage.getTotal());
    }

    private void fillCommentUsernames(List<CommentVO> list) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (CommentVO vo : list) {
            if (vo.getUserId() != null) userIds.add(vo.getUserId());
            if (vo.getReplies() != null) {
                for (CommentVO r : vo.getReplies()) {
                    if (r.getUserId() != null) userIds.add(r.getUserId());
                }
            }
        }
        if (userIds.isEmpty()) return;
        List<UserInfo> users = userInfoMapper.selectBatchIds(userIds);
        Map<Long, UserInfo> userMap = users.stream().collect(Collectors.toMap(UserInfo::getId, u -> u));
        for (CommentVO vo : list) {
            UserInfo u = userMap.get(vo.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setAvatar(u.getAvatar());
            }
            if (vo.getReplies() != null) {
                for (CommentVO r : vo.getReplies()) {
                    UserInfo ru = userMap.get(r.getUserId());
                    if (ru != null) {
                        r.setUsername(ru.getUsername());
                        r.setAvatar(ru.getAvatar());
                    }
                }
            }
        }
    }
}
