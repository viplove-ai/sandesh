import { useState } from 'react';
import { Alert, Button, Paper, Stack, Typography } from '@mui/material';
import { performAction, type CardAction } from './actions';
import { tokens } from '../../app/theme';

/**
 * A card from Nirman — an expense waiting on an approval, a DPR waiting on a verification.
 *
 * Rendered distinctly from a message on purpose: it is not somebody talking, it is a record
 * waiting on you, and a bubble that looks like a colleague's would be read as one.
 */
export default function SystemCard({
  title,
  body,
  actions,
}: {
  title: string;
  body: string;
  actions: CardAction[];
}) {
  const [outcome, setOutcome] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run(action: CardAction) {
    if (action.confirm && !window.confirm(action.confirm)) return;
    setBusy(true);
    const result = await performAction(action);
    setOutcome(result.message);
    // Both a success and an "already handled elsewhere" retire the card. The second is not a
    // failure — it is the same record reaching the same end by another route.
    setDone(result.ok);
    setBusy(false);
  }

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 1.5,
        borderColor: tokens.annotation,
        bgcolor: tokens.surface,
        maxWidth: '92%',
        alignSelf: 'flex-start',
      }}
    >
      <Typography variant="overline" sx={{ display: 'block', color: tokens.annotation }}>
        Nirman
      </Typography>
      <Typography variant="body1" sx={{ fontWeight: 700 }}>
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {body}
      </Typography>

      {outcome && (
        <Alert severity={done ? 'success' : 'error'} sx={{ mb: 1 }}>
          {outcome}
        </Alert>
      )}

      {!done && actions.length > 0 && (
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
          {actions.map((action) => (
            <Button
              key={action.label}
              size="small"
              variant={action.primary ? 'contained' : 'outlined'}
              color={action.primary ? 'secondary' : 'primary'}
              disabled={busy}
              onClick={() => void run(action)}
            >
              {action.label}
            </Button>
          ))}
        </Stack>
      )}
    </Paper>
  );
}
