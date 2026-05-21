# Mobile Discussion Tree Runbook

## Scope

Mobile discussion tree is the mobile fallback for `/workspace/:rhizomeId`. Viewports `<= 1024px` lazy-load `MobileDiscussionTreeView`; wider viewports lazy-load `DesktopGraphWorkspace`.

The backend endpoint is read-only:

```http
GET /api/nodes/{rootId}/discussion-tree?max_depth=5&limit=200
```

It returns `DiscussionTreeResponse { root, meta }`, where `root.children` contains only `Human_Post` comments and `root.artifacts` contains anchored `AI_Consensus` / `Result` notes. `source_node_ids` is not filtered to the visible slice.

## Deploy Order

1. Deploy backend first. Existing desktop clients do not call the new endpoint.
2. Deploy frontend second. Mobile clients switch routes automatically through the viewport shell.
3. Verify the endpoint through authenticated mobile traffic or a JWT-backed API request.

## Verification

Backend:

```bash
mvn -Dspring.profiles.active=test -Dtest=DiscussionTreeQueryServiceTest test
mvn -Dspring.profiles.active=test -Dtest=DiscussionTreeApiIntegrationTest test
```

Frontend:

```bash
cd frontend
npx vitest run src/stores/discussionTreeStore.test.ts src/hooks/useViewport.test.ts src/hooks/useLongPress.test.tsx src/components/mobile/CommentTreeItem.test.tsx src/components/mobile/ClosureNote.test.tsx src/components/mobile/MobileReplyComposer.test.tsx
npm run build
grep -l "@xyflow" dist/assets/MobileDiscussionTreeView-*.js
grep -l "d3-force" dist/assets/MobileDiscussionTreeView-*.js
grep -l "@dagrejs/dagre" dist/assets/MobileDiscussionTreeView-*.js
grep -l "@tiptap/" dist/assets/MobileDiscussionTreeView-*.js
```

The four grep commands must print no files. A non-zero grep exit is expected for the passing case.

Manual checks:

- Desktop width `> 1024px`: `/workspace/:rhizomeId` renders the graph workspace.
- Mobile width `<= 1024px`: the same route renders the discussion tree.
- Tap a comment: reply target changes without focusing the textarea.
- Long press a comment: bottom sheet opens; Escape closes it.
- Submit a reply: pending placeholder appears and reconciles through SSE when `post_node_id` arrives.
- Expand a closure note: visible source comments receive the consensus-colored left rail.

## Monitoring

Use existing Spring HTTP metrics:

- `http.server.requests{uri="/api/nodes/{id}/discussion-tree"}` latency, P95 target `< 200ms` for a 200-node tree.
- 4xx rate: expected for invalid root type, invalid params, missing JWT.
- 5xx rate: should stay zero; investigate immediately.

Frontend build target: `MobileDiscussionTreeView-*.js` should stay below `120 KB` gzip. Current implementation is about `4.49 KB` gzip.

## Rollback

Frontend-only rollback: revert `frontend/src/components/GraphWorkspace.tsx` to always render `DesktopGraphWorkspace`, or revert the frontend deployment. Backend endpoint can remain available unused.

Backend rollback: remove `DiscussionTreeQueryService`, discussion-tree DTOs, and the controller method. Do this only after frontend rollback, otherwise mobile clients will receive API errors.

## Optional Feature Flag

No feature flag is currently implemented. If gradual rollout is required, add `rhizodelta.feature.mobile-discussion-tree.enabled`, register it in `FeatureFlagRegistry.java`, document it in `docs/runbooks/feature-flags.md`, and have the frontend shell fall back to `DesktopGraphWorkspace` when the flag is disabled.
