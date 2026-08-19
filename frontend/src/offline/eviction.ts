import { db, type StoredMessage } from './db';
import { mediaStore, type StorageUsage } from './mediaStore';

/**
 * Making room before the browser makes it for us.
 *
 * Media outweighs text on this device by roughly two hundred to one: three years of a
 * supervisor's messages is tens of megabytes, one year of photographs is several gigabytes. So
 * retention here is asymmetric — text and thumbnails are effectively kept forever, and the
 * full-size original is the first thing to go.
 *
 * What makes that safe is the tier the conversation belongs to. A site or project channel is
 * retained on the server, so the device is a cache and dropping an original costs a re-fetch.
 * A direct message is not retained anywhere else, so its original is the only copy and is
 * evicted last, with a warning, and never silently.
 */

/**
 * Run at 70 %, never at 100 %. Hitting the quota raises QuotaExceededError in the middle of a
 * write, and a half-written record is a worse outcome than a missing one.
 */
export const EVICT_ABOVE = 0.7;

/** Nothing recent is ever dropped, whatever the pressure. */
export const KEEP_RECENT_DAYS = 7;

/** A retained channel's original is re-fetchable, so it need not be held long. */
export const KEEP_ORIGINAL_DAYS = 30;

export interface EvictionReport {
  freedBytes: number;
  evicted: number;
  /** Direct-message media dropped — the only step the user has to be told about. */
  unbackedEvicted: number;
  ranBecause: 'pressure' | 'schedule';
}

function isRetained(convId: string): boolean {
  // Site, project and announcement channels are Tier 2 on the server. A direct message is not.
  return !convId.startsWith('dm:');
}

function olderThan(days: number) {
  const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
  return (message: StoredMessage) => Date.parse(message.sentAt) < cutoff;
}

/** Oldest first, so what goes is what somebody is least likely to open next. */
function oldestFirst(a: StoredMessage, b: StoredMessage): number {
  return Date.parse(a.sentAt) - Date.parse(b.sentAt);
}

/**
 * The ladder, in order. Each rung is only descended if the one above did not free enough — so
 * a device under mild pressure never reaches the step that costs somebody a photograph.
 */
export async function runEviction(usage: StorageUsage): Promise<EvictionReport> {
  const report: EvictionReport = {
    freedBytes: 0,
    evicted: 0,
    unbackedEvicted: 0,
    ranBecause: usage.quota > 0 && usage.used / usage.quota > EVICT_ABOVE ? 'pressure' : 'schedule',
  };

  // Everything still holding bytes, oldest first, excluding anything from the last week —
  // nothing recent is dropped whatever the pressure.
  const candidates = (await db.messages.toArray())
    .filter((m) => Boolean(m.mediaId) && !m.mediaEvicted)
    .filter(olderThan(KEEP_RECENT_DAYS))
    .sort(oldestFirst);

  // 1 — retained channels, oldest first. Free, because the server still has them.
  const rung1 = candidates.filter((m) => isRetained(m.convId));
  // 2 — retained channels the user has since left behave the same way; they are simply older.
  // 3 — direct messages past the original-retention window. The only copy, so it is last and
  //     it is counted separately so the caller can say so.
  const rung3 = candidates.filter((m) => !isRetained(m.convId) && olderThan(KEEP_ORIGINAL_DAYS)(m));

  for (const message of [...rung1, ...rung3]) {
    if (report.ranBecause === 'pressure' && report.freedBytes >= targetToFree(usage)) break;
    if (!message.mediaId) continue;

    await mediaStore.evict(message.mediaId);
    // The row stays and the thumbnail stays: the thread still reads as a thread rather than as
    // a column of grey boxes, and a retained original comes back when it is tapped.
    await db.messages.update(message.msgId, { mediaEvicted: true });

    report.freedBytes += message.mediaSizeBytes ?? 0;
    report.evicted += 1;
    if (!isRetained(message.convId)) report.unbackedEvicted += 1;
  }

  return report;
}

/** Free enough to get back under half the quota, so this is not run again in a minute. */
function targetToFree(usage: StorageUsage): number {
  return Math.max(0, usage.used - usage.quota * 0.5);
}

/** Called on start and after each media write. Cheap when there is nothing to do. */
export async function evictIfNeeded(): Promise<EvictionReport | null> {
  const usage = await mediaStore.usage();
  if (usage.quota === 0) return null;
  if (usage.used / usage.quota <= EVICT_ABOVE) return null;
  return runEviction(usage);
}
