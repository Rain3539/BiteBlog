# Recommend Service 非功能测试说明

## 1. 非功能性需求

| 指标 | 要求 | 来源 |
|------|------|------|
| 推荐列表响应时间 | 平均 < 600ms，需求目标 < 1s | 需求说明书 3.6.3 / 概设 6.7 |
| 冷启动可用性 | 新用户无行为时仍能返回推荐内容 | 人员分工 Recommend Service |
| 标签召回性能 | 使用 ES `post_index` 召回候选，避免 MySQL 全量扫描 | 人员分工 Recommend Service |
| ItemCF 召回性能 | 使用 Redis ZSet 读取预计算相似笔记，避免在线全量计算 | 人员分工 Recommend Service |
| MQ 准实时更新 | 消费发帖和互动事件，刷新推荐侧 ES/Redis 数据 | 跨服务联调要求 |
| 行为画像缓存 | Redis `behavior:{userId}` 缓存近期行为，TTL 分钟级 | 人员分工 Recommend Service |
| 作者信息缓存 | Redis `recommend:user:profile:{authorId}` 缓存作者昵称头像，并用批量 `multiGet` 读取，避免列表逐条同步调用 User Service | 并发性能优化 |
| 笔记摘要缓存 | Redis `recommend:post:summary:{postId}` 缓存推荐卡片展示字段，减少热门/冷启动路径 MySQL 查询 | 并发性能优化 |
| 曝光去重准确性 | 同一用户连续请求不重复推荐已曝光内容 | 人员分工 Recommend Service |
| 并发一致性 | Redis Lua 原子预占曝光，降低并发刷新重复推荐 | 概设 6.7 |
| 冷启动降级 | 优先读取 Rank Service 的 `rank:daily:{date}`，Redis 不可用或为空时降级 MySQL 热门笔记 | 需求说明书 3.5 |
| ES 降级 | ES 不可用时降级 Rank 日榜 / Redis 相似池 / MySQL 兜底，不报错 | 需求说明书 3.5 |
| 推荐多样性 | 相邻内容尽量不来自同一作者 | 人员分工 Recommend Service |
| 容量边界 | `size` 最大 50，曝光上报单次最多 100 个 postId | 概设 6.7 |

## 2. 测试总览

| 编号 | 测试项 | 测试方式 | 结果 |
|------|--------|----------|------|
| R-1 | 健康检查与预计算 | PS1 调用 `/recommend/health`、Gateway `/api/recommend/health`、`/recommend/internal/precompute`，检查 `rank:daily:{date}` 和 `recommend:post:summary:{postId}` | **通过** |
| R-2 | MQ 发帖与互动事件消费 | PS1 发布笔记触发 `note.published`，点赞触发 `interaction.like`，检查 ES/Redis 更新 | **通过** |
| R-3 | 冷启动链路冒烟 | PS1 少量请求验证新用户可返回推荐，并记录单次耗时辅助排查 | **通过** |
| R-4 | 个性化链路冒烟 | PS1 少量请求验证有行为用户可返回推荐，并检查 `behavior:{userId}` 与 `recommend:user:profile:{authorId}` TTL | **通过** |
| R-5 | 标签召回链路冒烟 | PS1 少量请求验证 `tag=Hotpot&city=Guangzhou` 可返回结果 | **通过** |
| R-6 | 游标分页与曝光去重 | PS1 连续请求两页，检查 postId 无重复 | **通过** |
| R-7 | 标签召回功能 | PS1 检查 ES 标签召回返回 Hotpot 相关内容 | **通过** |
| R-8 | 曝光 Lua 预占与幂等上报 | redis-cli 检查 `exposure:{userId}`，重复上报同一批 postId | **通过** |
| R-9 | Redis ItemCF 预计算数据 | 查询 Redis `recommend:itemcf:similar:{postId}`，检查相似候选与 score | **通过** |
| R-10 | 边界参数 | `size=1`、`size=999` 验证分页上限 | **通过** |
| R-11 | JMeter 并发压测 | 200 并发线程 x 10 轮，按推荐页读多写少特点覆盖列表召回、翻页、标签召回和曝光上报 | **通过，标签筛选路径仍是最重读路径** |
| R-12 | 一致性验证 | 对照数据一致性测试说明，验证 MQ 事件、ES `post_index`、Rank 日榜、Redis ItemCF、曝光集合的一致性 | **通过 / 可复测** |
| R-13 | 可靠性验证 | 对照可靠性测试说明，验证冷启动、ES 降级、Redis ItemCF 降级、Redis 停止降级和预计算自愈 | **脚本已补充 / 可复测** |
| R-14 | Redis 展示缓存验证 | PS1 检查 `recommend:post:summary:{postId}` 与 `recommend:user:profile:{authorId}` TTL，JMeter 复测验证 200 线程响应时间 | **通过** |

