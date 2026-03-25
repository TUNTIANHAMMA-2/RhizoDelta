import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NodeActionToolbar } from "./NodeActionToolbar";
import { PostForm } from "../forms/PostForm";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";

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

function createToken(payload: Record<string, unknown>) {
  const encode = (value: Record<string, unknown>) =>
    btoa(JSON.stringify(value))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");

  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("NodeActionToolbar", () => {
  beforeEach(() => {
    useAuthStore.getState().clearToken();
    useGraphStore.setState({
      selectedNodeId: null,
      nodes: new Map(),
    });
  });

  it("should show reply action for user role without edit actions", () => {
    useAuthStore.getState().setToken(createToken({ sub: "user-001", roles: ["USER"] }));

    render(<NodeActionToolbar nodeId="node-1" />);

    expect(screen.getByRole("button", { name: "回复" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "延续注入" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "分叉" })).not.toBeInTheDocument();
  });

  it("should show edit actions for agent role", () => {
    const openEditPanel = vi.fn();
    useUiStore.setState({ openEditPanel });
    useAuthStore.getState().setToken(createToken({ sub: "agent-001", roles: ["AGENT"] }));

    render(<NodeActionToolbar nodeId="node-1" />);

    fireEvent.click(screen.getByRole("button", { name: "延续注入" }));
    fireEvent.click(screen.getByRole("button", { name: "分叉" }));

    expect(openEditPanel).toHaveBeenNthCalledWith(1, "node-1", "inject");
    expect(openEditPanel).toHaveBeenNthCalledWith(2, "node-1", "fork");
  });

  it("should enter reply mode and allow clearing the target", () => {
    useAuthStore.getState().setToken(createToken({ sub: "user-002", roles: ["USER"] }));
    useGraphStore.setState({
      selectedNodeId: null,
      nodes: new Map([
        [
          "node-1",
          {
            node_id: "node-1",
            label: "Human_Post",
            content: "原帖内容",
            summary_content: null,
            author_id: "author-1",
            agent_version: null,
            operation_id: null,
            created_at: new Date().toISOString(),
            has_embedding: false,
          },
        ],
      ]),
    });

    render(
      <>
        <NodeActionToolbar nodeId="node-1" />
        <PostForm />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: "回复" }));

    expect(screen.getByText("正在回复")).toBeInTheDocument();
    expect(screen.getByText("原帖内容")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "取消回复" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "回复" })).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "取消回复" }));

    expect(screen.queryByText("正在回复")).not.toBeInTheDocument();
  });
});
