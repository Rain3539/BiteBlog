# Recommend Service 测试说明

## 1. 测试目标

本文档说明成员 3 负责的 Recommend Service 测试方案和测试产物。测试重点：

- 功能正确性：发现页推荐、冷启动、标签/城市召回、分页加载、曝光去重。
- 跨服务联调：前端发现页、Gateway、User Service、Post Detail、Redis、MySQL、Elasticsearch。
- 非功能性需求：响应时间、并发能力、可用性、一致性、安全性、容量边界、可维护性。
- 分布式能力：Gateway 路由、Nacos 注册、Redis 共享状态、ES 召回、Redis Lua 原子曝光预占、JMeter 并发压测。

说明文件中不直接放截图。截图和测试结果文件单独放在对应目录中。

## 2. 测试产物

| 类型 | 路径 | 说明 |
|---|---|---|
| 命令行验证脚本 | `测试脚本/recommend-test-verify.ps1` | 功能、延迟、分页、曝光、ItemCF、边界参数验证 |
| 命令行结果文本 | `测试脚本/recommend-test-result.txt` | 执行脚本后由 `Start-Transcript` 自动生成 |
| JMeter 脚本 | `jmeter/recommend-service-test.jmx` | 推荐接口并发压测 |
| JMeter 原始结果 | `jmeter/recommend-service-result.jtl` | JMeter CLI 输出 |
| JMeter HTML 报告 | `jmeter/recommendservice-report/index.html` | 压测报告首页，用于截图 |

截图要求：

- JMeter 结果截图：打开 `jmeter/recommendservice-report/index.html`，截 Dashboard 首页和 Statistics 表格。
- PS1 脚本结果截图：运行 `测试脚本/recommend-test-verify.ps1` 后截 PowerShell 终端即可。
- 同时保留 `测试脚本/recommend-test-result.txt`，方便老师直接查看文本结果。

## 3. 测试环境

启动基础组件：

```powershell
cd E:\desktop\BiteBlog
docker compose up -d
docker compose ps
```

启动后端服务：

```powershell
.\start-all.ps1
```

启动前端：

```powershell
cd E:\desktop\BiteBlog\frontend
npm run dev
```

访问地址：

```text
http://localhost:3000
```

## 4. 测试数据准备

```powershell
cd E:\desktop\BiteBlog
.\sql\init-data.ps1
.\sql\init-recommend-data.ps1
```

`init-recommend-data.ps1` 只复用 `init-data.ps1` 创建的 `13800000001` ~ `13800000060`，不会额外创建用户。

推荐数据包括：

| 数据 | 说明 |
|---|---|
| `13800000001` | 大 V 作者，发布 `Recommend Test 01` ~ `Recommend Test 18` |
| `13800000004` | 普通作者，发布 `Recommend Test 19` ~ `Recommend Test 30` |
| `13800000005` | 有火锅/烧烤行为的个性化用户 |
| `13800000006` | 有甜品/茶饮行为的个性化用户 |
| `13800000007` | ItemCF 相似用户 |
| `13800000060` | 冷启动用户 |
| `recommend:hot:pool` | Redis ZSet，保存 30 条冷启动热门候选 |
| `recommend:itemcf:similar:{postId}` | Redis ZSet，保存 ItemCF 降级用的相似笔记 |
| `exposure:{userId}` | Redis Set，保存曝光记录 |
| `post_index` | ES 索引，保存推荐样例的全文检索数据 |
| `item_sim_index` | ES 索引，保存 `item_id/similar_item_id/score`，用于 ItemCF 相似候选召回 |

## 5. PS1 功能与延迟测试

运行：

```powershell
cd E:\desktop\BiteBlog
.\测试脚本\recommend-test-verify.ps1
```

脚本开头包含：

```powershell
$transcriptFile = Join-Path $PSScriptRoot "recommend-test-result.txt"
Start-Transcript -Path $transcriptFile -Force | Out-Null
```

因此终端输出会自动保存到：

```text
测试脚本/recommend-test-result.txt
```

脚本覆盖：

| 编号 | 测试项 | 验证内容 |
|---|---|---|
| 1 | 健康检查与预计算 | 直连 `8084/recommend/health`、Gateway `/api/recommend/health`，并触发 `/recommend/internal/precompute` |
| 2 | 冷启动响应时间 | 连续 10 次请求冷启动推荐，统计平均/最小/最大耗时，目标平均 < 600ms |
| 3 | 分页一致性 | 请求两页推荐，检查前两页 `postId` 无重复 |
| 4 | 标签召回 | 请求 `tag=Hotpot&city=Guangzhou`，验证 ES 优先、MySQL 兜底链路可返回 |
| 5 | 曝光预占与幂等 | 检查 Redis `exposure:{userId}`、TTL，并重复上报同一批 `postIds` |
| 6 | ES ItemCF | 检查 `item_sim_index` 是否写入相似候选，Redis 相似池作为降级 |
| 7 | 边界参数 | 测试 `size=1`、`size=999`，验证最大分页限制 |

