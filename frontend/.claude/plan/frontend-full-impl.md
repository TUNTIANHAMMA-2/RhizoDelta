# 前端全量实现计划（方案 A：按架构分层推进）

## 阶段一：基础架构（通用组件 + SSE）

### 1.1 扩展 uiStore — Toast + Tab 状态
- **文件**: `src/stores/uiStore.ts`
- **新增状态**: `toasts: ToastMessage[]`, `activeNodeTab`, `isMobileMenuOpen`
- **新增方法**: `addToast()`, `removeToast()`, `setActiveNodeTab()`

### 1.2 useSse hook
- **文件**: `src/hooks/useSse.ts`
- **核心**: fetch + ReadableStream + TextDecoderStream + AbortController
- **重连**: 指数退避 + Jitter（防雪崩）
- **清理**: useEffect cleanup 调用 controller.abort()
- **集成**: 更新 sseStore.status，调用 graphStore.addNode/addEdge

### 1.3 Toast 通知
- **文件**: `src/components/feedback/Toast.tsx`
- **订阅**: uiStore.toasts
- **样式**: 右下角 fixed，左边框4px对应色，自动消失（成功3s/错误5s）
- **挂载**: GraphWorkspace.tsx 中渲染 `<ToastContainer />`

### 1.4 ConfirmDialog 确认弹窗
- **文件**: `src/components/modals/ConfirmDialog.tsx`
- **Props**: isOpen, title, description, onConfirm, onCancel, confirmText, isDestructive
- **实现**: 遮罩 + 居中弹窗 + Focus Trap + Escape 关闭

### 1.5 Skeleton 骨架屏
- **文件**: `src/components/feedback/Skeleton.tsx`
- **Props**: variant (text/circular/rectangular), width, height
- **样式**: CSS pulse 动画

### 1.6 EmptyState 空状态
- **文件**: `src/components/feedback/EmptyState.tsx`
- **Props**: message, icon?
- **样式**: 居中文案，color-text-tertiary

### 1.7 RoleBadge 角色徽章
- **文件**: `src/components/chrome/RoleBadge.tsx`
- **Props**: role ('ADMIN' | 'AGENT' | 'USER')
- **样式**: pill 样式，颜色按角色区分

---

## 阶段二：表单与布局

### 2.1 PostForm 发帖表单
- **文件**: `src/components/forms/PostForm.tsx`
- **依赖**: @uiw/react-md-editor, api/posts.ts
- **关键**: onKeyDown stopPropagation 防止 React Flow 拦截快捷键
- **字段**: content (MD editor), target_node_id (自动填充)

### 2.2 InjectForm 注入表单
- **文件**: `src/components/forms/InjectForm.tsx`
- **依赖**: api/decisions.ts (executeInject)
- **字段**: content (MD), reason (input), source_node_id (只读)

### 2.3 ForkForm 分叉表单
- **文件**: `src/components/forms/ForkForm.tsx`
- **依赖**: api/decisions.ts (executeFork)
- **字段**: branches 动态列表 (≥2), reason
- **交互**: "+添加分支" 按钮追加行

### 2.4 NodeDetailPanel Tabs 重构
- **文件**: `src/components/panels/NodeDetailPanel.tsx`
- **Tabs**: 详情 | 确权溯源 | 关联 | 审计
- **状态**: uiStore.activeNodeTab（节点切换时保留 Tab）

### 2.5 Breadcrumb 面包屑
- **文件**: `src/components/chrome/Breadcrumb.tsx`
- **位置**: Header 展开时 Logo 右侧
- **数据**: graphStore lineage 路径

---

## 阶段三：业务面板与图谱集成

### 3.1 ProvenancePanel 确权溯源
- **文件**: `src/components/panels/ProvenancePanel.tsx`
- **数据**: fetchProvenance(nodeId)
- **展示**: 简化节点列表，可点击跳转

### 3.2 AssociationPanel 语义关联
- **文件**: `src/components/panels/AssociationPanel.tsx`
- **数据**: graphStore.associations
- **展示**: 关联类型标签 + 置信度 + 关联节点

### 3.3 AuditPanel 审计时间线
- **文件**: `src/components/panels/AuditPanel.tsx`
- **数据**: fetchAuditList(), 游标分页
- **展示**: 时间线列表，决策类型 pill，可展开详情
- **ADMIN**: 显示回滚按钮 → ConfirmDialog

### 3.4 Zoom 形态变化
- **位置**: DagCanvas 监听 viewport.zoom → graphStore.isCompactMode
- **节点**: 订阅 isCompactMode，CSS class 切换（圆形⇔方形）
- **性能**: DagCanvas 级别 throttle，避免节点级 useViewport

### 3.5 MiniMap
- **位置**: DagCanvas 内
- **条件**: nodes.size > 20 时自动显示
- **配色**: 映射节点类型色

### 3.6 移动端响应式
- **断点**: 1024px
- **<1024px**: 画布全屏，左栏 overlay 滑出，右面板底部抽屉
- **触发**: 汉堡菜单按钮

### 3.7 决策进行中状态
- **触发**: POST 决策 API 后，等待 SSE DECISION_COMPLETE
- **展示**: 面板 spinner + "处理中..."，Toast "决策已提交"

---

## 依赖关系

```
阶段一 (无依赖，可并行开发)
├── uiStore 扩展 ← Toast, ConfirmDialog, Tabs
├── useSse ← sseStore, graphStore, authStore
├── Toast ← uiStore
├── ConfirmDialog (独立)
├── Skeleton (独立)
├── EmptyState (独立)
└── RoleBadge (独立)

阶段二 (依赖阶段一的 Toast + uiStore)
├── PostForm ← Toast, uiStore
├── InjectForm ← Toast, uiStore
├── ForkForm ← Toast, uiStore
├── NodeDetailPanel Tabs ← uiStore.activeNodeTab
└── Breadcrumb ← graphStore

阶段三 (依赖阶段一+二)
├── ProvenancePanel ← Skeleton, EmptyState
├── AssociationPanel ← Skeleton, EmptyState
├── AuditPanel ← Skeleton, EmptyState, ConfirmDialog, RoleBadge
├── Zoom ← graphStore (新增 isCompactMode)
├── MiniMap ← graphStore.nodes
├── 移动端 ← uiStore
└── 决策状态 ← Toast, useSse, Skeleton
```
