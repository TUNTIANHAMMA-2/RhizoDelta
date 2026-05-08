> **Progress**: ✅ All actionable issues resolved and verified. Codebase updated accordingly.

# Code Review — Phase 3 User-State (commits 25dc324..fee1e05)

> 4 commits / 102 files / +7780 / −2782
> Reviewer: Claude (multi-model upstream unavailable)
> Test status at review time: `mvn test` → 353 / 0 fail / 0 err

This file is meant to be consumed by **Codex** (or any agent) to drive the
follow-up fix pass. Each finding includes:

- **Where** — file + line range
- **What** — concrete failure mode
- **Why** — what breaks if left as-is
- **How** — prescribed fix, with code snippets where useful
- **Acceptance** — how to verify the fix

Findings are ordered by severity. Critical and Major must land before
production rollout; Minor / Suggestions can be batched.

---

## Working Conventions for the Fixer

1. Each Critical / Major fix should land as **its own commit** with a
   `fix(scope): subject` message; pair Minor fixes by file ownership.
2. After each commit, run the relevant integration test class plus
   `mvn -q test -Dtest='<RelevantTestClass>'` and confirm green before
   moving on.
3. Do **not** refactor beyond what each finding asks for. If you spot
   adjacent issues, add them under "New findings" at the bottom of this
   doc instead of fixing them silently.
4. Treat the existing `application-test.yml` and `TestRestTemplateConfig`
   as fixed contracts — do not modify them to make a test pass.

---

# 🔴 Critical (must fix before merge)

## C1. `:Decision` half-write when target node is missing

- **Where**: `src/main/java/com/rhizodelta/consensus/service/DecisionMetadataService.java:23-35`
- **What**: The Cypher does `MERGE (d:Decision {decision_id})` first, then
  `WITH d MATCH (target:GraphNode {node_id: $targetNodeId})`. If the
  target doesn't exist, the post-`WITH` row is dropped, but the `:Decision`
  node is **already committed**. Result: a `:Decision` with no
  `RESULTED_IN` edge — invisible to `AuditRelationService` traversals.
- **Why it matters**: This silently corrupts the audit lineage. The
  decision shows up as "executed" via the `:Decision` node, but no edge
  ties it to the produced node. Replays of the same `decision_id` then
  no-op (idempotent MERGE), so the missing edge is never repaired.
- **How to fix**: Either pre-validate `target` exists before MERGE-ing, or
  rewrite the Cypher so the `:Decision` node is only created when the
  target exists. Prefer the second — it's atomic and idempotent.

  ```cypher
  MATCH (target:GraphNode {node_id: $targetNodeId})
  MERGE (d:Decision {decision_id: $decisionId})
    ON CREATE SET d.decision_type = $decisionType,
                  d.operator_type = $operatorType,
                  d.operator_id = $operatorId,
                  d.reason = $reason,
                  d.created_at = $createdAt
  MERGE (d)-[r:RESULTED_IN]->(target)
    ON CREATE SET r.created_at = $createdAt
  RETURN d.decision_id AS decisionId
  ```

  If the target is genuinely missing, the whole statement returns 0 rows.
  Update the Java caller to log a WARN and throw
  `NoSuchElementException("target node not found: " + targetNodeId)`
  instead of silently returning. The current `recordDecision` swallows
  the result; change it to assert the row count.
- **Acceptance**:
  1. Add a unit test that calls `recordDecision` with a non-existent
     `targetNodeId`, expects an exception, and confirms via Cypher that
     no `:Decision` node is left behind.
  2. `mvn -q test -Dtest='AuditRelationIntegrationTest,DecisionApi*'` still green.

---

# 🟠 Major (fix soon — production hardening)

## M1. Full-graph label-disjunctive scan in feed queries

- **Where**:
  - `src/main/java/com/rhizodelta/infrastructure/user/service/FeedService.java:80-102` (`GLOBAL_FEED_QUERY`)
  - `src/main/java/com/rhizodelta/infrastructure/user/service/FeedService.java:33-78` (`FEED_QUERY` Topic branch — line 45 `MATCH (n) WHERE n.topic_id = t.topic_id AND (n:Human_Post OR ...)`)
- **What**: `MATCH (n) WHERE (n:Human_Post OR n:AI_Consensus OR n:Result)`
  scans **every node** in the graph and post-filters by label. As
  `:UserAccount`, `:Topic`, `:Decision`, `:UserProfile` accumulate, this
  becomes a full-graph scan per request.
