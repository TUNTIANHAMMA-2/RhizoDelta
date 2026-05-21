import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CommentNode, DiscussionArtifact } from "../../api/types";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { CommentTreeItem } from "./CommentTreeItem";

vi.mock("react-markdown", () => ({
  default: ({ children }: { children: string }) => <p>{children}</p>,
}));

vi.mock("remark-gfm", () => ({ default: vi.fn() }));

const root: CommentNode = {
  node_id: "root",
  content: "root text",
  author: { user_id: "u-root", username: "root", display_name: "Root" },
  created_at: "2026-05-20T10:00:00Z",
  parent_id: null,
  depth: 0,
  children: [],
  artifacts: [],
  has_more_children: false,
  total_children_count: 1,
};

const child: CommentNode = {
  ...root,
  node_id: "child",
  content: "child text",
  parent_id: "root",
  depth: 1,
  total_children_count: 0,
};

const deep: CommentNode = {
  ...root,
  node_id: "deep",
  content: "deep text",
  parent_id: "child",
  depth: 7,
  total_children_count: 0,
};

const artifact: DiscussionArtifact = {
  node_id: "artifact",
  kind: "CONSENSUS",
  anchor_node_id: "root",
  body: "summary",
  source_node_ids: ["child"],
  source_count: 1,
  created_at: "2026-05-20T10:02:00Z",
  agent_version: null,
};

function resetStore() {
  useDiscussionTreeStore.setState({
    rootId: "root",
    nodesById: new Map([
      ["root", root],
      ["child", child],
      ["deep", deep],
    ]),
    childrenByParent: new Map([
      ["root", ["child"]],
      ["child", ["deep"]],
      ["deep", []],
    ]),
    artifactsByAnchor: new Map([["root", [artifact]]]),
    selectedReplyTargetId: "root",
    activeArtifactId: null,
    expandedArtifactIds: new Set(),
    pendingPosts: new Map(),
  });
}

describe("CommentTreeItem", () => {
  beforeEach(() => {
    resetStore();
  });

  it("selects reply target on tap and renders children recursively", () => {
    render(<CommentTreeItem nodeId="root" depth={0} />);

    fireEvent.click(screen.getByText("child text"));

    expect(useDiscussionTreeStore.getState().selectedReplyTargetId).toBe("child");
    expect(screen.getByText("deep text")).toBeInTheDocument();
  });

  it("caps deep indentation and shows the depth badge", () => {
    render(<CommentTreeItem nodeId="deep" depth={7} />);

    const article = screen.getByText("deep text").closest("article");
    expect(article?.className).toContain("ml-9");
    expect(screen.getByText("深度 7")).toBeInTheDocument();
  });

  it("highlights source nodes for the active artifact", () => {
    useDiscussionTreeStore.setState({ activeArtifactId: "artifact" });

    render(<CommentTreeItem nodeId="root" depth={0} />);

    const childArticle = screen.getByText("child text").closest("article");
    expect(childArticle?.getAttribute("data-source-highlighted")).toBe("true");
  });
});
