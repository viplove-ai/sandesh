import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Alert, Box, IconButton, Paper, Stack, TextField, Typography } from '@mui/material';
import SendIcon from '@mui/icons-material/Send';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import AttachFileIcon from '@mui/icons-material/AttachFile';
import DescriptionIcon from '@mui/icons-material/Description';
import { useLiveQuery } from 'dexie-react-hooks';
import { useNavigate, useParams } from 'react-router-dom';
import { db } from '../../offline/db';
import { openMedia as resolveMedia, sendDocument, sendImage, sendText } from './api';
import {
  ACCEPT_ATTRIBUTE, describeBytes, isSendableDocument, isSendableImage,
} from '../../shared/uploads';
import { useAuth } from '../auth/AuthContext';
import { apiErrorDetail } from '../../shared/apiClient';
import { tokens } from '../../app/theme';
import SystemCard from './SystemCard';
import { parseActions } from './actions';

export default function ThreadPage() {
  const { convId = '' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const filePicker = useRef<HTMLInputElement>(null);

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

  async function attach(file: File | undefined) {
    if (!file || !user) return;
    setError(null);
    if (!isSendableImage(file) && !isSendableDocument(file)) {
      // Name what is allowed rather than failing silently — a supervisor who tries something
      // and gets nothing concludes the app is broken.
      setError('Photographs, PDFs and Office documents only.');
      return;
    }
    try {
      if (isSendableImage(file)) {
        await sendImage(convId, file, { id: user.id, fullName: user.fullName });
      } else {
        await sendDocument(convId, file, { id: user.id, fullName: user.fullName });
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : apiErrorDetail(failure));
    }
  }

  async function openMedia(message: {
    mediaId?: string;
    convId: string;
    mediaEvicted?: boolean;
  }) {
    try {
      window.open(await resolveMedia(message), '_blank', 'noopener,noreferrer');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : apiErrorDetail(failure));
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
          {convId.startsWith('sys:')
            ? 'Nirman'
            : convId.startsWith('org:')
              ? 'Announcements'
              : convId.startsWith('dm:')
                ? 'Direct message'
                : 'Site conversation'}
        </Typography>
      </Stack>

      <Box sx={{ flex: 1, overflowY: 'auto', p: 2, bgcolor: tokens.paper }}>
        {messages?.length === 0 && (
          <Typography variant="body2" color="text.secondary" align="center" sx={{ mt: 4 }}>
            Nothing here yet.
          </Typography>
        )}
        <Stack spacing={1}>
          {messages?.map((message) =>
            message.kind === 'SYSTEM' ? (
              // A record waiting on you, not somebody talking. Rendered as a card rather than a
              // bubble so it does not read as a colleague's message.
              <SystemCard
                key={message.msgId}
                title={(message.body ?? '').split('\n')[0]}
                body={(message.body ?? '').split('\n').slice(1).join('\n')}
                actions={parseActions(message.actions)}
              />
            ) : (
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
              {message.kind === 'IMAGE' && message.thumbnail && (
                <Box
                  component="img"
                  src={message.thumbnail}
                  alt={message.mediaFileName ?? 'Photograph'}
                  onClick={() => void openMedia(message)}
                  sx={{
                    display: 'block',
                    maxWidth: '100%',
                    borderRadius: '8px',
                    cursor: message.mediaId ? 'pointer' : 'default',
                    mb: message.body ? 0.75 : 0,
                  }}
                />
              )}
              {message.kind === 'DOC' && (
                <Stack
                  direction="row"
                  spacing={1}
                  alignItems="center"
                  onClick={() => void openMedia(message)}
                  sx={{ cursor: message.mediaId ? 'pointer' : 'default', mb: message.body ? 0.75 : 0 }}
                >
                  <DescriptionIcon fontSize="small" sx={{ color: tokens.annotation }} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
                      {message.mediaFileName ?? 'Document'}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {[describeBytes(message.mediaSizeBytes), 'Tap to open']
                        .filter(Boolean)
                        .join(' · ')}
                    </Typography>
                  </Box>
                </Stack>
              )}
              {message.body && (
                <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                  {message.body}
                </Typography>
              )}
              {message.state === 'failed' && (
                <Typography variant="caption" sx={{ color: tokens.stop }}>
                  Not sent
                </Typography>
              )}
            </Paper>
            ),
          )}
        </Stack>
        <div ref={bottom} />
      </Box>

      {error && <Alert severity="error" sx={{ borderRadius: 0 }}>{error}</Alert>}

      {/* Nirman's channel is written to by Nirman. A composer that looks usable and is not
          is worse than no composer. */}
      {!convId.startsWith('sys:') && (
      <Box
        component="form"
        onSubmit={submit}
        sx={{ display: 'flex', gap: 1, p: 1, borderTop: `1.6px solid ${tokens.ink}`, bgcolor: tokens.surface }}
      >
        <input
          ref={filePicker}
          type="file"
          accept={ACCEPT_ATTRIBUTE}
          hidden
          onChange={(event) => {
            void attach(event.target.files?.[0]);
            event.target.value = '';   // so the same file can be picked twice running
          }}
        />
        <IconButton
          onClick={() => filePicker.current?.click()}
          aria-label="Attach a photograph or document"
          sx={{ minWidth: 48 }}
        >
          <AttachFileIcon />
        </IconButton>
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
      )}
    </Box>
  );
}