- **Why it matters**: At 100k posts + 50k users + 10k topics + 100k
  decisions = ~260k node scan per feed request. Scales linearly with
  unrelated entity growth.
- **How to fix**: Replace the disjunctive MATCH with three labeled
  matches under UNION ALL, and ensure the Topic branch uses an indexed
  property lookup.

  GLOBAL_FEED_QUERY rewrite:

  ```cypher
  CALL {
    MATCH (n:Human_Post) WHERE NOT coalesce(n._deleted, false) RETURN n
    UNION ALL
    MATCH (n:AI_Consensus) WHERE NOT coalesce(n._deleted, false) RETURN n
    UNION ALL
    MATCH (n:Result) WHERE NOT coalesce(n._deleted, false) RETURN n
  }
  WITH n
  ORDER BY n.created_at DESC
  SKIP $skip LIMIT $limit
  // ... existing OPTIONAL MATCH author + RETURN ...
  ```

  Topic branch in `FEED_QUERY` — same trick:

  ```cypher
  MATCH (u:UserAccount {user_id: userId})-[:FOLLOWS]->(t:Topic)
  WHERE NOT t.topic_id IN mutedTopics
  CALL {
    WITH t
    MATCH (n:Human_Post {topic_id: t.topic_id}) RETURN n
    UNION
    MATCH (n:AI_Consensus {topic_id: t.topic_id}) RETURN n
    UNION
    MATCH (n:Result {topic_id: t.topic_id}) RETURN n
  }
  WITH n, mutedUsers
  WHERE NOT coalesce(n._deleted, false)
    AND NOT coalesce(n.author_id, '__none__') IN mutedUsers
  RETURN n
  ```

  Then add the supporting indexes in
  `src/main/java/com/rhizodelta/infrastructure/persistence/config/DatabaseInitializer.java`:

  ```cypher
  CREATE INDEX human_post_topic IF NOT EXISTS FOR (n:Human_Post) ON (n.topic_id);
  CREATE INDEX ai_consensus_topic IF NOT EXISTS FOR (n:AI_Consensus) ON (n.topic_id);
  CREATE INDEX result_topic IF NOT EXISTS FOR (n:Result) ON (n.topic_id);
  CREATE INDEX human_post_created_at IF NOT EXISTS FOR (n:Human_Post) ON (n.created_at);
  CREATE INDEX ai_consensus_created_at IF NOT EXISTS FOR (n:AI_Consensus) ON (n.created_at);
  CREATE INDEX result_created_at IF NOT EXISTS FOR (n:Result) ON (n.created_at);
  ```
- **Acceptance**:
  1. `EXPLAIN` the new query in `cypher-shell` and confirm `NodeIndexSeek`
     instead of `AllNodesScan`.
  2. `mvn -q test -Dtest='FeedApiIntegrationTest'` still green.
  3. Add an assertion in
     `PostCreationPerformanceTest` (or a new perf test) that 1000 posts +
     50 follows yields p95 < 100ms on the feed endpoint.

---

## M2. SSRF / phishing vector via user-controlled `avatar_url`

- **Where**:
  - `src/main/java/com/rhizodelta/infrastructure/user/api/UserProfileController.java` — the PUT `/me/profile` path persists arbitrary `avatar_url` from the request body.
  - `src/main/java/com/rhizodelta/infrastructure/user/service/AvatarStorageService.java:149-154` — `getPresignedUrl` short-circuits any `http://` / `https://` value back to the caller verbatim.
- **What**: A user can `PUT /api/users/me/profile` with
  `{"avatar_url": "http://attacker.com/track.gif"}`. The frontend (and
  any other client that reads the public profile) then renders that URL
  via `<img src>`. This:
  1. Lets the attacker's server log the IP / cookies / referrer of every
     viewer of that user's public node listings.
  2. Bypasses the magic-byte validation enforced by `AvatarController`.
  3. If served over `http://` (not https), can also degrade page security.
- **Why it matters**: Cross-user surface (other users see the avatar). Low
  effort, persistent, hard to detect.