测试产物：

| 类型 | 路径 |
|------|------|
| PS1 验证脚本 | `测试脚本/recommend-test-verify.ps1` |
| PS1 文本结果 | `测试脚本/recommend-test-result.txt` |
| 终端截图 | `测试脚本/recommend-test-截图1.png`、`测试脚本/recommend-test-截图2.png` |
| JMeter 脚本 | `jmeter/recommend-service-test.jmx` |
| JMeter 结果文件 | `jmeter/recommend-service-result.jtl` |
| JMeter 报告目录 | `jmeter/recommendservice-report` |
| JMeter 截图 | `jmeter/recommend-service-截图.png` |

## 3. 测试结果详情

### R-1: 健康检查与预计算

**要求**: Recommend Service 可用，Gateway 路由可用，预计算可手动触发，并能写入 Rank 日榜热度池和 Redis 笔记展示摘要。

**方法**: PS1 调用健康检查和预计算接口。

```text
Direct /recommend/health: 3ms OK
Gateway /api/recommend/health: 10ms OK
Manual precompute rank daily and Redis ItemCF: 260ms OK
Redis rank daily key=rank:daily:2026-05-19, count=...
post summary cache key=recommend:post:summary:..., TTL=1800 seconds
```

- **预计算内容**: 重建 Redis `rank:daily:{date}`、Redis ItemCF 相似池，并写入 `recommend:post:summary:{postId}` 展示摘要
- **结论**: 通过。服务直连、网关访问和准实时预计算接口均正常。

### R-2: MQ 发帖与互动事件消费

**要求**: Recommend Service 能消费 Post Service 发出的发帖和互动事件，并准实时刷新推荐侧 ES/Redis 数据。

**方法**: PS1 通过 Gateway 调用 Post Service 发布一条笔记，触发 `note.published`；等待 Recommend 消费后检查 ES `post_index` 和 Redis `rank:daily:{date}`。随后用另一个用户点赞该笔记，触发 `interaction.like`，检查 Rank 日榜分数刷新。

预期输出示例：

```text
publish note via Post Service: OK
MQ note.published -> ES post_index: OK
note.published consumed: postId=..., ES found=True, rankDailyScore=...
like note to publish interaction.like: OK
interaction.like consumed: rankDailyScoreBefore=..., rankDailyScoreAfter=...
```

- **结论**: 通过。脚本结果显示 `note.published` 消费后 ES `post_index` 能查到新笔记，`interaction.like` 消费后 Rank 日榜分数会按最新笔记统计重新写入。

### R-3: 冷启动链路冒烟

**要求**: 新用户也能返回推荐；串行脚本只做低量冒烟和问题定位，正式响应时间以 R-11 JMeter 并发压测为准。

**方法**: 使用 `13800000060` 冷启动用户默认请求 3 次，可通过 `-SmokeSamples` 调整次数。

| 指标 | 结果 |
|------|------|
| 冒烟平均耗时 | **190ms** |
| 最小响应时间 | 164ms |
| 最大响应时间 | 212ms |
| 返回条数 | 20 |
| hasMore | True |

```text
cold-start smoke latency avg=190ms, min=164ms, max=212ms, target<1000ms
result count=20, hasMore=True, cursor=21
```

- **结论**: 通过。Rank 日榜冷启动结果稳定返回；性能结论见 R-11 的 200 并发压测。

### R-4: 个性化链路冒烟

**要求**: 有行为用户能返回个性化推荐，并能写入/读取行为画像与作者信息缓存。

**方法**: 使用 `13800000005` 个性化用户默认请求 3 次，可通过 `-SmokeSamples` 调整次数。

| 指标 | 结果 |
|------|------|
| 冒烟平均耗时 | **208ms** |
| 最小响应时间 | 166ms |
| 最大响应时间 | 260ms |
| 返回条数 | 20 |
| hasMore | True |

```text
personalized smoke latency avg=208ms, min=166ms, max=260ms, target<1000ms
result count=20, hasMore=True, cursor=20
behavior cache key=behavior:10, TTL=300 seconds
user profile cache key=recommend:user:profile:6, TTL=1800 seconds
```

- **结论**: 通过。在线请求只做 ES 召回、行为画像缓存读取、曝光过滤、轻量排序和作者打散；作者昵称和头像通过 Redis `recommend:user:profile:{authorId}` 缓存，避免推荐列表每条内容都同步调用 User Service。

### R-5: 标签召回链路冒烟

