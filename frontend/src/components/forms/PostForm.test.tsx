import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { PostForm } from "./PostForm";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";
import { createPost } from "../../api/posts";

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

function createToken(payload: Record<string, unknown>) {
  const encode = (value: Record<string, unknown>) =>
    btoa(JSON.stringify(value))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");

  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("PostForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useUiStore.setState({ toasts: [] });
    useAuthStore.getState().clearToken();
    useGraphStore.setState({ selectedNodeId: null });
  });

  it("should not submit empty content or only formatting", async () => {
    useAuthStore.getState().setToken(createToken({ sub: "user-empty", roles: ["USER"] }));
    render(<PostForm />);
    
    const submitButton = screen.getByRole("button", { name: /发布/i });
    expect(submitButton).toBeDisabled();
  });

  it("should submit using the authenticated user id", async () => {
    vi.mocked(createPost).mockResolvedValue({
      event_id: "event-1",
      status: "QUEUED",
    });
    useAuthStore.getState().setToken(createToken({ sub: "user-001", roles: ["USER"] }));

    render(<PostForm />);

    fireEvent.change(screen.getByLabelText("markdown-editor"), {
      target: { value: "hello graph" },
    });
    fireEvent.click(screen.getByRole("button", { name: /发布/i }));

    await waitFor(() =>
      expect(createPost).toHaveBeenCalledWith(
        expect.objectContaining({
          author_id: "user-001",
          content: "hello graph",
        }),
      ),
    );
  });

  it("should display the markdown editor", () => {
    useAuthStore.getState().setToken(createToken({ sub: "user-editor", roles: ["USER"] }));

    render(<PostForm />);
    expect(screen.getByLabelText("markdown-editor")).toBeInTheDocument();
  });
});
