import imageCompression from 'browser-image-compression';

/**
 * What a photograph is compressed to before it leaves the phone.
 *
 * The numbers are Nirman's and the reasoning carries over unchanged: a supervisor on a 2G edge
 * uploads what the device sends, so a four-megabyte original is four megabytes of his morning
 * whatever the server does with it afterwards. 1600 pixels on the long edge is enough to read a
 * crack in a beam or a number on a delivery note; 700 KB is the ceiling the compressor works
 * down to from there.
 *
 * It matters more here than it did there. Media outweighs text in the device store by roughly
 * two hundred to one, so this constant is the single biggest lever on whether a ₹9,000 handset
 * still has room in six months.
 */
export const PHOTO_MAX_BYTES = 700 * 1024;
export const PHOTO_MAX_EDGE_PX = 1600;

/** Small enough to keep forever beside the text, once the original has been evicted. */
export const THUMBNAIL_MAX_EDGE_PX = 320;

const OUTPUT_TYPE = 'image/jpeg';

export interface PreparedPhoto {
  blob: Blob;
  fileName: string;
  contentType: string;
  /** A data URL, kept in Dexie for as long as the message. */
  thumbnail: string;
  originalBytes: number;
}

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/** What a site actually exchanges. Every one has a magic number the server re-checks. */
const DOCUMENT_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
];

export const ACCEPT_ATTRIBUTE = [...IMAGE_TYPES, ...DOCUMENT_TYPES].join(',');

/** Matches the server's cap, so a file that cannot be sent is refused before it is uploaded. */
export const DOCUMENT_MAX_BYTES = 25 * 1024 * 1024;

export function isSendableImage(file: File): boolean {
  return IMAGE_TYPES.includes(file.type);
}

export function isSendableDocument(file: File): boolean {
  return DOCUMENT_TYPES.includes(file.type);
}

/**
 * A size somebody can act on. Worth showing beside a document rather than only its name: on a
 * 2G edge the difference between tapping a 200 KB drawing and an 8 MB one is the difference
 * between reading it now and losing ten minutes.
 */
export function describeBytes(bytes: number | undefined): string {
  if (!bytes) return '';
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/**
 * Shrinks a photograph to something a phone can hold and a bad connection can send, and takes a
 * thumbnail on the way past.
 *
 * A file already under the ceiling is not re-encoded: a second JPEG pass over an
 * already-compressed image costs quality and buys nothing.
 */
export async function preparePhoto(file: File): Promise<PreparedPhoto> {
  const originalBytes = file.size;

  const blob =
    file.size <= PHOTO_MAX_BYTES
      ? file
      : await imageCompression(file, {
          maxSizeMB: PHOTO_MAX_BYTES / (1024 * 1024),
          maxWidthOrHeight: PHOTO_MAX_EDGE_PX,
          fileType: OUTPUT_TYPE,
          useWebWorker: true,
        });

  return {
    blob,
    fileName: file.name || 'photo.jpg',
    contentType: blob.type || OUTPUT_TYPE,
    thumbnail: await makeThumbnail(blob),
    originalBytes,
  };
}

/**
 * A data URL small enough to live in the message row.
 *
 * This is what makes the eviction ladder possible: the full-size original can be dropped and
 * re-fetched, and the thread still renders as a thread rather than as a column of grey boxes.
 */
async function makeThumbnail(blob: Blob): Promise<string> {
  const bitmap = await createImageBitmap(blob);
  const scale = Math.min(1, THUMBNAIL_MAX_EDGE_PX / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);

  const context = canvas.getContext('2d');
  if (!context) return '';
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return canvas.toDataURL(OUTPUT_TYPE, 0.7);
}
