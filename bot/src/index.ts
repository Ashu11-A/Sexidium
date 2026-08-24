import "./discord/commands/index.js";

import { env } from "#env";
import { bootstrap } from "@constatic/base";
import { startRpcServer } from "./minecraft/index.js";
import { initDatabase } from "./database/index.js";

// Bring up the type-safe Minecraft bridge (WebSocket RPC server) and the database connection before
// the Discord client loads, so commands and event relays have them available.
startRpcServer();
await initDatabase();

await bootstrap({ meta: import.meta, env });
