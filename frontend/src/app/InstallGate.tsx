import { useEffect, useState, type ReactNode } from 'react';
import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material';
import { isInstalled, requestPersistentStorage } from '../shared/push';
import { HAND, tokens } from './theme';

/**
 * Installing is a requirement here, not an invitation.
 *
 * Two facts make it one. The device is the only copy of a delivered message, and browsers evict
 * IndexedDB under storage pressure — silently; an installed PWA granted persistent storage is
 * the only configuration where that does not happen. And on iOS, notification permission cannot
 * even be requested from a Safari tab: only from inside an app opened from the Home Screen.
 *
 * So a tab gets one screen, and it explains why rather than nagging. `?browser=1` is a deliberate
 * escape hatch for development and for anybody debugging on a desktop.
 */
export default function InstallGate({ children }: { children: ReactNode }) {
  const [dismissed, setDismissed] = useState(
    () => new URLSearchParams(window.location.search).has('browser'),
  );
  const [prompt, setPrompt] = useState<BeforeInstallPromptEvent | null>(null);

  useEffect(() => {
    if (isInstalled()) void requestPersistentStorage();
  }, []);

  useEffect(() => {
    function capture(event: Event) {
      event.preventDefault();
      setPrompt(event as BeforeInstallPromptEvent);
    }
    window.addEventListener('beforeinstallprompt', capture);
    return () => window.removeEventListener('beforeinstallprompt', capture);
  }, []);

  if (isInstalled() || dismissed) return <>{children}</>;

  const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);

  return (
    <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, maxWidth: 420 }}>
        <Typography variant="h1" sx={{ fontFamily: HAND, mb: 1 }}>
          Add Sandesh to your phone
        </Typography>

        <Typography variant="body1" sx={{ mb: 2 }}>
          Sandesh keeps your conversations on this phone and nowhere else. Added to the Home
          Screen it can hold them safely and notify you when something arrives. Left in the
          browser it can do neither.
        </Typography>

        {isIos ? (
          <Alert severity="info" sx={{ mb: 2 }}>
            Tap <strong>Share</strong>, then <strong>Add to Home Screen</strong>, then open
            Sandesh from your Home Screen. On iPhone, notifications only work that way.
          </Alert>
        ) : (
          <Alert severity="info" sx={{ mb: 2 }}>
            Tap the menu (⋮) and choose <strong>Install app</strong> or{' '}
            <strong>Add to Home screen</strong>.
          </Alert>
        )}

        <Stack spacing={1}>
          {prompt && (
            <Button
              variant="contained"
              color="secondary"
              onClick={async () => {
                await prompt.prompt();
                setPrompt(null);
              }}
            >
              Install
            </Button>
          )}
          <Button
            variant="text"
            sx={{ color: tokens.muted }}
            onClick={() => setDismissed(true)}
          >
            Continue in the browser anyway
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
}
