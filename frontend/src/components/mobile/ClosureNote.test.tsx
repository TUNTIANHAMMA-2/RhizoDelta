import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CommentNode, DiscussionArtifact } from "../../api/types";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { ClosureNote } from "./ClosureNote";

vi.mock("react-markdown", () => ({
  default: ({ children }: { children: string }) => <p>{children}</p>,
}));

vi.mock("remark-gfm", () => ({ default: vi.fn() }));

const source: CommentNode = {
  node_id: "source",
  content: "source text",
  author: { user_id: "u", username: "u", display_name: "User" },
  created_at: "2026-05-20T10:00:00Z",
  parent_id: "root",
  depth: 1,
  children: [],
  artifacts: [],
  has_more_children: false,
  total_children_count: 0,
};

const artifact: DiscussionArtifact = {
  node_id: "artifact",
  kind: "CONSENSUS",
  anchor_node_id: "root",
  body: "summary body",
  source_node_ids: ["source", "external"],
  source_count: 2,
  created_at: "2026-05-20T10:02:00Z",
  agent_version: null,
};

describe("ClosureNote", () => {
  beforeEach(() => {
    useDiscussionTreeStore.setState({
      nodesById: new Map([["source", source]]),
      expandedArtifactIds: new Set(),
      activeArtifactId: null,
    });
  });

  it("toggles expanded state and marks the artifact active", () => {
    render(<ClosureNote artifact={artifact} />);

    fireEvent.click(screen.getByRole("button", { name: /展开/ }));

    expect(useDiscussionTreeStore.getState().expandedArtifactIds.has("artifact")).toBe(true);
    expect(useDiscussionTreeStore.getState().activeArtifactId).toBe("artifact");
    expect(screen.getByText(/还有 1 条来源不在视图内/)).toBeInTheDocument();
    expect(screen.getByText("source text")).toBeInTheDocument();
  });
});
