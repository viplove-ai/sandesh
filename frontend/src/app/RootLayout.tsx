import { Navigate, Outlet } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { useAuth } from '../features/auth/AuthContext';

export default function RootLayout() {
  const { status } = useAuth();

  if (status === 'loading') {
    return (
      <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center' }}>
        <CircularProgress />
      </Box>
    );
  }
  if (status === 'signedOut') return <Navigate to="/login" replace />;
  return <Outlet />;
}
