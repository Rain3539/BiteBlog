# Location Service 测试说明

## 1. 测试范围

本文档记录 Location Service 的非功能性测试和命令行验证方法。

| 测试资源 | 路径 |
|----------|------|
| JMeter 压测脚本 | `jmeter/location-service-test.jmx` |
| JMeter 压测报告 | `jmeter/locationservice-report/index.html` |
| 测试数据初始化 | `sql/init-location-data.ps1` |
| 交叉功能验证脚本 | `测试脚本/location-test-verify.ps1` |

测试目标：

1. 附近笔记查询响应时间 < 300ms
2. 不同半径筛选正确，距离排序准确
3. 坐标参数校验（非法值拒绝）
4. POI 搜索返回正确结果
5. POI 缓存生效（第二次调用显著加速）
6. 笔记发布 → RabbitMQ → GEO 写入 → 附近查询 完整链路
7. 笔记删除 → GEO 清理
8. JMeter 并发压测通过

## 2. 启动前准备

```bash
# 1. 启动 Docker 中间件
docker compose up -d

# 2. 确认全部 healthy
docker compose ps

# 3. 启动必要服务（至少 User + Post + Location + Gateway）
cd biteblog-backend
mvn -pl biteblog-gateway -am spring-boot:run      # 终端2
cd biteblog-location && mvn spring-boot:run        # 终端3
# User Service, Post Service 同理
```

## 3. 初始化测试数据

```powershell
cd sql
.\init-data.ps1             # 如果用户不存在
.\init-location-data.ps1    # 发布12条带坐标笔记
```

使用已有的 60 个用户（13800000001~13800000060），由 13800000001（大V）和 13800000004（普通用户）各发布 6 条笔记，覆盖武汉不同区域。

## 4. 非功能需求与测试用例

| 非功能指标 | 要求 | 测试方式 | 测试结果 |
|-----------|------|---------|---------|
| 附近查询响应时间 | < 300ms | curl 循环 10 次取平均 | **14-35ms** |
| 坐标写入延迟 | 秒级可接受 | 发布后轮询 Redis GEO | **500-2000ms** |
| POI 缓存加速 | 缓存命中显著快 | 同关键词调两次对比 | **15ms vs 566ms** |
| 坐标参数校验 | 非法值拒绝 | 传入 longitude=200 | **返回 5003** |
| 位置授权降级 | 无坐标不写入 | 发布无坐标笔记，查 GEO | **跳过写入** |
| 删除清理 | GEO 移除 | 删笔记后查 Redis | **ZREM 成功** |
| 并发安全 | 20 线程 0 错误 | JMeter 400次请求 | **错误率 0%** |

## 5. 命令行接口测试

### 5.1 附近查询响应时间

```bash
for i in $(seq 1 10); do
  curl -o /dev/null -s -w "%{time_total}\n" \
    "http://localhost:8085/location/nearby/markers?longitude=114.3&latitude=30.59&radius=5"
done | awk '{sum+=$1; n++} END {printf "平均: %.3fs\n", sum/n}'
```

实测结果：**0.014-0.035s，平均 0.020s**

### 5.2 不同半径测试

```bash
for r in 1 3 5 10 50; do
  curl -s "http://localhost:8085/location/nearby/markers?longitude=114.3&latitude=30.59&radius=$r" | \
    python -c "import sys,json;print(f'半径${r}km: {len(json.load(sys.stdin)[\"data\"][\"markers\"])}条')"
done
```

实测结果：
- 1km: 3 条
- 3km: 3 条
- 5km: 4 条
- 10km: 4 条
- 50km: 12 条

### 5.3 坐标参数校验

```bash
# 非法经度
curl -s "http://localhost:8085/location/nearby/markers?longitude=200&latitude=30&radius=5"
# → {"code":5003,"msg":"坐标参数无效"}

# 非法纬度
curl -s "http://localhost:8085/location/nearby/markers?longitude=114.3&latitude=100&radius=5"
# → {"code":5003,"msg":"坐标参数无效"}
```

### 5.4 POI 搜索

