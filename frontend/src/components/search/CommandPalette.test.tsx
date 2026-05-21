import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CommandPalette } from "./CommandPalette";
import { searchSimilar } from "../../api/search";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";

vi.mock("../../api/search", () => ({
  searchSimilar: vi.fn(),
}));

vi.mock("../../api/nodes", () => ({
  fetchEmbedding: vi.fn(),
  fetchLineage: vi.fn(),
  fetchChildren: vi.fn(),
  fetchAssociations: vi.fn(),
  fetchRhizomes: vi.fn(),
}));

describe("CommandPalette", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useUiStore.setState({
      commandPaletteOpen: true,
      rightPanelMode: "hidden",
      rightPanelPayload: null,
    });
    useGraphStore.setState({
      nodes: new Map(),
      edges: [],
      selectedNodeId: null,
      rootNodeId: null,
      lineageRequestId: 0,
    });
  });

  it("uses backend vector search for typed queries instead of local graph cache", async () => {
    vi.mocked(searchSimilar).mockResolvedValue([
      {
        node_id: "node-remote",
        label: "Human_Post",
        score: 0.91,
        content: "远端全局搜索结果",
        created_at: "2026-05-21T00:00:00Z",
        neighbors: [],
      },
    ]);

    render(<CommandPalette />);
    fireEvent.change(screen.getByPlaceholderText("搜索节点..."), {
      target: { value: "全局搜索" },
    });

    await waitFor(() => {
      expect(searchSimilar).toHaveBeenCalledWith({ query: "全局搜索", top_k: 20 });
    });
    expect(await screen.findByText("远端全局搜索结果")).toBeTruthy();
  });
});
