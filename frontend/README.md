# RhizoDelta Frontend

当前前端使用 React + TypeScript + Vite，主界面入口在：

- `src/App.tsx`
- `src/components/GraphWorkspace.tsx`

完整项目手册见：

- [../Doc/使用手册.md](../Doc/使用手册.md)

## 启动

安装依赖：

```bash
npm install
```

开发模式：

```bash
npm run dev
```

构建：

```bash
npm run build
```

Lint：

```bash
npm run lint
```

## API 访问方式

前端请求封装在 `src/api/client.ts`。

- 如果没有设置 `VITE_API_BASE_URL`，前端会直接请求相对路径 `/api/...`
- 开发模式下，Vite 会把 `/api` 代理到 `http://localhost:8080`
- 如果前后端不在同一个地址上运行，可以手动指定：

```bash
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

## JWT 调试

当前仓库没有登录页，也没有签发 token 的接口。
前端通过 `localStorage.jwt_token` 读取令牌，读取位置在：

- `src/stores/authStore.ts`
- `src/api/client.ts`
- `src/hooks/useSse.ts`

### Token 载荷要求

最少需要这些字段：

```json
{
  "sub": "dev-admin",
  "roles": ["ADMIN"],
  "iat": 1710000000,
  "exp": 1710003600
}
```

注意：

- `roles` 里写的是 `ADMIN` / `AGENT` / `USER`
- 不要写成 `ROLE_ADMIN`
- 前端直接读取 `roles` claim
- 后端会在鉴权阶段把它映射成 `ROLE_` 前缀权限

### 生成本地调试 JWT

把下面命令中的 `JWT_SECRET` 替换成
`../src/main/resources/application-local.yml` 中
`rhizodelta.jwt.secret` 的当前值：

```bash
JWT_SECRET='replace_with_local_jwt_secret' node - <<'EOF'
const crypto = require("crypto");

const secret = process.env.JWT_SECRET;
if (!secret) {
  throw new Error("JWT_SECRET is required");
}

const now = Math.floor(Date.now() / 1000);
const header = { alg: "HS256", typ: "JWT" };
const payload = {
  sub: "dev-admin",
  roles: ["ADMIN"],
  iat: now,
  exp: now + 3600,
};

const encode = (value) =>
  Buffer.from(JSON.stringify(value)).toString("base64url");

const unsigned = `${encode(header)}.${encode(payload)}`;
const signature = crypto
  .createHmac("sha256", secret)
  .update(unsigned)
  .digest("base64url");

console.log(`${unsigned}.${signature}`);
EOF
```

把生成结果写入浏览器控制台：

```js
localStorage.setItem("jwt_token", "paste_your_token_here");
location.reload();
```

## 页面使用说明

### 左侧栏

- 展示根话题列表（Rhizones）
- 点击某个根节点会加载该根下的 lineage 和 children 图
- 底部 `+ 发起新话题` 会打开发布面板

### 中央画布

支持两种模式：

- `版本视图`：展示 lineage DAG
- `探索视图`：展示更自由的探索画布

### 节点交互

- 选中节点后可以打开详情面板
- 节点操作条支持：
  - `延续注入`
  - `分叉`

### 右侧面板

节点详情面板包含：

- `详情`
- `确权溯源`
- `关联`
- `审计`

编辑面板支持：

- 发布观点
- 延续注入
- 分叉

## 实时更新

前端会使用 `/api/events/stream` 建立 SSE 连接。
连接建立后会接收这些事件类型：

- `ORCHESTRATION_STATUS`
- `NODE_CREATED`
- `EDGE_CREATED`
- `DECISION_COMPLETE`

如果页面右上角状态从连接态变成断开态，优先检查：

- 浏览器里是否已有有效 `jwt_token`
- 后端是否正常运行在 `http://localhost:8080`
- SSE 请求是否返回了 `401`
