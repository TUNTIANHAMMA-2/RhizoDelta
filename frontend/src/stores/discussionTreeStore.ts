import { create } from "zustand";
import { fetchDiscussionTree } from "../api/nodes";
import type {
  CommentNode,
  DiscussionArtifact,
  DiscussionTreeMeta,
  GraphNodeDTO,
} from "../api/types";

export interface PendingPost {
  requestId: string;
  targetNodeId: string;
  content: string;
  authorId: string;
  createdAt: string;
  status: "submitting" | "accepted" | "failed";
  errorMessage?: string;
}

export interface DiscussionTreeState {
  rootId: string | null;
  meta: DiscussionTreeMeta | null;
  nodesById: Map<string, CommentNode>;
  childrenByParent: Map<string, string[]>;
  artifactsByAnchor: Map<string, DiscussionArtifact[]>;
  rootChildrenSnapshot: string | null;
  loadingState: "idle" | "loading" | "loaded" | "error";
  error: string | null;
  selectedReplyTargetId: string | null;
  activeArtifactId: string | null;
  expandedArtifactIds: Set<string>;
  pendingPosts: Map<string, PendingPost>;
  longPressMenuNodeId: string | null;

  loadTree: (rootId: string) => Promise<void>;
  refreshTree: () => Promise<void>;
  selectReplyTarget: (nodeId: string | null) => void;
  setActiveArtifact: (artifactId: string | null) => void;
  toggleArtifactExpanded: (artifactId: string) => void;
  addPendingPost: (post: PendingPost) => void;
  markPendingAccepted: (requestId: string) => void;
  resolvePendingPost: (requestId: string, realNode: CommentNode) => void;
  failPendingPost: (requestId: string, errorMessage: string) => void;
  insertExternalNode: (node: CommentNode, parentNodeId: string) => void;
  openLongPressMenu: (nodeId: string) => void;
  closeLongPressMenu: () => void;
}

export function graphNodeToCommentNode(
  node: GraphNodeDTO,
  parentId: string | null,
  depth = 0,
): CommentNode {
  return {
    node_id: node.node_id,
    content: node.content ?? node.summary_content ?? "",
    author: {
      user_id: node.author_id ?? null,
      username: node.author_username ?? null,
      display_name: node.author_display_name ?? node.author_username ?? null,
    },
    created_at: node.created_at,
    parent_id: parentId,
    depth,
    children: [],
    artifacts: [],
    has_more_children: false,
    total_children_count: 0,
  };
}

function normalizeTree(root: CommentNode) {
  const nodesById = new Map<string, CommentNode>();
  const childrenByParent = new Map<string, string[]>();
  const artifactsByAnchor = new Map<string, DiscussionArtifact[]>();

  const visit = (node: CommentNode) => {
    nodesById.set(node.node_id, node);
    childrenByParent.set(
      node.node_id,
      node.children.map((child) => child.node_id),
    );
    artifactsByAnchor.set(node.node_id, node.artifacts);
    node.children.forEach(visit);
  };

  visit(root);
  return { nodesById, childrenByParent, artifactsByAnchor };
}

function rootChildrenSnapshot(childrenByParent: Map<string, string[]>, rootId: string) {
  return (childrenByParent.get(rootId) ?? []).join("|");
}

function compareByCreatedAtThenId(left: CommentNode, right: CommentNode) {
  const leftTime = left.created_at ? Date.parse(left.created_at) : Number.MAX_SAFE_INTEGER;
  const rightTime = right.created_at ? Date.parse(right.created_at) : Number.MAX_SAFE_INTEGER;
  if (leftTime !== rightTime) return leftTime - rightTime;
  return left.node_id.localeCompare(right.node_id);
}

function insertChildIdSorted(
  existingIds: string[],
  childId: string,
  nodesById: Map<string, CommentNode>,
) {
  const ids = existingIds.filter((id) => id !== childId);
  ids.push(childId);
  ids.sort((left, right) => {
    const leftNode = nodesById.get(left);
    const rightNode = nodesById.get(right);
    if (!leftNode || !rightNode) return left.localeCompare(right);
    return compareByCreatedAtThenId(leftNode, rightNode);
  });
  return ids;
}

function childDepth(parentId: string | null, nodesById: Map<string, CommentNode>) {
  if (!parentId) return 0;
  return (nodesById.get(parentId)?.depth ?? 0) + 1;
}

