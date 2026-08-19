/**
 * Where the bytes of a photograph or a document live on the device.
 *
 * Behind an interface for the same reason as push: today this is the Origin Private File
 * System; if the pilot shows device storage is the binding constraint, the same seam takes
 * `@capacitor/filesystem` and nothing else in the app changes. Nothing outside this file
 * touches an OPFS handle.
 *
 * OPFS rather than IndexedDB, and that is not a preference. Large Blobs in IndexedDB are
 * serialised through structured clone on every read and have a long history of trouble in
 * WebKit specifically. Dexie keeps the message row and the file name; the bytes live here.
 */

export interface MediaStore {
  put(id: string, bytes: Blob): Promise<void>;
  get(id: string): Promise<Blob | null>;
  has(id: string): Promise<boolean>;
  evict(id: string): Promise<void>;
  usage(): Promise<StorageUsage>;
}

export interface StorageUsage {
  /** Bytes the origin is using, across everything — IndexedDB included, not only media. */
  used: number;
  /** What the browser will allow before it starts refusing writes. */
  quota: number;
  /** True once the browser has promised not to evict this origin on its own. */
  persisted: boolean;
}

const DIRECTORY = 'media';

async function directory(): Promise<FileSystemDirectoryHandle | null> {
  if (!navigator.storage?.getDirectory) return null;
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle(DIRECTORY, { create: true });
}

export const opfsMediaStore: MediaStore = {
  async put(id: string, bytes: Blob): Promise<void> {
    const dir = await directory();
    if (!dir) return;
    const file = await dir.getFileHandle(id, { create: true });
    const writable = await file.createWritable();
    try {
      await writable.write(bytes);
    } finally {
      // Closing is what commits it. A throw between write and close leaves a partial file, so
      // the close belongs in a finally and the caller re-fetches if the read comes back short.
      await writable.close();
    }
  },

  async get(id: string): Promise<Blob | null> {
    const dir = await directory();
    if (!dir) return null;
    try {
      const handle = await dir.getFileHandle(id);
      return await handle.getFile();
    } catch {
      // Evicted, or never held here. Both mean "ask the server", and for a retained channel
      // that works — see the eviction ladder.
      return null;
    }
  },

  async has(id: string): Promise<boolean> {
    return (await opfsMediaStore.get(id)) !== null;
  },

  async evict(id: string): Promise<void> {
    const dir = await directory();
    if (!dir) return;
    await dir.removeEntry(id).catch(() => undefined);
  },

  async usage(): Promise<StorageUsage> {
    const estimate = (await navigator.storage?.estimate?.()) ?? {};
    return {
      used: estimate.usage ?? 0,
      quota: estimate.quota ?? 0,
      persisted: (await navigator.storage?.persisted?.().catch(() => false)) ?? false,
    };
  },
};

export const mediaStore: MediaStore = opfsMediaStore;
