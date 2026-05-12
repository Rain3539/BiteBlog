# Notify Service 测试说明

## 1. 测试范围

本文件记录 Notify Service 的命令行测试方法与前端联调步骤。JMeter 压测脚本单独保存在：

```text
jmeter/notify-service-test.jmx
```

测试目标包括：

1. 服务是否可以正常启动；
2. `/notify/health` 是否返回正常；
3. 通知列表与未读数接口是否正确返回数据；
4. 单条已读与全部已读是否生效；
5. RabbitMQ 互动事件（点赞/收藏/评论）是否能被消费并写入通知；
6. WebSocket 实时推送是否正常工作；
7. 前端通知中心与导航栏未读角标是否联动。

## 2. 启动前准备

需要先启动基础服务：MySQL、RabbitMQ、Nacos，并执行 `sql/init.sql` 初始化表结构。然后启动以下微服务：

```powershell
cd biteblog-backend

mvn -pl biteblog-user -am spring-boot:run
mvn -pl biteblog-post -am spring-boot:run
mvn -pl biteblog-notify -am spring-boot:run
```

如果通过网关测试，还需要启动：

```powershell
mvn -pl biteblog-gateway -am spring-boot:run
```

## 3. 初始化测试数据

在项目 `BiteBlog/sql` 目录执行：

```powershell
cd BiteBlog\sql
.\init-notify-data.ps1
```

该脚本会注册或登录两个专用账号（手机号 `13900004001` 作者 / `13900004002` 互动方，密码均为 `12345678`），由作者发布测试笔记，互动方执行点赞、收藏、评论，最后调用 Notify 接口验证通知条数与未读数。

脚本执行成功后，预期输出 `[OK] /notify/list total=3`（或更多）和 `[OK] /notify/unread-count unreadCount=3`（或更多）。

## 4. 命令行接口测试

### 4.1 健康检查

直接访问 Notify 服务：

```bash
curl -s http://localhost:8087/notify/health
```

通过网关访问（需携带 JWT）：

```bash
curl -s http://localhost:8080/api/notify/health -H "Authorization: Bearer <TOKEN>"
```

预期结果：`code=200`，`data.status=UP`。

### 4.2 登录获取 Token（PowerShell）

**请求体必须是合法 JSON**，有以下两种正确写法：

写法 A（原始 JSON 字符串）：

```powershell
$body = '{"phone":"13900004001","password":"12345678"}'
$r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"
$token = $r.data.token
```

写法 B（哈希表转 JSON）：

```powershell
$body = @{ phone = "13900004001"; password = "12345678" } | ConvertTo-Json -Compress
$r = Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method POST -Body $body -ContentType "application/json; charset=utf-8"
$token = $r.data.token
```

**错误写法**：不要对已经是 JSON 字符串的变量再 `| ConvertTo-Json`，否则会生成双重转义，登录返回 401。

### 4.3 查询通知列表

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/list?page=1&size=20" -Headers @{ Authorization = "Bearer $token" }
```

预期结果：`code=200`，`data.total` 大于等于 3，`data.list` 中包含 `like`、`collect`、`comment` 类型的通知，`senderUsername` 为 `notify_demo_fan`。

### 4.4 查询未读数

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/unread-count" -Headers @{ Authorization = "Bearer $token" }
```

预期结果：`data.unreadCount` 与列表中 `readStatus=0` 的条数一致。

### 4.5 全部标为已读

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/notify/read-all" -Method POST -Headers @{ Authorization = "Bearer $token" }
```

执行后再次查询未读数，预期 `unreadCount=0`。

### 4.6 直连 Notify 服务（调试用）

跳过网关，直接传 `X-User-Id` 请求：

```powershell
Invoke-RestMethod -Uri "http://localhost:8087/notify/list?page=1&size=20" -Headers @{ "X-User-Id" = "11" }
```

将 `11` 换成作者用户的实际 `userId`（脚本执行后有显示）。

## 5. RabbitMQ 检查

登录 RabbitMQ 管理台 `http://localhost:15672`（默认账号 guest/guest），进入 **Queues and Streams**，确认：

