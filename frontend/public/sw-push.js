/*
 * The push half of the service worker, pulled into the generated Workbox bundle by
 * importScripts. Plain JavaScript on purpose — it is copied verbatim rather than compiled, so
 * there is no build step between what is written here and what runs on the phone.
 *
 * Everything is rendered from the payload and nothing is fetched. iOS revokes a subscription
 * that produces pushes showing nothing, so the attractive "wake up and go read the spool"
 * design is not available: whatever arrives here has to be a complete notification on its own.
 * The stream collects the message when the app is next opened.
 */

self.addEventListener('push', function (event) {
  if (!event.data) return;

  var payload;
  try {
    payload = event.data.json();
  } catch {
    // Never swallow a push without showing something. A few of those on iOS and the
    // subscription is revoked for good.
    payload = { title: 'Sandesh', body: 'New message', deepLink: '/', tag: 'fallback', badge: -1 };
  }

  event.waitUntil(
    self.registration
      .showNotification(payload.title, {
        body: payload.body,
        // Collapses by conversation, so twenty messages in a site channel are one line on the
        // lock screen rather than twenty.
        tag: payload.tag,
        renotify: true,
        icon: '/brand/icon-192.png',
        badge: '/brand/icon-192.png',
        data: { deepLink: payload.deepLink },
      })
      .then(function () {
        if (payload.badge >= 0 && self.navigator && self.navigator.setAppBadge) {
          return self.navigator.setAppBadge(payload.badge).catch(function () {});
        }
      }),
  );
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var deepLink = (event.notification.data && event.notification.data.deepLink) || '/';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clients) {
      // Focus a window that is already open rather than opening a second one — a supervisor
      // with four copies of the app reads the message in whichever one is stale.
      for (var i = 0; i < clients.length; i++) {
        if ('focus' in clients[i]) {
          return clients[i].focus().then(function (client) {
            if (client && client.navigate) return client.navigate(deepLink).catch(function () {});
          });
        }
      }
      return self.clients.openWindow(deepLink);
    }),
  );
});
