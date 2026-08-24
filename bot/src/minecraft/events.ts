import { events, type EventName, type EventPayload } from "../types/contract.js";

/**
 * Typed event bus for Java -> bot pushes. Handlers registered with `on("player.join", …)` receive a
 * fully-inferred, runtime-validated payload — the whole point of the "100% type-safe events" goal.
 */

type Handler<E extends EventName> = (payload: EventPayload<E>) => void | Promise<void>;

const handlers = new Map<EventName, Set<Handler<EventName>>>();

/** Subscribe to a Java event. Returns an unsubscribe function. */
export function on<E extends EventName>(event: E, handler: Handler<E>): () => void {
  let set = handlers.get(event);
  if (!set) {
    set = new Set();
    handlers.set(event, set);
  }
  set.add(handler as Handler<EventName>);
  return () => {
    handlers.get(event)?.delete(handler as Handler<EventName>);
  };
}

/**
 * Validate + fan out an inbound event frame. Called by the WS server; not part of the public API.
 * Unknown methods and payloads that fail validation are dropped with a warning.
 */
export async function deliverEvent(method: string, rawPayload: unknown): Promise<void> {
  const schema = (events as Record<string, (typeof events)[EventName]>)[method];
  if (!schema) {
    console.warn(`[rpc] dropped unknown event: ${method}`);
    return;
  }
  const parsed = schema.safeParse(rawPayload);
  if (!parsed.success) {
    console.warn(`[rpc] dropped malformed payload for event ${method}`);
    return;
  }
  const set = handlers.get(method as EventName);
  if (!set) return;
  for (const handler of set) {
    try {
      await handler(parsed.data as EventPayload<EventName>);
    } catch (error) {
      console.error(`[rpc] event handler for ${method} threw:`, error);
    }
  }
}
