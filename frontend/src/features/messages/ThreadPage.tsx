import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Alert, Box, IconButton, Paper, Stack, TextField, Typography } from '@mui/material';
import SendIcon from '@mui/icons-material/Send';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { useLiveQuery } from 'dexie-react-hooks';
import { useNavigate, useParams } from 'react-router-dom';
import { db } from '../../offline/db';
import { sendText } from './api';
import { useAuth } from '../auth/AuthContext';
import { apiErrorDetail } from '../../shared/apiClient';
import { tokens } from '../../app/theme';

export default function ThreadPage() {
  const { convId = '' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  // Straight from the device's own store. Messages are never fetched over HTTP — they arrive on
  // the stream and are read from here, which is what makes the thread render with no signal.
  const messages = useLiveQuery(
    () => db.messages.where('convId').equals(convId).sortBy('sentAt'),
    [convId],
    [],
  );

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages?.length]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const body = draft.trim();
    if (!body || !user) return;
    setDraft('');
    setError(null);
    try {
      await sendText(convId, body, { id: user.id, fullName: user.fullName });
    } catch (failure) {
      setError(apiErrorDetail(failure));
    }
  }

  return (
    // dvh, not vh: the composer must not be pushed under the phone's keyboard.
    <Box sx={{ height: '100dvh', display: 'flex', flexDirection: 'column' }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1}
        sx={{ p: 1, borderBottom: `1.6px solid ${tokens.ink}`, bgcolor: tokens.surface }}
      >
        <IconButton onClick={() => navigate('/')} aria-label="Back to conversations">
          <ArrowBackIcon />
        </IconButton>
        <Typography variant="h3" noWrap>
          {convId.startsWith('dm:') ? 'Direct message' : 'Site conversation'}
        </Typography>
      </Stack>

      <Box sx={{ flex: 1, overflowY: 'auto', p: 2, bgcolor: tokens.paper }}>
        {messages?.length === 0 && (
          <Typography variant="body2" color="text.secondary" align="center" sx={{ mt: 4 }}>
            Nothing here yet.
          </Typography>
        )}
        <Stack spacing={1}>
          {messages?.map((message) => (
            <Paper
              key={message.msgId}
              elevation={0}
              sx={{
                alignSelf: message.mine ? 'flex-end' : 'flex-start',
                maxWidth: '80%',
                p: 1.25,
                // A plain radius, deliberately: Nirman's drawn irregular edge is beautiful on a
                // register card and is visual noise plus paint cost on two hundred bubbles.
                borderRadius: '12px',
                border: `1px solid ${tokens.line}`,
                bgcolor: message.mine ? 'var(--accent-50, #FDF0E7)' : tokens.surface,
                opacity: message.state === 'pending' ? 0.6 : 1,
              }}
            >
              {!message.mine && (
                <Typography variant="overline" sx={{ display: 'block', color: tokens.annotation }}>
                  {message.fromName}
                </Typography>
              )}
              <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                {message.body}
              </Typography>
              {message.state === 'failed' && (
                <Typography variant="caption" sx={{ color: tokens.stop }}>
                  Not sent
                </Typography>
              )}
            </Paper>
          ))}
        </Stack>
        <div ref={bottom} />
      </Box>

      {error && <Alert severity="error" sx={{ borderRadius: 0 }}>{error}</Alert>}

      <Box
        component="form"
        onSubmit={submit}
        sx={{ display: 'flex', gap: 1, p: 1, borderTop: `1.6px solid ${tokens.ink}`, bgcolor: tokens.surface }}
      >
        <TextField
          placeholder="Message"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          size="small"
          autoComplete="off"
        />
        <IconButton type="submit" color="secondary" aria-label="Send" sx={{ minWidth: 48 }}>
          <SendIcon />
        </IconButton>
      </Box>
    </Box>
  );
}
