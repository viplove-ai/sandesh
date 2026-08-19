import { useQuery } from '@tanstack/react-query';
import { useLiveQuery } from 'dexie-react-hooks';
import { apiClient } from '../../shared/apiClient';
import { readConversations, saveConversations, type StoredConversation } from '../../offline/db';

export interface ConversationView {
  id: string;
  kind: 'ORG' | 'SYSTEM' | 'SITE' | 'PROJECT';
  name: string;
  subtitle?: string;
  members: { userId: string; fullName: string; username: string }[];
}

export interface ConversationList {
  conversations: StoredConversation[];
  /** Nothing to show yet and the server has not answered. */
  isLoading: boolean;
  /** The server could not be asked and there is nothing stored to show instead. */
  isError: boolean;
  /** The server answered, and this person has no channels at all. */
  isEmpty: boolean;
}

/**
 * The list, read from the device and refreshed from the server.
 *
 * <p>That order matters. Membership is derived from Nirman on every call, so the server is the
 * authority — but it is not always reachable, and the list is what every thread is reached
 * through. Read straight from the query it was network-only: a sign-out cleared it, and a phone
 * signing back in without signal opened on an empty screen with every message still sitting in
 * the store behind it. Now the answer is written through to Dexie and the screen renders from
 * there, so a bad morning costs a stale list rather than the whole app.</p>
 */
export function useConversations(): ConversationList {
  const query = useQuery({
    queryKey: ['conversations'],
    queryFn: async () => {
      const { data } = await apiClient.get<ConversationView[]>('/conversations');
      await saveConversations(data);
      return data;
    },
  });

  const stored = useLiveQuery(() => readConversations(), [], undefined);
  const conversations = stored ?? [];

  return {
    conversations,
    isLoading: stored === undefined || (conversations.length === 0 && query.isPending),
    // A stale list is not an error worth a banner — it is the app working as intended.
    isError: query.isError && conversations.length === 0,
    isEmpty: query.isSuccess && conversations.length === 0,
  };
}
