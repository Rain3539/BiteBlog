# Rank Service 非功能测试说明

## 1. 非功能性需求

| 指标 | 要求 | 来源/实现 |
|------|------|-----------|
| 热榜读取响应时间 | Top10 平均响应时间 < 100ms，常规读取 < 300ms | Rank Service 使用 Redis ZSet 缓存榜单 |
| 缓存可用性 | Redis 榜单为空时可自动重建 | `RankService.ensureCache` 触发 `rebuild` |
| 数据一致性 | 发布、点赞、收藏、评论事件后热度分数及时刷新 | RabbitMQ 消费 `note.published` 与 `interaction.*` |
| 容量边界 | 分页 `size` 限制 1 ~ 50，单榜缓存最多 200 条 | Controller 参数约束 + `RankService.trim` |
| 排名准确性 | 日榜、周榜、总榜按热度公式稳定排序 | `like*3 + collect*5 + comment*4 + quality + timeBonus` |
| 安全性 | 通过 Gateway 访问写操作时要求登录态 | Gateway JWT 过滤器注入用户身份 |
| 可维护性 | 榜单类型、缓存 key、热度公式集中维护 | `RankService` 统一封装 |

## 2. 测试总览

| 编号 | 测试项 | 测试方式 | 结果 |
|------|--------|----------|------|
| R-1 | 基础组件与测试账号 | PowerShell 脚本检查 Redis、RabbitMQ、作者/互动用户登录 | **通过** |
| R-2 | 健康检查 | `GET /rank/health` | **通过** |
| R-3 | 榜单重建 | `POST /rank/rebuild?type=daily/weekly/all` | **通过** |
| R-4 | Top10 响应时间 | 通过 Gateway 连续调用 20 次 `GET /api/rank/top10?type=daily` | **通过** |
| R-5 | 发布入榜 | 作者发布笔记后检查 Redis 日榜分数 | **通过** |
| R-6 | 互动刷新热度 | 点赞、收藏、评论后检查分数递增 | **通过** |
| R-7 | 榜单分页查询 | `GET /api/rank/list?type=daily&page=1&size=50` | **通过** |
| R-8 | RabbitMQ 消费状态 | 检查 rank 相关队列 ready/unacked/consumer | **通过** |
| R-9 | JMeter 并发压测 | `rank-service-result.jtl` 统计 800 次请求 | **通过** |

## 3. 测试结果详情

### R-1: 基础组件与测试账号

**方法**: 执行 `测试脚本/rank-test-verify.ps1`

- Redis 容器: `biteblog-redis`，运行正常
- RabbitMQ 容器: `biteblog-rabbitmq`，运行正常
- 作者账号: `13800000001`，登录成功，`userId=1`
- 互动账号: `13800000004`，登录成功，`userId=4`

### R-2: Rank Service 健康检查

**方法**: 调用 `GET /rank/health`

| 字段 | 值 |
|------|----|
| service | rank-service |
| status | UP |

- **结论**: Rank Service 可正常响应健康检查。

### R-3: 榜单缓存重建

**方法**: 分别调用 `POST /rank/rebuild?type=daily`、`weekly`、`all`

| 榜单类型 | 结果 |
|----------|------|
| daily | rebuilt=true |
| weekly | rebuilt=true |
| all | rebuilt=true |

- **结论**: 日榜、周榜、总榜均可手动重建缓存。

### R-4: Top10 响应时间

**要求**: 平均响应时间 < 100ms  
**方法**: 通过 Gateway 连续调用 20 次 `GET /api/rank/top10?type=daily`

```text
第1次: 1097ms  第2次: 35ms   第3次: 31ms   第4次: 34ms   第5次: 34ms
第6次: 32ms    第7次: 33ms   第8次: 30ms   第9次: 30ms   第10次: 30ms
第11次: 40ms   第12次: 31ms  第13次: 36ms  第14次: 28ms  第15次: 30ms
第16次: 28ms   第17次: 30ms  第18次: 31ms  第19次: 31ms  第20次: 30ms
平均: 85.05ms
```

- **结论**: 平均 85.05ms，满足 < 100ms 目标；首个请求包含冷启动/路由预热开销，后续请求稳定在约 28ms ~ 40ms。

### R-5: 发布笔记进入热榜

**方法**: 使用 `13800000001` 发布测试笔记，检查 Redis 日榜 ZSet。

| 项目 | 值 |
|------|----|
| 发布 postId | 2 |
| 标题 | `RankEventTest-191150` |
| 初始热度分数 | 29 |
| Redis key | `rank:daily:2026-05-19` |

- **结论**: `note.published` 事件被 Rank Service 消费，新笔记成功进入日榜。

### R-6: 点赞、收藏、评论刷新热度

**方法**: 使用 `13800000004` 对 `postId=2` 执行点赞、收藏、评论。

| 操作 | 操作前分数 | 操作后分数 | 结果 |
|------|------------|------------|------|
| 点赞 | 29 | 32 | 通过 |
| 收藏 | 32 | 37 | 通过 |
| 评论 | 37 | 41 | 通过 |

- **结论**: 互动事件通过 RabbitMQ 到达 Rank Service，热度分数按权重递增。

### R-7: 榜单分页查询

**方法**: 调用 `GET /api/rank/list?type=daily&page=1&size=50`

| rankNo | postId | hotScore | title |
|--------|--------|----------|-------|
| 1 | 2 | 41.0 | RankEventTest-191150 |
| 2 | 1 | 41.0 | RankEventTest-190740 |

- **结论**: 分页接口返回 `page=1,size=50,total=2`，新发布笔记存在于日榜结果中。

### R-8: RabbitMQ 队列诊断

**方法**: 检查 rank 相关队列消息堆积和消费者状态。

| 队列 | messages_ready | messages_unacknowledged | consumers |
|------|----------------|-------------------------|-----------|
| rank.note.published.queue | 0 | 0 | 1 |
| rank.note.deleted.queue | 0 | 0 | 1 |
| rank.interaction.queue | 0 | 0 | 1 |

- **结论**: Rank 事件队列无积压，消费者在线。

### R-9: JMeter 并发压测

**方法**: 使用 `jmeter/rank-service-result.jtl` 汇总压测结果。

| 指标 | 值 |
|------|----|
| 总请求数 | 800 |
| 错误数 / 错误率 | 0 / 0% |
| 平均响应时间 | 19.03ms |
| 最小 / 最大响应时间 | 2ms / 115ms |
| 吞吐量 | 161.32 req/s |

- **结论**: JMeter 压测全部通过，800 次请求无失败，平均响应时间 19.03ms。

## 4. 测试截图
![](../测试脚本/rank-test截图.png)

![alt text](../jmeter/rank-service截图.png)
