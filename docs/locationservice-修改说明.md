# Location Service 修改说明

## 1. 服务定位

Location Service（端口 8085）是 BiteBlog 探店平台的位置服务模块。核心数据流如下：

1. Post Service 发布带坐标笔记 -> MySQL note 表（longitude/latitude）。
2. Post Service 发送 note.published 事件到 RabbitMQ biteblog.post 交换机。
3. Location Service 消费事件 -> 从 MySQL 读取坐标 -> GEOADD 写入 Redis location:notes。
4. 用户打开附近页面 -> Frontend NearbyView.vue -> Gateway /api/location/nearby/markers -> Location Service -> Redis GEORADIUS + MySQL 补充详情 -> 返回。
5. Post Service 删除笔记时发送 note.deleted 事件，Location Service 消费后 ZREM 清理 GEO。
6. POI 搜索通过高德 API 代理实现，首次查询缓存到 Redis（TTL=1h），后续命中缓存。

## 2. 新增与调整文件

| 文件 | 说明 |
|------|------|
| frontend/src/views/NearbyView.vue | 新增附近探店页面，附近笔记 + 搜索地点 |
| frontend/src/api/location.js | 前端 API：getNearbyMarkers、searchPoi |
| LocationApplication.java | 启动类，MapperScan + scanBasePackages |
| controller/LocationController.java | 3 个端点：nearbyMarkers、searchPoi、health |
| service/LocationService.java | GEORADIUS + POI 搜索+缓存 + GEO 增删 |
| service/LocationEventListener.java | 消费 note.published/note.deleted，双格式解析 |
| config/LocationRabbitConfig.java | 交换机/队列/绑定声明 |
| entity/Note.java | 映射 note 表，BigDecimal longitude/latitude |
| mapper/NoteMapper.java | MyBatis-Plus Mapper |
| dto/NearbyMarkerVO.java | 附近笔记 VO（noteId/title/shopName/lng/lat/distance） |
| dto/PoiItemVO.java | POI 结果 VO（id/name/address/lng/lat/type） |
| ErrorCode.java (common) | 新增 POI_SEARCH_FAIL(5002)、COORDINATE_INVALID(5003) |
| application.yml | 高德 API Key、RabbitMQ listener auto-startup |
| sql/init-location-data.ps1 | 复用 60 个用户，各发布 6 条笔记 |
| jmeter/location-service-test.jmx | 200 线程 x 25 循环，直连 8085 |
| 测试脚本/location-test-verify.ps1 | 12 项自动化验证脚本 |
| docs/locationservice-测试说明.md | 非功能测试说明（L-1 ~ L-10） |
| docs/locationservice-修改说明.md | 本文档 |

## 3. 新增接口说明

### 3.1 GET /location/health

健康检查接口。返回 `{"code":200,"data":{"service":"location-service","status":"UP"}}`。

### 3.2 GET /location/nearby/markers

按坐标和半径查询附近笔记。Redis GEORADIUS + MySQL selectBatchIds 批量补充。

| 参数 | 类型 | 必填 | 范围 | 默认值 |
|------|------|------|------|--------|
| longitude | Double | 是 | [-180, 180] | -- |
| latitude | Double | 是 | [-90, 90] | -- |
| radius | int | 否 | -- | 3 (km) |

返回 markers 数组：noteId、authorId、title、shopName、longitude、latitude、distance(km)。最多 50 条，按距离升序，仅 status=1 笔记。

### 3.3 GET /location/poi/search

高德 POI 搜索代理。首次调高德 API，结果 Redis 缓存（TTL=1h）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 是 | 搜索关键词 |
| city | String | 否 | 不填则全国范围 |

返回 list 数组：id、name、address、longitude、latitude、type。

### 3.4 错误码

| 错误码 | 含义 |
|--------|------|
| 5002 | POI_SEARCH_FAIL |
| 5003 | COORDINATE_INVALID |

## 4. 前端业务功能设计

新增 /nearby 页面，用户登录后顶部可见「附近」入口。

- 附近笔记模式：输入坐标 + 半径（1/3/5/10/20km），浏览器定位按钮，NoteCard 网格展示，距离自动格式化（<1km 显示 m，>=1km 显示 km）
- 搜索地点模式：关键词 + 城市，POI 卡片列表（名称/地址/坐标/分类）
- NoteCard 复用已有组件，点击跳转 post/{postId}

