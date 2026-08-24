// Minimal ambient declarations for the subset of the Bun runtime API the RPC WebSocket server uses.
// The bot only ever runs under Bun (the Java plugin launches it with `bun run`), so these are safe;
// at runtime the real Bun globals are used and these types are erased. Avoids a `bun-types` dependency.

declare namespace Bun {
  interface ServerWebSocket<T = unknown> {
    data: T;
    send(data: string | ArrayBufferView | ArrayBuffer): number;
    close(code?: number, reason?: string): void;
    readonly readyState: number;
  }

  interface WebSocketHandler<T = unknown> {
    open?(ws: ServerWebSocket<T>): void | Promise<void>;
    message?(ws: ServerWebSocket<T>, message: string | Buffer): void | Promise<void>;
    close?(ws: ServerWebSocket<T>, code: number, reason: string): void | Promise<void>;
  }

  interface Server {
    stop(closeActiveConnections?: boolean): void;
    readonly port: number;
  }

  interface ServeUpgrade<T> {
    upgrade<U = T>(request: Request, options?: { data?: U }): boolean;
  }

  function serve<T = unknown>(options: {
    port?: number;
    hostname?: string;
    fetch(
      request: Request,
      server: Server & ServeUpgrade<T>,
    ): Response | undefined | Promise<Response | undefined>;
    websocket: WebSocketHandler<T>;
  }): Server;
}
