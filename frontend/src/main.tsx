import React from 'react';
import ReactDOM from 'react-dom/client';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { theme } from './app/theme';
import { queryClient } from './app/queryClient';
import { router } from './app/router';
import { applyDayAccent } from './app/dayAccent';
import { AppUpdateProvider } from './shared/appUpdate';
import { UpdatePrompt } from './shared/UpdatePrompt';

applyDayAccent();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/*
        Around both the app and the offer below, because it is the one owner of the service
        worker registration: the snackbar offers a waiting version and the settings screen asks
        for one, and two registrations would have the offer and the asking talking to different
        workers.
      */}
      <AppUpdateProvider>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
        {/*
          Outside the router on purpose: a waiting version is worth offering on the login screen
          and in a thread alike, and it has no business changing per route.
        */}
        <UpdatePrompt />
      </AppUpdateProvider>
    </ThemeProvider>
  </React.StrictMode>,
);
