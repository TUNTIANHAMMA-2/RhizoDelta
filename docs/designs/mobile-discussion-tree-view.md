# 移动端讨论树视图设计探索

本文记录一次关于 RhizoDelta 移动端阅读体验的设计探索。当前内容是设计讨论沉淀，不代表已经实施。

## 主题

目标是在不改变后端写路径、不破坏底层图结构的前提下，为移动端新增一个“读路径视图退化”的讨论树视图。

核心思想：

```text
底层存储仍是图 / DAG
  Human_Post、AI_Consensus、Result
  CONTINUES_FROM、BRANCHED_FROM、MERGED_INTO、SYNTHESIZED_FROM、MATERIALIZED_FROM 等

移动端读取时退化成树
  root Human_Post
  ├─ child Human_Post
  ├─ child Human_Post
  │  └─ grandchild Human_Post
  └─ ...
```

这个视图不是替代图谱，而是面向移动端阅读和回复场景的读模型投影。桌面端暂时不改，继续使用现有图式视图。移动端完成后，再考虑把这套讨论树和闭包视觉适配到桌面端。

## 总体方向

采用“读路径做视图退化”的方案。

```text
后端物理结构：
  保持绝对图结构，不改 PostService 写入逻辑

后端查询层：
  新增只读讨论树查询，把 root 下的 Human_Post 回复关系投影成树

前端移动端：
  打开 rhizome 后默认进入 root discussion tree
  不再默认展示 React Flow / D3 图谱画布

前端桌面端：
  暂时不改，继续保留图式视图
```

关键背景事实：当前 RhizoDelta 代码中，回复关系不是 `PARENT_OF`，而是：

```text
(post)-[:CONTINUES_FROM]->(target)
```

同时存在：

```text
Human_Post -[:BRANCHED_FROM]-> GraphNode
AI_Consensus -[:MERGED_INTO]-> GraphNode
AI_Consensus -[:SYNTHESIZED_FROM]-> Human_Post
Result -[:MATERIALIZED_FROM]-> GraphNode
Result -[:CROSS_SYNTHESIZED_FROM]-> Result
AI_Consensus -[:CONVERGED_FROM]-> GraphNode
```

因此讨论树不能照搬 `PARENT_OF` 示例，应基于：

```text
root <-[:CONTINUES_FROM|BRANCHED_FROM*0..]- descendants
```

移动端讨论树主体主要由 `Human_Post` 组成，`AI_Consensus` / `Result` 不作为普通评论节点，而作为闭包批注或结果批注挂在讨论树上。

## 已确认决策

### 1. 移动端打开 rhizome 后展示 root 整棵讨论树

移动端入口采用：

```text
进入 rhizome
  ↓
直接加载 rootNodeId 的 discussion tree
  ↓
像论坛 / 评论区一样阅读
  ↓
需要时再进入图谱 / 审计视图
```

而不是：

```text
进入 rhizome
  ↓
加载图谱画布
  ↓
用户点击节点
  ↓
读详情面板
```

语义：

```text
Rhizome = 一个话题 / 一棵讨论树的根

A 根帖
├─ B 回复 A
│  ├─ D 回复 B
│  └─ E 回复 B
└─ C 回复 A
   └─ F 回复 C
```

移动端主入口默认展示 `A` 这棵树，而不是要求用户先在图谱中点选节点。

### 2. 产品语义是整棵讨论树，工程上默认限深限量

默认策略：

```text
默认展示 root 下前 5 层 / 最多约 200 条
后续再做“展开更多回复”
```

即：

```text
产品体验：
  打开后像是在读整棵讨论树

工程保护：
  max_depth = 5
  limit ≈ 200
```

原因：

- 一次返回完整大树可能导致移动端网络响应、DOM 数量、渲染成本过高。
- 先用深度和数量上限解决主要场景。
- 后续可扩展“展开更多回复”。

数据契约最好预留：

```text
has_more_children
truncated
next_cursor / continuation token
```

MVP 不一定马上实现完整分页，但不应把接口设计死。

### 3. Human_Post 是主阅读树，AI_Consensus / Result 不混入 children

树主体：

```text
Human_Post：
  形成 discussion tree 主体

AI_Consensus / Result：
  不作为普通评论节点
  不混入 children
  作为 artifact / closure note / result note 挂在树节点区域
```

原因：

- `AI_Consensus` 不是某个人对某条评论的回复。
- 它通常是对若干 `Human_Post` 的总结。
- 它的核心语义是“覆盖范围”和“总结结果”，不是楼层顺序。
- 如果作为 `children` 渲染，会破坏讨论阅读流，也容易误导。

### 4. AI_Consensus 作为 Closure Note，而不是卡片或小点

移动端使用闭包括号 / 分组批注的原始稿，而不是图谱式小点，也不是重卡片。

