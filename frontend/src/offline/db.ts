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
  kind: 'TEXT' | 'IMAGE' | 'DOC' | 'SYSTEM';
  /** The buttons on a card from Nirman, as delivered. Absent on every ordinary message. */
  actions?: unknown;
  body?: string;
  mediaId?: string;
  mediaFileName?: string;
  mediaContentType?: string;
  mediaSizeBytes?: number;
  /**
   * The full-size original has been dropped to make room; the thumbnail and the text remain.
   * For a site or project channel the server still has it and a tap re-fetches. For a direct
   * message it is gone, and the interface has to say so rather than showing a broken image.
   */
  mediaEvicted?: boolean;
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
  /**
   * Where the server put it — announcements first, then Nirman, then the sites and projects.
   * Held as a field rather than re-derived, because the order is the server's opinion about
   * what matters and sorting by name here would quietly overrule it.
   */
  order?: number;
  lastMessageAt?: string;
  lastPreview?: string;
}

/**
 * Keep the list of conversations on the device, not only in memory.
 *
 * <p>Membership is the server's to decide and is re-derived from Nirman on every call, so this
 * is a cache rather than a record — but it is the cache every thread is reached through. Held
 * only in the query client, it went with the sign-out, and a phone signing back in on a bad
 * connection opened on an empty list with three years of messages sitting in the store behind
 * it, unreachable. That is what this fixes.</p>
 *
 * <p>A channel the server no longer lists is dropped, because a posting that has ended is not
 * still on offer. Its messages stay: they are the record, and they were never the server's to
 * take back.</p>
 */
export async function saveConversations(
  list: { id: string; kind: string; name: string; subtitle?: string }[],
): Promise<void> {
  const rows: StoredConversation[] = list.map((conversation, index) => ({
    convId: conversation.id,
    kind: conversation.kind,
    name: conversation.name,
    subtitle: conversation.subtitle,
    order: index,
  }));
  const listed = new Set(rows.map((row) => row.convId));

  await db.transaction('rw', db.conversations, async () => {
    const stale = (await db.conversations.toCollection().primaryKeys())
      .filter((convId) => !listed.has(convId));
    await db.conversations.bulkDelete(stale);
    await db.conversations.bulkPut(rows);
  });
}

/** The stored list, in the order the server gave it. */
export async function readConversations(): Promise<StoredConversation[]> {
  const rows = await db.conversations.toArray();
  return rows.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
}

/**
 * How far this person has read in a conversation. One row per conversation, written when the
 * thread is on screen.
 *
 * <p>It lives here rather than on the server for the same reason the messages do: the device is
 * the copy, and a supervisor who reads a message in a tunnel has read it whether or not the
 * server ever hears about it.</p>
 */
export interface ReadMark {
  convId: string;
  /** ISO timestamp of the newest message that has been seen. */
  lastReadAt: string;
}

/** Where the stream resumes from. One row, id 'stream'. */
export interface StreamCursor {
  id: string;
  lastEventId: string;
}

class SandeshDb extends Dexie {
  messages!: Table<StoredMessage, string>;
  conversations!: Table<StoredConversation, string>;
  reads!: Table<ReadMark, string>;
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
    // Added rather than folded into version 1: a phone already carrying three years of messages
    // must open on the new build, not be handed an upgrade that rewrites its only copy.
    this.version(2).stores({
      reads: 'convId',
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

/**
 * Mark a conversation read up to the newest message on screen.
 *
 * <p>The high-water mark is a message's own sentAt rather than the clock, because the two
 * disagree: a phone that has been offline commits a batch whose timestamps are all in the past,
 * and a mark written as "now" would swallow every one of them unread. It only ever moves
 * forward, so a thread scrolled back through does not un-read what came in behind it.</p>
 */
export async function markConversationRead(convId: string, upTo: string): Promise<void> {
  await db.transaction('rw', db.reads, async () => {
    const existing = await db.reads.get(convId);
    if (existing && existing.lastReadAt >= upTo) return;
    await db.reads.put({ convId, lastReadAt: upTo });
  });
}

/**
 * Unread counts for the whole list, keyed by conversation id.
 *
 * <p>Counted on the device from what is stored, not asked of the server — the badge has to be
 * right on a phone that has not had a signal since this morning, and the messages it would be
 * counting are already here.</p>
 *
 * <p>Read through Dexie's `state` index: everything that arrived from somebody else is
 * 'received', so this never walks the sender's own three years of messages.</p>
 */
export async function unreadCounts(meId: string): Promise<Record<string, number>> {
  const marks = new Map((await db.reads.toArray()).map((mark) => [mark.convId, mark.lastReadAt]));
  const counts: Record<string, number> = {};

  await db.messages.where('state').equals('received').each((message) => {
    // Our own message, delivered back from another device, is not something to be told about.
    if (message.mine || message.from === meId) return;
    const seenTo = marks.get(message.convId);
    if (seenTo && message.sentAt <= seenTo) return;
    counts[message.convId] = (counts[message.convId] ?? 0) + 1;
  });

  return counts;
}

export async function readCursor(): Promise<string | undefined> {
  return (await db.cursors.get('stream'))?.lastEventId;
}

export async function writeCursor(lastEventId: string): Promise<void> {
  await db.cursors.put({ id: 'stream', lastEventId });
}

/** A site handset changes hands, and the conversations on it are not the next person's. */
export async function forgetEverything(): Promise<void> {
  await db.transaction('rw', db.messages, db.conversations, db.reads, db.cursors, async () => {
    await db.messages.clear();
    await db.conversations.clear();
    await db.reads.clear();
    await db.cursors.clear();
  });
}