- 队列 `notify.interaction.queue` 存在；
- `Consumers` 为 1，表示 Notify 服务已成功连接；
- 运行 `init-notify-data.ps1` 后，`Ready` 消息短暂出现后归 0，`Unacked` 也归 0，说明消费正常。

若看到 `Unacked` 持续增加、`deliver ≈ redelivered`，说明消费端抛出异常、消息无法 ack。此时在管理台 **Purge** 该队列，重新编译启动 Notify 服务后再跑脚本。

## 6. JMeter 压测

JMeter 脚本路径：

```text
jmeter/notify-service-test.jmx
```

建议执行方式（将 JMeter 路径替换为本机实际路径）：

```powershell
cd BiteBlog
& "D:\apache-jmeter-5.6.3\bin\jmeter.bat" -n -t jmeter\notify-service-test.jmx -l jmeter\notify-service-result.jtl -e -o jmeter\notifyservice-report
```

脚本默认变量：host=`localhost`，port=`8080`，登录手机 `13900004001` / 密码 `12345678`。每个线程迭代内先登录，再依次压测 `health`、`list`、`unread-count` 接口。

非功能目标建议：错误率 0%，列表与未读接口平均响应时间低于 300ms。

结果文件：`jmeter/notify-service-result.jtl`；HTML 报告目录：`jmeter/notifyservice-report/`。

## 7. 前端联调

1. 在 `frontend` 目录执行 `npm install`，确保 `@stomp/stompjs` 和 `sockjs-client` 已安装；
2. 执行 `npm run dev` 启动前端（默认 `http://localhost:3000`）；
3. 登录有通知的账号（`13900004001`），导航到 **通知中心** 页面；
4. 页面右上角应显示「实时已连接」标签，表示 WebSocket 连接成功；
5. 用另一账号（`13900004002`）对该用户笔记点赞，通知中心应实时出现新条目，导航栏铃铛角标数字增加。

若 WebSocket 显示「实时未连接」，默认连接地址为 `http://localhost:8087`。如果 Notify 部署在其他主机或端口，在 `frontend` 目录下新建 `.env.development`：

```env
VITE_NOTIFY_WS_ORIGIN=http://你的主机:8087
```

修改 Vite 配置（`vite.config.js`）后需重启 `npm run dev` 才能生效。

## 8. 常见问题

| 现象 | 处理 |
|---|---|
| `Unacked` 持续增加、`ack=0` | 消费端抛异常无法 ack。先在 RabbitMQ 管理台 **Purge** `notify.interaction.queue`，再重启 Notify 服务后重跑脚本。 |
| 列表有数据但 `total=0` | `MybatisPlusConfig` 分页拦截器未加载，`Page.getTotal()` 恒为 0；确认 `config/MybatisPlusConfig.java` 存在，重新编译启动。 |
| 列表为空、`unreadCount=0` | RabbitMQ 消费异常，通知未写入。检查 Notify 启动日志中是否有 `SecurityException: CollSer`；确认 `NotifyApplication.main()` 中有 `System.setProperty` 语句，并重启服务。 |
| 网关返回 **401** | 检查登录 body，不要对 JSON 字符串再 `ConvertTo-Json`；参考 4.2 节写法。 |
| JMeter 登录失败 | 先执行 `init-notify-data.ps1` 创建测试账号，或修改 JMeter 脚本中 `PHONE`/`PASSWORD` 变量。 |
| 前端「实时未连接」 | 确认 `8087` 端口可访问；检查浏览器控制台是否有 `global is not defined`（需 Vite `define: { global: 'globalThis' }` 且重启 dev server）；检查 `VITE_NOTIFY_WS_ORIGIN` 配置。 |