export const useDiscussionTreeStore = create<DiscussionTreeState>((set, get) => ({
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

  loadTree: async (rootId) => {
    set({ loadingState: "loading", error: null, rootId });
    try {
      const response = await fetchDiscussionTree(rootId, { maxDepth: 5, limit: 200 });
      const normalized = normalizeTree(response.root);
      set({
        rootId,
        meta: response.meta,
        nodesById: normalized.nodesById,
        childrenByParent: normalized.childrenByParent,
        artifactsByAnchor: normalized.artifactsByAnchor,
        rootChildrenSnapshot: rootChildrenSnapshot(normalized.childrenByParent, rootId),
        loadingState: "loaded",
        error: null,
        selectedReplyTargetId: rootId,
      });
    } catch (error) {
      set({
        loadingState: "error",
        error: error instanceof Error ? error.message : "Failed to load discussion tree",
      });
      throw error;
    }
  },

  refreshTree: async () => {
    const rootId = get().rootId;
    if (!rootId) return;
    await get().loadTree(rootId);
  },

  selectReplyTarget: (nodeId) => {
    set((state) => ({ selectedReplyTargetId: nodeId ?? state.rootId }));
  },

  setActiveArtifact: (artifactId) => set({ activeArtifactId: artifactId }),

  toggleArtifactExpanded: (artifactId) => {
    set((state) => {
      const expandedArtifactIds = new Set(state.expandedArtifactIds);
      if (expandedArtifactIds.has(artifactId)) {
        expandedArtifactIds.delete(artifactId);
        return {
          expandedArtifactIds,
          activeArtifactId:
            state.activeArtifactId === artifactId ? null : state.activeArtifactId,
        };
      }
      expandedArtifactIds.add(artifactId);
      return { expandedArtifactIds, activeArtifactId: artifactId };
    });
  },

  addPendingPost: (post) => {
    set((state) => {
      const pendingPosts = new Map(state.pendingPosts);
      pendingPosts.set(post.requestId, {
        ...post,
        targetNodeId: post.targetNodeId || state.rootId || "",
      });
      return { pendingPosts };
    });
  },

  markPendingAccepted: (requestId) => {
    set((state) => {
      const pending = state.pendingPosts.get(requestId);
      if (!pending) return {};
      const pendingPosts = new Map(state.pendingPosts);
      pendingPosts.set(requestId, { ...pending, status: "accepted" });
      return { pendingPosts };
    });
  },

  resolvePendingPost: (requestId, realNode) => {
    set((state) => {
      const pending = state.pendingPosts.get(requestId);
      const parentId = realNode.parent_id ?? pending?.targetNodeId ?? state.rootId;
      const nodesById = new Map(state.nodesById);
      const nextNode = {
        ...realNode,
        parent_id: parentId,
        depth: childDepth(parentId, nodesById),
      };
      nodesById.set(nextNode.node_id, nextNode);

      const childrenByParent = new Map(state.childrenByParent);
      if (parentId) {
        childrenByParent.set(
          parentId,
          insertChildIdSorted(
            childrenByParent.get(parentId) ?? [],
            nextNode.node_id,
            nodesById,
          ),
        );
      }

      const pendingPosts = new Map(state.pendingPosts);
      pendingPosts.delete(requestId);

      return {
        nodesById,
        childrenByParent,
        pendingPosts,
        rootChildrenSnapshot: state.rootId
          ? rootChildrenSnapshot(childrenByParent, state.rootId)
          : state.rootChildrenSnapshot,
      };
    });
  },

  failPendingPost: (requestId, errorMessage) => {
    set((state) => {
      const pending = state.pendingPosts.get(requestId);
      if (!pending) return {};
      const pendingPosts = new Map(state.pendingPosts);
      pendingPosts.set(requestId, {
        ...pending,
        status: "failed",
        errorMessage,
      });
      return { pendingPosts };
    });
  },

  insertExternalNode: (node, parentNodeId) => {
    set((state) => {
      if (!state.nodesById.has(parentNodeId)) return {};
      const nodesById = new Map(state.nodesById);
      const nextNode = {
        ...node,
        parent_id: parentNodeId,
        depth: childDepth(parentNodeId, nodesById),
      };
      nodesById.set(nextNode.node_id, nextNode);

      const childrenByParent = new Map(state.childrenByParent);
      childrenByParent.set(
        parentNodeId,
        insertChildIdSorted(
          childrenByParent.get(parentNodeId) ?? [],
          nextNode.node_id,
          nodesById,
        ),
      );

      return {
        nodesById,
        childrenByParent,
        rootChildrenSnapshot: state.rootId
          ? rootChildrenSnapshot(childrenByParent, state.rootId)
          : state.rootChildrenSnapshot,
      };
    });
  },

  openLongPressMenu: (nodeId) => set({ longPressMenuNodeId: nodeId }),
  closeLongPressMenu: () => set({ longPressMenuNodeId: null }),
}));