- **How to fix**: Reject `avatar_url` writes from the generic profile PUT
  endpoint — `avatar_url` must only flow through `PUT /me/avatar` (which
  goes through `validateFile` → MinIO/local storage and writes back the
  storage path).

  In `UserProfileController` (and/or its `UpdateUserProfileRequest`
  payload), explicitly drop / reject `avatar_url`:

  ```java
  if (request.getAvatarUrl() != null) {
      throw new IllegalArgumentException(
          "avatar_url is read-only here; use PUT /me/avatar to upload");
  }
  ```

  Or remove the field from the request DTO entirely.

  Optionally, also keep the absolute-URL short-circuit in
  `getPresignedUrl` for the case where future code legitimately stores
  external CDN URLs — but only behind a feature flag like
  `rhizodelta.avatar.allow-external-urls=false` (default off).
- **Acceptance**:
  1. New integration test in
     `src/test/java/com/rhizodelta/api/UserProfileApiIntegrationTest.java`
     asserting `PUT /me/profile {"avatar_url": "http://x"}` returns 400.
  2. `mvn -q test -Dtest='UserProfileApi*,AvatarLifecycle*'` green.

---

## M3. Refresh-token reuse tombstone expires too early

- **Where**: `src/main/java/com/rhizodelta/infrastructure/security/service/RefreshTokenService.java:88-94` (`issue` pipeline) + `consume` success path lines 115-128.
- **What**: The `userIndex` tombstone gets the same TTL as the refresh
  token (default `P30D`). Successful `consume()` deletes only the
  payload; the tombstone keeps its remaining TTL. So a token issued at
  `T=0` and consumed at `T=0+ε` still has a tombstone that expires at
  `T+30d`. After that, replays of the original token are seen as "just
  invalid" and **no cascade fires**.
- **Why it matters**: Real attackers don't replay within 30 days of theft
  — they sit on tokens until rotation has rotated them out of value.
  Detection should outlive the rotation window, not coincide with it.
- **How to fix**: When `consume()` succeeds, refresh the userIndex TTL to
  a longer detection window (e.g. 90 days) decoupled from refresh TTL.

  In `consume()`, after `opsForSet().remove(...)`:

  ```java
  // Extend the tombstone TTL for late-replay detection — independent of
  // refresh TTL so an attacker sitting on a stolen token past 30d is
  // still caught.
  refreshTokenRedisTemplate.expire(
      userIndexKey,
      Duration.ofDays(90).getSeconds(),
      TimeUnit.SECONDS
  );
  ```

  Make `90d` configurable via
  `@Value("${rhizodelta.jwt.reuse-detection-ttl:P90D}") Duration reuseDetectionTtl`.
- **Acceptance**:
  1. New unit test: issue token, consume it, advance time 31 days
     (use `Clock` injection or `@SpyBean`), replay → still detects reuse.
  2. `mvn -q test -Dtest='RefreshTokenIntegrationTest'` green.

---

## M4. Fragile Mockito matchers in `ServiceLayerUnitTest` / `PostReplyUnitTest`

- **Where**:
  - `src/test/java/com/rhizodelta/service/ServiceLayerUnitTest.java` (multiple `argThat((String q) -> q.contains("MATCH (post:Human_Post"))` matchers)
  - `src/test/java/com/rhizodelta/infrastructure/messaging/consumer/PostReplyUnitTest.java` (same pattern)
- **What**: `CREATE_AUTHORED_RELATIONSHIP_QUERY` in `PostService` also
  contains `MATCH (post:Human_Post:GraphNode {node_id: $postNodeId})`,
  so the matcher matches **two** different production queries. Mockito
  resolves to the last-registered matching stub — order-sensitive.
- **Why it matters**: Tests pass today because order happens to align,
  but a refactor of stub order silently breaks coverage. Tests
  pass for the wrong reasons.
- **How to fix**: Use unique-substring matchers for each query.

  | Production query | Suggested unique substring |
  |---|---|
  | Idempotency check (`MATCH (post:Human_Post {request_id: $requestId}) RETURN toString(post.node_id)`) | `"RETURN toString(post.node_id)"` |
  | Author exists | `"MATCH (user:UserAccount"` (already used) |
  | Target node exists | `"MATCH (node:GraphNode"` (already used) |
  | Upsert post | `"MERGE (post:Human_Post"` (already used) |
  | Authored edge | `"MERGE (author)-[rel:AUTHORED]"` (already used) |
  | Continues-from edge | `"CONTINUES_FROM"` (already used) |

  Replace every `contains("MATCH (post:Human_Post")` with
  `contains("RETURN toString(post.node_id)")` so it ONLY matches the
  idempotency check.