建议截图：

- PowerShell 终端中“冷启动推荐响应时间”段落。
- PowerShell 终端中“分页一致性”“曝光 Lua 预占与幂等上报”段落。
- `测试脚本/recommend-test-result.txt` 文件内容可作为文本版结果提交。

## 6. JMeter 并发测试

压测前建议先运行一次：

```powershell
curl.exe -X POST http://localhost:8084/recommend/internal/precompute
```

该步骤会提前重建 Redis `recommend:hot:pool` 和 ES `item_sim_index`，使 JMeter 压测更接近“在线请求只做曝光过滤、轻量重排和多样性打散”的设计。

先登录获取 Token 和 userId：

```powershell
curl.exe -X POST http://localhost:8080/api/user/login `
  -H "Content-Type: application/json" `
  -d "{\"phone\":\"13800000060\",\"password\":\"12345678\"}"
```

运行 JMeter：

```powershell
jmeter -n -t jmeter/recommend-service-test.jmx `
  -Jhost=localhost `
  -Jport=8080 `
  -Jtoken=<登录后获取的token> `
  -JuserId=<当前用户ID> `
  -l jmeter/recommend-service-result.jtl `
  -e -o jmeter/recommendservice-report
```

建议并发参数：

| 配置项 | 建议值 |
|---|---:|
| Number of Threads | 20 |
| Ramp-up Period | 10s |
| Loop Count | 10 |

JMeter 覆盖接口：

- `GET /api/recommend/health`
- `GET /api/recommend/discover?cursor=0&size=20`
- `GET /api/recommend/discover?cursor=0&size=5`
- `GET /api/recommend/discover?cursor=0&size=20&tag=Hotpot`
- `POST /api/recommend/exposures`

JMeter 截图：

```text
jmeter/recommendservice-report/index.html
```

打开该网页后截：

- Dashboard 首页：Total、Error%、Average、Throughput。
- Statistics 表：各接口 sample 数、平均响应时间、错误率。

## 7. 非功能需求与测试方式

| 非功能需求 | 实现方式 | 测试方式 | 预期结果 |
|---|---|---|---|
| 性能 | 标签召回优先 ES；ItemCF 优先 ES `item_sim_index`；冷启动读 Redis ZSet；分页最大 50 | PS1 统计响应时间；JMeter 并发压测 | 推荐列表平均响应时间建议 < 600ms |
| 并发能力 | Recommend 无本地会话；Redis 共享曝光/热门；ES 共享召回索引；Lua 原子预占 | JMeter 多线程请求推荐和曝光 | 错误率 0%，无明显重复推荐 |
| 可用性 | ES 失败降级 MySQL；Redis 热门池失败降级 MySQL；ItemCF 缺失降级在线行为扫描 | 清空或停用部分缓存后请求推荐 | 接口不返回 500，有兜底结果 |
| 一致性 | 曝光 Set 幂等；TTL 7 天；Lua 返回前预占 | 重复上报同一批 ID，检查 Redis Set | 不重复，TTL 正常 |
| 安全性 | Gateway JWT；直连必须传 `X-User-Id`；曝光最多 100 个 ID | 未登录访问、缺 Header 访问、超大曝光请求 | 未授权被拦截，非法请求有明确错误 |
| 容量边界 | `size` 最大 50，`postIds` 最多处理 100 个 | `size=999`、超长 `postIds` | 返回数量被限制 |
| 可维护性 | Controller/Service/DataService/SearchService 分层 | Maven 编译、代码结构检查 | 模块可独立编译，召回源可替换 |

## 8. 当前已验证结果

| 验证项 | 结果 |
|---|---|
| Recommend 后端编译 | `mvn -pl biteblog-recommend -am test -DskipTests` 通过 |
| 前端构建 | `npm run build` 通过 |
| PS1 脚本语法 | `recommend-test-verify.ps1` 可被 PowerShell 解析 |
| 运行时接口/JMeter | 需要 Docker、后端服务和 JMeter 启动后执行，并保存 txt/report 截图 |

## 9. 提交材料建议

最终建议提交或展示以下材料：

| 材料 | 路径 |
|---|---|
| 测试说明 | `docs/recommendservice-测试说明.md` |
| 修改说明 | `docs/recommendservice-修改说明.md` |
| 命令行测试脚本 | `测试脚本/recommend-test-verify.ps1` |
| 命令行测试结果 | `测试脚本/recommend-test-result.txt` |
| JMeter 脚本 | `jmeter/recommend-service-test.jmx` |
| JMeter 报告 | `jmeter/recommendservice-report/index.html` |
| JMeter 截图 | 从 `index.html` 截取 Dashboard 和 Statistics |