**要求**: 标签/城市过滤走 ES `post_index` 召回候选；串行脚本只验证链路可用，正式性能看 R-11。

**方法**: 默认请求 3 次 `tag=Hotpot&city=Guangzhou`，可通过 `-SmokeSamples` 调整次数。

| 指标 | 结果 |
|------|------|
| 冒烟平均耗时 | **208ms** |
| 最小响应时间 | 177ms |
| 最大响应时间 | 238ms |
| 返回条数 | 20 |

```text
tag-recall smoke latency avg=208ms, min=177ms, max=238ms, target<1000ms
result count=20, hasMore=True, cursor=20
```

- **结论**: 通过。ES 标签召回链路可用；并发响应时间见 R-11。

### R-6: 游标分页与曝光去重

**要求**: 连续翻页不重复，已曝光内容不再次返回。

**方法**: 清空用户曝光集合后请求两页，比较两页 `postId`。

| 页码 | postIds | hasMore | cursor |
|------|---------|---------|--------|
| 1 | [42,43,44,45,46,47,48,49,50,51] | True | 11 |
| 2 | [53,59,54,60,55,61,56,62,57,58] | True | 22 |

```text
pagination check: PASS, no duplicates in first two pages
```

- **结论**: 通过。分页结果未发现重复 postId。

### R-7: 标签召回功能

**要求**: `Hotpot + Guangzhou` 能召回相关内容，并在 ES 不可用时具备 MySQL 降级能力。

**方法**: 请求 `GET /recommend/discover?tag=Hotpot&city=Guangzhou`。

```text
tag=Hotpot city=Guangzhou: 232ms OK
top titles=[Recommend Test 11 Hotpot | Recommend Test 21 Hotpot | Recommend Test 05 Noodles | Recommend Test 19 Cantonese | Recommend Test 02 BBQ]
```

- **结论**: 通过。结果包含 Hotpot 相关内容，同时补量逻辑会加入其他候选，避免列表过短。

### R-8: 曝光 Lua 预占与幂等上报

**要求**: 曝光记录实时写入 Redis Set，重复上报不产生重复数据。

**方法**: 查询 `exposure:{userId}`，重复调用 `/recommend/exposures`。

| 指标 | 结果 |
|------|------|
| 曝光集合 | `exposure:65` |
| 初始 members | ["41","52"] |
| 重复上报样例 | [42,43,44] |
| Redis SCARD | 5 |
| 上报接口 | 8ms / 5ms |

```text
exposures-post-1: 8ms OK
exposures-post-2-repeat: 5ms OK
repeated exposure ids=[42,43,44], Redis SCARD=5
```

- **结论**: 通过。Redis Set 天然幂等，Lua 预占降低并发重复推荐概率。
- **备注**: 测试结果中的 `redis-cli -a` 安全警告来自 Redis 命令行提示，不影响测试结果。

### R-9: Redis ItemCF 预计算数据

**要求**: ItemCF 相似度预计算后写入 Redis ZSet。

**方法**: 查询 `recommend:itemcf:similar:{postId}` 中某个 `postId` 的相似候选。

```text
Redis recommend:itemcf:similar:42 => [41,11.325,45,6.325]
```

- **结论**: 通过。ItemCF 已由预计算任务写入 Redis，在线推荐请求只读取 TopN 相似候选。

### R-10: 边界参数

**要求**: 分页参数有上限，异常 size 不导致一次拉取过多数据。

**方法**: 请求 `size=1` 和 `size=999`。

| 参数 | 结果 |
|------|------|
| `size=1` | 返回 1 条 |
| `size=999` | 返回 29 条，未超过最大上限 50 |

```text
size=1: 21ms OK
size=999 capped to 50: 261ms OK
size=1 count=1
size=999 count=29, expected <= 50
```

- **结论**: 通过。最大分页限制生效。

### R-11: JMeter 并发压测

**要求**: 支持一定并发用户量，接口错误率应为 0%，混合场景平均响应时间低于 1s。

**方法**: 使用 `jmeter/recommend-service-test.jmx` 直连 Recommend Service 8084 端口压测。推荐服务的主要成本在 ES 候选召回、Redis 曝光过滤和内存排序，压测时不把 Gateway JWT 鉴权算入推荐服务耗时；同时用随机 `X-User-Id` 分散曝光状态，避免同一用户反复刷新导致曝光集合耗尽。

脚本配置为 200 个并发线程、每线程 10 轮，每轮执行 5 个请求，总请求数 10,000。其中 4 个请求为推荐读取，1 个请求为曝光上报，符合发现页“读多写少”的实际使用特点。本次统计是并发压测结果，不是单个 curl 请求的串行响应时间。

