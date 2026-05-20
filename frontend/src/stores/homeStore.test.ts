import { beforeEach, describe, expect, it, vi } from "vitest";
import { getFeed } from "../api/feed";
import type { FeedItem } from "../api/types";
import {
  collectFollowingTargetIds,
  filterRhizomes,
  qualityBandOf,
  sortRhizomes,
  useHomeStore,
} from "./homeStore.ts";

vi.mock("../api/feed", () => ({
  getFeed: vi.fn(),
}));

const fixture = [
  {
    node_id: "a",
    label: "Human_Post",
    author_id: "alice",
    created_at: "2026-04-18T10:00:00Z",
    quality_overall: 0.8,
  },
  {
    node_id: "b",
    label: "AI_Consensus",
    author_id: "bob",
    created_at: "2026-04-19T10:00:00Z",
    quality_overall: 0.4,
  },
  {
    node_id: "c",
    label: "Result",
    author_id: "alice",
    created_at: "2026-04-10T10:00:00Z",
    quality_overall: undefined,
  },
  {
    node_id: "d",
    label: "Human_Post",
    author_id: "alice",
    created_at: "2026-04-17T10:00:00Z",
    quality_overall: 0.65,
  },
];

beforeEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
  useHomeStore.setState({
    activeNav: "all",
    sortBy: "latest",
    feedItems: [],
    feedLoading: false,
    feedError: null,
    followingTargetIds: new Set(),
    followingLoading: false,
  });
});

describe("homeStore helpers", () => {
it("filterRhizomes: all returns everything", () => {
  const out = filterRhizomes(fixture, "all", null);
  expect(out.length).toBe(4);
});

it("filterRhizomes: mine filters by current user", () => {
  const out = filterRhizomes(fixture, "mine", "alice");
  expect(out.map((n) => n.node_id)).toEqual(["a", "c", "d"]);
});

it("filterRhizomes: mine returns empty when not logged in", () => {
  const out = filterRhizomes(fixture, "mine", null);
  expect(out.length).toBe(0);
});

it("filterRhizomes: recent uses 24h window", () => {
  const now = Date.parse("2026-04-19T15:00:00Z");
  const out = filterRhizomes(fixture, "recent", null, new Set(), now);
  // a (18 Apr 10:00) 29h ago → out; b (19 Apr 10:00) 5h ago → in; c 9d ago → out; d 53h ago → out
  expect(out.map((n) => n.node_id)).toEqual(["b"]);
});

it("filterRhizomes: quality:top matches >= 0.8", () => {
  const out = filterRhizomes(fixture, "quality:top", null);
  expect(out.map((n) => n.node_id)).toEqual(["a"]);
});

it("filterRhizomes: quality:good matches [0.5, 0.8)", () => {
  const out = filterRhizomes(fixture, "quality:good", null);
  expect(out.map((n) => n.node_id)).toEqual(["d"]);
});

it("filterRhizomes: quality:basic matches < 0.5", () => {
  const out = filterRhizomes(fixture, "quality:basic", null);
  expect(out.map((n) => n.node_id)).toEqual(["b"]);
});

it("filterRhizomes: quality:unrated matches missing score", () => {
  const out = filterRhizomes(fixture, "quality:unrated", null);
  expect(out.map((n) => n.node_id)).toEqual(["c"]);
});

it("qualityBandOf: boundaries land in the expected bucket", () => {
  expect(qualityBandOf(undefined)).toBe("unrated");
  expect(qualityBandOf(null)).toBe("unrated");
  expect(qualityBandOf(NaN)).toBe("unrated");
  expect(qualityBandOf(0)).toBe("basic");
  expect(qualityBandOf(0.49)).toBe("basic");
  expect(qualityBandOf(0.5)).toBe("good");
  expect(qualityBandOf(0.79)).toBe("good");
  expect(qualityBandOf(0.8)).toBe("top");
  expect(qualityBandOf(1)).toBe("top");
});

it("sortRhizomes: latest sorts by created_at DESC", () => {
  const out = sortRhizomes(fixture, "latest");
  expect(out.map((n) => n.node_id)).toEqual(["b", "a", "d", "c"]);
});

it("sortRhizomes: quality sorts quality DESC, undefined last", () => {
  const out = sortRhizomes(fixture, "quality");
  expect(out.map((n) => n.node_id)).toEqual(["a", "d", "b", "c"]);
});

it("sortRhizomes: active falls back to latest for MVP", () => {
  const out = sortRhizomes(fixture, "active");
  expect(out.map((n) => n.node_id)).toEqual(["b", "a", "d", "c"]);
});

it("collectFollowingTargetIds fetches pages until exhaustion", async () => {
  const calls: Array<[number, number]> = [];
  const ids = await collectFollowingTargetIds(async (page, size) => {
    calls.push([page, size]);
    const pages = [
      {
        items: [
          { follow_id: "f1", target_type: "node", target_id: "node-1", since: "now" },
          { follow_id: "f2", target_type: "topic", target_id: "topic-1", since: "now" },
        ],
        page: 0,
        size,
        total: 3,
        total_pages: 2,
        has_next: true,
      },
      {
        items: [
          { follow_id: "f3", target_type: "user", target_id: "user-1", since: "now" },
        ],
        page: 1,
        size,
        total: 3,
        total_pages: 2,
        has_next: false,
      },
    ];
    return pages[page];
  });

  expect(calls).toEqual([[0, 100], [1, 100]]);
  expect([...ids].sort()).toEqual(["node-1", "user-1"]);
});
});

describe("homeStore loadFeed", () => {
  it("discards stale responses by feedRequestSeq", async () => {
    vi.useFakeTimers();
    const slowItems: FeedItem[] = [
      {
        ...fixture[0],
        label: "Human_Post",
        content: "slow post",
        has_embedding: false,
        created_at: fixture[0].created_at,
      },
    ];
    const fastItems: FeedItem[] = [
      {
        ...fixture[1],
        label: "AI_Consensus",
        summary_content: "fast summary",
        has_embedding: false,
        created_at: fixture[1].created_at,
      },
    ];
    vi.mocked(getFeed)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            setTimeout(
              () =>
                resolve({
                  items: slowItems,
                  page: 0,
                  size: 50,
                  has_next: false,
                }),
              100,
            );
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            setTimeout(
              () =>
                resolve({
                  items: fastItems,
                  page: 0,
                  size: 50,
                  has_next: false,
                }),
              10,
            );
          }),
      );

    const firstRequest = useHomeStore.getState().loadFeed();
    const secondRequest = useHomeStore.getState().loadFeed();

    await vi.advanceTimersByTimeAsync(10);
    expect(useHomeStore.getState().feedItems).toEqual(fastItems);
    expect(useHomeStore.getState().feedLoading).toBe(false);

    await vi.advanceTimersByTimeAsync(90);
    await Promise.all([firstRequest, secondRequest]);

    expect(useHomeStore.getState().feedItems).toEqual(fastItems);
  });
});
