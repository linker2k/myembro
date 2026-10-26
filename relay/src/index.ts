import { createHash } from 'node:crypto';
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
  private server(client?: string): Server {
    const server = new Server({ name: 'myembro-relay', version: '0.2.0' }, { capabilities: { tools: { listChanged: false } } });
    server.setRequestHandler('tools/list', async () => ({ tools: this.broker.tools }));
    // Capture identity per request; concurrent sessions must never share mutable name state.
    server.setRequestHandler('tools/call', async request => this.broker.call(request.params.name, request.params.arguments ?? {},
      clientName((request.params._meta as any)?.['io.modelcontextprotocol/clientInfo']?.name) ?? client));
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
    const body: any = request.method === 'POST' ? await request.clone().json().catch(() => undefined) : undefined;
    const supplied = clientName(body?.method === 'initialize' ? body.params?.clientInfo?.name :
      body?.params?._meta?.['io.modelcontextprotocol/clientInfo']?.name);
    const session = request.headers.get('Mcp-Session-Id');
    // Hash caller-controlled session IDs: bounded storage keys and no raw identifiers in storage keys/logs.
    const key = session ? 'client:session:' + createHash('sha256').update(session).digest('hex') : 'client:last';
    const client = supplied ?? clientName(await this.state.storage.get<string>(key));
    let response: Response;
    if (!await isLegacyRequest(request)) {
      response = await createMcpHandler(() => this.server(client), { legacy: 'reject' }).fetch(request);
    } else {
      const server = this.server(client);
      const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      await server.connect(transport);
      try { response = await transport.handleRequest(request); }
      finally { await server.close(); }
    }
    // Failed initialize must not poison persisted identity. Store before returning the response.
    if (supplied && response.ok && (body?.method !== 'initialize' ||
        !(await response.clone().json().catch(() => ({ error: true })) as any)?.error)) {
      await this.state.storage.put({ [key]: supplied, 'client:last': supplied });
    }
    return response;
  }
}
