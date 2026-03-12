# RhizoDelta

## 本地开发环境

1. 启动 Neo4j 5.x：

```bash
docker compose up -d neo4j
```

2. 访问：

- Browser: `http://localhost:7474`
- Bolt: `bolt://localhost:7687`

3. 准备应用配置：

```bash
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

4. 执行测试（需要 JDK17+）：

```bash
mvn test -Dspring.profiles.active=test
```

## API 概览

### 1. 提交帖子

- `POST /api/posts`

请求示例：

```json
{
  "request_id": "req-1001",
  "author_id": "author-001",
  "content": "hello graph",
  "target_node_id": null
}
```

响应示例（202）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "event_id": "1f6ce8a4-8f98-4f8e-a1f6-c1497dd76f94",
    "status": "QUEUED"
  }
}
```

### 2. 查询节点

- `GET /api/nodes/{id}`

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "node_id": "1f6ce8a4-8f98-4f8e-a1f6-c1497dd76f94",
    "label": "Human_Post",
    "content": "hello graph",
    "summary_content": null,
    "author_id": "author-001",
    "agent_version": null,
    "created_at": "2026-03-08T12:00:00Z"
  }
}
```

### 3. 查询 lineage

- `GET /api/nodes/{id}/lineage?max_depth=10`

### 4. 查询 provenance

- `GET /api/nodes/{id}/provenance`
