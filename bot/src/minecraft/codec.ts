import { zEnvelope, type Envelope } from "../types/contract.js";

/** Serialize an envelope to a WebSocket text frame. */
export function encode(envelope: Envelope): string {
  return JSON.stringify(envelope);
}

/** Parse + validate an inbound text frame. Returns `null` for anything malformed. */
export function decode(raw: string): Envelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  const result = zEnvelope.safeParse(parsed);
  return result.success ? result.data : null;
}
