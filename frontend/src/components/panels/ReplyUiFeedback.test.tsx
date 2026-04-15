import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PostForm } from "../forms/PostForm";
import { NodeDetailPanel } from "./NodeDetailPanel";
import { createPost } from "../../api/posts";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";
import { useSseStore } from "../../stores/sseStore";
import { useUiStore } from "../../stores/uiStore";

vi.mock("../../api/posts", () => ({
  createPost: vi.fn(),
}));

vi.mock("../editor/MarkdownEditor", () => ({
  MarkdownEditor: ({
    value,
    onChange,
  }: {
    value: string;
    onChange: (value: string) => void;
  }) => (
    <textarea
      aria-label="markdown-editor"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  ),
}));

vi.mock("./ProvenancePanel", () => ({
  ProvenancePanel: () => <div>ProvenancePanel</div>,
}));

vi.mock("./AssociationPanel", () => ({
  AssociationPanel: () => <div>AssociationPanel</div>,
}));

vi.mock("./AuditPanel", () => ({
  AuditPanel: () => <div>AuditPanel</div>,
}));

vi.mock("../editor/MarkdownViewer", () => ({
  MarkdownViewer: ({ content }: { content: string }) => <div>{content}</div>,
}));

function createToken(payload: Record<string, unknown>) {
  const encode = (value: Record<string, unknown>) =>
    btoa(JSON.stringify(value))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");

  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("ReplyUiFeedback", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.getState().clearToken();
    useUiStore.setState({ toasts: [], rightPanelPayload: null, activeNodeTab: "details" });
    useGraphStore.setState({ selectedNodeId: null, nodes: new Map() });
    useSseStore.setState({ orchestrationStatuses: {} });
  });

  it("should emit a reply-specific success toast", async () => {
    vi.mocked(createPost).mockResolvedValue({
      event_id: "reply-event-1",
      status: "QUEUED",
    });
    useAuthStore.getState().setToken(createToken({ sub: "user-reply", roles: ["USER"] }));
    useGraphStore.setState({
      selectedNodeId: "node-1",
      nodes: new Map([
        [
          "node-1",
          {
            node_id: "node-1",
            label: "Human_Post",
            content: "父帖",
            summary_content: null,
            author_id: "author-1",
            agent_version: null,
            created_at: new Date().toISOString(),
            has_embedding: false,
          },
        ],
      ]),
    });

    render(<PostForm />);

    fireEvent.change(screen.getByLabelText("markdown-editor"), {
      target: { value: "回复内容" },
    });
    fireEvent.click(screen.getByRole("button", { name: "回复" }));

    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((toast) => toast.message === "回复已排队")).toBe(true),
    );
  });

  it("should display a human-friendly orchestration status label", () => {
    const createdAt = new Date().toISOString();
    useUiStore.setState({
      rightPanelPayload: { nodeId: "node-2" },
      activeNodeTab: "details",
    });
    useGraphStore.setState({
      nodes: new Map([
        [
          "node-2",
          {
            node_id: "node-2",
            label: "Human_Post",
            content: "回复节点",
            summary_content: null,
            author_id: "user-2",
            agent_version: null,
            created_at: createdAt,
            has_embedding: true,
          },
        ],
      ]),
    });
    useSseStore.setState({
      orchestrationStatuses: {
        "node-2": {
          request_id: "req-2",
          event_id: "evt-2",
          post_node_id: "node-2",
          status: "REVIEW_PENDING",
          message: "review task created",
          review_id: "review-2",
        },
      },
    });

    render(<NodeDetailPanel />);

    expect(screen.getByText("等待人工复核")).toBeInTheDocument();
    expect(screen.getByText("review task created")).toBeInTheDocument();
  });
});
