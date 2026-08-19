import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';

export interface ConversationView {
  id: string;
  kind: 'SITE' | 'PROJECT';
  name: string;
  subtitle?: string;
  members: { userId: string; fullName: string; username: string }[];
}

export interface PersonView {
  userId: string;
  fullName: string;
  username: string;
}

export function useConversations() {
  return useQuery({
    queryKey: ['conversations'],
    queryFn: async () => (await apiClient.get<ConversationView[]>('/conversations')).data,
  });
}

/** Org-wide, so an accountant or a new hire with no posting can still find somebody. */
export function useDirectory(query: string) {
  return useQuery({
    queryKey: ['directory', query],
    enabled: query.trim().length >= 2,
    queryFn: async () =>
      (await apiClient.get<PersonView[]>('/conversations/directory', { params: { q: query } })).data,
  });
}
