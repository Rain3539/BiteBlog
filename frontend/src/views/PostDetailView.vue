<template>
  <div class="post-detail">
    <h3>笔记详情 #{{ $route.params.id }}</h3>
    <el-empty description="待开发 - 负责人: 组员3 (Post Service)">
      <template #description>
        <p>笔记内容 + 图片 + 点赞/收藏/评论</p>
        <p>接口: GET /api/post/:id</p>
      </template>
    </el-empty>
  </div>
</template>