视觉方向：

```text
A 根帖

┌ AI 共识：本段讨论主要收束为三个观点……
│
│  B 回复 A
│
│  C 回复 A
│  └─ D 回复 C
│
└  E 回复 A
```

分支级闭包：

```text
A 根帖
├─ B 回复
│
│  ┌ AI 共识：B 分支目前形成了一个局部结论……
│  │
│  │  D 回复 B
│  │
│  └  E 回复 B
│
└─ C 回复
```

桌面端暂时不管，继续图式。移动端先做这个闭包视觉。

### 5. 闭包视觉不是精确审计线，而是阅读区范围提示

当 `AI_Consensus` 覆盖来源节点不连续时：

```text
A
├─ B   ← 被总结
├─ C   ← 没被总结
├─ D   ← 被总结
└─ E   ← 被总结
```

如果画成：

```text
┌ AI 共识
│ B
│ C
│ D
└ E
```

会误导用户以为 `C` 也被总结。

因此定义为：

```text
左侧闭包括号 / 轨道：
  表达“这个分支 / 区域存在 AI 总结”
  不承诺每条都被覆盖

展开态来源列表：
  表达精确的 SYNTHESIZED_FROM / MATERIALIZED_FROM 证据
```

默认阅读优先美观和流畅，学术严谨性通过展开态来源列表保留。

### 6. 默认树线保持灰色，被总结来源用共识色高亮

未被总结节点不应出现“空出来”的效果。采用：

```text
所有评论保留默认灰色树线
被 Closure Note 覆盖的来源节点，在同一树线位置叠加共识色高亮
未覆盖节点仍显示灰线，不留空
展开 Closure Note 后列出精确来源
```

示意：

```text
A 根帖

│  AI 共识：本段讨论总结……
│
├─ B   被总结：紫色连接
│
├─ C   未总结：灰色默认连接
│
├─ D   被总结：紫色连接
└─ E   被总结：紫色连接
```

视觉语义：

```text
灰色线 / 灰色连接点：
  普通讨论树结构

紫色线 / 紫色连接点：
  这个节点是当前 AI 共识来源
```

这相当于两层叠加：

```text
默认讨论树线：连接所有 sibling
共识覆盖强调：只高亮 sourceNodeIds
```

### 7. 暂不为多闭包重叠做复杂设计

当前系统大概率不会出现大量互相交叉的局部闭包。`DecisionService` 的 merge-or-append 逻辑倾向于：

```text
多个对同一主题 / root 的回复
  → 追加到同一个 AI_Consensus
```

而不是频繁产生多个互相交叉的闭包。

MVP 规则：

```text
每个 anchor 节点下通常只有 0 或 1 个主要 Closure Note
如果偶尔有多个 artifact，就纵向显示多个简短 note
不专门设计多轨道、不做复杂重叠高亮
```

多闭包重叠作为低优先级风险记录，不作为当前核心问题。

### 8. AI_Consensus / Result 的挂载位置基于关系锚点

挂载规则遵循图关系：

```text
AI_Consensus -[:MERGED_INTO]-> X
  → 显示在 X 的讨论区域下

Result -[:MATERIALIZED_FROM]-> X
  → 显示在 X 的讨论区域下
```

对于当前系统，由于 root fallback，大多数 `AI_Consensus` 可能自然挂在 root 下。但规则仍应保持关系驱动，这样未来出现分支级共识时不用改 UI 语义。

来源关系：

```text
AI_Consensus -[:SYNTHESIZED_FROM]-> Human_Post
  → 用于展开态展示“精确来源”
  → 用于默认视图中高亮被覆盖的 Human_Post

Result -[:MATERIALIZED_FROM]-> X
  → 既是 anchor，也可作为来源依据

CONVERGED_FROM / CROSS_SYNTHESIZED_FROM
  → 后续再扩展，当前不是 MVP 重点
```

### 9. 移动端轻触 Human_Post 选择回复目标，长按打开其他操作

交互规则：

```text
轻触 Human_Post = 选中并准备回复
长按 Human_Post = 其他操作
```

移动端状态：

```text
默认：
底部输入栏：回复主帖

轻触 B：
B 高亮
底部输入栏：回复 B

再轻触 C：
C 高亮
底部输入栏：回复 C
```

写入语义：

```text
selectedReplyTargetId = tappedHumanPost.nodeId

CreatePostRequest {
  content,
  target_node_id: selectedReplyTargetId
}
```

建议补充交互规则：

```text
轻触选中不自动弹键盘
  只有点击底部输入栏才弹键盘，避免误触打断阅读

长按不改变当前回复目标
  长按只打开更多操作菜单

点击空白 / 取消选择
  selectedReplyTargetId 回到 rootNodeId
  底部栏显示“回复主帖”
```

