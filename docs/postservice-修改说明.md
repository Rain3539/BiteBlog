# Post Service 修改说明

## 1. 服务定位

Post Service 是 BiteBlog 的笔记内容服务，服务端口为 `8082`，负责笔记 CRUD、图片上传（MinIO）、ES 全文搜索、点赞/收藏/评论互动。作为系统的核心内容服务，它是以下链路的数据源头：

1. **发布链路**：前端 PublishView → `POST /api/post/publish` → MySQL 事务写入（note + note_image）→ MQ `note.published` → 下游 Feed/Rank/Location/Recommend 服务消费。
2. **阅读链路**：前端 PostDetailView → `GET /api/post/{id}` → Redis Cache-Aside（命中直接返回，未命中查 MySQL 回填）。
3. **搜索链路**：前端 SearchOverlay → `GET /api/post/search` → ES `multiMatch` 查询（title/content/shopName，IK 分词）→ 降级返回空列表。
4. **互动链路**：前端点赞/收藏/评论 → `POST /api/post/{id}/like|favorite|comment` → MySQL 更新计数器 + MQ `interaction.*` → 下游 Rank/Notify/Recommend 消费。

## 2. 新增与调整文件

| 文件 | 说明 |
|------|------|
| `biteblog-backend/biteblog-post/pom.xml` | 新增 `spring-boot-starter-validation` 依赖（Spring Boot 3.x 已将 validation 从 web starter 中移除） |
| `biteblog-backend/biteblog-post/src/main/resources/application.yml` | 新增 MinIO 配置（endpoint/access-key/secret-key/bucket） |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/entity/*.java` | 5 个实体类：Note、NoteImage、NoteLike、NoteFavorite、Comment |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/mapper/*.java` | 5 个 Mapper 接口，均继承 BaseMapper |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/dto/*.java` | DTO/VO：PublishNoteRequest、PostDetailVO、CommentRequest、CommentVO、EsPostDocument |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/config/MinioConfig.java` | MinIO Client Bean 配置 |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/ImageService.java` | 图片上传 MinIO，自动建 bucket，返回可访问 URL |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/PostService.java` | 核心服务：发布（事务）、详情（Cache-Aside）、删除（逻辑）、搜索（ES with 降级） |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/LikeService.java` | 点赞/取消点赞：UNIQUE KEY + 原子计数器 |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/FavoriteService.java` | 收藏/取消收藏：与 Like 相同的幂等模式 |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/CommentService.java` | 评论发布 + 分页查询（一层回复，不嵌套） |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/service/EsSyncService.java` | ES 同步：NativeQuery + multiMatch 三字段检索，IK 分词，status=1 过滤，异常降级 |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/event/PostEventPublisher.java` | MQ 发布器：afterCommit 回调发送 `note.published` / `note.deleted` |
| `biteblog-backend/biteblog-post/src/main/java/com/biteblog/post/controller/PostController.java` | 9 个 REST 端点（详见第 3 节） |
| `frontend/src/api/post.js` | 9 个前端 API 函数（uploadImage / publishPost / getPostDetail / likePost / favoritePost / commentPost / getPostComments / searchPosts / getUserPosts 等） |
| `frontend/src/views/PublishView.vue` | 发布页面：图片上传、标题/内容、POI 搜索、评分、表单验证、发布提交 |
| `frontend/src/views/PostDetailView.vue` | 详情页面：图片轮播、作者信息、评分展示、内容、店铺信息、点赞/收藏按钮、评论系统（含回复+分页）、删除按钮（仅作者可见） |
| `frontend/src/components/SearchOverlay.vue` | 搜索浮层：ES 全文搜索、结果列表展示 |
| `frontend/src/components/layout/AppLayout.vue` | 布局重构：将 `el-menu` 替换为 `<router-link>` 平铺导航，"发布"按钮独立置于 header-right |
| `jmeter/post-service-test.jmx` | JMeter 并发压测脚本：setUp 登录 → 4 线程组 100 并发，峰值 400 线程 |
| `测试脚本/post-test-verify.ps1` | 全量验证脚本：15 项测试覆盖一致性/可靠性/性能/安全 |
| `docs/postservice-测试说明.md` | 测试说明文档：14 项测试结果、设计要点 |
| `docs/postservice-修改说明.md` | 本修改说明（当前文件） |

## 3. 接口说明

所有接口均通过 Gateway（8080）以 `/api/post/*` 访问。

### 3.1 `POST /api/post/upload-image`

上传图片到 MinIO，返回可访问 URL。`Content-Type: multipart/form-data`。

### 3.2 `POST /api/post/publish`

发布笔记。`@Valid` 校验 title/content 非空，`@Transactional` 保证 note + note_image 原子写入，`afterCommit` 发送 `note.published` MQ 事件。

### 3.3 `GET /api/post/{id}`

笔记详情。Cache-Aside 模式：Redis `post:cache:{postId}` 命中直接返回（TTL 30min），未命中查 MySQL 并回填。

### 3.4 `POST /api/post/{id}/like`

点赞/取消点赞切换。幂等设计：MySQL UNIQUE KEY `uk_like(note_id, user_id)` + 原子 `SET like_count = like_count +/- 1`。

### 3.5 `POST /api/post/{id}/favorite`

收藏/取消收藏切换。与 like 相同的幂等模式，UNIQUE KEY `uk_favorite(note_id, user_id)`。

### 3.6 `POST /api/post/{id}/comment`

发表评论。支持 `parentId` 参数实现一层回复。

### 3.7 `GET /api/post/{id}/comments?page=1&size=20`

评论分页查询。仅查询顶级评论，嵌套回复通过 `parentId` 关联。

### 3.8 `DELETE /api/post/{id}`

逻辑删除笔记（仅作者）。`@TableLogic` 将 status 设为 0，发送 `note.deleted` MQ 事件。

### 3.9 `GET /api/post/search?keyword=xxx&page=1&size=20`

ES 全文搜索。`NativeQuery.multiMatch("title", "content", "shopName")` + IK 分词器，filter `status=1` 过滤已删除笔记。ES 不可用时降级返回空列表。

### 3.10 `GET /api/post/user/{userId}?page=1&size=20`

用户笔记列表。MyBatis-Plus 分页查询，`@TableLogic` 自动过滤 status=0。

### 3.11 `GET /api/post/user/{userId}/liked?page=1&size=20`

用户点赞列表。

### 3.12 `GET /api/post/user/{userId}/favorited?page=1&size=20`

用户收藏列表。

## 4. 前端业务功能设计

### 4.1 发布页面（PublishView）

- 图片上传：支持多图上传到 MinIO，上传与发布分离（可边写边传图）
- 表单字段：标题、正文、店铺名称（POI 搜索自动补全）、地址、三个维度的评分（环境/卫生/口味 1-5 星）
- 发布：表单验证通过后调用 `publishPost()`，成功后跳转至新发布的笔记详情页

### 4.2 笔记详情页面（PostDetailView）

- 图片展示：轮播/图集模式
- 作者信息：头像、用户名、关注按钮
- 内容展示：标题、评分（3 维度）、正文、店铺名+地址（可跳转地图）
- 互动区域：点赞/收藏按钮（toggle 模式，视觉反馈）、评论列表（顶级+回复+分页）
- 删除功能：仅作者可见删除按钮，`watch(postId, ..., { immediate: true })` 处理路由切换重新加载

### 4.3 搜索浮层（SearchOverlay）

- 呼出方式：点击顶部导航搜索图标
- 搜索逻辑：调用 `GET /api/post/search?keyword=` 实时检索
- 结果展示：笔记标题、摘要、作者信息，点击跳转详情

### 4.4 导航栏重构（AppLayout）

- 将 Element Plus `el-menu`（水平模式自动折叠）替换为 `<router-link>` 平铺导航
- "发布"按钮独立置于 `header-right` 区域，用户无需展开菜单即可找到

## 5. 后端业务功能设计

### 5.1 发布事务原子性

```text
POST /api/post/publish
  → @Transactional
    → NoteMapper.insert(note)       // MySQL note 表
    → NoteImageMapper.insert(images) // MySQL note_image 表
  → TransactionSynchronization.afterCommit()
    → PostEventPublisher.publish(note.published)  // RabbitMQ
    → FeedService.addToAuthorInbox(noteId)        // 写作者自身 inbox
```

事务回滚时 MQ 不发出，下游无脏数据。

### 5.2 详情缓存（Cache-Aside）

```text
GET /api/post/{id}
  → Redis GET post:cache:{postId}
    → 命中 → 直接返回 (TTL 30min)
    → 未命中 → MySQL SELECT → 回填 Redis → 返回
  → 删除笔记 → Redis DEL post:cache:{postId}
```

点赞/收藏不主动失效缓存（likeCount 允许短暂延迟），保证读路径 P95 稳定。

### 5.3 点赞/收藏幂等

```text
POST /api/post/{id}/like
  → INSERT INTO note_like (note_id, user_id) VALUES (?, ?)
    → 成功 → SET note.like_count = like_count + 1 → 返回 liked=true
    → DuplicateKeyException → DELETE FROM note_like → SET like_count = like_count - 1 → 返回 liked=false
```

MySQL `UNIQUE KEY uk_like(note_id, user_id)` 天然防止重复；`setSql("col = col +/- 1")` 原子更新避免并发丢失。

### 5.4 ES 搜索与降级

```text
GET /api/post/search?keyword=烧烤
  → EsSyncService.search(keyword, page, size)
    → NativeQuery.multiMatch("title", "content", "shopName")
      .filter(term("status", 1))
    → try { elasticsearchClient.search(...) }
      catch (Exception e) { return Page.empty() }  // 降级，不报 500
```

增量同步：`note.published` → `EsSyncService.indexNote()` → ES `post_index`  
删除同步：`note.deleted` → `EsSyncService.deleteNote()` → ES 删除文档

### 5.5 逻辑删除

MyBatis-Plus `@TableLogic` 自动在 SQL 追加 `WHERE status=1`；ES 查询也添加 `filter(term("status", 1))`。删除操作不物理删除数据，仅将 status 设为 0。

### 5.6 事件发布

| Exchange | Routing Key | 触发时机 |
|----------|-------------|----------|
| `biteblog.post` | `note.published` | 发布笔记 afterCommit |
| `biteblog.post` | `note.deleted` | 删除笔记 afterCommit |
| `biteblog.interaction` | `interaction.like` | 点赞/取消点赞 |
| `biteblog.interaction` | `interaction.collect` | 收藏/取消收藏 |
| `biteblog.interaction` | `interaction.comment` | 发表评论 |

Exchange/Queue 声明为 `durable`，消息投递 `PERSISTENT`。

## 6. 测试数据设计

- 测试用户：`13800000001`（bb_bigv_01），密码 `12345678`，由 `sql/init-data.ps1` 统一创建
- Post 数据：通过 `post-test-verify.ps1` 脚本动态发布测试笔记（PostVerify-xxxxxx、PostDel-xxxxxx），测试完成后笔记被逻辑删除
- JMeter 压测依赖笔记 ID=1 存在（由 `init-*-data.ps1` 中发布的样例笔记产生）

## 7. 非功能需求处理

### 7.1 并发

**测试编号：P-1、P-2、P-3、P-14**

**问题**：Post Service 作为核心内容服务，笔记详情、ES 搜索、点赞切换等接口需要承受较高并发访问。

**解决方案**：

- **Redis 缓存热路径**：（P-1）详情查询采用 Cache-Aside 模式，首次查 MySQL 后回填 Redis（TTL 30min），后续请求直接命中缓存。本地测试 P95=33ms，远低于 300ms 目标。
- **ES 独立搜索层**：（P-2）全文搜索不查 MySQL，直接走 ES `multiMatch` 查询。ES 索引由 MQ 事件异步更新，与写路径解耦。本地测试 P95=67ms，远低于 800ms 目标。
- **原子计数器**：（P-3）点赞/收藏使用 `setSql("col = col +/- 1")` 原子更新，避免 SELECT-UPDATE 的并发丢失更新问题（lost update）。本地测试 P95=45ms。
- **JMeter 800 并发验证**：（P-14）200 线程 × 4 接口组同时压测，峰值 800 线程并发，17401 样本仅 4 次错误（0.02%）。ES 搜索 P50=863ms 表现最稳定，P99（1694ms）较 400 线程测试反而改善，MySQL 连接池自然限流削平极端尾延迟。

### 7.2 一致性

**测试编号：P-4、P-5、P-6、P-7、P-9、P-10**

**问题**：系统涉及 MySQL（主存储）、Redis（缓存）、Elasticsearch（搜索）三种异构存储，需要保证数据一致性。

**解决方案**：

- **Cache-Aside 缓存一致性**：（P-4、P-6）读路径先查缓存，未命中查 DB 并回填；删除笔记时主动 DEL 缓存 key。冷路径 39ms → 热路径 29ms，缓存生效且与 DB 一致。
- **发布事务原子性**：（P-5）`note` + `note_image` 在同一 `@Transactional` 中写入 MySQL，事务提交后 `afterCommit` 回调才发送 MQ 事件。DB 回滚时 MQ 不发出，杜绝下游收到脏事件。
- **ES 搜索一致性**：（P-7）发布笔记后 MQ `note.published` → `EsSyncService.indexNote()` 增量同步到 ES `post_index`。实测同步延迟 < 1.5s（首次轮询即命中），搜索可立即找到新笔记。删除笔记时 `note.deleted` 事件触发 ES 文档删除。
- **逻辑删除过滤**：（P-9）MySQL 侧 `@TableLogic` 自动追加 `WHERE status=1`；ES 侧 `NativeQuery.filter(term("status", 1))` 过滤已删除笔记。删除后详情接口返回 code=2001（NOT_FOUND），用户帖子列表不含 status=0 笔记。
- **搜索分页去重**：（P-10）ES 分页使用 `PageRequest.of(page-1, size)` 游标，连续两页 postId 无交集。

### 7.3 可靠性

**测试编号：P-8、P-11**

**问题**：外部依赖（ES）可能出现故障；高并发下互操作可能存在重复请求。

**解决方案**：

- **ES 降级**：（P-8）`EsSyncService.search()` 使用 try-catch 包裹 ES NativeQuery 调用，ES 不可用时降级返回 `Page.empty()` 而非抛出 500 异常，保障搜索接口可用性。前端显示"暂无搜索结果"而非报错。
- **点赞幂等性**：（P-11）MySQL `UNIQUE KEY uk_like(note_id, user_id)` 保证同一用户不能对同一笔记插入重复点赞记录。5 次快速连续 toggle 后 likeCount 变化仅 1（delta=1），幂等逻辑正确。并发场景下 DuplicateKeyException 被捕获并转为取消点赞操作。
- **消息持久化**：Exchange/Queue 声明为 `durable`，消息投递模式 `PERSISTENT`，RabbitMQ Broker 重启后消息不丢失。
- **afterCommit 发送**：`PostEventPublisher` 使用 Spring `TransactionSynchronization.afterCommit()` 回调，确保 DB 事务已提交后才发送 MQ。MySQL 回滚时 MQ 不发出。

### 7.4 安全性

**测试编号：P-13**

**问题**：用户只能操作自己的笔记；未登录用户不能访问受保护接口。

**解决方案**：

- **JWT 鉴权**：（P-13）Gateway 层 `JwtAuthFilter` 拦截所有 `/api/**` 请求，无 Token 返回 HTTP 401。
- **作者校验**：`DELETE /api/post/{id}` 校验 `note.authorId == X-User-Id`，非作者删除返回 403。`POST /api/post/publish` 从 `X-User-Id` 头获取发布者身份，不信任客户端传入的 authorId。
- **直连 X-User-Id**：绕过 Gateway 直连 Post Service 时，通过 `X-User-Id` 头传递用户身份（用于内部服务间调用或测试），Gateway 在生产环境对外部请求过滤该头。

### 7.5 可维护性

**问题**：代码需要在团队协作中易于理解和修改。

**解决方案**：

- **服务分层清晰**：Controller → Service → Mapper，Entity/DTO/VO 分离。各层职责明确，新人可快速定位修改点。
- **幂等模式复用**：LikeService 和 FavoriteService 使用相同的 UNIQUE KEY + `setSql` 原子更新模式，两个服务结构一致，后续修改一处即可类推。
- **配置集中**：MinIO 配置集中在 `application.yml`，ES 连接、Redis 连接、RabbitMQ 连接均通过 Spring Cloud Alibaba + Nacos 服务发现统一管理。
- **逻辑删除标准化**：所有查询使用 MyBatis-Plus `@TableLogic`，无需在每个 Mapper XML 中手写 `WHERE status=1`。
- **事件发布集中管理**：`PostEventPublisher` 统一管理所有 MQ 事件发布，Exchange/RoutingKey 集中定义，新增事件类型只需添加方法。
- **测试脚本独立可运行**：`测试脚本/post-test-verify.ps1` 不依赖特定 IDE，PowerShell 5.1+ 即可执行，输出结果保存到 txt 文件便于归档。
