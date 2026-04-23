/* @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { HumanPostNode } from "./HumanPostNode";
import { useGraphStore } from "../../stores/graphStore";
import type { GraphNodeDTO } from "../../api/types";

vi.mock("./NodeActionToolbar", () => ({
  NodeActionToolbar: () => null,
}));

vi.mock("./VersionHandles", () => ({
  VersionHandles: () => null,
}));

vi.mock("./NodeEdgeInfo", () => ({
  NodeEdgeInfo: () => null,
}));

vi.mock("./QualityBadge", () => ({
  QualityBadge: () => <span>quality</span>,
}));

function makeNode(overrides: Partial<GraphNodeDTO> = {}): GraphNodeDTO {
  return {
    node_id: "node-1",
    label: "Human_Post",
    content: "human content",
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

describe("HumanPostNode author display", () => {
  beforeEach(() => {
    useGraphStore.setState({ semanticZoom: "normal" });
  });

  it("prefers author_display_name in normal zoom header", () => {
    render(<HumanPostNode data={makeNode()} selected={false} {...({} as any)} />);

    expect(screen.getByText("Alice")).toBeInTheDocument();
  });

  it("falls back to author_username then author_id", () => {
    const node = makeNode({
      author_display_name: undefined,
      author_username: "alice-user",
    });

    render(<HumanPostNode data={node} selected={false} {...({} as any)} />);

    expect(screen.getByText("alice-user")).toBeInTheDocument();
  });

  it("falls back to Anonymous when no author fields exist", () => {
    const node = makeNode({
      author_id: undefined,
      author_username: undefined,
      author_display_name: undefined,
    });

    render(<HumanPostNode data={node} selected={false} {...({} as any)} />);

    expect(screen.getByText("Anonymous")).toBeInTheDocument();
  });
});
