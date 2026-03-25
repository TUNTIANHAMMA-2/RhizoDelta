# RhizoDelta

RhizoDelta 是一个基于图谱的非线性讨论系统。当前仓库包含：

- Spring Boot 后端
- React + Vite 前端
- Neo4j / RabbitMQ / Redis 本地依赖

如果你是第一次接手这个项目，先看完整手册：

- [项目使用手册](Doc/使用手册.md)
- [前端说明](frontend/README.md)

## 快速启动

### 1. 准备本地依赖

需要先准备：

- JDK 17+
- Maven
- Node.js 与 npm
- Docker Compose
- 可用的 `SILICON_FLOW_API_KEY`

### 2. 准备 Docker 环境变量

先复制 `.env`：

```bash
cp .env.example .env
```

本地 profile 默认使用的 Neo4j 密码在
`src/main/resources/application-local.yml` 中写死为 `12345678`。
因此建议把 `.env` 至少改成下面这样，避免 Docker 中的 Neo4j 密码和后端配置不一致：

```dotenv
NEO4J_AUTH=neo4j/12345678
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=12345678
NEO4J_URI=bolt://localhost:7687
```

### 3. 启动 Neo4j / RabbitMQ / Redis

```bash
docker compose up -d neo4j rabbitmq redis
```

常用地址：

- Neo4j Browser: `http://localhost:7474`
- Neo4j Bolt: `bolt://localhost:7687`
- RabbitMQ Management: `http://localhost:15672`
- Redis: `localhost:6379`

### 4. 导出 AI 模型 API Key

后端启动时会校验 `langchain4j.open-ai.*.api-key`，为空会直接启动失败。
本地 profile 通过环境变量读取 `SILICON_FLOW_API_KEY`：

```bash
export SILICON_FLOW_API_KEY=your_api_key_here
```

### 5. 启动后端

```bash
mvn spring-boot:run
```

默认会使用 `local` profile。

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
```

Vite 开发服务器会把 `/api` 代理到 `http://localhost:8080`。

### 7. 准备 JWT

当前项目没有登录接口。前端和 SSE 都依赖浏览器里的
`localStorage.jwt_token`。

JWT 的生成方式、角色说明和浏览器注入步骤见：

- [项目使用手册](Doc/使用手册.md#jwt-本地调试)
- [前端说明](frontend/README.md#jwt-调试)

## 常用验证命令

后端测试：

```bash
mvn test -Dspring.profiles.active=test
```

前端构建：

```bash
cd frontend
npm run build
```

## 项目入口

- 后端启动类：`src/main/java/com/rhizodelta/RhizoDeltaApplication.java`
- 后端配置：`src/main/resources/application.yml`
- 本地配置：`src/main/resources/application-local.yml`
- 前端入口：`frontend/src/App.tsx`
- 主工作区：`frontend/src/components/GraphWorkspace.tsx`