| 压测接口 | 验证点 |
|------|------|
| `GET /recommend/discover?cursor=0&size=20` | 默认发现页推荐列表 |
| `GET /recommend/discover?cursor=0&size=5` | 小分页场景 |
| `GET /recommend/discover?cursor=0&size=20&tag=Hotpot&city=Guangzhou` | 标签召回路径 |
| `POST /recommend/exposures` | 曝光上报与 Redis 幂等写入 |
| `GET /recommend/discover?cursor=20&size=20` | 翻页推荐场景 |

压测前建议执行：

```powershell
curl.exe -X POST http://localhost:8084/recommend/internal/precompute
```

JMeter 命令：

```powershell
jmeter -n -f -t jmeter/recommend-service-test.jmx `
  -Jhost=localhost `
  -Jport=8084 `
  -l jmeter/recommend-service-result.jtl `
  -e -o jmeter/recommendservice-report
```

报告生成后打开：

```text
jmeter/recommendservice-report/index.html
```

测试结果记录位置：

| 指标 | 结果 |
|------|------|
| 总请求数 | **10,000** |
| 错误数 / 错误率 | **0 / 0%** |
| 平均响应时间 | **236ms** |
| 中位数响应时间 | **176ms** |
| 最小 / 最大响应时间 | 3ms / 1285ms |
| 90% / 95% / 99% 响应时间 | 588ms / 724ms / 879ms |
| 吞吐量 | **518.9 req/s** |

分接口结果：

| 接口场景 | 请求数 | 错误率 | 平均响应时间 | 99% 响应时间 |
|------|------|------|------|------|
| 默认推荐列表 `size=20` | 2000 | 0% | 202ms | 660ms |
| 小分页 `size=5` | 2000 | 0% | 189ms | 476ms |
| 标签 + 城市召回 | 2000 | 0% | 568ms | 1201ms |
| 曝光上报 | 2000 | 0% | 15ms | 60ms |
| 下一页推荐 | 2000 | 0% | 205ms | 601ms |

- **结论**: 通过。Redis 笔记摘要缓存和作者信息批量缓存生效后，200 并发线程、10,000 次混合请求错误率为 0%，总体平均响应时间从优化前约 2479ms 降至 236ms，吞吐量达到 518.9 req/s；默认推荐、小分页、翻页和曝光上报都保持在较低延迟，标签 + 城市召回平均 568ms，是当前最重的筛选读路径。
- **截图要求**: 从 `jmeter/recommendservice-report/index.html` 截取 Dashboard 首页和 Statistics 表格，保存到 `jmeter/recommend-service-截图.png`。

### R-14: Redis 展示缓存与远程调用压缩

**要求**: 推荐列表接口避免每条内容都同步调用其他服务，并减少冷启动/热门路径的 MySQL 查询，降低 200 线程并发下的远程调用和数据库访问放大。

**优化点**:

- 推荐列表不再逐条调用 Post Service 补全详情，直接使用 Recommend 本地 `note` 表字段和 `note_image` 批量封面。
- User Service 仍作为作者信息来源，但增加 Redis 缓存 `recommend:user:profile:{authorId}`，TTL 30 分钟；推荐列表按作者去重后批量 `multiGet` 读取缓存，缓存命中时不再同步 Feign 调用。
- 预计算和 MQ 事件刷新时写入 Redis `recommend:post:summary:{postId}`，保存标题、封面、店名、作者、计数和创建时间；冷启动/热门路径在线请求先批量 `multiGet` 摘要，缓存缺失才回源 MySQL 并回填 Redis。
- PS1 脚本在个性化推荐后检查作者缓存 TTL，证明推荐请求已写入作者信息缓存。

**验证方式**:

```powershell
.\测试脚本\recommend-test-verify.ps1
```

预期输出：

```text
post summary cache key=recommend:post:summary:{postId}, TTL=... seconds
user profile cache key=recommend:user:profile:{authorId}, TTL=... seconds
```

然后重启 Recommend Service，重新执行 R-11 的 JMeter 命令，对比优化前 2479ms 基线。

- **当前状态**: 代码已实现，Maven 编译通过；200 线程 JMeter 复测错误率 0%，平均响应时间 236ms。

### R-12: 一致性验证

**要求**: 推荐侧准实时数据与上游业务事件保持最终一致，重点验证发帖、删帖、互动事件和曝光去重。

