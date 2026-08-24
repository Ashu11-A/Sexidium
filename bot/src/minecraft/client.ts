import {
  procedures,
  type ProcedureName,
  type ProcedureInput,
  type ProcedureOutput,
} from "../types/contract.js";
import { sendRequest, isConnected } from "./server.js";

/**
 * Typed procedure caller (bot -> Java). Replaces the old hand-mirrored `lib/api.ts` HTTP client.
 * Inputs are validated against the contract before sending and outputs after receiving, so a caller
 * always gets exactly `ProcedureOutput<M>` back (or a thrown error / `BridgeOfflineError`).
 */
export async function call<M extends ProcedureName>(
  method: M,
  input: ProcedureInput<M>,
): Promise<ProcedureOutput<M>> {
  const spec = procedures[method];
  const parsedInput = spec.input.parse(input ?? {});
  const raw = await sendRequest(method, parsedInput);
  return spec.output.parse(raw) as ProcedureOutput<M>;
}

/** Ergonomic, fully-typed facade over the procedure set. */
export const mc = {
  rankTop: (limit = 10) => call("rank.top", { limit }),
  rankByName: (name: string) => call("rank.byName", { name }),
  rankByDiscord: (id: string) => call("rank.byDiscord", { id }),
  serverCommand: (command: string) => call("server.command", { command }),
  serverInfo: () => call("server.info", {}),
  serverRestart: () => call("server.restart", {}),
  serverStop: () => call("server.stop", {}),
  consoleTail: (n = 50) => call("console.tail", { n }),
  skinGet: (target: string) => call("skin.get", { target }),
  authLink: (args: ProcedureInput<"auth.link">) => call("auth.link", args),
  authDecide: (args: ProcedureInput<"auth.decide">) => call("auth.decide", args),
  authSessions: (discordUserId: string) => call("auth.sessions", { discordUserId }),
  authRevoke: (discordUserId: string, sessionId?: string) =>
    call("auth.revoke", { discordUserId, sessionId }),
  isOnline: () => isConnected(),
};

export type RunResult = "ok" | "forbidden" | "disabled" | "unauthorized" | "error";

/**
 * Runs a console command and maps failures to the same tokens the old HTTP bridge used, so the
 * `/mc` and `/announce` command messaging keeps working unchanged. The Java `server.command` handler
 * replies with an `err` envelope whose message is one of `forbidden`/`disabled`/`unauthorized`.
 */
export async function runServerCommand(command: string): Promise<RunResult> {
  try {
    await mc.serverCommand(command);
    return "ok";
  } catch (error) {
    const message = error instanceof Error ? error.message : "error";
    if (message === "forbidden" || message === "disabled" || message === "unauthorized") {
      return message;
    }
    return "error";
  }
}
