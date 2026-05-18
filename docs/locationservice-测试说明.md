# Location Service 测试说明

## 1. 测试范围

本文档记录 Location Service 的非功能性测试和命令行验证方法。

| 测试资源 | 路径 |
|----------|------|
| JMeter 压测脚本 | `jmeter/location-service-test.jmx` |
| JMeter 压测报告 | `jmeter/locationservice-report/index.html` |
| 测试数据初始化 | `sql/init-location-data.ps1` |
| 功能验证脚本 | `测试脚本/location-test-verify.ps1` |
| 验证结果输出 | `测试脚本/location-test-result.txt` |

测试目标：

1. 附近笔记查询响应时间 < 300ms
2. 不同半径筛选正确，距离排序准确
3. 坐标参数校验（非法值拒绝）
4. POI 搜索返回正确结果
5. POI 缓存生效（第二次调用显著加速）
6. 笔记发布 -> RabbitMQ -> GEO 写入 -> 附近查询 完整链路
7. 笔记删除 -> GEO 清理
8. JMeter 并发压测通过

## 2. 启动前准备

```bash
# 1. 启动 Docker 中间件
docker compose up -d
sleep 30
docker compose ps

# 2. 编译 common
cd biteblog-backend
mvn -pl biteblog-common install -DskipTests

# 3. 启动必要服务（至少 User + Post + Location + Gateway）
cd biteblog-gateway && mvn spring-boot:run
cd biteblog-user && mvn spring-boot:run
cd biteblog-post && mvn spring-boot:run
cd biteblog-location && mvn spring-boot:run

# 4. 前端
cd frontend
npm install && npm run dev
```

## 3. 初始化测试数据

```powershell
cd sql
.\init-data.ps1              # 创建 60 个用户（如果不存在）
.\init-location-data.ps1     # 发布 12 条带坐标笔记
```

使用已有用户 13800000001（大V）和 13800000004（普通用户），各发布 6 条笔记，覆盖武汉不同区域。12 条笔记包含经度/纬度，发布后通过 RabbitMQ 事件自动写入 Redis GEO。

## 4. 非功能需求与测试结果

| 非功能指标 | 要求 | 测试方式 | 实测结果 |
|-----------|------|---------|---------|
| 附近查询响应时间 | < 300ms | 脚本循环 10 次 | **9-14ms** |
| 坐标写入延迟 | 秒级 | 发布后轮询 Redis GEO | **500ms** |
| POI 缓存加速 | 缓存命中更快 | 随机关键词两次对比 | **高德 ~500ms，缓存 ~10ms** |
| 坐标参数校验 | 非法值拒绝 | longitude=200, latitude=100 | **code=5003** |
| 位置授权降级 | 无坐标跳过 | 发布无坐标笔记 | **跳过 GEO 写入** |
| 删除清理 | GEO 同步移除 | 删笔记后查 Redis ZRANGE | **3s 内清除** |
| 并发压测 | 5000 次 0 错误 | JMeter 20 线程 x 50 循环 | **错误率 0%** |

## 5. 功能验证脚本

执行一条命令即可完成全部 10 项验证：

```powershell
.\测试脚本\location-test-verify.ps1
```

### 验证内容

| 编号 | 测试项 | 说明 |
|------|--------|------|
| 1 | 健康检查 | GET /location/health -> status=UP |
| 2 | 附近查询响应时间 | 10 次循环取平均，目标 < 300ms |
| 3 | 附近笔记结果验证 | 返回结果含 noteId/title/distance，距离升序 |
| 4 | 不同半径测试 | 1/3/5/10/20km，结果数单调非递减 |
| 5 | 坐标参数校验 | 非法经度/纬度返回 5003 |
| 6 | POI 搜索 | 关键词=火锅 城市=武汉，返回结果 |
| 7 | POI 缓存性能 | 随机关键词首次调高德 -> 二次命中 Redis 缓存 |
| 8 | 交叉功能验证 | 发布带坐标笔记 -> MQ -> GEO -> 查看者在附近 API 找到 |
| 9 | 删除清理 | 删除笔记后 GEO 数据同步移除 |
| 10 | 补充演示数据 | 为前端演示保留一条测试笔记 |

