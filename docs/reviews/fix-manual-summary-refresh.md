> **Progress**: ✅ Fix successfully verified and merged.

# Fix Report — Manual Summary Refresh

**Date**: 2026-04-16
**Commit**: `e1a0920`
**Scope**: `frontend/src/components/panels/NodeDetailPanel.tsx`

---

## Problem

After clicking "生成摘要" on an AI_Consensus node, the frontend fires
`POST /api/nodes/{id}/summarize` but never consumes the result or
re-fetches the node. The only refresh path is the `SUMMARY_GENERATED`
SSE event (from the automatic summarization pipeline), so users see
no visible change after a manual trigger.

## Root Cause

The `onClick` handler called `summarizeNode()` and reset
the `summarizing` flag on completion, but did not fetch the updated
node from the server or write anything back to `graphStore`.

## Fix

Chain `fetchNode(node.node_id)` after `summarizeNode` succeeds,
then push the authoritative result into `graphStore.addNode()`.

**Request chain after fix:**

```
POST /api/nodes/{id}/summarize   →  success
GET  /api/nodes/{id}             →  latest node with summary_content
graphStore.addNode(updated)      →  React re-render
```

### Changes (2 files)

| File | Change |
|------|--------|
| `NodeDetailPanel.tsx` | Import `fetchNode`; chain it after `summarizeNode` with `.catch(() => {}).finally(() => setSummarizing(false))` |
| `NodeDetailPanel.test.tsx` | 6 new tests (see below) |

### What was NOT changed

- No backend changes, no new API endpoints or SSE event types.
- No new UI elements (toasts, notifications, modals).
- Existing SSE-driven auto-refresh logic in `useSse.ts` untouched.

## Test Coverage

| # | Test Case | Verifies |
|---|-----------|----------|
| 1 | Button visibility | Only `AI_Consensus` nodes render "生成摘要" |
| 2 | Success path | `summarizeNode` → `fetchNode` → `graphStore` updated with new `summary_content` |
| 3 | Call order | `summarize` is called before `fetch` |
| 4 | Loading state | Button text changes to "生成中..." and disables; restores after completion |
| 5 | Error resilience | `summarizeNode` rejection resets button; `fetchNode` is not called |
| 6 | Non-SSE refresh | UI updates via API calls alone, no SSE event injected |

## Prompt (refined)

### Objective

When manually clicking "生成摘要" on an AI_Consensus node,
the detail panel should refresh to show the latest `summary_content`
without depending on the `SUMMARY_GENERATED` SSE event.

### Constraints

- Fix only the frontend; no backend, no new interfaces or event types.
- Reuse the existing `GET /api/nodes/{id}` endpoint for authoritative data.
- Preserve the automatic SSE-driven refresh path as-is.
- Limit changes to ≤ 2 files.

### Acceptance Criteria

1. Manual summary success → panel shows updated summary without page reload.
2. Auto-summary (SSE) path unchanged.
3. No new toasts, buttons, API endpoints, event types, or state fields.
4. Loading state and double-click prevention remain functional.

### Verification

- **Automated**: 6 Vitest component tests (all passing).
- **Manual**: Click "生成摘要", confirm Network shows POST then GET,
  confirm panel text updates in-place.
