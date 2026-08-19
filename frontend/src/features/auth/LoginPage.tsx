import { useState, type FormEvent } from 'react';
import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { apiErrorDetail } from '../../shared/apiClient';

export default function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
      navigate('/', { replace: true });
    } catch (failure) {
      setError(apiErrorDetail(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, width: '100%', maxWidth: 400 }}>
        {/* The lockup carries its own paper-white bed (#FFFDF7), so it belongs on the card
            rather than on the ground behind it. The "BY NIRMAN" endorsement line is never
            cropped — it is what tells somebody this is the same company as the app they
            already sign into. */}
        <Box
          component="img"
          src="/brand/logo-lockup.png"
          alt="Sandesh by Nirman"
          sx={{ width: 240, height: 'auto', display: 'block', mx: 'auto', mb: 3 }}
        />
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Sign in with your Nirman username and password. There is no separate account.
        </Typography>

        <form onSubmit={submit}>
          <Stack spacing={2}>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField
              label="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              required
            />
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
            <Button type="submit" variant="contained" color="secondary" disabled={busy}>
              {busy ? 'Signing in…' : 'Sign in'}
            </Button>
          </Stack>
        </form>
      </Paper>
    </Box>
  );
}
