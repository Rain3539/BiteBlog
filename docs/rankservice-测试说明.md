# Rank Service 测试说明

## 1. 测试范围

本文档覆盖成员 5 负责的 Rank Service 及其前端接入测试，重点验证：

- 热榜接口：健康检查、Top10、分页榜单、手动重建缓存。
- 跨服务链路：Post Service 发布、点赞、收藏、评论、删除事件对 Rank 榜单的影响。
- 前端接入：顶部导航进入 `/rank`，日榜/周榜/总榜切换，分页，重建榜单，跳转笔记详情。
- 测试数据：只使用 `sql/init-data.ps1` 创建的 `13800000001` ~ `13800000060` 用户，不再创建额外用户。
- 非功能性需求：性能、容量边界、可用性、一致性、安全性、可维护性。

## 2. 非功能性需求与测试点

| 非功能性需求 | 实现方式 | 测试方式 | 预期结果 |
|---|---|---|---|
| 性能 | 榜单读取优先访问 Redis Sorted Set | JMeter 直连 `8086/rank` 压测 Top10/list | 平均响应时间低于 300ms，错误率 0% |
| 容量边界 | `size` 限制为 1 ~ 50，Redis 每个榜单保留前 200 条 | 调用大分页参数、检查返回 `size` | 不出现超大分页查询 |
| 可用性 | Redis 榜单为空时自动从 MySQL 重建 | 清空 Redis key 后访问榜单 | 返回非异常结果，并重建缓存 |
| 一致性 | RabbitMQ 事件实时加分 + 定时全量重建 | 发布/点赞/收藏/评论后查询榜单，随后手动 rebuild 对账 | 事件后热度上升，重建后与 MySQL 计数一致 |
| 安全性 | Gateway JWT 鉴权保护管理类操作 | 通过 Gateway 调用 `/api/rank/rebuild` | 未登录返回 401，登录后可执行 |
| 可维护性 | 热度公式、榜单类型、缓存 key 集中在 `RankService` | Maven 编译测试 | Rank 模块可独立编译 |

## 3. 测试数据准备

先启动基础组件和服务：

```powershell
docker compose up -d
.\start-all.ps1 run
```

然后按顺序准备数据：

```powershell
cd sql
.\init-data.ps1
.\init-rank-data.ps1
```

数据约定：

- `13800000001` ~ `13800000003` 为大 V 用户，每个粉丝数超过 50。
- `13800000004` ~ `13800000060` 为普通用户。
- Rank 测试笔记集中发布在 `13800000001` 和 `13800000004` 上。
- `init-rank-data.ps1` 只登录已有用户，不调用注册接口创建新账号。

详细数据说明见 [sql/数据说明.md](../sql/数据说明.md)。

## 4. 接口功能测试

| 编号 | 用例 | 命令 | 预期 |
|---|---|---|---|
| R1 | 健康检查 | `curl http://localhost:8086/rank/health` | `code=200`，`status=UP` |
| R2 | 重建日榜 | `curl -X POST "http://localhost:8086/rank/rebuild?type=daily"` | `rebuilt=true` |
| R3 | 查询 Top10 | `curl "http://localhost:8086/rank/top10?type=daily"` | 返回 `list`，包含 `rankNo/postId/hotScore` |
| R4 | 分页查询 | `curl "http://localhost:8086/rank/list?type=weekly&page=1&size=10"` | 返回 `page=1,size=10,total,list` |
| R5 | Gateway Top10 | `curl "http://localhost:8080/api/rank/top10?type=daily"` | 网关路由到 Rank Service |
| R6 | Gateway 鉴权 | 未登录调用 `POST /api/rank/rebuild` | 返回 401 |

## 5. 跨服务功能测试

| 编号 | 链路 | 操作 | 验证点 |
|---|---|---|---|
| C1 | Post 发布 -> Rank 入榜 | 使用 `13800000001` 发布笔记 | RabbitMQ `note.published` 被消费，新笔记进入日榜/周榜/总榜 |
| C2 | 点赞 -> Rank 加分 | 其他用户调用 `POST /post/{id}/like` | 对应笔记热度增加 3 |
| C3 | 收藏 -> Rank 加分 | 其他用户调用 `POST /post/{id}/favorite` | 对应笔记热度增加 5 |
| C4 | 评论 -> Rank 加分 | 其他用户调用 `POST /post/{id}/comment` | 对应笔记热度增加 4 |
| C5 | 删除 -> Rank 移除 | 作者删除笔记 | 该笔记从 `daily/weekly/all` 榜单移除 |
| C6 | 重建对账 | 执行 `POST /rank/rebuild?type=all` | Redis 榜单按 MySQL 互动计数重新计算 |

## 6. 前端功能测试

| 编号 | 页面操作 | 预期 |
|---|---|---|
| F1 | 登录后点击顶部“热榜” | 进入 `/rank` 页面 |
| F2 | 切换“日榜/周榜/总榜” | 调用 `/api/rank/list?type=...` 并刷新列表 |
| F3 | 点击刷新按钮 | 重新拉取当前榜单 |
| F4 | 点击“重建榜单” | 调用 `/api/rank/rebuild`，成功后刷新列表 |
| F5 | 点击表格行或“查看” | 跳转 `/post/{postId}`，进入 Post Detail 页面 |
| F6 | 调整分页大小或页码 | 请求参数同步变化，列表稳定展示 |

建议截图：

- `/rank` 页面日榜列表。
- 切换周榜后的列表。
- 点击“查看”进入笔记详情。
- 重建榜单成功提示。
- JMeter HTML 报告首页。

## 7. JMeter 压测

脚本路径：

```text
jmeter/rank-service-test.jmx
```

脚本已调整为直连 Rank Service：

- host: `localhost`
- port: `8086`
- paths: `/rank/health`、`/rank/rebuild`、`/rank/top10`、`/rank/list`

推荐命令：

```powershell
jmeter -n -t jmeter/rank-service-test.jmx `
  -l jmeter/rank-service-result.jtl `
  -e -o jmeter/rankservice-report
```

## 8. 本次实际验证结果

验证时间：2026-05-13。

| 项目 | 命令/方式 | 结果 |
|---|---|---|
| 前端依赖安装 | `npm.cmd install` | 通过 |
| 前端构建 | `npm.cmd run build` | 通过，生成 `RankView` chunk；仅有 Vite 大 chunk 提示 |
| Rank 后端编译测试 | `mvn.cmd -pl biteblog-rank -am test` | 通过，Reactor Build Success |
| PowerShell 脚本语法 | `PSParser.Tokenize` 检查 `init-data.ps1`、`init-rank-data.ps1` | 通过，无语法错误 |
| JMeter 脚本格式 | PowerShell XML 解析 `rank-service-test.jmx` | 通过 |
| JMeter CLI | `where.exe jmeter` | 当前环境未安装或未加入 PATH，未执行压测 |
| 本地前端预览 | Vite dev server | 已启动：`http://127.0.0.1:3000` |
| 接口联调 | 探测 `8080/8081/8086` | 当前环境服务未启动，端口请求超时 |
| Docker 环境 | `docker ps` | Docker daemon 未运行，无法在本次环境采集接口截图 |

说明：本次已完成代码级、构建级和脚本级验证。完整运行时接口联调和截图需要先启动 Docker 基础组件与 8 个后端服务，再执行第 3 ~ 7 节命令。
