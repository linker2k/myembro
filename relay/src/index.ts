import { Server, createMcpHandler, isLegacyRequest, WebStandardStreamableHTTPServerTransport } from '@modelcontextprotocol/server';
import { Broker, clientName, type ConnectionAttachment } from './broker';

import { authorizationFailure, type RelaySecrets } from './auth';

interface Env extends RelaySecrets { RELAY: DurableObjectNamespace }

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const denied = await authorizationFailure(request, env);
    if (denied) return denied;
    return env.RELAY.get(env.RELAY.idFromName('single-relay')).fetch(request);
  }
};

/** One object and one active app per deployment. Discovery always uses the bundled static catalog. */
export class Relay {
  private broker: Broker;
  /** clientInfo.name from the last legacy initialize (memory only: gone after hibernation, then omitted). */
  private lastClient?: string;
  private handler = createMcpHandler(() => this.server(), { legacy: 'reject' });
  private server(): Server {
    const server = new Server({ name: 'myembro-relay', version: '0.2.0' }, { capabilities: { tools: { listChanged: false } } });
    server.setRequestHandler('tools/list', async () => ({ tools: this.broker.tools }));
    // The calling AI's name: the 2026 envelope's clientInfo (the SDK takes it out of params._meta and gives it to
    // this per-request server), else the last legacy initialize (one user per relay).
    server.setRequestHandler('tools/call', async request => this.broker.call(request.params.name, request.params.arguments ?? {},
      clientName(server.getClientVersion()?.name) ?? this.lastClient));
    return server;
  }
  constructor(private state: DurableObjectState, private env: Env) {
    // Ignore legacy cached hello definitions: the advertised catalog is a deployment artifact.
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"type":"ping"}', '{"type":"pong"}'));
    this.broker = new Broker(
      (socket, attachment) => (socket as WebSocket).serializeAttachment(attachment),
      socket => this.state.getWebSocketAutoResponseTimestamp(socket as WebSocket)?.getTime() ?? 0,
    );
    // Runtime-owned sockets and their small attachments survive hibernation; promises do not.
    for (const socket of this.state.getWebSockets()) {
      if (socket.readyState === WebSocket.OPEN)
        this.broker.open(socket, socket.deserializeAttachment() as ConnectionAttachment | undefined);
    }
  }
  webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): void {
    if (typeof message === 'string') this.broker.receive(socket, message);
    else { this.broker.close(socket); socket.close(1008, 'JSON text required'); }
  }
  webSocketClose(socket: WebSocket, code: number, reason: string): void {
    this.broker.close(socket); socket.close(code === 1006 ? 1000 : code, reason);
  }
  webSocketError(socket: WebSocket): void { this.broker.close(socket); socket.close(1011, 'WebSocket error'); }
  async fetch(request: Request): Promise<Response> {
    const denied = await authorizationFailure(request, this.env);
    if (denied) return denied;
    if (new URL(request.url).pathname === '/app') {
      if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
        return new Response('WebSocket upgrade required', { status: 426 });
      const pair = new WebSocketPair(); const [client, app] = Object.values(pair);
      this.state.acceptWebSocket(app); this.broker.open(app);
      return new Response(null, { status: 101, webSocket: client });
    }
    // Official v2 routes both 2026 per-request envelopes and legacy POST traffic.
    // No unsolicited notifications: an optional idle GET/SSE would prevent hibernation.
    // MCP Streamable HTTP explicitly permits 405 when this stream is not offered.
    if (request.method === 'GET') return new Response('Method not allowed', { status: 405, headers: { Allow: 'POST' } });
    if (!await isLegacyRequest(request)) return this.handler.fetch(request);
    if (request.method === 'POST') {
      const body: any = await request.clone().json().catch(() => undefined);
      if (body?.method === 'initialize') this.lastClient = clientName(body.params?.clientInfo?.name);
    }
    const server = this.server();
    const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
    try { return await transport.handleRequest(request); }
    finally { await server.close(); }
  }
}
