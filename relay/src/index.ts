import { Server, createMcpHandler, isLegacyRequest, WebStandardStreamableHTTPServerTransport } from '@modelcontextprotocol/server';
import { Broker, clientName } from './broker';

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
  private legacyStreams = new Set<Server>();
  /** clientInfo.name from the last legacy initialize; legacy tools/call requests do not carry it. */
  private lastClient?: string;
  private handler = createMcpHandler(() => this.server(), { legacy: 'reject' });
  private server(): Server {
    const server = new Server({ name: 'myembro-relay', version: '0.2.0' }, { capabilities: { tools: { listChanged: false } } });
    server.setRequestHandler('tools/list', async () => ({ tools: this.broker.tools }));
    // The calling AI's name: per request in 2026 envelopes, else the last legacy initialize (one user per relay).
    server.setRequestHandler('tools/call', async request => this.broker.call(request.params.name, request.params.arguments ?? {},
      clientName((request.params._meta as any)?.['io.modelcontextprotocol/clientInfo']?.name) ?? this.lastClient));
    return server;
  }
  constructor(private state: DurableObjectState, private env: Env) {
    // Ignore legacy cached hello definitions: the advertised catalog is a deployment artifact.
    this.broker = new Broker();
  }
  async fetch(request: Request): Promise<Response> {
    const denied = await authorizationFailure(request, this.env);
    if (denied) return denied;
    if (new URL(request.url).pathname === '/app') {
      if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket')
        return new Response('WebSocket upgrade required', { status: 426 });
      const pair = new WebSocketPair(); const [client, app] = Object.values(pair);
      app.accept(); this.broker.open(app);
      app.addEventListener('message', event => {
        if (typeof event.data === 'string') this.broker.receive(app, event.data);
        else { this.broker.close(app); app.close(1008, 'JSON text required'); }
      });
      app.addEventListener('close', () => this.broker.close(app));
      app.addEventListener('error', () => this.broker.close(app));
      return new Response(null, { status: 101, webSocket: client });
    }
    // Official v2 routes both 2026 per-request envelopes and legacy POST traffic.
    // Legacy GET subscriptions retain their official SDK transport until stream cancellation.
    if (!await isLegacyRequest(request)) return this.handler.fetch(request);
    if (request.method === 'POST') {
      const body: any = await request.clone().json().catch(() => undefined);
      if (body?.method === 'initialize') this.lastClient = clientName(body.params?.clientInfo?.name);
    }
    const server = this.server();
    const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
    const response = await transport.handleRequest(request);
    if (request.method !== 'GET' || response.status !== 200 || !response.body) { await server.close(); return response; }
    this.legacyStreams.add(server);
    const reader = response.body.getReader();
    const cleanup = async () => { this.legacyStreams.delete(server); await server.close(); };
    const body = new ReadableStream<Uint8Array>({
      async pull(controller) {
        try { const part = await reader.read(); if (part.done) { controller.close(); await cleanup(); } else controller.enqueue(part.value); }
        catch (error) { controller.error(error); await cleanup(); }
      },
      async cancel() { try { await reader.cancel(); } finally { await cleanup(); } }
    });
    return new Response(body, { status: response.status, headers: response.headers });
  }
}
