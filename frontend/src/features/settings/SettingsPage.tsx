import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Divider, FormControlLabel, IconButton, Stack, Switch, Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { useNavigate } from 'react-router-dom';
import { apiClient, apiErrorDetail } from '../../shared/apiClient';
import {
  isInstalled, readHealth, requestPersistentStorage, sendTestNotification, webPush,
  type PushHealth,
} from '../../shared/push';
import { tokens } from '../../app/theme';
import { mediaStore, type StorageUsage } from '../../offline/mediaStore';
import { runEviction } from '../../offline/eviction';
import { describeBytes } from '../../shared/uploads';
import { useAuth } from '../auth/AuthContext';
import { useAppUpdate, type UpdateCheck } from '../../shared/appUpdate';

interface Settings {
  previewsEnabled: boolean;
  /**
   * Not on this screen for now, and deliberately still carried: the PUT is a full replace, so a
   * payload without these would clear a window somebody had already set.
   */
  quietFrom: string | null;
  quietTo: string | null;
  mutedConvIds: string[];
}

/**
 * The Notification Health screen.
 *
 * Android OEM battery management — Xiaomi, Realme, Oppo, Vivo — is the biggest single threat to
 * this app being useful, and it presents to the user as "I just don't get them". Half a dozen
 * causes look identical from the outside: permission never granted, battery saver, restricted
 * background data, an expired subscription, push not configured on the server at all.
 *
 * So the screen states what is actually known, and the test button splits the chain in half. It
 * is the difference between support diagnosing this over the telephone and the user seeing it.
 */