```bash
curl -s "http://localhost:8085/location/poi/search?keyword=火锅&city=武汉"
# → 返回 20 条武汉火锅 POI，含 name/address/lng/lat/type
```

### 5.5 POI 缓存性能

```bash
# 首次
curl -o /dev/null -s -w "首次: %{time_total}s\n" \
  "http://localhost:8085/location/poi/search?keyword=%E7%83%A4%E8%82%89&city=%E5%8C%97%E4%BA%AC"
# 首次: 0.566s

# 缓存命中
curl -o /dev/null -s -w "缓存: %{time_total}s\n" \
  "http://localhost:8085/location/poi/search?keyword=%E7%83%A4%E8%82%89&city=%E5%8C%97%E4%BA%AC"
# 缓存: 0.022s  (25x 加速)
```

### 5.6 Redis GEO 验证

```bash
docker exec biteblog-redis redis-cli -a redis123456 ZRANGE location:notes 0 -1 WITHCOORDS
docker exec biteblog-redis redis-cli -a redis123456 ZCARD location:notes
```

## 6. 交叉功能验证（发布→MQ→GEO→API）

执行验证脚本：

```powershell
cd 测试脚本
.\location-test-verify.ps1
```

验证内容：
1. 用 13800000001（大V）发布带坐标笔记
2. 等待 RabbitMQ 消费 → LocationEventListener → GEOADD
3. Redis GEO 确认 noteId 出现
4. 用 13800000004（普通用户）查附近 API，能找到该笔记
5. 删除该笔记，确认 GEO 清除

测试结果输出到 `测试脚本/location-test-result.txt`。

## 7. JMeter 并发测试

### 7.1 执行命令

```powershell
& "E:\mysoftware\apache-jmeter-5.6.3\bin\jmeter.bat" `
  -n -t jmeter\location-service-test.jmx `
  -l jmeter\location-service-result.jtl `
  -e -o jmeter\locationservice-report `
  -Jport=8085
```

### 7.2 测试配置

- 线程数：20
- 循环次数：10
- 共 400 次请求
- 测试端点：
  1. `GET /api/location/nearby/markers?longitude=114.3&latitude=30.5&radius=5`
  2. `GET /api/location/poi/search?keyword=火锅&city=北京`

### 7.3 测试结果

| 指标 | 数值 |
|------|------|
| 总请求数 | 400 |
| 错误数 | 0 |
| 错误率 | **0%** |
| 平均响应时间 | **3ms** |
| 最小响应时间 | 1ms |
| 最大响应时间 | 67ms |
| 吞吐量 | 41.6 req/s |

打开 `jmeter/locationservice-report/index.html` 查看完整 HTML 仪表盘报告。

## 8. 前端附近页面

NearbyView.vue 提供两种模式：

- **附近笔记**：输入坐标+半径，浏览器定位按钮，NoteCard 网格展示结果
- **搜索地点**：关键词+城市输入，POI 结果卡片列表

启动前端：

```bash
cd frontend
npm install && npm run dev
```

访问 `http://localhost:3000` → 登录 → 顶部导航栏点击 **"附近"**。

## 9. 测试结果汇总

| 测试项 | 目标 | 结果 | 状态 |
|--------|------|------|------|
| 附近查询响应 | < 300ms | 14-35ms | 通过 |
| 不同半径筛选 | 单调非递减 | 3/3/4/4/12 | 通过 |
| 距离排序 | 升序 | 正确 | 通过 |
| 坐标校验（非法经度） | 返回 5003 | 5003 | 通过 |
| 坐标校验（非法纬度） | 返回 5003 | 5003 | 通过 |
| POI 搜索 | 返回结果 | 20 条 | 通过 |
| POI 缓存 | 显著加速 | 15ms vs 566ms | 通过 |
| 发布→GEO→查询链路 | 完整可达 | 500-2000ms 延迟 | 通过 |
| 删除→GEO清理 | 同步清理 | ZREM 成功 | 通过 |
| JMeter 并发 | 400次 0错误 | 平均 3ms | 通过 |
| 健康检查 | status=UP | UP | 通过 |
