/* @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NodeDetailPanel } from "./NodeDetailPanel";
import { ProvenancePanel } from "./ProvenancePanel";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useSseStore } from "../../stores/sseStore";
import type { GraphNodeDTO } from "../../api/types";

const mockFetchProvenance = vi.fn();

vi.mock("../../api/nodes", () => ({
  fetchProvenance: (...args: unknown[]) => mockFetchProvenance(...args),
  summarizeNode: vi.fn(),
  fetchNode: vi.fn(),
}));

vi.mock("../editor/MarkdownViewer", () => ({
  MarkdownViewer: ({ content }: { content: string }) => <div>{content}</div>,
}));

vi.mock("./DecisionCard", () => ({
  DecisionCard: () => null,
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
    label: "Human_Post",
    content: "content",
    summary_content: "summary",
    author_id: "author-1",
    author_username: "alice",
    author_display_name: "Alice",
    agent_version: null as unknown as string,
    created_at: new Date("2026-04-23T00:00:00Z").toISOString(),
    has_embedding: false,
    ...overrides,
  };
}

describe("Author display panels", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useUiStore.setState({
      rightPanelPayload: null,
      activeNodeTab: "details",
      openDetailPanel: vi.fn() as never,
    });
    useGraphStore.setState({
      nodes: new Map(),
      selectNode: vi.fn() as never,
    });
    useSseStore.setState({ orchestrationStatuses: {} });
  });

  afterEach(() => {
    cleanup();
  });

  it("NodeDetailPanel prefers author display name over author_id", () => {
    const node = makeNode();
    useUiStore.setState({
      rightPanelPayload: { nodeId: node.node_id },
      activeNodeTab: "details",
    });
    useGraphStore.setState({
      nodes: new Map([[node.node_id, node]]),
    });

    render(<NodeDetailPanel />);

    expect(screen.getByText(/^Alice ·/)).toBeInTheDocument();
  });

  it("NodeDetailPanel falls back to agent version when no human author fields exist", () => {
    const node = makeNode({
      label: "AI_Consensus",
      author_id: undefined,
      author_username: undefined,
      author_display_name: undefined,
      agent_version: "v1-agent",
    });
    useUiStore.setState({
      rightPanelPayload: { nodeId: node.node_id },
      activeNodeTab: "details",
    });
    useGraphStore.setState({
      nodes: new Map([[node.node_id, node]]),
    });

    render(<NodeDetailPanel />);

    expect(screen.getAllByText(/^v1-agent ·/)).not.toHaveLength(0);
  });

  it("ProvenancePanel prefers human-readable author fields", async () => {
    mockFetchProvenance.mockResolvedValueOnce([
      makeNode({
        node_id: "source-1",
        author_id: "author-2",
        author_username: "bob",
        author_display_name: "Bob",
      }),
    ]);

    render(<ProvenancePanel nodeId="consensus-1" />);

    await waitFor(() => {
      expect(screen.getByText(/^Bob ·/)).toBeInTheDocument();
    });
  });
});