- **Acceptance**: tests still green, but reorder the `when()` clauses in
  the test to verify they're not order-dependent.

---

# 🟡 Minor

## m1. Double-layered fail-open in JWT filter

- **Where**: `src/main/java/com/rhizodelta/infrastructure/security/filter/JwtAuthenticationFilter.java:124-144` and `src/main/java/com/rhizodelta/infrastructure/security/service/TokenBlacklistService.java:44-78`.
- **What**: Both layers swallow `RuntimeException` and emit a WARN. On
  Redis failure you get **two** WARN logs per request. Functionally
  correct, but log spam during a Redis outage.
- **How**: Pick one layer. Recommended: keep the catch in the **service**
  (`TokenBlacklistService`) since other callers (e.g. `AuthController.logout`)
  also benefit. Drop `isJtiRevokedSafe` / `revokedBeforeSafe` from the
  filter — call the service directly.

## m2. Second-precision `iat` vs millisecond `revokedBefore`

- **Where**: `src/main/java/com/rhizodelta/infrastructure/security/service/TokenBlacklistService.java:57-61`.
- **What**: `revokeAllForUser` writes `Instant.now().toEpochMilli()` (ms).
  JWT `iat` is seconds (jjwt rounds to seconds). Same-second
  issue-then-revoke could leave a fresh token live by 1-999ms.
- **How**: Round up to the next second when writing:

  ```java
  long revokeAtMillis = Instant.now().plusSeconds(1).getEpochSecond() * 1000;
  ```

  Or compare `iat <= revokedBefore` (inclusive) instead of `isBefore`
  (exclusive) at the filter side.

## m3. `AvatarUpload` doesn't load existing avatar on mount

- **Where**: `frontend/src/components/settings/AvatarUpload.tsx:8-13`.
- **What**: `avatarUrl` state initializes to `null`. User who already
  uploaded an avatar sees the placeholder until they upload again.
- **How**: Add a `useEffect` to fetch profile on mount.

  ```tsx
  import { useEffect } from "react";
  import { getMyProfile } from "../../api/profile";
  // ...
  useEffect(() => {
    getMyProfile()
      .then((p) => setAvatarUrl(p.avatar_url))
      .catch(() => {/* ignore */});
  }, []);
  ```

## m4. `homeStore.loadFeed` lacks stale-response guard + error surface

- **Where**: `frontend/src/stores/homeStore.ts:49-58`.
- **What**: Rapid sortBy toggles between `for_you` and others can let
  stale responses overwrite fresh state. Network errors only `console.error`,
  user sees a stale empty state with no message.
- **How**:
  1. Track an in-flight request id; ignore responses whose id mismatches.
  2. Add a `feedError: string | null` field; populate on catch; render in
     `HomeMainColumn`'s EmptyState when present.

  ```ts
  let feedRequestSeq = 0;
  // ...
  loadFeed: async () => {
    const myReq = ++feedRequestSeq;
    set({ feedLoading: true, feedError: null });
    try {
      const r = await getFeed(0, 50);
      if (myReq !== feedRequestSeq) return; // stale
      set({ feedItems: (r.items as GraphNodeDTO[]) ?? [] });
    } catch (e) {
      if (myReq !== feedRequestSeq) return;
      set({ feedError: e instanceof Error ? e.message : "feed failed" });
    } finally {
      if (myReq === feedRequestSeq) set({ feedLoading: false });
    }
  },
  ```

## m5. `FeedResponse.items: unknown[]` loses type

- **Where**: `frontend/src/api/types.ts:323-328`.
- **How**: change to `items: GraphNodeDTO[]`. Drop the cast in `homeStore.loadFeed`.

## m6. `MuteCreatedResponse` / `MuteListResponse` defined in `mutes.ts`, not `types.ts`

- **Where**: `frontend/src/api/mutes.ts:3-22`.
- **How**: Move to `frontend/src/api/types.ts` next to `FollowItem` /
  `FollowListResponse` for consistency. `mutes.ts` should only `import type`.

## m7. `AuthorLabel` falls back to UUID

