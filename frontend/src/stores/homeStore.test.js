import test from "node:test";
import assert from "node:assert/strict";

import { filterRhizomes, qualityBandOf, sortRhizomes } from "./homeStore.ts";

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

test("filterRhizomes: all returns everything", () => {
  const out = filterRhizomes(fixture, "all", null);
  assert.equal(out.length, 4);
});

test("filterRhizomes: mine filters by current user", () => {
  const out = filterRhizomes(fixture, "mine", "alice");
  assert.deepEqual(out.map((n) => n.node_id), ["a", "c", "d"]);
});

test("filterRhizomes: mine returns empty when not logged in", () => {
  const out = filterRhizomes(fixture, "mine", null);
  assert.equal(out.length, 0);
});

test("filterRhizomes: recent uses 24h window", () => {
  const now = Date.parse("2026-04-19T15:00:00Z");
  const out = filterRhizomes(fixture, "recent", null, now);
  // a (18 Apr 10:00) 29h ago → out; b (19 Apr 10:00) 5h ago → in; c 9d ago → out; d 53h ago → out
  assert.deepEqual(out.map((n) => n.node_id), ["b"]);
});

test("filterRhizomes: quality:top matches >= 0.8", () => {
  const out = filterRhizomes(fixture, "quality:top", null);
  assert.deepEqual(out.map((n) => n.node_id), ["a"]);
});

test("filterRhizomes: quality:good matches [0.5, 0.8)", () => {
  const out = filterRhizomes(fixture, "quality:good", null);
  assert.deepEqual(out.map((n) => n.node_id), ["d"]);
});

test("filterRhizomes: quality:basic matches < 0.5", () => {
  const out = filterRhizomes(fixture, "quality:basic", null);
  assert.deepEqual(out.map((n) => n.node_id), ["b"]);
});

test("filterRhizomes: quality:unrated matches missing score", () => {
  const out = filterRhizomes(fixture, "quality:unrated", null);
  assert.deepEqual(out.map((n) => n.node_id), ["c"]);
});

test("qualityBandOf: boundaries land in the expected bucket", () => {
  assert.equal(qualityBandOf(undefined), "unrated");
  assert.equal(qualityBandOf(null), "unrated");
  assert.equal(qualityBandOf(NaN), "unrated");
  assert.equal(qualityBandOf(0), "basic");
  assert.equal(qualityBandOf(0.49), "basic");
  assert.equal(qualityBandOf(0.5), "good");
  assert.equal(qualityBandOf(0.79), "good");
  assert.equal(qualityBandOf(0.8), "top");
  assert.equal(qualityBandOf(1), "top");
});

test("sortRhizomes: latest sorts by created_at DESC", () => {
  const out = sortRhizomes(fixture, "latest");
  assert.deepEqual(out.map((n) => n.node_id), ["b", "a", "d", "c"]);
});

test("sortRhizomes: quality sorts quality DESC, undefined last", () => {
  const out = sortRhizomes(fixture, "quality");
  assert.deepEqual(out.map((n) => n.node_id), ["a", "d", "b", "c"]);
});

test("sortRhizomes: active falls back to latest for MVP", () => {
  const out = sortRhizomes(fixture, "active");
  assert.deepEqual(out.map((n) => n.node_id), ["b", "a", "d", "c"]);
});
