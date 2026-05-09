package com.biteblog.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biteblog.post.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
