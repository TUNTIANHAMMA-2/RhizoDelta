export interface SelectRhizomeDeps {
  selectNode: (nodeId: string | null) => void;
  closeRightPanel: () => void;
  loadGraphForRoot: (nodeId: string) => Promise<void>;
}

export async function selectRhizome(
  nodeId: string,
  deps: SelectRhizomeDeps,
): Promise<void> {
  deps.selectNode(null);
  deps.closeRightPanel();
  await deps.loadGraphForRoot(nodeId);
}