前端链路：NearbyView.vue -> /api/location/* -> Gateway -> lb://location-service

## 5. 后端业务功能设计

### 5.1 Redis GEO 数据结构

Key: location:notes（GEO/底层 Sorted Set），成员为 noteId 字符串，WGS84 Point 坐标。

- GEOADD location:notes lng lat noteId
- GEORADIUS location:notes lng lat radius km WITHDIST ASC COUNT 50
- ZREM location:notes noteId

GEORADIUS O(N+logM)，纯内存。查询后 selectBatchIds 批量从 MySQL 补充标题/店铺名。status!=1 的笔记在组装时过滤。

### 5.2 事件驱动更新

发布链路：Post INSERT -> RabbitMQ note.published -> LocationEventListener 消费 -> parseEvent() 双格式兼容 -> addNoteLocation() 重试5次(200ms) -> GEOADD。坐标为空时跳过（位置降级）。

删除链路：Post UPDATE status=0 -> RabbitMQ note.deleted -> removeNoteLocation() -> ZREM。

### 5.3 POI 缓存策略

- key: location:poi:{keyword}:{city}，TTL 1h
- 首次: WebClient -> 高德 /v3/place/text -> Jackson readTree 解析 -> 缓存
- location 字段格式为 "lng,lat"，拆分存入 PoiItemVO
- UriComponentsBuilder 自动 URL 编码中文

## 6. 测试数据设计

sql/init-location-data.ps1 复用 init-data.ps1 的 60 个用户。

| 角色 | 账号 | 数量 | 覆盖 |
|------|------|------|------|
| 大V | 13800000001 | 6 条 | 江汉、武昌、江岸 |
| 普通 | 13800000004 | 6 条 | 光谷、珞珈山、汉阳、青山、江汉、武昌 |

共 12 条笔记，以武汉中山公园(114.3,30.59)为中心，1~20km 半径内。通过 Post API 发布后自动触发完整链路。

## 7. 非功能需求处理

### 7.1 并发

**问题**：附近查询是地图页高频入口，如果每次都对 MySQL 做空间距离计算，并发访问会放大数据库压力。

**解决方案**：

- 读附近主路径改为 Redis GEORADIUS，纯内存计算 O(N+logM)。
- 单次查询限制返回 50 条，避免大半径结果集膨胀。
- JMeter 压测线程数提升至 200，循环 25 次，直连 8085，5 个端点。已重新生成 HTML 报告。
- 坐标参数加范围校验，非法值在入口即拒绝。

**对应测试编号**：L-2、L-10。
L-2 单请求基线（平均 15.2ms），L-10 200 线程并发：样本数 25,000，错误率 0.00%，吞吐量 658.8 req/s，平均响应 268ms。附近查询（纯 Redis GEO）在并发下保持极低延迟，平均响应受 POI 搜索（外部高德 API）拉高。

### 7.2 一致性

**问题**：Location GEO 由 MySQL 主数据、RabbitMQ 事件、Redis 缓存共同维护，发布/删除存在异步延迟；消息丢失或消费异常会导致不一致。

**解决方案**：

- note.published 消费后从 MySQL 读取最新坐标写入 Redis GEO。
- note.deleted 消费后 ZREM 移除。
- 查询返回时按 status=1 过滤，GEO 残留已删除笔记不会出现。
- 消费端 parseEvent() + deserializeMap() 双格式兼容 JSON 字符串和 Java 序列化。
- GEOADD 幂等，POI 缓存 TTL=1h。

**对应测试编号**：L-3、L-4、L-5、L-7、L-8、L-9；对应总说明中 LC-1~LC-7、E2E-3、E2E-8。
测试结果：发布后 postId=89 经 500ms 进入 GEO，查看者找到（0.0003km）；删除后清理成功；POI 缓存 126.2x（757ms->6ms）；半径 16/18/22/46/46 单调递增；非法坐标返回 5003。

### 7.3 可靠性

**问题**：Post 事务提交与 MQ 消费存在时间差，消费时 note 可能未写入 MySQL（跨服务事务时序差）；MQ 消息可能丢失导致个别笔记未入 GEO；GEO 可能因 Redis 重启丢失全部数据。

**解决方案**：

- addNoteLocation 查不到 note 时重试 5 次（间隔 200ms），Post 回滚时跳过并 warn 日志。
- RabbitMQ exchange/queue durable，消息 PERSISTENT。队列诊断：ready=0, unacked=0, consumers=1。
- **定时自愈**：`@Scheduled(cron = "0 0/10 * * * ?")` 每 10 分钟从 MySQL 全量重建 `location:notes`。遍历所有 status=1 且坐标非空的笔记 → 删除旧 key → GEOADD 批量写入。兜底修复 MQ 消息丢失、Redis 重启、GEO 数据漂移。
- 坐标为空（用户拒绝位置授权）时跳过写入，不影响发布主流程。

**对应测试编号**：L-8、L-11、L-12；对应可靠性测试说明.md 第 6 节「Location -- 跨服务事务时序重试 + 定时自愈」。
测试结果：交叉功能链路 500ms 确认 GEO 写入；MQ 队列无积压；定时重建逻辑已在服务代码中落地。

### 7.4 安全性

**问题**：坐标参数若不受限可能被恶意传入非法值；高德 Key 硬编码会泄露。

**解决方案**：

- 入口范围校验 [-180,180]/[-90,90]，非法返回 5003。
- 高德 Key 配置在 application.yml，@Value 注入。
- 所有接口通过 Gateway JWT 鉴权（/health 除外）。

**对应测试编号**：L-5。测试结果：longitude=200 和 latitude=100 均返回 5003。

### 7.5 可维护性

**问题**：GEO key、POI 缓存前缀、TTL、错误码若分散各处，后续调整易遗漏。

**解决方案**：

- 常量集中在 LocationService 顶部。
- RabbitMQ 配置独立在 LocationRabbitConfig，常量 public static final。
- 错误码统一在 ErrorCode 枚举（5001/5002/5003）。
- 消费端解析集中在 parseEvent()，双格式兼容。
- 测试编号 L-* 关联各维度，验证脚本 Invoke-Redis 统一封装。

**对应测试编号**：L-1 ~ L-12 全部。
