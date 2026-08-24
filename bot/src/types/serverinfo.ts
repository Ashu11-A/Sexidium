import type { ServerInfo } from "./dto.js";

/** View-model for the server-info image card (the live `ServerInfo` DTO plus display-only fields). */
export interface ServerInfoData extends ServerInfo {
  serverName: string;
  address: string;
}
