import { ToolSchema, type Tool, type CallToolResult } from '@modelcontextprotocol/sdk/types.js';

export const DISCONNECTED = 'myembro app is not connected. Open myembro and turn on AI connection.';
export const failure = (text: string): CallToolResult => ({ isError: true, content: [{ type: 'text', text }] });
export interface AppSocket { send(data: string): void; close(code?: number, reason?: string): void }

/** Only app JSON is handled here. MCP framing/initialization is entirely the official SDK. */
export class Broker {
  tools: Tool[] = [];
  private active?: AppSocket;
  private connections = new Map<AppSocket, { heartbeat: ReturnType<typeof setInterval>; silence: ReturnType<typeof setTimeout> }>();
  private pending = new Map<string, { socket: AppSocket; resolve: (result: CallToolResult) => void; timer: ReturnType<typeof setTimeout> }>();
  constructor(private readonly cache: (tools: Tool[]) => void = () => {}) {}

  open(socket: AppSocket): void {
    const heartbeat = setInterval(() => {
      try { socket.send(JSON.stringify({ type: 'ping' })); } catch { this.close(socket); }
    }, 30_000);
    this.connections.set(socket, { heartbeat, silence: this.silenceDeadline(socket) });
  }

  private silenceDeadline(socket: AppSocket): ReturnType<typeof setTimeout> {
    return setTimeout(() => {
      this.close(socket); socket.close(1000, 'App silent for 90 seconds');
    }, 90_000);
  }

  receive(socket: AppSocket, raw: string): void {
    if (!this.connections.has(socket)) return;
    let m: Record<string, any>;
    try { m = JSON.parse(raw); } catch { this.reject(socket); return; }
    if (!m || typeof m !== 'object' || Array.isArray(m)) { this.reject(socket); return; }
    const connection = this.connections.get(socket)!;
    clearTimeout(connection.silence); connection.silence = this.silenceDeadline(socket);
    if (m.type === 'hello') {
      if (typeof m.appVersion !== 'string' || !Array.isArray(m.tools)) { this.reject(socket); return; }
      const tools: Tool[] = [];
      for (const candidate of m.tools) {
        const parsed = ToolSchema.safeParse(candidate);
        if (!parsed.success) { this.reject(socket); return; }
        const a = candidate.annotations;
        if (!['get_project', 'render_preview'].includes(candidate.name)) continue;
        if (!(a?.readOnly === true || a?.readOnlyHint === true) ||
            (a.needsUserConfirmation !== undefined && a.needsUserConfirmation !== false)) continue;
        // Retain the original advertised definition, including annotations, unchanged.
        tools.push(candidate);
      }
      if (new Set(tools.map(t => t.name)).size !== tools.length) { this.reject(socket); return; }
      const previous = this.active;
      if (previous && previous !== socket) { this.close(previous); previous.close(1000, 'Replaced by new hello'); }
      this.active = socket; this.tools = tools; this.cache(tools);
    } else if (m.type === 'ping') {
      socket.send(JSON.stringify({ type: 'pong' }));
    } else if (m.type === 'pong') {
      // Any valid app message refreshes lastSeen.
    } else if (m.type === 'result' && socket === this.active) {
      if (typeof m.id !== 'string' || typeof m.isError !== 'boolean' || !Array.isArray(m.content) ||
          !m.content.every((c: any) => c && (c.type === 'text' && typeof c.text === 'string' ||
            c.type === 'image' && c.mimeType === 'image/png' && typeof c.data === 'string' &&
            /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(c.data)))) {
        this.reject(socket); return;
      }
      const pending = this.pending.get(m.id);
      if (pending?.socket === socket) {
        clearTimeout(pending.timer); this.pending.delete(m.id);
        pending.resolve({ isError: m.isError, content: m.content });
      }
    } else { this.reject(socket); }
  }

  call(tool: string, args: Record<string, unknown>): Promise<CallToolResult> {
    if (!this.active) return Promise.resolve(failure(DISCONNECTED));
    if (!this.tools.some(t => t.name === tool)) return Promise.resolve(failure('Tool is not available in this read-only spike.'));
    if (tool === 'get_project' && Object.keys(args).length !== 0 ||
        tool === 'render_preview' && (Object.keys(args).length !== 1 || !Number.isInteger(args.widthPx) ||
          (args.widthPx as number) < 256 || (args.widthPx as number) > 1024))
      return Promise.resolve(failure('Invalid tool arguments.'));
    const socket = this.active;
    return new Promise(resolve => {
      const id = crypto.randomUUID();
      const timer = setTimeout(() => { this.pending.delete(id); resolve(failure('timed out')); }, 60_000);
      this.pending.set(id, { socket, resolve, timer });
      try { socket.send(JSON.stringify({ type: 'call', id, tool, args })); }
      catch { this.close(socket); }
    });
  }

  close(socket: AppSocket): void {
    const connection = this.connections.get(socket);
    if (connection) { clearInterval(connection.heartbeat); clearTimeout(connection.silence); }
    this.connections.delete(socket);
    if (this.active === socket) this.active = undefined;
    for (const [id, pending] of this.pending) if (pending.socket === socket) {
      clearTimeout(pending.timer); this.pending.delete(id); pending.resolve(failure(DISCONNECTED));
    }
  }
  private reject(socket: AppSocket): void { this.close(socket); socket.close(1008, 'Invalid app message'); }
}
