import { create } from "zustand";

export type QualityBand = "top" | "good" | "basic" | "unrated";

export type HomeNavKey =
  | "all"
  | "mine"
  | "recent"
  | `quality:${QualityBand}`;

export type HomeSortKey = "latest" | "quality" | "active";

export const QUALITY_TOP_THRESHOLD = 0.8;
export const QUALITY_GOOD_THRESHOLD = 0.5;

export function qualityBandOf(q: number | null | undefined): QualityBand {
  if (typeof q !== "number" || Number.isNaN(q)) return "unrated";
  if (q >= QUALITY_TOP_THRESHOLD) return "top";
  if (q >= QUALITY_GOOD_THRESHOLD) return "good";
  return "basic";
}

export interface HomeState {
  activeNav: HomeNavKey;
  sortBy: HomeSortKey;
  setActiveNav: (key: HomeNavKey) => void;
  setSortBy: (key: HomeSortKey) => void;
}

export const useHomeStore = create<HomeState>((set) => ({
  activeNav: "all",
  sortBy: "latest",
  setActiveNav: (key) => set({ activeNav: key }),
  setSortBy: (key) => set({ sortBy: key }),
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
  // quality:<band>
  const band = nav.slice("quality:".length) as QualityBand;
  return list.filter((n) => qualityBandOf(n.quality_overall) === band);
}

export function sortRhizomes<T extends RhizomeLike>(
  list: T[],
  sortBy: HomeSortKey,
): T[] {
  const copy = [...list];
  if (sortBy === "latest") {
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
