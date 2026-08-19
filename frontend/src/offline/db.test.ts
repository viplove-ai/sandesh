import { beforeEach, describe, expect, it } from 'vitest';
import { commitIncoming, db, forgetEverything, markSent, readCursor, writeCursor } from './db';

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

  it('keeps a stream cursor so a reconnect resumes rather than replays', async () => {
    expect(await readCursor()).toBeUndefined();
    await writeCursor('1755594000000-m-1');
    expect(await readCursor()).toBe('1755594000000-m-1');
  });

  it('forgets everything when the handset changes hands', async () => {
    // A site phone is passed to the next shift, and the conversations on it are the record of
    // somebody else's work. Sign-in compares against the last user and calls this.
    await commitIncoming({ ...base, msgId: 'm-1', clientMsgId: 'm-1', body: 'private', state: 'received' });
    await writeCursor('1755594000000-m-1');

    await forgetEverything();

    expect(await db.messages.count()).toBe(0);
    expect(await readCursor()).toBeUndefined();
  });
});