长按菜单可包含：

```text
查看详情
复制内容
查看图谱位置
后续其他操作
```

MVP 可以先做简单菜单，甚至只保留复制 / 详情。

### 10. 同层回复按 created_at ASC 排列

排序规则：

```text
同一父节点 children：
  按 created_at ASC
  旧 → 新

新回复：
  追加到对应父节点 children 末尾
```

示意：

```text
A 根帖
├─ B  10:00
├─ D  10:02
└─ C  10:05
```

原因：

- 树状讨论按时间正序更像读对话。
- 旧回复通常是新回复的语境。
- 后续可以扩展“最新优先 / 最热优先”，但 MVP 不做。

### 11. 移动端视觉缩进最多 3 档

规则：

```text
真实层级保留
视觉缩进最多 3 档
```

例如：

```text
depth 0: margin-left 0
depth 1: margin-left 12px
depth 2: margin-left 24px
depth >= 3: margin-left 36px，不再继续增加
```

原因：手机上如果无限缩进，会变成：

```text
A
  B
    C
      D
        E
          F
            G  ← 正文区域被挤没
```

因此：

```text
结构语义：
  后端保留完整 parent / children

视觉布局：
  移动端压缩深层缩进，避免横向空间耗尽
```

后续如果需要，可以做“聚焦分支模式”，但不属于 MVP。

### 12. 桌面端暂时不改

范围控制：

```text
移动端先使用闭包括号 / 分组批注设计
桌面端暂时不管，继续使用图式
等移动端开发完成，再把这套适配到桌面端
```

设计范围应避免过早牵扯桌面图谱重构。

## 工程约束

移动端不能只是把讨论树盖在图谱上。

如果只是隐藏或覆盖：

```text
DagCanvas / ExploreCanvas 仍然挂载
React Flow 仍存在
D3 force simulation 仍在运行
backdrop-filter 节点仍参与重绘
```

性能收益会打折。

因此移动端应为：

```text
mobile route / mobile layout:
  render DiscussionTreeView

desktop:
  render existing GraphWorkspace / DagCanvas / ExploreCanvas
```

或至少在移动端默认 discussion mode 时，不挂载 `DagCanvas` / `ExploreCanvas`。

这是移动端性能收益的关键。

## 后端 DTO 方向

尚未最终定 DTO，但探索方向如下：

```ts
interface DiscussionTreeResponse {
  root: CommentNode;
  meta: {
    rootNodeId: string;
    maxDepth: number;
    limit: number;
    truncated: boolean;
  };
}

interface CommentNode {
  nodeId: string;
  content: string;
  author: AuthorProjection;
  createdAt: string;

  parentId: string | null;
  depth: number;

  children: CommentNode[];

  artifacts: DiscussionArtifact[];

  hasMoreChildren?: boolean;
}

interface DiscussionArtifact {
  nodeId: string;
  kind: "CONSENSUS" | "RESULT";
  anchorNodeId: string;

  title: string;
  body: string;

  sourceNodeIds: string[];
  sourceCount: number;

  createdAt: string;
}
```

其中：

```text
CommentNode.children:
  只放 Human_Post

CommentNode.artifacts:
  放 AI_Consensus / Result 等注记

sourceNodeIds:
  用于展开态来源列表
  用于默认视图高亮被总结来源

anchorNodeId:
  决定 artifact 挂在哪个 CommentNode 下
```

未来可能扩展：

```ts
kind: "CONSENSUS" | "RESULT" | "JOIN" | "CROSS_SYNTH"
```

但 MVP 先支持：

```text
CONSENSUS:
  AI_Consensus -[:MERGED_INTO]-> anchor
  sourceNodeIds from SYNTHESIZED_FROM

RESULT:
  Result -[:MATERIALIZED_FROM]-> anchor
```

## 后端查询方向

讨论树主体：

```cypher
MATCH path = (root:Human_Post:GraphNode {node_id: $rootId})
             <-[:CONTINUES_FROM|BRANCHED_FROM*0..50]-(reply:Human_Post)
WHERE NOT coalesce(root._deleted, false)
  AND NOT coalesce(reply._deleted, false)
  AND length(path) <= $maxDepth
...
```

注意：

- Neo4j Cypher variable length 上限不能直接参数化成 `*0..$maxDepth`，实际实现可使用 `*0..50` + `WHERE length(path) <= $maxDepth`，项目现有查询也是这种风格。
- `parentId` 应从关系推导，而不是假设节点上有 `parent_id`。
- `rootId` 可用 `root_id` 辅助过滤，但树结构最好从边关系推导。
- 作者投影应复用现有 author projection 逻辑，不要只依赖 `author_id`。
- 必须过滤 `_deleted`。

artifact 查询：

