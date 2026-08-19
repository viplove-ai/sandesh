import { beforeEach, describe, expect, it } from 'vitest';
import {
  commitIncoming, db, forgetEverything, markConversationRead, markSent, readCursor, unreadCounts,
  writeCursor,
} from './db';

/** Whoever is holding the phone. Their own messages are never unread. */
const ME = '33333333-3333-3333-3333-333333333333';

const base = {
  convId: 'site:11111111-1111-1111-1111-111111111111',
  from: '22222222-2222-2222-2222-222222222222',
  fromName: 'R. Negi',
  kind: 'TEXT' as const,
  sentAt: '2026-08-19T09:00:00.000Z',
  mine: false,
};

describe('the device store', () => {
  beforeEach(async () => {
    await forgetEverything();
  });

  it('overwrites a redelivered message rather than duplicating it', async () => {
    // A crash between committing and acknowledging costs a redelivery, which is free precisely
    // because the store is keyed by msgId. If this ever stops being true, every message that
    // arrives twice appears twice.
    const message = { ...base, msgId: 'm-1', clientMsgId: 'm-1', body: 'Beam cracked', state: 'received' as const };

    await commitIncoming(message);
    await commitIncoming(message);

    expect(await db.messages.where('convId').equals(base.convId).count()).toBe(1);
  });

  it('replaces the client id with the server id when a send is confirmed', async () => {
    // Otherwise our own message, delivered back from another device under the server's id,
    // lands beside the optimistic copy instead of on top of it.
    await db.messages.put({
      ...base,
      msgId: 'client-abc',
      clientMsgId: 'client-abc',
      body: 'Sending',
      state: 'pending',
      mine: true,
    });

    await markSent('client-abc', 'server-xyz', '2026-08-19T09:00:01.000Z');

    expect(await db.messages.get('client-abc')).toBeUndefined();
    const confirmed = await db.messages.get('server-xyz');
    expect(confirmed?.state).toBe('sent');
    expect(confirmed?.body).toBe('Sending');
    expect(await db.messages.count()).toBe(1);
  });

  it('reads a thread back in the order it was sent', async () => {
    await commitIncoming({ ...base, msgId: 'b', clientMsgId: 'b', body: 'second', sentAt: '2026-08-19T09:00:02.000Z', state: 'received' });
    await commitIncoming({ ...base, msgId: 'a', clientMsgId: 'a', body: 'first', sentAt: '2026-08-19T09:00:01.000Z', state: 'received' });

    const thread = await db.messages.where('convId').equals(base.convId).sortBy('sentAt');
    expect(thread.map((m) => m.body)).toEqual(['first', 'second']);
  });

  it('counts what has arrived since the thread was last read, per conversation', async () => {
    const announcements = 'org:44444444-4444-4444-4444-444444444444';
    await commitIncoming({ ...base, msgId: 'a-1', clientMsgId: 'a-1', body: 'one', sentAt: '2026-08-19T09:00:01.000Z', state: 'received' });
    await commitIncoming({ ...base, msgId: 'a-2', clientMsgId: 'a-2', body: 'two', sentAt: '2026-08-19T09:00:02.000Z', state: 'received' });
    await commitIncoming({
      ...base, convId: announcements, msgId: 'b-1', clientMsgId: 'b-1', body: 'holiday',
      sentAt: '2026-08-19T08:00:00.000Z', state: 'received',
    });

    expect(await unreadCounts(ME)).toEqual({ [base.convId]: 2, [announcements]: 1 });

    // Opening the site thread reads it up to its newest message and no further — the
    // announcement that arrived elsewhere is still waiting.
    await markConversationRead(base.convId, '2026-08-19T09:00:02.000Z');

    expect(await unreadCounts(ME)).toEqual({ [announcements]: 1 });
  });

  it('does not count what this person sent, from this device or another', async () => {
    // Otherwise a supervisor who posts to the site channel from a tablet comes back to the
    // phone and finds a badge on his own message.
    await db.messages.put({ ...base, msgId: 'mine-1', clientMsgId: 'mine-1', body: 'sent', state: 'sent', mine: true });
    await commitIncoming({
      ...base, msgId: 'mine-2', clientMsgId: 'mine-2', from: ME, fromName: 'Me', body: 'from the tablet',
      sentAt: '2026-08-19T09:00:05.000Z', state: 'received',
    });

    expect(await unreadCounts(ME)).toEqual({});
  });

  it('never moves the read mark backwards', async () => {
    // Scrolling back through an old thread must not un-read the message that came in behind it.
    await markConversationRead(base.convId, '2026-08-19T09:00:02.000Z');
    await markConversationRead(base.convId, '2026-08-19T08:00:00.000Z');

    expect((await db.reads.get(base.convId))?.lastReadAt).toBe('2026-08-19T09:00:02.000Z');
  });

  it('keeps a stream cursor so a reconnect resumes rather than replays', async () => {
    expect(await readCursor()).toBeUndefined();
    await writeCursor('1755594000000-m-1');
    expect(await readCursor()).toBe('1755594000000-m-1');
  });

  it('forgets everything when the handset changes hands', async () => {
    // A site phone is passed to the next shift, and the conversations on it are the record of
    // somebody else's work. Sign-in compares against the last user and calls this.
    await commitIncoming({ ...base, msgId: 'm-1', clientMsgId: 'm-1', body: 'private', state: 'received' });
    await markConversationRead(base.convId, base.sentAt);
    await writeCursor('1755594000000-m-1');

    await forgetEverything();

    expect(await db.messages.count()).toBe(0);
    expect(await db.reads.count()).toBe(0);
    expect(await readCursor()).toBeUndefined();
  });
});
