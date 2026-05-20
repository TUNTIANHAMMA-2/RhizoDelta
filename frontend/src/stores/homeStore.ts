import { create } from "zustand";
import type { GraphNodeDTO } from "../api/types";
import { getFeed } from "../api/feed";
import { listFollows } from "../api/follows";
import { useUiStore } from "./uiStore";

export type QualityBand = "top" | "good" | "basic" | "unrated";

export type HomeNavKey =
  | "all"
  | "mine"
  | "recent"
  | "following"
  | `quality:${QualityBand}`;

export type HomeSortKey = "latest" | "quality" | "active" | "for_you";

export const QUALITY_TOP_THRESHOLD = 0.8;
export const QUALITY_GOOD_THRESHOLD = 0.5;
const FOLLOWING_PAGE_SIZE = 100;

export function qualityBandOf(q: number | null | undefined): QualityBand {
  if (typeof q !== "number" || Number.isNaN(q)) return "unrated";
  if (q >= QUALITY_TOP_THRESHOLD) return "top";
  if (q >= QUALITY_GOOD_THRESHOLD) return "good";
  return "basic";
}

type FollowingPage = Awaited<ReturnType<typeof listFollows>>;

export async function collectFollowingTargetIds(
  fetchPage: (page: number, size: number) => Promise<FollowingPage>,
): Promise<Set<string>> {
  const ids = new Set<string>();
  let page = 0;
  let hasNext = true;
  while (hasNext) {
    const response = await fetchPage(page, FOLLOWING_PAGE_SIZE);
    for (const item of response.items) {
      if (item.target_type === "node" || item.target_type === "user") {
        ids.add(item.target_id);
      }
    }
    hasNext = response.has_next;
    page += 1;
  }
  return ids;
}

export interface HomeState {
  activeNav: HomeNavKey;
  sortBy: HomeSortKey;
  feedItems: GraphNodeDTO[];
  feedLoading: boolean;
  feedError: string | null;
  followingTargetIds: Set<string>;
  followingLoading: boolean;
  setActiveNav: (key: HomeNavKey) => void;
  setSortBy: (key: HomeSortKey) => void;
  loadFeed: () => Promise<void>;
  loadFollowing: () => Promise<void>;
}

// Module-level monotonic counter so two stores can't race; each loadFeed
// call captures its sequence number and aborts state-writes if a newer
// call has already started.
let feedRequestSeq = 0;

export const useHomeStore = create<HomeState>((set) => ({
  activeNav: "all",
  sortBy: "latest",
  feedItems: [],
  feedLoading: false,
  feedError: null,
  followingTargetIds: new Set<string>(),
  followingLoading: false,
  setActiveNav: (key) => set({ activeNav: key }),
  setSortBy: (key) => set({ sortBy: key }),
  loadFeed: async () => {
    const myReq = ++feedRequestSeq;
    set({ feedLoading: true, feedError: null });
    try {
      const response = await getFeed(0, 50);
      if (myReq !== feedRequestSeq) return; // 用户已经切到别的 sortBy，丢弃
      set({ feedItems: response.items ?? [] });
    } catch (e) {
      if (myReq !== feedRequestSeq) return;
      const msg = e instanceof Error ? e.message : "feed request failed";
      set({ feedError: msg });
      useUiStore.getState().addToast({ type: "error", message: msg });
    } finally {
      // 只在「我们仍是最新请求」时清 loading，避免覆盖更晚启动的请求的状态
      if (myReq === feedRequestSeq) set({ feedLoading: false });
    }
  },
  loadFollowing: async () => {
    set({ followingLoading: true });
    try {
      const ids = await collectFollowingTargetIds(listFollows);
      set({ followingTargetIds: ids });
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to load following",
      });
    } finally {
      set({ followingLoading: false });
    }
  },
}));

const RECENT_WINDOW_MS = 24 * 60 * 60 * 1000;

export interface RhizomeLike {
  node_id: string;
  label: string;
  author_id?: string;
  created_at: string;
  quality_overall?: number;
}

export function filterRhizomes<T extends RhizomeLike>(
  list: T[],
  nav: HomeNavKey,
  currentUserId: string | null,
  followingTargetIds: Set<string> = new Set(),
  now = Date.now(),
): T[] {
  if (nav === "all") return list;
  if (nav === "mine") {
    if (!currentUserId) return [];
    return list.filter((n) => n.author_id === currentUserId);
  }
  if (nav === "recent") {
    return list.filter(
      (n) => now - Date.parse(n.created_at) < RECENT_WINDOW_MS,
    );
  }
  if (nav === "following") {
    if (followingTargetIds.size === 0) return [];
    return list.filter(
      (n) =>
        followingTargetIds.has(n.node_id) ||
        (n.author_id != null && followingTargetIds.has(n.author_id)),
    );
  }
  // quality:<band>
  const band = nav.slice("quality:".length) as QualityBand;
  return list.filter((n) => qualityBandOf(n.quality_overall) === band);
}

export function sortRhizomes<T extends RhizomeLike>(
  list: T[],
  sortBy: HomeSortKey,
): T[] {
  const copy = [...list];
  if (sortBy === "latest" || sortBy === "for_you") {
    // for_you 已由后端按推荐顺序返回，这里仅保底维持时间倒序
    copy.sort(
      (a, b) => Date.parse(b.created_at) - Date.parse(a.created_at),
    );
    return copy;
  }
  if (sortBy === "quality") {
    copy.sort((a, b) => {
      const qa = a.quality_overall ?? -Infinity;
      const qb = b.quality_overall ?? -Infinity;
      if (qa === qb) {
        return Date.parse(b.created_at) - Date.parse(a.created_at);
      }
      return qb - qa;
    });
    return copy;
  }
  // "active" — not implemented on backend; fall back to latest for now
  copy.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at));
  return copy;
}