- **Where**: `frontend/src/components/shared/AuthorLabel.tsx:8-14`.
- **What**: `displayName ?? username ?? authorId ?? "Anonymous"` — if both
  display and username are missing, the UUID gets rendered. Bad UX.
- **How**: drop the `authorId` fallback:

  ```ts
  return displayName ?? username ?? "Anonymous";
  ```

## m8. Generic `alt="Avatar"` text

- **Where**: `frontend/src/components/settings/AvatarUpload.tsx:51`.
- **How**: `alt={`${displayName ?? username ?? "User"} avatar`}`.

---

# 💡 Suggestions

| # | Where | Suggestion |
|---|-------|------------|
| s1 | `FeedService.java` | Extract the shared RETURN projection to a `private static final String GRAPH_NODE_PROJECTION` constant + string concat. Both queries currently duplicate ~14 lines that must stay in sync. |
| s2 | `JwtAuthenticationFilter.java:86,137,138` | Replace inlined `java.time.Instant` / `java.util.Date` FQNs with imports for readability. |
| s3 | `RefreshTokenService.consume()` | The two Redis round-trips (`getAndDelete` + `opsForValue.get(userIndex)`) could be one pipeline. Sub-ms gain, low priority. |
| s4 | `frontend/src/api/profile.ts` vs `follows.ts` | Inconsistent error shapes. profile.ts re-throws `Error.message`; follows.ts throws raw Response. Consolidate via a single `throwIfNotOk` helper in `client.ts`. |

---

# ✅ Verified clean (no action)

- `FeedService` Cypher alias `n` — no residual `content` collisions.
- `AvatarStorageService.validateFile` — magic-byte coverage for JPEG / PNG / WebP, run before storage.
- `SecurityConfig` RBAC — no accidental `permitAll` on write endpoints.
- `ConflictException` → 409 mapping correct.
- `FollowController` / `MuteController` / `FeedController` field shape matches `frontend/src/api/types.ts` (`follow_id`, `mute_id`, `target_type`, `since`, `items`, `total`, `total_pages`).
- `TokenBlacklistIntegrationTest.shouldFailOpenWhenBlacklistServiceThrows` — asserts 200 OK (not just absence of reject), genuine fail-open coverage.
- `RefreshTokenIntegrationTest.reuseDetectionRevokesAllSiblingTokens` — verifies cascade via `newRefresh` invalidation, not assumed.

---

# Execution Plan for the Fixer

Run these as separate commits in order:

1. **`fix(consensus): require target node when recording decision metadata`** — C1
2. **`fix(security): block avatar_url writes via generic profile PUT`** — M2 (security-sensitive, ship before user data exists)
3. **`fix(security): extend refresh-token reuse detection window beyond rotation TTL`** — M3
4. **`perf(feed): split label-disjunctive Cypher and add topic/created_at indexes`** — M1
5. **`test: tighten Mockito matchers in PostService unit tests`** — M4
6. **`fix(security): drop double-layered Redis fail-open in JWT filter`** — m1
7. **`fix(security): pad revokedBefore by one second to defeat clock-granularity edge`** — m2
8. **`fix(frontend): load existing avatar on settings mount + add stale guard to feed`** — m3, m4
9. **`refactor(frontend): consolidate API types and harden author label`** — m5, m6, m7, m8

After each, run the targeted suite. After the last, run full
`mvn test` and confirm 353 / 0 / 0.

---

# New Findings (append below)

## n1. m1 was a false positive — keep the double fail-open

- **Finding revisited**: m1 proposed dropping the filter-level
  `isJtiRevokedSafe` / `revokedBeforeSafe` since `TokenBlacklistService`
  already has internal fail-open. Dropping it broke
  `TokenBlacklistIntegrationTest.shouldFailOpenWhenBlacklistServiceThrows`
  immediately.
- **Why the second layer is necessary**: the integration test (and any
  Spring AOP proxy edge case) mocks/throws *at the service entry* —
  before the service's own try/catch executes. Without the filter
  wrapper the exception bubbles to the request handler and returns 500.
  The two layers are not redundant; they're defense-in-depth across
  two distinct failure modes (Redis I/O failure vs. service-layer throw).
- **Decision**: keep both layers, document the rationale inline in
  `JwtAuthenticationFilter`. WARN log volume is bounded — the service's
  catch returns before re-throwing, so only one WARN per request.
- **Status**: closed without code change.
