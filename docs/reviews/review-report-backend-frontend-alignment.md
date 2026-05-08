> **Progress**: ✅ All issues identified in this report have been resolved and merged.

# Code Review Report — Backend-Frontend Alignment

**Date**: 2026-04-15
**Reviewers**: Codex (backend) + Gemini (frontend) + Claude (synthesis)
**Scope**: Full backend-frontend API alignment analysis

---

## Issues Found & Resolution Status

### Critical

| # | Issue | Status | Commit |
|---|-------|--------|--------|
| C1 | Fork rollback passes node_id instead of operation_id; audit query cannot distinguish FORK from BRANCH | **FIXED** | `8c721b6` |
| C2 | Association form allows empty reason submission, backend @NotBlank rejects with raw JSON error | **FIXED** | `302cc92` |
| C3 | ReplyUiFeedback.tsx component file missing (Gemini) | **FALSE POSITIVE** — test file is an integration test for PostForm + NodeDetailPanel, both implemented | — |

### Major

| # | Issue | Status | Commit |
|---|-------|--------|--------|
| M4 | ReviewController frontend completely missing — 5 endpoints with no API/types/UI | **FIXED** | `9e57034` |
| M5 | GraphNodeDTO declares operation_id that backend never returns; NON_NULL omission vs `\| null` mismatch | **FIXED** | `c6dfb5b` |
| M6 | SummaryResult returns camelCase (sourceCount/modelUsed), frontend expects snake_case | **FIXED** | `8827fa2` |
| M7 | client.ts throws raw JSON text for non-2xx errors instead of parsing ApiResponse.message | **FIXED** | `dd61644` |

### Minor

| # | Issue | Status | Commit |
|---|-------|--------|--------|
| m8 | /api/auth/me not called; Header displays UUID instead of username | **FIXED** (via JWT parsing) | `ab86551` |
| m9 | OrchestrationStatusEvent: post_node_id declared required but backend sends null; missing decision_id/author_id | **FIXED** | `f586fd8` |
| m10 | deleteAssociation response type missing association_id field | **FIXED** | `f586fd8` |
| m11 | Multiple defined API helpers not called from UI | **SKIPPED** — intentional pre-built API layer | — |

### Post-Review Fix

| # | Issue | Status | Commit |
|---|-------|--------|--------|
| PR1 | ReviewPanel silently shows "no tasks" on fetch failure instead of error state | **FIXED** | (this commit) |
| PR2 | ReviewPanel draft_payload null safety missing | **FIXED** | (this commit) |

---

## Summary

- **Issues identified**: 11 (2 Critical, 4 Major, 4 Minor, 1 Suggestion)
- **Fixed**: 9
- **False positive**: 1 (C3)
- **Intentionally skipped**: 1 (m11)
- **Post-review fixes**: 2 (ReviewPanel error state + null safety)
- **Overall**: All actionable issues resolved. Codebase is ready for merge.
