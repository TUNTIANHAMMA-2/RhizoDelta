import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPost } from "../../api/posts";
import type { CommentNode } from "../../api/types";
import { useAuthStore } from "../../stores/authStore";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { MobileReplyComposer } from "./MobileReplyComposer";

vi.mock("../../api/posts", () => ({
  createPost: vi.fn(),
}));

const root: CommentNode = {
  node_id: "root",
  content: "root content",
  author: { user_id: "u-root", username: "root", display_name: "Root" },
  created_at: "2026-05-20T10:00:00Z",
  parent_id: null,
  depth: 0,
  children: [],
  artifacts: [],
  has_more_children: false,
  total_children_count: 0,
};

const child: CommentNode = {
  ...root,
  node_id: "child",
  content: "child target content",
  parent_id: "root",
  depth: 1,
};

function createToken(payload: Record<string, unknown>) {
  const encode = (value: Record<string, unknown>) =>
    btoa(JSON.stringify(value))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");

  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("MobileReplyComposer", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.getState().clearToken();
    useAuthStore.getState().setToken(createToken({ sub: "user-1", roles: ["USER"] }));
    useDiscussionTreeStore.setState({
      rootId: "root",
      nodesById: new Map([
        ["root", root],
        ["child", child],
      ]),
      selectedReplyTargetId: "root",
      pendingPosts: new Map(),
    });
    vi.mocked(createPost).mockResolvedValue({ event_id: "event-1", status: "QUEUED" });
  });

  it("submits a pending post with the selected target", async () => {
    useDiscussionTreeStore.getState().selectReplyTarget("child");
    render(<MobileReplyComposer />);

    expect(screen.getByText(/回复 · Reply to child target/)).toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "hello mobile" } });
    fireEvent.click(screen.getByRole("button", { name: /发送/ }));

    await waitFor(() => expect(createPost).toHaveBeenCalled());
    expect(createPost).toHaveBeenCalledWith(
      expect.objectContaining({
        author_id: "user-1",
        content: "hello mobile",
        target_node_id: "child",
      }),
    );
    expect([...useDiscussionTreeStore.getState().pendingPosts.values()][0].targetNodeId).toBe("child");
  });

  it("cancels reply target back to root", () => {
    useDiscussionTreeStore.getState().selectReplyTarget("child");
    render(<MobileReplyComposer />);

    fireEvent.click(screen.getByRole("button", { name: /取消/ }));

    expect(useDiscussionTreeStore.getState().selectedReplyTargetId).toBe("root");
    expect(screen.getByText(/回复主帖/)).toBeInTheDocument();
  });
});
