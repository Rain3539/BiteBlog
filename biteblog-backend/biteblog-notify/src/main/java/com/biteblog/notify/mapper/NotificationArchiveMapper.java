package com.biteblog.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biteblog.notify.entity.NotificationArchive;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationArchiveMapper extends BaseMapper<NotificationArchive> {

    /**
     * INSERT IGNORE：若归档任务重复执行（如服务重启导致定时任务重跑），
     * 已存在的记录直接跳过，不报错，保证幂等。
     */
    @Insert("INSERT IGNORE INTO notification_archive" +
            "(id, receiver_id, sender_id, type, biz_id, content, read_status, created_at, archived_at)" +
            " VALUES" +
            "(#{id}, #{receiverId}, #{senderId}, #{type}, #{bizId}, #{content}, #{readStatus}, #{createdAt}, #{archivedAt})")
    int insertIgnore(NotificationArchive archive);
}
