import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The conversation list is derived from Nirman's assignments and changes on the scale of
      // weeks. The messages are not fetched through React Query at all — they come off the
      // stream and live in Dexie.
      staleTime: 60_000,
      retry: 1,
      refetchOnWindowFocus: true,
    },
  },
});
