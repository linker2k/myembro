import { createHash } from 'node:crypto';
import { isSpecType, type Tool, type CallToolResult } from '@modelcontextprotocol/server';
import { RELAY_TOOLS, RELAY_TOOL_NAMES, sameSchema } from './tools';

export const UPDATE_REQUIRED = 'myembro 앱을 최신으로 업데이트해 주세요';
export const DISCONNECTED = 'myembro app is not connected. Open myembro and turn on AI connection.';
/**
 * Largest relay→app call message (UTF-8 bytes of the JSON). Cloudflare documents 32 MiB only for WebSocket messages
 * a Worker/Durable Object receives and nothing for the ones it sends; 1 MiB was the documented limit before
 * 2025-10-31, so calls stay within it. The app's import_picture limits (RelayPicture.kt) fit inside it.
 */
export const MAX_CALL_BYTES = 1_048_576;
export const TOO_LARGE = 'Too large for the AI connection. Ask the user to save the picture and share it to myembro ' +
  "(Share → myembro) instead; in Korean: '그림을 저장한 뒤 공유 → myembro로 보내 주세요'.";
/** The calling AI's MCP clientInfo.name as the app may show it: one line, at most 40 characters, or undefined. */
export function clientName(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const name = value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 40).trim();
  return name || undefined;
}
export const failure = (text: string): CallToolResult => ({ isError: true, content: [{ type: 'text', text }] });
// Fingerprint prevents a sleeping old app from inheriting newly deployed tool schemas.
const CATALOG = createHash('sha256').update(JSON.stringify(RELAY_TOOLS)).digest('hex');
export interface ConnectionAttachment { catalog: string; ready: boolean; supported: string[]; lastSeen: number }
export interface AppSocket { send(data: string): void; close(code?: number, reason?: string): void }

/** Only app JSON is handled here. MCP framing/initialization is entirely the official SDK. */
export class Broker {
  get tools(): Tool[] { return RELAY_TOOLS; }
  private supported = new Set<string>();
  private active?: AppSocket;
  private connections = new Map<AppSocket, ConnectionAttachment>();
  private pending = new Map<string, { socket: AppSocket; resolve: (result: CallToolResult) => void; timer: ReturnType<typeof setTimeout> }>();


  constructor(
    private persist: (socket: AppSocket, attachment: ConnectionAttachment) => void = () => {},
    private autoResponseTime: (socket: AppSocket) => number = () => 0,
  ) {}

  open(socket: AppSocket, restored?: ConnectionAttachment): void {
    const connection = restored ?? { catalog: CATALOG, ready: false, supported: [], lastSeen: Date.now() };
    this.connections.set(socket, connection);
    if (connection.ready) {
      this.active = socket;
      this.supported = new Set(connection.catalog === CATALOG ? connection.supported : []);
    }
    this.persist(socket, connection);
  }

