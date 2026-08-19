import Dexie, { type Table } from 'dexie';

/**
 * The device is the copy.
 *
 * Once a message has been acknowledged it exists here and nowhere else on the server — so this
 * store is not a cache, it is the record. It is also why the app insists on being installed:
 * a browser evicts IndexedDB under storage pressure, and an installed PWA that has been granted
 * persistent storage is the only configuration where that does not happen silently.
 */

export type MessageState = 'pending' | 'sent' | 'received' | 'failed';

export interface StoredMessage {
  /** The server's id once known; before that, the client id. They are both UUIDs. */
  msgId: string;
  clientMsgId: string;
  convId: string;
  from: string;
  fromName: string;
  kind: 'TEXT' | 'IMAGE' | 'DOC';
  body?: string;
  mediaId?: string;
  mediaFileName?: string;
  mediaContentType?: string;
  /** A data URL for the thumbnail. Kept as long as the text; the original is evictable. */
  thumbnail?: string;
  sentAt: string;
  state: MessageState;
  mine: boolean;
}

export interface StoredConversation {
  convId: string;
  kind: string;
  name: string;
  subtitle?: string;
  lastMessageAt?: string;
  lastPreview?: string;
}

/** Where the stream resumes from. One row, id 'stream'. */
export interface StreamCursor {
  id: string;
  lastEventId: string;
}

class SandeshDb extends Dexie {
  messages!: Table<StoredMessage, string>;
  conversations!: Table<StoredConversation, string>;
  cursors!: Table<StreamCursor, string>;

  constructor() {
    super('sandesh');
    this.version(1).stores({
      // Compound index on [convId+sentAt] is what the thread view reads; msgId is the key so a
      // redelivery after a crash overwrites rather than duplicates.
      messages: 'msgId, clientMsgId, [convId+sentAt], convId, state',
      conversations: 'convId, lastMessageAt',
      cursors: 'id',
    });
  }
}

export const db = new SandeshDb();

/**
 * Write the message down, then tell the caller it is safe to acknowledge.
 *
 * The order is the whole point: acknowledging on receipt would delete the server's only copy
 * while the phone was still writing it, and a browser killed in that window loses the message
 * permanently. A crash before this resolves costs a redelivery, which is free — the put is
 * keyed by msgId, so the second copy overwrites the first.
 */
export async function commitIncoming(message: StoredMessage): Promise<void> {
  await db.messages.put(message);
}

export async function markSent(clientMsgId: string, msgId: string, sentAt: string): Promise<void> {
  const existing = await db.messages.where('clientMsgId').equals(clientMsgId).first();
  if (!existing) return;
  // The server's id replaces the client's, so a delivery of our own message from another device
  // lands on the same row rather than beside it.
  await db.transaction('rw', db.messages, async () => {
    await db.messages.delete(existing.msgId);
    await db.messages.put({ ...existing, msgId, sentAt, state: 'sent' });
  });
}

export async function readCursor(): Promise<string | undefined> {
  return (await db.cursors.get('stream'))?.lastEventId;
}

export async function writeCursor(lastEventId: string): Promise<void> {
  await db.cursors.put({ id: 'stream', lastEventId });
}

/** A site handset changes hands, and the conversations on it are not the next person's. */
export async function forgetEverything(): Promise<void> {
  await db.transaction('rw', db.messages, db.conversations, db.cursors, async () => {
    await db.messages.clear();
    await db.conversations.clear();
    await db.cursors.clear();
  });
}