```text
AI_Consensus:
  MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(anchor:GraphNode)
  WHERE anchor.node_id IN commentNodeIds
  OPTIONAL MATCH (ai)-[:SYNTHESIZED_FROM]->(source:Human_Post)
  RETURN ai, anchor.node_id, collect(source.node_id)

Result:
  MATCH (result:Result)-[:MATERIALIZED_FROM]->(anchor:GraphNode)
  WHERE anchor.node_id IN commentNodeIds
  RETURN result, anchor.node_id
```

## 前端组件方向

移动端可能需要新增组件：

```text
MobileDiscussionTreeView
CommentTreeItem
ClosureNote
MobileReplyComposer
LongPressMenu
```

职责：

```text
MobileDiscussionTreeView:
  加载 root discussion tree
  管理 selectedReplyTargetId
  管理 active / expanded closure note
  管理发布后的 pending 状态

CommentTreeItem:
  渲染 Human_Post
  处理轻触选中
  处理长按菜单
  渲染 children
  控制 depth 缩进上限

ClosureNote:
  渲染闭包批注 / 左侧轨道
  展开后显示 body + source list
  根据 sourceNodeIds 高亮对应 Human_Post

MobileReplyComposer:
  固定底部输入栏
  显示“回复主帖 / 回复某评论”
  发布时传 target_node_id

LongPressMenu:
  查看详情 / 复制 / 查看图谱位置 等
```

注意：

```text
轻触 Human_Post 不自动弹键盘
点击底部输入栏才聚焦
```

## 发帖后的关键风险

当前系统发帖是异步队列语义，接口可能返回 `QUEUED`，真实节点稍后才创建。

移动端回复后不能让用户觉得“内容丢了”。

需要处理：

```text
用户发送
  ↓
底部输入框显示发布中
  ↓
对应 target 节点下出现 pending 回复
  ↓
SSE 收到 NodeCreated / EdgeCreated 或重新拉树
  ↓
pending 替换为真实 Human_Post
```

这是体验上最大的细节风险之一。

可选策略：

```text
MVP 简化：
  发布成功后显示 toast + 重新拉 discussion tree

更好体验：
  optimistic pending node + SSE / refetch reconcile
```

这部分尚未最终定。

## 剩余问题

### 1. API DTO 最终形状

需要继续设计稳定的数据契约：

```text
root
children
artifacts
sourceNodeIds
truncated / hasMoreChildren
meta
```

### 2. 移动端是否走独立 route / layout

待定：

```text
/rhizomes/:id 在 mobile 下渲染 MobileDiscussionTree
```

还是在现有 `GraphWorkspace` 内做 responsive 分支。

关键要求：移动端 discussion tree 默认模式下不要挂载 React Flow / D3。

### 3. 发帖后的 pending / SSE / refetch 策略

需要定：

```text
optimistic node?
仅 refetch?
失败状态怎么展示?
request_id 如何用于 reconcile?
```

### 4. Closure Note 的精确视觉设计

已定方向：

```text
左侧闭包轨道
灰色默认树线
被总结来源使用共识色高亮
展开显示真实来源
```

仍需具体设计：

```text
行距
颜色
展开动效
source 高亮方式
深度压缩后的线条如何处理
```

### 5. 截断提示和后续展开机制

已定 MVP 默认：

```text
max_depth = 5
limit ≈ 200
```

待定：

```text
超过深度时显示什么
超过数量时显示什么
每个节点是否有 hasMoreChildren
展开更多是否本期做
```

### 6. 长按菜单内容

已定：

```text
长按 = 其他操作
```

菜单项未最终定。MVP 可考虑：

```text
复制内容
查看详情
查看图谱位置
```

### 7. AI / Result artifact 的类型边界

MVP 先支持：

```text
AI_Consensus via MERGED_INTO + SYNTHESIZED_FROM
Result via MATERIALIZED_FROM
```

后续：

```text
JOIN / CONVERGED_FROM
CROSS_SYNTHESIZED_FROM
```

是否本期预留 DTO `kind`，需要设计。

### 8. 复用现有 children topology 还是新增接口

当前后端已有 `/api/nodes/{id}/children` 返回图 topology。

新需求更适合新增：

```text
GET /api/nodes/{rootId}/discussion-tree
```

也可评估：

```text
扩展 children endpoint with view=tree
```

当前倾向专用 endpoint，语义更干净。

## 一句话原则

移动端 RhizoDelta 不再默认渲染图谱，而是渲染一个由读路径实时生成的 root discussion tree。`Human_Post` 构成主阅读树；`AI_Consensus` 与 `Result` 不进入 `children`，而作为关系锚定的 Closure Note 挂在对应讨论区域。闭包视觉优先服务移动端阅读，美学上采用左侧轨道和灰 / 共识色线条；精确来源通过展开态展示，保留图谱审计语义。
