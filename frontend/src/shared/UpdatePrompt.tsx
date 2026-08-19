import { Alert, Button, Snackbar, Stack, Typography } from '@mui/material';
import { useAppUpdate } from './appUpdate';
import { TOUCH_TARGET } from '../app/theme';

/**
 * Offers a waiting version to somebody already using the app.
 *
 * <p>The service worker is registered with `registerType: 'prompt'`, which is a contract with
 * two halves: the new worker installs and then parks, and the app asks before letting it take
 * over. Without this half the worker waits forever — it only activates once every client it
 * controls is gone, and a phone that keeps the app in its task switcher never closes the last
 * one. An installed app then serves the version it was installed with for as long as the
 * supervisor keeps it open, which on a site phone is measured in weeks.</p>
 *
 * <p>Asking rather than reloading is the point. Nothing already sent is at risk — the device's
 * own store is the copy of a conversation and it survives a reload — but the message half typed
 * into the composer does not, and only the person typing it knows whether now is a good
 * moment.</p>
 *
 * <p>The registration itself lives in {@link ./appUpdate AppUpdateProvider}, which is also what
 * looks for a new version in the first place and what the settings screen asks through. This
 * component is the offer and nothing else.</p>
 */
export function UpdatePrompt() {
  const { ready, install, postpone } = useAppUpdate();

  if (!ready) {
    return null;
  }

  return (
    <Snackbar
      open
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      sx={{ maxWidth: 480 }}
    >
      <Alert
        severity="info"
        variant="filled"
        icon={false}
        sx={{ width: '100%', '& .MuiAlert-action': { alignItems: 'center' } }}
        action={
          <Stack direction="row" alignItems="center" spacing={0.5}>
            <Button
              color="inherit"
              size="small"
              /* Reloads the page once the new worker has taken control. */
              onClick={() => void install()}
              sx={{ minHeight: TOUCH_TARGET, whiteSpace: 'nowrap', fontWeight: 700 }}
            >
              Update
            </Button>
            {/*
              "Later" dismisses the offer, not the update: the worker stays waiting and the
              prompt returns next launch. Somebody part-way through a message can say no without
              being asked again on the next keystroke — and Settings carries the same offer for
              whenever they are ready.
            */}
            <Button
              color="inherit"
              size="small"
              onClick={postpone}
              sx={{ minHeight: TOUCH_TARGET, whiteSpace: 'nowrap' }}
            >
              Later
            </Button>
          </Stack>
        }
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          A new version is ready
        </Typography>
        <Typography variant="body2">
          Your conversations stay on this phone.
        </Typography>
      </Alert>
    </Snackbar>
  );
}
