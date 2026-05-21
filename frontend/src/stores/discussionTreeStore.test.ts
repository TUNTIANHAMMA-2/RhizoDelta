import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchDiscussionTree } from "../api/nodes";
import type { CommentNode, DiscussionTreeResponse } from "../api/types";
import { graphNodeToCommentNode, useDiscussionTreeStore } from "./discussionTreeStore";

vi.mock("../api/nodes", () => ({
  fetchDiscussionTree: vi.fn(),
}));

const rootNode: CommentNode = {
  node_id: "root",
  content: "root content",
  author: { user_id: "u-root", username: "root", display_name: "Root" },
  created_at: "2026-05-20T10:00:00Z",
  parent_id: null,
  depth: 0,
  children: [
    {
      node_id: "child",
      content: "child content",
      author: { user_id: "u-child", username: "child", display_name: "Child" },
      created_at: "2026-05-20T10:01:00Z",
      parent_id: "root",
      depth: 1,
      children: [],
      artifacts: [],
      has_more_children: false,
      total_children_count: 0,
    },
  ],
  artifacts: [
    {
      node_id: "artifact",
      kind: "CONSENSUS",
      anchor_node_id: "root",
      body: "summary",
      source_node_ids: ["root"],
      source_count: 1,
      created_at: "2026-05-20T10:02:00Z",
      agent_version: "agent-v1",
    },
  ],
  has_more_children: false,
  total_children_count: 1,
};

const response: DiscussionTreeResponse = {
  root: rootNode,
  meta: {
    root_node_id: "root",
    max_depth: 5,
    limit: 200,
    truncated: false,
    has_more: false,
    next_cursor: null,
  },
};

function resetStore() {
  useDiscussionTreeStore.setState({
    rootId: null,
    meta: null,
    nodesById: new Map(),
    childrenByParent: new Map(),
    artifactsByAnchor: new Map(),
    rootChildrenSnapshot: null,
    loadingState: "idle",
    error: null,
    selectedReplyTargetId: null,
    activeArtifactId: null,
    expandedArtifactIds: new Set(),
    pendingPosts: new Map(),
    longPressMenuNodeId: null,
  });
}

describe("discussionTreeStore", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetStore();
  });

  it("loadTree normalizes the nested response", async () => {
    vi.mocked(fetchDiscussionTree).mockResolvedValue(response);

    await useDiscussionTreeStore.getState().loadTree("root");

    const state = useDiscussionTreeStore.getState();
    expect(fetchDiscussionTree).toHaveBeenCalledWith("root", { maxDepth: 5, limit: 200 });
    expect(state.loadingState).toBe("loaded");
    expect(state.nodesById.get("child")?.parent_id).toBe("root");
    expect(state.childrenByParent.get("root")).toEqual(["child"]);
    expect(state.artifactsByAnchor.get("root")?.[0].node_id).toBe("artifact");
    expect(state.selectedReplyTargetId).toBe("root");
  });

  it("resolvePendingPost removes pending and inserts the real node by created_at", async () => {
    vi.mocked(fetchDiscussionTree).mockResolvedValue(response);
    await useDiscussionTreeStore.getState().loadTree("root");

    useDiscussionTreeStore.getState().addPendingPost({
      requestId: "req-1",
      targetNodeId: "root",
      content: "pending",
      authorId: "u-1",
      createdAt: "2026-05-20T10:03:00Z",
      status: "submitting",
    });

    useDiscussionTreeStore.getState().resolvePendingPost("req-1", {
      ...rootNode.children[0],
      node_id: "real",
      parent_id: "root",
      created_at: "2026-05-20T10:00:30Z",
    });

    const state = useDiscussionTreeStore.getState();
    expect(state.pendingPosts.has("req-1")).toBe(false);
    expect(state.childrenByParent.get("root")).toEqual(["real", "child"]);
    expect(state.nodesById.get("real")?.depth).toBe(1);
  });

  it("tracks selection, active artifacts, accepted and failed pending posts", async () => {
    vi.mocked(fetchDiscussionTree).mockResolvedValue(response);
    await useDiscussionTreeStore.getState().loadTree("root");

    useDiscussionTreeStore.getState().selectReplyTarget("child");
    expect(useDiscussionTreeStore.getState().selectedReplyTargetId).toBe("child");
    useDiscussionTreeStore.getState().selectReplyTarget(null);
    expect(useDiscussionTreeStore.getState().selectedReplyTargetId).toBe("root");

    useDiscussionTreeStore.getState().toggleArtifactExpanded("artifact");
    expect(useDiscussionTreeStore.getState().expandedArtifactIds.has("artifact")).toBe(true);
    expect(useDiscussionTreeStore.getState().activeArtifactId).toBe("artifact");

    useDiscussionTreeStore.getState().addPendingPost({
      requestId: "req-2",
      targetNodeId: "root",
      content: "pending",
      authorId: "u-1",
      createdAt: "2026-05-20T10:04:00Z",
      status: "submitting",
    });
    useDiscussionTreeStore.getState().markPendingAccepted("req-2");
    expect(useDiscussionTreeStore.getState().pendingPosts.get("req-2")?.status).toBe("accepted");
    useDiscussionTreeStore.getState().failPendingPost("req-2", "network");
    expect(useDiscussionTreeStore.getState().pendingPosts.get("req-2")?.errorMessage).toBe("network");
  });

  it("converts graph nodes to comment nodes for SSE reconciliation", () => {
    const comment = graphNodeToCommentNode(
      {
        node_id: "node-1",
        label: "Human_Post",
        content: "hello",
        author_id: "u-1",
        author_username: "alice",
        author_display_name: "Alice",
        created_at: "2026-05-20T10:00:00Z",
        has_embedding: false,
      },
      "root",
      2,
    );

    expect(comment.parent_id).toBe("root");
    expect(comment.depth).toBe(2);
    expect(comment.author.display_name).toBe("Alice");
  });
});