  receive(socket: AppSocket, raw: string): void {
    if (!this.connections.has(socket)) return;
    let m: Record<string, any>;
    try { m = JSON.parse(raw); } catch { this.reject(socket); return; }
    if (!m || typeof m !== 'object' || Array.isArray(m)) { this.reject(socket); return; }
    const connection = this.connections.get(socket)!;
    connection.lastSeen = Date.now();
    if (m.type === 'hello') {
      if (typeof m.appVersion !== 'string' || !Array.isArray(m.tools)) { this.reject(socket); return; }
      const tools: Tool[] = [];
      for (const candidate of m.tools) {
        if (!isSpecType.Tool(candidate)) { this.reject(socket); return; }
        const a = candidate.annotations as Record<string, unknown> | undefined;
        if (!RELAY_TOOL_NAMES.has(candidate.name)) continue;
        if (candidate.name !== 'remove_objects' && a?.needsUserConfirmation !== undefined && a?.needsUserConfirmation !== false) continue;
        // Retain the original advertised definition, including annotations, unchanged.
        tools.push(candidate);
      }
      if (new Set(tools.map(t => t.name)).size !== tools.length) { this.reject(socket); return; }
      const previous = this.active;
      if (previous && previous !== socket) { this.close(previous); previous.close(1000, 'Replaced by new hello'); }
      this.active = socket;
      this.supported = new Set(tools.filter(t => sameSchema(t.inputSchema, RELAY_TOOLS.find(r => r.name === t.name)!.inputSchema)).map(t => t.name));
      Object.assign(connection, { catalog: CATALOG, ready: true, supported: [...this.supported] });
    } else if (m.type === 'ping') {
      socket.send(JSON.stringify({ type: 'pong' }));
    } else if (m.type === 'pong') {
      // Any valid app message refreshes lastSeen.
    } else if (m.type === 'result' && socket === this.active) {
      if (typeof m.id !== 'string' || typeof m.isError !== 'boolean' || !Array.isArray(m.content) ||
          !m.content.every((c: any) => c && (c.type === 'text' && typeof c.text === 'string' ||
            c.type === 'image' && ['image/png', 'image/jpeg'].includes(c.mimeType) && typeof c.data === 'string' &&
            /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(c.data)))) {
        this.reject(socket); return;
      }
      const pending = this.pending.get(m.id);
      if (pending?.socket === socket) {
        clearTimeout(pending.timer); this.pending.delete(m.id);
        pending.resolve({ isError: m.isError, content: m.content });
      }
    } else { this.reject(socket); }
    if (this.connections.has(socket)) this.persist(socket, connection);
  }

  /** [client]: the calling AI's name (clientInfo.name), sent to the app only when known. */
  call(tool: string, args: Record<string, unknown>, client?: string): Promise<CallToolResult> {
    if (this.active) {
      const seen = Math.max(this.connections.get(this.active)!.lastSeen, this.autoResponseTime(this.active));
      // Lazy expiry preserves the 90-second liveness rule without an idle timer/alarm.
      if (Date.now() - seen >= 90_000) {
        const stale = this.active; this.close(stale); stale.close(1000, 'App silent for 90 seconds');
      }
    }
    if (!this.active) return Promise.resolve(failure(DISCONNECTED));
    const advertised = RELAY_TOOLS.find(t => t.name === tool);
    if (!advertised) return Promise.resolve(failure('Tool is not available in this relay.'));
    if (!this.supported.has(tool)) return Promise.resolve(failure(UPDATE_REQUIRED));
    if (tool === 'get_project' && Object.keys(args).length !== 0 ||
        tool === 'render_preview' && (Object.keys(args).some(k => k !== 'widthPx') || args.widthPx !== undefined &&
          (!Number.isInteger(args.widthPx) || (args.widthPx as number) < 256 || (args.widthPx as number) > 1024)))
      return Promise.resolve(failure('Invalid tool arguments.'));
    const socket = this.active;
    const id = crypto.randomUUID();
    const name = clientName(client);
    const message = JSON.stringify({ type: 'call', id, tool, args, ...(name ? { client: name } : {}) });
    if (new TextEncoder().encode(message).length > MAX_CALL_BYTES) return Promise.resolve(failure(TOO_LARGE));
    return new Promise(resolve => {
      const timer = setTimeout(() => { this.pending.delete(id); resolve(failure('timed out')); }, 120_000);
      this.pending.set(id, { socket, resolve, timer });
      try { socket.send(message); }
      catch { this.close(socket); }
    });
  }

  close(socket: AppSocket): void {
    const connection = this.connections.get(socket);
    if (connection) { connection.ready = false; this.persist(socket, connection); }
    this.connections.delete(socket);
    if (this.active === socket) this.active = undefined;
    for (const [id, pending] of this.pending) if (pending.socket === socket) {
      clearTimeout(pending.timer); this.pending.delete(id); pending.resolve(failure(DISCONNECTED));
    }
  }
  private reject(socket: AppSocket): void { this.close(socket); socket.close(1008, 'Invalid app message'); }
}
