<template>
  <div class="profile">
    <h3>个人主页 #{{ $route.params.id || '当前用户' }}</h3>
    <el-empty description="待开发 - 负责人: 组员2 (User Service)">
      <template #description>
        <p>用户信息 + 粉丝/关注 + 我的笔记</p>
        <p>接口: GET /api/user/:id</p>
      </template>
    </el-empty>
  </div>
</template>
