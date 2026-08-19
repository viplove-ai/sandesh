import { commitIncoming, readCursor, writeCursor, type StoredMessage } from '../offline/db';
import { apiClient, currentAccessToken } from './apiClient';
import { API_BASE_URL } from './session';

/**
 * The one held connection.
 *
 * EventSource cannot carry an Authorization header, so this uses fetch with a streaming body
 * reader and parses the event framing itself — which costs about forty lines and buys the thing
 * that matters: the token stays out of the query string. A credential in a URL lands in every
 * proxy access log between the phone and the server.
 *
 * Reconnect is ours for the same reason, so it carries the jitter the browser's own reconnect
 * would not: without a random factor every device retries inside the same hundred milliseconds
 * after a tower comes back, and a restart becomes an outage.
 */

export interface Delivery {
  msgId: string;
  convId: string;
  from: string;
  fromName: string;
  kind: 'TEXT' | 'IMAGE' | 'DOC';
  body?: string;
  media?: { mediaId: string; fileName: string; contentType: string; sizeBytes?: number };
  sentAt: string;
}

type Listener = (message: StoredMessage) => void;

const MAX_BACKOFF_MS = 30_000;

export class MessageStream {
  private controller: AbortController | null = null;
  private attempt = 0;
  private stopped = false;
  private listeners = new Set<Listener>();

  onMessage(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  start(): void {
    this.stopped = false;
    void this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.controller?.abort();
    this.controller = null;
  }

  private async connect(): Promise<void> {
    if (this.stopped) return;
    this.controller = new AbortController();
    const lastEventId = await readCursor();

    try {
      const response = await fetch(`${API_BASE_URL}/stream`, {
        headers: {
          Authorization: `Bearer ${currentAccessToken() ?? ''}`,
          Accept: 'text/event-stream',
          ...(lastEventId ? { 'Last-Event-ID': lastEventId } : {}),
        },
        signal: this.controller.signal,
      });

      if (response.status === 401) {
        // Let the api client's single-flight refresh do the work, then come back.
        await apiClient.get('/conversations').catch(() => undefined);
        throw new Error('reauthenticating');
      }
      if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);

      this.attempt = 0;
      await this.read(response.body.getReader());
    } catch {
      // Every failure is the same failure: the connection broke. It always will.
    }

    if (!this.stopped) {
      const delay = Math.min(MAX_BACKOFF_MS, 2 ** this.attempt * 1000) * (0.5 + Math.random());
      this.attempt = Math.min(this.attempt + 1, 5);
      setTimeout(() => void this.connect(), delay);
    }
  }

  private async read(reader: ReadableStreamDefaultReader<Uint8Array>): Promise<void> {
    const decoder = new TextDecoder();
    let buffer = '';

    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });

      // Events are separated by a blank line; anything short of one is a partial frame.
      let split = buffer.indexOf('\n\n');
      while (split !== -1) {
        await this.handleFrame(buffer.slice(0, split));
        buffer = buffer.slice(split + 2);
        split = buffer.indexOf('\n\n');
      }
    }
  }

  private async handleFrame(frame: string): Promise<void> {
    let id: string | undefined;
    let event = 'message';
    const data: string[] = [];

    for (const line of frame.split('\n')) {
      if (line.startsWith(':')) return;                 // a heartbeat comment
      if (line.startsWith('id:')) id = line.slice(3).trim();
      else if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trim());
    }
    if (event !== 'message' || data.length === 0) return;

    const delivery = JSON.parse(data.join('\n')) as Delivery;

    // Commit first, then acknowledge. If this line throws, the server still holds the message
    // and will send it again — which is the correct failure.
    await commitIncoming({
      msgId: delivery.msgId,
      clientMsgId: delivery.msgId,
      convId: delivery.convId,
      from: delivery.from,
      fromName: delivery.fromName,
      kind: delivery.kind,
      body: delivery.body,
      mediaId: delivery.media?.mediaId,
      mediaFileName: delivery.media?.fileName,
      mediaContentType: delivery.media?.contentType,
      mediaSizeBytes: delivery.media?.sizeBytes,
      sentAt: delivery.sentAt,
      state: 'received',
      mine: false,
    });
    if (id) await writeCursor(id);

    // Deliberately no media download here. An arriving message carries a reference and a
    // thumbnail; the full-size original is fetched when somebody taps it. Pulling every
    // photograph eagerly would fill a phone with files nobody opened, over a connection that
    // is the scarce thing in the first place.
    await apiClient.post(`/messages/${delivery.msgId}/ack`).catch(() => {
      // The ack is lost, not the message. The server redelivers and the put above overwrites.
    });

    this.listeners.forEach((listener) =>
      listener({
        msgId: delivery.msgId,
        clientMsgId: delivery.msgId,
        convId: delivery.convId,
        from: delivery.from,
        fromName: delivery.fromName,
        kind: delivery.kind,
        body: delivery.body,
        sentAt: delivery.sentAt,
        state: 'received',
        mine: false,
      }),
    );
  }
}

export const messageStream = new MessageStream();
