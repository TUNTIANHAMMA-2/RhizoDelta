import { useEffect, useRef } from "react";
import { useAuthStore } from "../stores/authStore";
import { useSseStore } from "../stores/sseStore";
import { useGraphStore } from "../stores/graphStore";
import { fetchNode } from "../api/nodes";
import type {
  NodeCreatedEvent,
  EdgeCreatedEvent,
  EdgeRemovedEvent,
  DecisionCompleteEvent,
  GraphEdgeDTO,
  OrchestrationStatusEvent,
} from "../api/types";

const SSE_URL = "/api/events/stream";
const MAX_RETRY_DELAY = 30_000;
const BASE_RETRY_DELAY = 1_000;
const MAX_RETRIES = 20;

interface SseEvent {
  type: string;
  data: string;
}

function parseSseBlock(block: string): SseEvent | null {
  let type = "";
  let data = "";
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      type = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      data += line.slice(5).trim();
    }
  }
  if (!type || !data) return null;
  return { type, data };
}

export function useSse() {
  const token = useAuthStore((s) => s.token);
  const setStatus = useSseStore((s) => s.setStatus);
  const abortRef = useRef<AbortController | null>(null);
  const retryCount = useRef(0);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    if (!token) {
      setStatus("disconnected");
      return;
    }

    const connect = async () => {
      if (!mountedRef.current) return;

      const controller = new AbortController();
      abortRef.current = controller;
      setStatus("connecting");

      try {
        const res = await fetch(SSE_URL, {
          headers: { Authorization: `Bearer ${token}` },
          signal: controller.signal,
        });

        if (!res.ok || !res.body) {
          throw new Error(`SSE connection failed: ${res.status}`);
        }

        setStatus("connected");
        retryCount.current = 0;

        const reader = res.body
          .pipeThrough(new TextDecoderStream())
          .getReader();
        let buffer = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += value;

          const blocks = buffer.split("\n\n");
          buffer = blocks.pop() ?? "";

          for (const block of blocks) {
            const event = parseSseBlock(block);
            if (event) handleSseEvent(event);
          }
        }

        // Stream ended normally — reconnect
        if (mountedRef.current) scheduleReconnect();
      } catch (err) {
        if ((err as Error).name === "AbortError") return;
        if (mountedRef.current) {
          setStatus("disconnected");
          scheduleReconnect();
        }
      }
    };

    const scheduleReconnect = () => {
      if (!mountedRef.current) return;
      if (retryCount.current >= MAX_RETRIES) {
        setStatus("disconnected");
        return;
      }
      const delay = Math.min(
        BASE_RETRY_DELAY * 2 ** retryCount.current + Math.random() * 1000,
        MAX_RETRY_DELAY,
      );
      retryCount.current++;
      setTimeout(() => {
        if (mountedRef.current) connect();
      }, delay);
    };

    connect();

    // Page Visibility: pause reconnects when hidden, reconnect when visible
    const handleVisibility = () => {
      if (document.visibilityState === "hidden") {
        abortRef.current?.abort();
        setStatus("disconnected");
      } else if (document.visibilityState === "visible" && mountedRef.current) {
        retryCount.current = 0;
        connect();
      }
    };
    document.addEventListener("visibilitychange", handleVisibility);

    return () => {
      mountedRef.current = false;
      abortRef.current?.abort();
      setStatus("disconnected");
      document.removeEventListener("visibilitychange", handleVisibility);
    };
  }, [token, setStatus]);
}

function handleSseEvent(event: SseEvent) {
  const graphStore = useGraphStore.getState();

  switch (event.type) {
    case "NODE_CREATED": {
      let payload: NodeCreatedEvent;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        console.error("Failed to parse NODE_CREATED event data:", e);
        break;
      }
      fetchNode(payload.node_id).then((node) => {
        graphStore.addNode(node);
        graphStore.loadRhizomes(); // Always refresh in case it's a new root
      });
      break;
    }
    case "EDGE_CREATED": {
      let payload: EdgeCreatedEvent;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        console.error("Failed to parse EDGE_CREATED event data:", e);
        break;
      }
      const edge: GraphEdgeDTO = {
        source: payload.source,
        target: payload.target,
        type: payload.type,
        created_at: payload.created_at,
      };

      // Fetch missing endpoint nodes before adding the edge
      // (decisions broadcast EDGE_CREATED but not NODE_CREATED)
      const missing: Promise<void>[] = [];
      if (!graphStore.nodes.has(payload.source)) {
        missing.push(
          fetchNode(payload.source).then((n) => graphStore.addNode(n)),
        );
      }
      if (!graphStore.nodes.has(payload.target)) {
        missing.push(
          fetchNode(payload.target).then((n) => graphStore.addNode(n)),
        );
      }
      if (missing.length > 0) {
        Promise.all(missing).then(() => {
          graphStore.addEdge(edge);
          graphStore.scheduleFlushLayout();
        });
      } else {
        graphStore.addEdge(edge);
        graphStore.scheduleFlushLayout();
      }
      break;
    }
    case "EDGE_REMOVED": {
      let payload: EdgeRemovedEvent;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        console.error("Failed to parse EDGE_REMOVED event data:", e);
        break;
      }
      graphStore.removeEdgesBySourceAndType(payload.source, payload.type);
      break;
    }
    case "DECISION_COMPLETE": {
      let payload: DecisionCompleteEvent;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        console.error("Failed to parse DECISION_COMPLETE event data:", e);
        break;
      }
      fetchNode(payload.node_id).then((node) => {
        graphStore.addNode(node);
        graphStore.resolveOptimisticNode(`temp-${payload.decision_id}`, node);
      });
      break;
    }
    case "ORCHESTRATION_STATUS": {
      let payload: OrchestrationStatusEvent;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        console.error("Failed to parse ORCHESTRATION_STATUS event data:", e);
        break;
      }
      useSseStore.getState().setOrchestrationStatus(payload);
      break;
    }
  }
}
