import { randomUUID } from "node:crypto";
import { env } from "#env";
import { encode, decode } from "./codec.js";
import { deliverEvent } from "./events.js";

/**
 * The WebSocket transport. The bot hosts the server (loopback only) and the Java plugin connects to
 * it as a client — this fits the "Java launches the bot" ownership model and needs zero networking
 * deps on the Java side (JDK `java.net.http.WebSocket`). A single authenticated Java connection is
 * expected at a time.
 *
 * Frames are correlated envelopes (see `types/contract.ts`):
 *  - inbound  `hello` (handshake), `event` (push), `res`/`err` (reply to our request)
 *  - outbound `hello` (ack), `req` (procedure call)
 */

const REQUEST_TIMEOUT_MS = 10_000;

interface SocketData {
  authed: boolean;
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

/** Thrown by `sendRequest` when no authenticated Java connection is available. */
export class BridgeOfflineError extends Error {
  constructor() {
    super("minecraft bridge offline");
    this.name = "BridgeOfflineError";
  }
}

let server: Bun.Server | null = null;
let activeSocket: Bun.ServerWebSocket<SocketData> | null = null;
const pending = new Map<string, Pending>();

/** Start the loopback WebSocket RPC server. Idempotent. */
export function startRpcServer(): void {
  if (server) return;
  server = Bun.serve<SocketData>({
    hostname: "127.0.0.1",
    port: env.RPC_PORT,
    fetch(request, srv) {
      if (srv.upgrade(request, { data: { authed: false } })) return undefined;
      return new Response("sexidium rpc", { status: 426 });
    },
    websocket: {
      open() {
        // Wait for the `hello` handshake before trusting the connection.
      },
      message(ws, message) {
        handleMessage(ws, typeof message === "string" ? message : message.toString("utf8"));
      },
      close(ws) {
        if (ws === activeSocket) {
          activeSocket = null;
          failAllPending("bridge disconnected");
          console.warn("[rpc] minecraft server disconnected");
        }
      },
    },
  });
  console.log(`[rpc] listening on ws://127.0.0.1:${env.RPC_PORT}`);
}

function handleMessage(ws: Bun.ServerWebSocket<SocketData>, raw: string): void {
  const envelope = decode(raw);
  if (!envelope) return;

  if (envelope.type === "hello") {
    const token = (envelope.payload as { token?: string } | undefined)?.token ?? "";
    if (!env.API_TOKEN || token !== env.API_TOKEN) {
      console.warn("[rpc] rejected connection: bad handshake token");
      ws.close(4001, "unauthorized");
      return;
    }
    ws.data.authed = true;
    activeSocket = ws;
    ws.send(encode({ type: "hello", method: "", payload: { ok: true } }));
    console.log("[rpc] minecraft server connected + authenticated");
    return;
  }

  if (!ws.data.authed) {
    ws.close(4001, "unauthorized");
    return;
  }

  switch (envelope.type) {
    case "event":
      void deliverEvent(envelope.method, envelope.payload);
      break;
    case "res":
      settlePending(envelope.id, envelope.payload, null);
      break;
    case "err":
      settlePending(envelope.id, undefined, envelope.error ?? "error");
      break;
    // "req"/"hello" from an already-authed peer are ignored: procedures live on the Java side.
  }
}

function settlePending(id: string | undefined, payload: unknown, error: string | null): void {
  if (!id) return;
  const entry = pending.get(id);
  if (!entry) return;
  clearTimeout(entry.timer);
  pending.delete(id);
  if (error) entry.reject(new Error(error));
  else entry.resolve(payload);
}

function failAllPending(reason: string): void {
  for (const [, entry] of pending) {
    clearTimeout(entry.timer);
    entry.reject(new BridgeOfflineError());
  }
  pending.clear();
  if (reason) {
    // reason retained for logging context only
  }
}

/** True when an authenticated Java connection is live. */
export function isConnected(): boolean {
  return activeSocket !== null;
}

/**
 * Send a procedure request and await its `res`/`err`. Rejects with {@link BridgeOfflineError} when the
 * server is not connected, or a generic `Error` (message = the Java error token) on failure/timeout.
 */
export function sendRequest(method: string, payload: unknown): Promise<unknown> {
  const ws = activeSocket;
  if (!ws) return Promise.reject(new BridgeOfflineError());

  const id = randomUUID();
  return new Promise<unknown>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`rpc timeout: ${method}`));
    }, REQUEST_TIMEOUT_MS);
    pending.set(id, { resolve, reject, timer });
    try {
      ws.send(encode({ id, type: "req", method, payload }));
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(error instanceof Error ? error : new Error(String(error)));
    }
  });
}

/** Stop the RPC server (used on shutdown). */
export function stopRpcServer(): void {
  failAllPending("shutting down");
  server?.stop(true);
  server = null;
  activeSocket = null;
}
