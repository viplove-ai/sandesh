import { apiClient } from './apiClient';

/**
 * Getting a phone to ring.
 *
 * Behind an interface on purpose. Today this is Web Push; if the pilot shows that OEM battery
 * management eats too many of them, the same seam takes `@capacitor/push-notifications` and
 * nothing else in the app changes. Nothing outside this file touches `pushManager`.
 */
export interface PushProvider {
  supported(): boolean;
  permission(): NotificationPermission;
  subscribe(): Promise<void>;
  unsubscribe(): Promise<void>;
}

export interface PushHealth {
  pushConfiguredOnServer: boolean;
  registeredDevices: number;
  vapidPublicKey: string | null;
}

export async function readHealth(): Promise<PushHealth> {
  return (await apiClient.get<PushHealth>('/push/health')).data;
}

export async function sendTestNotification(): Promise<void> {
  await apiClient.post('/push/test');
}

/**
 * The VAPID key arrives as base64url and the browser wants raw bytes.
 *
 * Backed by an explicit ArrayBuffer rather than `Uint8Array.from`: since TypeScript 5.6 the
 * array is generic over its buffer, and the inferred `ArrayBufferLike` includes SharedArrayBuffer,
 * which `applicationServerKey` will not accept.
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

function encodeKey(buffer: ArrayBuffer | null): string {
  if (!buffer) return '';
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

export const webPush: PushProvider = {
  supported(): boolean {
    return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  },

  permission(): NotificationPermission {
    return 'Notification' in window ? Notification.permission : 'denied';
  },

  async subscribe(): Promise<void> {
    if (!webPush.supported()) throw new Error('This browser cannot receive notifications.');

    const health = await readHealth();
    if (!health.vapidPublicKey) {
      throw new Error('Notifications are not switched on for this deployment yet.');
    }

    // Must be called from a user gesture, and on iOS only from inside an installed PWA. Both
    // constraints are why the install screen comes first — see the install gate.
    const granted = await Notification.requestPermission();
    if (granted !== 'granted') {
      throw new Error('Notifications were not allowed. You can change this in site settings.');
    }

    const registration = await navigator.serviceWorker.ready;
    const existing = await registration.pushManager.getSubscription();
    // Re-subscribing on a changed key silently produces a subscription the server cannot push
    // to, so an old one is discarded rather than reused.
    if (existing) await existing.unsubscribe();

    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,   // required, and iOS enforces it — every push must show something
      applicationServerKey: toUint8Array(health.vapidPublicKey),
    });

    await apiClient.post('/push/subscribe', {
      endpoint: subscription.endpoint,
      p256dh: encodeKey(subscription.getKey('p256dh')),
      auth: encodeKey(subscription.getKey('auth')),
    });
  },

  async unsubscribe(): Promise<void> {
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (subscription) await subscription.unsubscribe();
    await apiClient.delete('/push/subscribe');
  },
};

/**
 * Whether this device can hold the conversations it is about to be given.
 *
 * The device is the only copy of a delivered message, and browsers evict IndexedDB under
 * storage pressure — silently. An installed PWA that has been granted persistent storage is the
 * only configuration where that does not happen, which is why installing is a requirement here
 * rather than an invitation.
 */
export async function requestPersistentStorage(): Promise<boolean> {
  if (!navigator.storage?.persist) return false;
  if (await navigator.storage.persisted()) return true;
  return navigator.storage.persist();
}

export function isInstalled(): boolean {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    // Safari's own flag; it does not report the standalone display mode.
    (window.navigator as unknown as { standalone?: boolean }).standalone === true
  );
}
