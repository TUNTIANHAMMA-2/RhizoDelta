import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NodeDetailPanel } from "./NodeDetailPanel";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useSseStore } from "../../stores/sseStore";
import type { GraphNodeDTO } from "../../api/types";

const mockSummarizeNode = vi.fn();
const mockFetchNode = vi.fn();

vi.mock("../../api/nodes", () => ({
  summarizeNode: (...args: unknown[]) => mockSummarizeNode(...args),
  fetchNode: (...args: unknown[]) => mockFetchNode(...args),
}));

vi.mock("../editor/MarkdownViewer", () => ({
  MarkdownViewer: ({ content }: { content: string }) => (
    <div data-testid="markdown-viewer">{content}</div>
  ),
}));

vi.mock("./DecisionCard", () => ({
  DecisionCard: () => null,
}));

vi.mock("./ProvenancePanel", () => ({
  ProvenancePanel: () => null,
}));

vi.mock("./AssociationPanel", () => ({
  AssociationPanel: () => null,
}));

vi.mock("./AuditPanel", () => ({
  AuditPanel: () => null,
}));

function makeNode(overrides: Partial<GraphNodeDTO> = {}): GraphNodeDTO {
  return {
    node_id: "node-1",
    label: "AI_Consensus",
    content: "旧内容",
    summary_content: "旧摘要",
    author_id: null as unknown as string,
    agent_version: "v1",
    created_at: new Date().toISOString(),
    has_embedding: true,
    ...overrides,
  };
}

function setupStores(node: GraphNodeDTO) {
  useUiStore.setState({
    rightPanelPayload: { nodeId: node.node_id },
    activeNodeTab: "details",
  });
  useGraphStore.setState({
    nodes: new Map([[node.node_id, node]]),
  });
  useSseStore.setState({ orchestrationStatuses: {} });
}

describe("NodeDetailPanel — manual summary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useUiStore.setState({
      rightPanelPayload: null,
      activeNodeTab: "details",
    });
    useGraphStore.setState({ nodes: new Map() });
    useSseStore.setState({ orchestrationStatuses: {} });
  });

  it("shows summarize button only for AI_Consensus nodes", () => {
    const consensus = makeNode();
    setupStores(consensus);
    const { unmount } = render(<NodeDetailPanel />);
    expect(screen.getByRole("button", { name: "生成摘要" })).toBeInTheDocument();
    unmount();

    const human = makeNode({ node_id: "node-2", label: "Human_Post" });
    setupStores(human);
    render(<NodeDetailPanel />);
    expect(screen.queryByRole("button", { name: "生成摘要" })).not.toBeInTheDocument();
  });

  it("refreshes node via fetchNode after summarizeNode succeeds", async () => {
    const node = makeNode();
    setupStores(node);

    const updatedNode = makeNode({ summary_content: "新摘要内容" });
    mockSummarizeNode.mockResolvedValueOnce({ summary: "ok", source_count: 3, model_used: "test" });
    mockFetchNode.mockResolvedValueOnce(updatedNode);

    render(<NodeDetailPanel />);
    fireEvent.click(screen.getByRole("button", { name: "生成摘要" }));

    await waitFor(() => {
      expect(mockSummarizeNode).toHaveBeenCalledWith("node-1");
      expect(mockFetchNode).toHaveBeenCalledWith("node-1");
    });

    // graphStore should contain the updated node
    expect(useGraphStore.getState().nodes.get("node-1")?.summary_content).toBe("新摘要内容");
  });

  it("calls summarizeNode before fetchNode (correct order)", async () => {
    const node = makeNode();
    setupStores(node);

    const callOrder: string[] = [];
    mockSummarizeNode.mockImplementation(() => {
      callOrder.push("summarize");
      return Promise.resolve({});
    });
    mockFetchNode.mockImplementation(() => {
      callOrder.push("fetch");
      return Promise.resolve(makeNode({ summary_content: "更新" }));
    });

    render(<NodeDetailPanel />);
    fireEvent.click(screen.getByRole("button", { name: "生成摘要" }));

    await waitFor(() => expect(mockFetchNode).toHaveBeenCalled());
    expect(callOrder).toEqual(["summarize", "fetch"]);
  });

  it("shows loading state during summarization and restores after", async () => {
    const node = makeNode();
    setupStores(node);

    let resolveSummarize!: (v: unknown) => void;
    mockSummarizeNode.mockReturnValueOnce(new Promise((r) => { resolveSummarize = r; }));
    mockFetchNode.mockResolvedValueOnce(makeNode());

    render(<NodeDetailPanel />);
    const btn = screen.getByRole("button", { name: "生成摘要" });

    fireEvent.click(btn);
    expect(screen.getByRole("button", { name: "生成中..." })).toBeDisabled();

    resolveSummarize({});
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "生成摘要" })).not.toBeDisabled();
    });
  });

  it("resets loading state even when summarizeNode fails", async () => {
    const node = makeNode();
    setupStores(node);

    mockSummarizeNode.mockRejectedValueOnce(new Error("server error"));

    render(<NodeDetailPanel />);
    fireEvent.click(screen.getByRole("button", { name: "生成摘要" }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "生成摘要" })).not.toBeDisabled();
    });
    // fetchNode should NOT be called when summarize fails
    expect(mockFetchNode).not.toHaveBeenCalled();
  });

  it("refreshes without any SSE event", async () => {
    const node = makeNode({ content: undefined, summary_content: "旧摘要" });
    setupStores(node);

    const updated = makeNode({ content: undefined, summary_content: "通过手动刷新获取的新摘要" });
    mockSummarizeNode.mockResolvedValueOnce({});
    mockFetchNode.mockResolvedValueOnce(updated);

    render(<NodeDetailPanel />);

    // Verify old content shown
    expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("旧摘要");

    fireEvent.click(screen.getByRole("button", { name: "生成摘要" }));

    await waitFor(() => {
      expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("通过手动刷新获取的新摘要");
    });

    // No SSE interaction needed — only API calls
    expect(mockSummarizeNode).toHaveBeenCalledTimes(1);
    expect(mockFetchNode).toHaveBeenCalledTimes(1);
  });
});
