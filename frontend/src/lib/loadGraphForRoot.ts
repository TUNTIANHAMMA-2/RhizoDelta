export interface RootGraphLoadDeps {
  loadLineage: (nodeId: string) => Promise<void>;
  loadChildren: (nodeId: string) => Promise<void>;
  onChildrenError?: (error: Error) => void;
}

export async function loadGraphForRoot(
  nodeId: string,
  deps: RootGraphLoadDeps,
): Promise<void> {
  await deps.loadLineage(nodeId);
  try {
    await deps.loadChildren(nodeId);
  } catch (error) {
    const resolvedError =
      error instanceof Error ? error : new Error(String(error));
    deps.onChildrenError?.(resolvedError);
  }
}
