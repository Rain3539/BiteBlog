# BiteBlog - 分布式美食探店社区平台

武汉大学计算机学院 2025-2026 学年第二学期《分布式系统》课程设计

## 项目简介

BiteBlog 是一个基于微服务架构的美食探店社区平台，支持探店笔记发布、关注 Feed 流、个性化推荐、附近探店、热度排行、实时通知等功能。

## 技术栈

- **前端**: Vue 3 + Vite + Element Plus + Pinia
- **后端**: Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba
- **存储**: MySQL 8.0 + Elasticsearch 8.12 + Redis 7
- **消息队列**: RabbitMQ 3.12
- **对象存储**: MinIO
- **服务注册**: Nacos 2.3
- **部署**: Docker + Docker Compose

##  快速启动

###  前置检查

启动前确认环境就绪：

```bash
# 检查 JDK 版本（需要 17+）
java -version

# 检查 Maven 版本（需要 3.8+）
mvn -v

# 检查 Node.js 版本（需要 18+）
node -v
npm -v

# 检查 Docker 和 Docker Compose
docker -v
docker compose version

# 克隆项目（如果是第一次）
git clone <仓库地址>
cd BiteBlog
```

###  启动中间件（Docker Compose）

```bash
# 在项目根目录启动全部中间件
docker compose up -d

# 查看容器启动状态（等待所有容器 health 状态变为 healthy）
docker compose ps

# 如果有容器启动失败，查看日志排查
docker compose logs <容器名>       # 例如: docker compose logs mysql
docker compose logs -f             # 实时跟踪所有日志
```

首次启动需要拉取镜像，可能需要几分钟。确认所有容器状态为 `healthy`：

```
NAME                  STATUS
biteblog-mysql        Up (healthy)
biteblog-redis        Up (healthy)
biteblog-rabbitmq     Up (healthy)
biteblog-elasticsearch Up (healthy)
biteblog-minio        Up (healthy)
biteblog-nacos        Up (healthy)
```

启动后各服务管理界面：

| 服务 | 地址 | 账号密码 | 用途 |
|------|------|----------|------|
| Nacos | http://localhost:8848/nacos | nacos/nacos | 服务注册发现、配置管理 |
| RabbitMQ 管理台 | http://localhost:15672 | guest/guest | 消息队列监控 |
| MinIO 控制台 | http://localhost:9001 | minioadmin/minioadmin | 对象存储管理 |
| Elasticsearch | http://localhost:9200 | 无 | 搜索引擎，直接浏览器访问 |

> **MySQL** 端口 3306，账号 root/root123456，可用 DBeaver 等工具连接。
> **Redis** 端口 6379，密码 redis123456，可用 RedisInsight 连接。

**如果需要停止/清理中间件：**

```bash
docker compose stop            # 停止容器（保留数据）
docker compose down            # 停止并移除容器
docker compose down -v         # 停止并移除容器 + 删除数据卷（清空所有数据）
```

###  初始化 ES 索引

```bash
# 回到项目根目录
cd elasticsearch

# 执行索引初始化脚本（会创建 9 个 ES 索引）
bash init-indices.sh
```

预期输出：每个索引显示 `"acknowledged": true`，最后打印 `全部索引创建完成`。

**验证索引是否创建成功：**

```bash
# 查看所有索引
curl http://localhost:9200/_cat/indices?v

# 应能看到 post_index、user_index、follow_index 等 9 个索引
```

###  编译后端项目

```bash
# 进入后端根目录
cd biteblog-backend

# 首次编译（跳过测试，安装 common 模块到本地仓库）
mvn clean install -DskipTests
```

编译成功后会看到 `BUILD SUCCESS`。如果依赖下载慢，参考下方 FAQ 配置阿里云镜像。

**常见编译问题：**

```bash
# 如果报 "Non-resolvable parent POM" 错误，先安装父 POM
mvn install -N

# 如果某个模块编译失败，可以单独编译该模块排查
cd biteblog-common
mvn clean install -DskipTests
```

### 启动后端服务

每个微服务独立启动，开多个终端窗口分别运行。**启动顺序建议：先 Gateway，再业务服务。**

**方式一：命令行启动（推荐调试用）**

```bash
# 终端 1 - 启动网关（端口 8080）
cd biteblog-backend/biteblog-gateway
mvn spring-boot:run

# 终端 2 - 启动用户服务（端口 8081）
cd biteblog-backend/biteblog-user
mvn spring-boot:run

# 终端 3 - 启动笔记服务（端口 8082）
cd biteblog-backend/biteblog-post
mvn spring-boot:run

# 终端 4 - 启动 Feed 服务（端口 8083）
cd biteblog-backend/biteblog-feed
mvn spring-boot:run

# 终端 5 - 启动推荐服务（端口 8084）
cd biteblog-backend/biteblog-recommend
mvn spring-boot:run

# 终端 6 - 启动位置服务（端口 8085）
cd biteblog-backend/biteblog-location
mvn spring-boot:run

# 终端 7 - 启动排行服务（端口 8086）
cd biteblog-backend/biteblog-rank
mvn spring-boot:run

# 终端 8 - 启动通知服务（端口 8087）
cd biteblog-backend/biteblog-notify
mvn spring-boot:run
```