### 实测输出示例

```
===== Location Service 非功能验证 =====
发布者: bb_bigv_01 (userId=1)
查看者: bb_user_04 (userId=4)

===== 1. 健康检查 =====
  状态: UP

===== 2. 附近查询响应时间 (目标 < 300ms) =====
  Redis GEO 已有笔记: 42 条
  第1次: 20ms
  第2次: 10ms
  ...
  第10次: 10ms
  平均: 11ms (目标 <300ms)

===== 8. 交叉功能: 发布笔记 -> MQ -> GEO -> 附近查询 =====
  发布成功: postId=48
  GEO 写入确认: 500ms
  查看者验证: 找到笔记 (distance=0.0003km)

===== 非功能指标汇总 =====
  附近查询响应: avg=11ms (目标 <300ms)
  POI 缓存加速: 50x
  GEO 异步写入: 秒级 (RabbitMQ)
  JMeter 压测: 20线程x50循环=5000次, 错误率0%
```

结果自动保存至 `测试脚本/location-test-result.txt`。

## 6. JMeter 并发测试

### 6.1 执行命令

```powershell
& "E:\mysoftware\apache-jmeter-5.6.3\bin\jmeter.bat" `
  -n -t jmeter\location-service-test.jmx `
  -l jmeter\location-service-result.jtl `
  -e -o jmeter\locationservice-report `
  -Jport=8085
```

### 6.2 测试配置

- 线程数：20
- ramp-up：5s
- 循环次数：50
- 共 **5000** 次请求
- 测试端点（5 个）：
  1. `GET /location/health`
  2. `GET /location/nearby/markers?radius=1`
  3. `GET /location/nearby/markers?radius=3`
  4. `GET /location/poi/search?keyword=火锅&city=武汉`
  5. `GET /location/poi/search?keyword=星巴克`

### 6.3 测试结果

| 指标 | 数值 |
|------|------|
| 总请求数 | **5,000** |
| 错误数 | **0** |
| 错误率 | **0%** |
| 平均响应时间 | **2ms** |
| 最小响应时间 | 0ms |
| 最大响应时间 | 52ms |
| 吞吐量 | **910.6 req/s** |

### 6.4 与其它服务对比

| 服务 | 线程 | 循环 | 总请求 | 端点 | 直连 |
|------|------|------|--------|------|------|
| Rank | 10 | 10 | ~400 | 4 | 8086 |
| Recommend | 20 | 10 | ~1000 | 5 | 8080 |
| Location | **20** | **50** | **5000** | **5** | **8085** |

打开 `jmeter/locationservice-report/index.html` 查看完整 HTML 仪表盘报告（Dashboard、响应时间曲线、错误统计等）。

## 7. 前端附近页面

NearbyView.vue 提供两种模式：

- **附近笔记**：输入坐标+半径，支持浏览器定位，NoteCard 网格展示结果（含距离格式化）
- **搜索地点**：关键词+城市输入，POI 结果卡片列表（名称/地址/坐标/分类）

启动前端：

```bash
cd frontend
npm install && npm run dev
```

访问 `http://localhost:3000` -> 登录 `13800000004` / `12345678` -> 顶部导航栏点击 **附近**。

## 8. 测试结果汇总

| 测试项 | 目标 | 结果 | 状态 |
|--------|------|------|------|
| 健康检查 | status=UP | UP | 通过 |
| 附近查询响应 | < 300ms | 9-14ms | 通过 |
| 不同半径筛选 | 单调非递减 | 1/3/5/10/20km 递增 | 通过 |
| 距离排序 | 升序 | 正确 | 通过 |
| 坐标校验（非法经度） | code=5003 | 5003 | 通过 |
| 坐标校验（非法纬度） | code=5003 | 5003 | 通过 |
| POI 搜索 | 返回结果 | 20 条 | 通过 |
| POI 缓存 | 显著加速 | ~500ms -> ~10ms | 通过 |
| 发布->MQ->GEO->API | 完整可达 | 500ms 延迟 | 通过 |
| 删除->GEO 清理 | 同步移除 | 3s 内清除 | 通过 |
| JMeter 并发 | 5000 次 0 错误 | 平均 2ms | 通过 |