export default function SettingsPage() {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const { ready: updateReady, install, check, lastCheck } = useAppUpdate();
  const [health, setHealth] = useState<PushHealth | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [usage, setUsage] = useState<StorageUsage | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setHealth(await readHealth());
        setSettings((await apiClient.get<Settings>('/push/settings')).data);
        setPersisted(await navigator.storage?.persisted?.().catch(() => false) ?? false);
        setUsage(await mediaStore.usage());
      } catch (failure) {
        setError(apiErrorDetail(failure));
      }
    })();
  }, []);

  async function enable() {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await webPush.subscribe();
      setHealth(await readHealth());
      setMessage('This phone is registered.');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : apiErrorDetail(failure));
    } finally {
      setBusy(false);
    }
  }

  async function test() {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await sendTestNotification();
      setMessage('Sent. If nothing appears within ten seconds, see the note below.');
    } catch (failure) {
      setError(apiErrorDetail(failure));
    } finally {
      setBusy(false);
    }
  }

  async function save(next: Settings) {
    setSettings(next);
    try {
      await apiClient.put('/push/settings', next);
    } catch (failure) {
      setError(apiErrorDetail(failure));
    }
  }

  const permission = webPush.supported() ? webPush.permission() : 'denied';

  return (
    <Box sx={{ maxWidth: 640, mx: 'auto', pb: 6 }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1}
        sx={{ p: 1, borderBottom: `1.6px solid ${tokens.ink}`, bgcolor: tokens.surface }}
      >
        <IconButton onClick={() => navigate('/')} aria-label="Back">
          <ArrowBackIcon />
        </IconButton>
        <Typography variant="h3">Settings</Typography>
      </Stack>

      <Box sx={{ p: 2 }}>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        {message && <Alert severity="success" sx={{ mb: 2 }}>{message}</Alert>}

        <Typography variant="overline" color="text.secondary">
          Notifications
        </Typography>

        <Stack direction="row" spacing={1} sx={{ my: 1, flexWrap: 'wrap', gap: 1 }}>
          <Chip
            size="small"
            label={isInstalled() ? 'Installed' : 'Not installed'}
            color={isInstalled() ? 'success' : 'warning'}
            variant="outlined"
          />
          <Chip
            size="small"
            label={`Permission: ${permission}`}
            color={permission === 'granted' ? 'success' : 'warning'}
            variant="outlined"
          />
          <Chip
            size="small"
            label={`${health?.registeredDevices ?? 0} device(s) registered`}
            color={(health?.registeredDevices ?? 0) > 0 ? 'success' : 'warning'}
            variant="outlined"
          />
          <Chip
            size="small"
            label={persisted ? 'Storage kept' : 'Storage evictable'}
            color={persisted ? 'success' : 'warning'}
            variant="outlined"
          />
        </Stack>

        {health && !health.pushConfiguredOnServer && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Notifications are not switched on for this deployment yet. Nothing on this phone will
            fix that — it needs a VAPID key pair on the server.
          </Alert>
        )}

        <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
          <Button variant="contained" color="secondary" onClick={enable} disabled={busy}>
            Turn on for this phone
          </Button>
          <Button variant="outlined" onClick={test} disabled={busy}>
            Send a test
          </Button>
        </Stack>

        <Alert severity="warning" sx={{ mb: 2 }}>
          <strong>If the test does not arrive.</strong> On Xiaomi, Redmi, Realme, Oppo and Vivo
          phones the battery saver stops apps being woken. Open Settings → Apps → Chrome →
          Battery and choose <em>No restrictions</em>, and turn off <em>Restrict background
          data</em>. On an iPhone, notifications only work once the app has been added to the
          Home Screen and opened from there.
        </Alert>

        {settings && (
          <>
            <FormControlLabel
              control={
                <Switch
                  checked={settings.previewsEnabled}
                  onChange={(e) => void save({ ...settings, previewsEnabled: e.target.checked })}
                />
              }
              label="Show who sent it and what it says"
            />
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Turn this off and notifications say only &ldquo;New message&rdquo;. Useful on a
              phone other people pick up.
            </Typography>
          </>
        )}

        <Divider sx={{ my: 3 }} />

        <Typography variant="overline" color="text.secondary">
          This phone
        </Typography>
        <Alert severity="info" sx={{ my: 1 }}>
          <strong>This phone is the only copy of your conversations.</strong> Nothing is kept on
          the server once a message has been delivered. If you lose this phone, or clear the
          app&rsquo;s data, these conversations are gone.
        </Alert>
        {!persisted && (
          <Button
            variant="outlined"
            sx={{ mb: 2 }}
            onClick={async () => setPersisted(await requestPersistentStorage())}
          >
            Ask the browser to keep them
          </Button>
        )}

        <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
          Space used
        </Typography>
        {usage && usage.quota > 0 && (
          <>
            <Typography variant="body2" sx={{ fontFamily: 'monospace', mb: 1 }}>
              {describeBytes(usage.used)} of {describeBytes(usage.quota)} (
              {Math.round((usage.used / usage.quota) * 100)}%)
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Photographs are much larger than messages. When space runs short, Sandesh removes
              the full-size copies from site conversations first — those are still on the server
              and come back when you tap them. Photographs in direct messages are removed last,
              because nothing else has a copy.
            </Typography>
            <Button
              variant="outlined"
              sx={{ mb: 2 }}
              onClick={async () => {
                setBusy(true);
                const report = await runEviction(await mediaStore.usage());
                setUsage(await mediaStore.usage());
                setBusy(false);
                setMessage(
                  report.evicted === 0
                    ? 'Nothing to free — everything here is recent or already trimmed.'
                    : `Freed ${describeBytes(report.freedBytes)} from ${report.evicted} file(s)` +
                      (report.unbackedEvicted > 0
                        ? `, including ${report.unbackedEvicted} from direct messages, which are now gone.`
                        : '.'),
                );
              }}
              disabled={busy}
            >
              Free up space now
            </Button>
          </>
        )}

        <Divider sx={{ my: 3 }} />

        {/*
          Beside the sign-out, because that is where somebody goes when the app is misbehaving
          and they are about to try the blunt instrument. A new version installs by itself and
          then waits to be let in, and the waiting is where updates go missing: an installed app
          on a site phone is never closed, so the snackbar can be dismissed once and not seen
          again for a week. This is the same offer, on a screen that does not go anywhere — and
          a way to ask when nothing has been offered at all.
        */}
        <Typography variant="overline" color="text.secondary">
          App version
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 1 }}>
          The app updates itself when it can. If it is behaving oddly, or somebody has told you a
          fix went out, ask for it here.
        </Typography>

        {updateReady ? (
          <Alert severity="info" sx={{ mb: 1 }}>
            A new version is ready. Your conversations stay on this phone.
          </Alert>
        ) : (
          <UpdateCheckNote state={lastCheck} />
        )}

        <Button
          variant={updateReady ? 'contained' : 'outlined'}
          color="secondary"
          disabled={lastCheck === 'CHECKING'}
          onClick={() => void (updateReady ? install() : check())}
          sx={{ display: 'block', mb: 2 }}
        >
          {updateReady
            ? 'Update now'
            : lastCheck === 'CHECKING'
              ? 'Checking…'
              : 'Check for updates'}
        </Button>

        <Divider sx={{ my: 3 }} />
        <Button variant="outlined" color="error" onClick={() => void signOut()}>
          Sign out
        </Button>
      </Box>
    </Box>
  );
}

/** What the last check found, said plainly. A check that cannot tell "there is nothing newer"
 *  from "I could not ask" is a button that teaches people to stop pressing it. */
function UpdateCheckNote({ state }: { state: UpdateCheck }) {
  if (state === 'CURRENT') {
    return <Alert severity="success" sx={{ mb: 1 }}>You have the latest version.</Alert>;
  }
  if (state === 'UNREACHABLE') {
    return (
      <Alert severity="info" sx={{ mb: 1 }}>
        Could not check — there is no connection to the server. Try again when you have signal.
      </Alert>
    );
  }
  if (state === 'UNSUPPORTED') {
    return (
      <Alert severity="info" sx={{ mb: 1 }}>
        This browser cannot check for updates. Close the app and open it again to pick up a new
        version.
      </Alert>
    );
  }
  return null;
}