**方式二：编译 JAR 后后台运行**

```bash
# 编译打包
cd biteblog-backend
mvn clean package -DskipTests

# 后台启动（示例：user-service）
cd biteblog-user/target
nohup java -jar biteblog-user-1.0.0.jar > user.log 2>&1 &

# 查看日志
tail -f user.log
```

**方式三：IntelliJ IDEA 启动**

1. 用 IDEA 打开 `biteblog-backend/` 目录（作为 Maven 项目导入）
2. 等待 Maven 依赖下载完成
3. 打开每个服务的 `XxxApplication.java`，点击绿色 ▶ 运行按钮
4. 在 Run Configuration 中为每个服务设置不同的端口（已在 application.yml 中配置）

**验证服务注册成功：**

打开 Nacos 控制台 http://localhost:8848/nacos → 服务管理 → 服务列表，应能看到已启动的服务。

也可以用命令行检查：

```bash
# 检查某个服务是否能响应（以 user-service 为例）
curl http://localhost:8081/user/1
# 预期返回: {"code":200,"msg":"success","data":{...}}
```

###  启动前端

```bash
# 进入前端目录
cd frontend

# 安装依赖（首次需要）
npm install

# 如果 npm install 慢，可切换淘宝源
npm install --registry=https://registry.npmmirror.com

# 启动开发服务器
npm run dev
```

启动成功后终端会显示：

```
  VITE v5.x.x  ready in xxx ms

  ➜  Local:   http://localhost:3000/
  ➜  Network: http://192.168.x.x:3000/
```

浏览器访问 http://localhost:3000 ，应能看到 BiteBlog 登录页面。

**前端开发常用命令：**

```bash
npm run dev          # 启动开发服务器（热更新）
npm run build        # 构建生产包（输出到 dist/ 目录）
npm run preview      # 预览生产构建结果
```

###  验证全链路

全部启动后，按以下步骤验证系统是否正常：

```bash
# 1. 测试用户注册
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"12345678","username":"测试用户"}'

# 2. 测试用户登录（获取 Token）
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"12345678"}'

# 3. 用返回的 Token 测试鉴权接口（将 <token> 替换为实际值）
curl http://localhost:8080/api/user/1 \
  -H "Authorization: Bearer <token>"

# 4. 测试热度榜（无需 Token，白名单接口）
curl http://localhost:8080/api/rank/top10
```

> 上述命令请求经过 Gateway（8080 端口）转发，验证了路由 + 鉴权全链路。
> 如果直接访问后端服务端口（如 8081）能通但经过 Gateway 不通，检查 Gateway 日志。

### 初始化测试数据

后端服务启动后，执行以下命令创建 10 个测试用户并建立关注关系：

```powershell
# 在 PowerShell 中执行
.\sql\init-data.ps1
```

测试账号：`13800000001` ~ `13800000010`，密码统一 `12345678`。

### 启动顺序总结

```
1. docker compose up -d          （等所有容器 healthy）
2. bash elasticsearch/init-indices.sh
3. cd biteblog-backend && mvn clean install -DskipTests
4. 启动 gateway-service（8080）
5. 启动其他业务服务（8081~8087，顺序不限）
6. .\sql\init-data.ps1             （创建测试用户）
7. cd frontend && npm install && npm run dev
8. 浏览器打开 http://localhost:3000
```

## 项目结构

```
├── docker-compose.yml          # 中间件编排
├── sql/init.sql                # MySQL 初始化
├── elasticsearch/              # ES 索引初始化
├── docs/                       # 项目文档
├── biteblog-backend/           # 后端 Maven 工程
│   ├── biteblog-common/        # 公共模块
│   ├── biteblog-gateway/       # 网关服务
│   ├── biteblog-user/          # 用户服务
│   ├── biteblog-post/          # 笔记服务
│   ├── biteblog-feed/          # Feed 服务
│   ├── biteblog-recommend/     # 推荐服务
│   ├── biteblog-location/      # 位置服务
│   ├── biteblog-rank/          # 排行服务
│   └── biteblog-notify/        # 通知服务
└── frontend/                   # 前端项目
```

## 文档

- [架构设计](docs/architecture.md)
- [接口规范](docs/api-convention.md)
- [开发指南](docs/development-guide.md)
- [需求文档](docs/分布式美食探店社区平台_软件需求说明书_推荐ES版.docx)
- [概要设计](docs/分布式美食探店社区平台_概要设计说明书_ES推荐版.docx)

