import { describe, expect, it } from 'vitest';

/**
 * The VAPID key conversion, exercised through the same path the browser takes.
 *
 * Worth a test because it fails silently: a mis-decoded key produces a subscription the push
 * service accepts and the server can never deliver to, and the symptom is "notifications don't
 * work on this phone" with nothing in any log.
 */
function toUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url.padEnd(base64Url.length + ((4 - (base64Url.length % 4)) % 4), '=');
  const binary = atob(padded.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

describe('the VAPID application server key', () => {
  it('decodes base64url, which is not base64', () => {
    // '-' and '_' stand in for '+' and '/'. Decoding it as plain base64 either throws or
    // silently yields different bytes.
    const bytes = toUint8Array('-_8');
    expect(Array.from(bytes)).toEqual([251, 255]);
  });

  it('pads a string whose length is not a multiple of four', () => {
    expect(Array.from(toUint8Array('QQ'))).toEqual([65]);
    expect(Array.from(toUint8Array('QUJD'))).toEqual([65, 66, 67]);
  });

  it('produces a real 65-byte P-256 point for a full-length key', () => {
    // What `web-push generate-vapid-keys` emits: 65 bytes, uncompressed, leading 0x04.
    const key = 'B' + 'A'.repeat(86);
    const bytes = toUint8Array(key);
    expect(bytes.length).toBe(65);
    expect(bytes.buffer).toBeInstanceOf(ArrayBuffer);
  });
});
