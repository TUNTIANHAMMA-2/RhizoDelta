/**
 * 加载某个根节点的画布上下文：一次 HTTP 拉回 lineage + children。
 *
 * 取代了过去 `loadLineage` 之后再串行 `loadChildren` 的瀑布。叶子节点
 * 的 children 端被后端降级为空，所以聚合 loader 不再有"children 失败但
 * lineage 成功"的中间态需要单独 toast。
 */
export interface RootGraphLoadDeps {
  loadTopologyContext: (nodeId: string) => Promise<void>;
}

export async function loadGraphForRoot(
  nodeId: string,
  deps: RootGraphLoadDeps,
): Promise<void> {
  await deps.loadTopologyContext(nodeId);
}