| 一致性点 | 验证方式 | 对应结果 |
|------|------|------|
| 发帖事件一致性 | 发布笔记后检查 ES `post_index` 能查到该笔记 | R-2 |
| 互动事件一致性 | 点赞后检查 Redis `rank:daily:{date}` 分数刷新 | R-2 |
| 预计算一致性 | 手动触发 `/recommend/internal/precompute` 后检查 `rank:daily:{date}` 和 Redis ItemCF | R-1 / R-9 |
| 曝光一致性 | 重复上报同一批 postId，Redis Set 不产生重复 member | R-8 |
| 翻页一致性 | 连续两页结果 postId 不重复 | R-6 |

- **结论**: 推荐服务采用“MQ 事件驱动 + Redis/ES 最终一致”的方式。发布、互动等强业务写入仍以 MySQL 为准，Recommend 消费事件后刷新 ES `post_index`、Rank 日榜和 Redis ItemCF；曝光去重用 Redis Lua/Set 做请求内原子预占，优先保证用户一次推荐流内不重复。
- **截图引用**: 该部分复用 PS1 终端截图 `测试脚本/recommend-test-截图1.png`、`测试脚本/recommend-test-截图2.png`、`测试脚本/recommend-test-截图3.png`。

### R-13: 可靠性验证

**要求**: ES、Rank 日榜或 Redis 相似池部分不可用/数据为空时，推荐页仍尽量返回内容，不直接报错。

| 可靠性点 | 降级链路 | 对应结果 |
|------|------|------|
| 冷启动兜底 | `rank:daily:{date}` -> MySQL 热门笔记 | R-3 |
| 标签召回兜底 | ES `post_index` 召回失败或不足 -> 热门候选补量 | R-7 |
| ItemCF 兜底 | Redis `recommend:itemcf:similar:{postId}` 为空 -> MySQL `user_behavior` 在线扫描补量；候选仍不足时由外层 Rank/MySQL 补量 | R-9 / R-13 |
| 曝光组件兜底 | Redis 曝光读取异常时记录 warn，并跳过曝光过滤，保证接口尽量返回 | R-8 |
| 容量边界保护 | `size` 最大截断到 50，曝光上报最多处理 100 个 postId | R-10 |

破坏性测试已补充到 PS1 脚本，默认不执行，避免普通测试时停 ES/Redis。需要完整验证时运行：

```powershell
.\测试脚本\recommend-test-verify.ps1 -RunDestructiveReliabilityTests
```

如果需要展示“等待定时任务自愈”的证据，使用：

```powershell
.\测试脚本\recommend-test-verify.ps1 -RunDestructiveReliabilityTests -WaitScheduledSelfHealing
```

该命令会额外等待约 11 分钟，让 `@Scheduled(fixedDelay=10min)` 有时间自动刷新 Rank 日榜和 Redis ItemCF。

新增破坏性场景：

| 编号 | 场景 | 预期结果 |
|------|------|----------|
| R-13-1 | 删除 `rank:daily:{date}` 后请求冷启动推荐 | 降级 MySQL 热门笔记，接口返回 `code=200` |
| R-13-2 | 删除 `recommend:itemcf:similar:*` 后请求个性化推荐 | 降级 MySQL 行为扫描，接口返回推荐列表 |
| R-13-3 | 停止 Elasticsearch 后请求 `tag=Hotpot&city=Guangzhou` | 降级 Rank 日榜 / MySQL 兜底，接口不报错 |
| R-13-4 | 停止 Redis 后请求冷启动推荐 | 降级 MySQL 热门笔记，曝光预占降级为本地过滤，接口尽量返回 |
| R-13-5 | 手动触发 `/recommend/internal/precompute` | Rank 日榜和 Redis ItemCF 相似池恢复 |

- **结论**: 推荐服务没有把推荐结果整体缓存为强依赖，而是把候选召回、热度、ItemCF 和曝光状态拆成可降级的数据源；单个召回源异常时走补量或兜底，保证发现页优先可用。
- **截图要求**: 运行破坏性测试后，截取终端中 `===== 8. Destructive reliability tests =====` 段落，保存为 `测试脚本/recommend-reliability-截图.png`，并在汇报时作为 R-13 证据。
- **截图引用**: 该部分复用 PS1 终端截图；破坏性测试截图保存为 `测试脚本/recommend-reliability-截图.png` 后追加到本文。

## 4. 测试截图

PS1 脚本测试结果截图：

![Recommend PS1 测试截图 1](../测试脚本/recommend-test-截图1.png)

![Recommend PS1 测试截图 2](../测试脚本/recommend-test-截图2.png)

Recommend 可靠性破坏性测试截图：

![Recommend 可靠性破坏性测试截图](../测试脚本/recommend-test-截图3.png)

JMeter 压测截图：


![Recommend JMeter 压测截图](../jmeter/recommend-service-截图.png)
