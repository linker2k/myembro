// Test-only constructor replay seam. Never bundled into dist/worker.js or deployed.
import { Relay } from '../src/index';
export default { fetch(request: Request, env: any) { return env.RELAY.get(env.RELAY.idFromName('test')).fetch(request); } };
export class ReconstructedRelay {
  private relay: Relay;
  private messages = 0;
  constructor(private state: DurableObjectState, private env: any) { this.relay = new Relay(state, env); }
  fetch(request: Request) {
    const path = new URL(request.url).pathname;
    if (path === '/__test/reconstruct') { this.relay = new Relay(this.state, this.env); return new Response('reconstructed'); }
    if (path === '/__test/status') return Response.json({ messages: this.messages, sockets: this.state.getWebSockets().length,
      attachments: this.state.getWebSockets().map(s => s.deserializeAttachment()),
      auto: this.state.getWebSockets().map(s => this.state.getWebSocketAutoResponseTimestamp(s)?.getTime() ?? null) });
    return this.relay.fetch(request);
  }
  webSocketMessage(socket: WebSocket, message: string | ArrayBuffer) { this.messages++; this.relay.webSocketMessage(socket, message); }
  webSocketClose(socket: WebSocket, code: number, reason: string) { this.relay.webSocketClose(socket, code, reason); }
  webSocketError(socket: WebSocket) { this.relay.webSocketError(socket); }
}
