import { beforeEach, describe, expect, it } from 'vitest';
import { db, forgetEverything, type StoredMessage } from './db';
import { KEEP_ORIGINAL_DAYS, KEEP_RECENT_DAYS, runEviction } from './eviction';

const DAY = 24 * 60 * 60 * 1000;

function daysAgo(days: number): string {
  return new Date(Date.now() - days * DAY).toISOString();
}

function message(over: Partial<StoredMessage> & { msgId: string }): StoredMessage {
  return {
    clientMsgId: over.msgId,
    convId: 'site:11111111-1111-1111-1111-111111111111',
    from: 'u1',
    fromName: 'R. Negi',
    kind: 'IMAGE',
    mediaId: `media-${over.msgId}`,
    mediaSizeBytes: 700 * 1024,
    sentAt: daysAgo(60),
    state: 'received',
    mine: false,
    ...over,
  };
}

/** Quota is well over the trigger, so the ladder runs to the bottom rather than stopping early. */
const SCHEDULED = { used: 10, quota: 1_000_000, persisted: true };

describe('the eviction ladder', () => {
  beforeEach(async () => {
    await forgetEverything();
  });

  it('never drops anything from the last week, whatever the pressure', async () => {
    await db.messages.bulkPut([
      message({ msgId: 'fresh', sentAt: daysAgo(KEEP_RECENT_DAYS - 1) }),
      message({ msgId: 'old', sentAt: daysAgo(90) }),
    ]);

    await runEviction(SCHEDULED);

    expect((await db.messages.get('fresh'))?.mediaEvicted).toBeFalsy();
    expect((await db.messages.get('old'))?.mediaEvicted).toBe(true);
  });

  it('drops a retained channel before a direct message', async () => {
    // A site channel is Tier 2 on the server, so the device is a cache and this costs a
    // re-fetch. A direct message is the only copy in existence.
    await db.messages.bulkPut([
      message({ msgId: 'site', sentAt: daysAgo(20) }),
      message({ msgId: 'dm', convId: 'dm:a:b', sentAt: daysAgo(20) }),
    ]);

    const report = await runEviction(SCHEDULED);

    expect((await db.messages.get('site'))?.mediaEvicted).toBe(true);
    // 20 days is past the recent window but inside the original-retention window, so the
    // direct message survives this pass.
    expect((await db.messages.get('dm'))?.mediaEvicted).toBeFalsy();
    expect(report.unbackedEvicted).toBe(0);
  });

  it('counts direct-message media separately, because the user has to be told', async () => {
    await db.messages.bulkPut([
      message({ msgId: 'dm', convId: 'dm:a:b', sentAt: daysAgo(KEEP_ORIGINAL_DAYS + 5) }),
    ]);

    const report = await runEviction(SCHEDULED);

    expect(report.unbackedEvicted).toBe(1);
    expect(report.evicted).toBe(1);
  });

  it('keeps the row and the text, and only drops the bytes', async () => {
    await db.messages.put(message({ msgId: 'old', body: 'the beam is cracked', thumbnail: 'data:,' }));

    await runEviction(SCHEDULED);

    const kept = await db.messages.get('old');
    expect(kept).toBeDefined();
    expect(kept?.body).toBe('the beam is cracked');
    expect(kept?.thumbnail).toBe('data:,');
    expect(kept?.mediaEvicted).toBe(true);
  });

  it('does not evict the same message twice', async () => {
    await db.messages.put(message({ msgId: 'old', mediaEvicted: true }));

    const report = await runEviction(SCHEDULED);

    expect(report.evicted).toBe(0);
  });
});
