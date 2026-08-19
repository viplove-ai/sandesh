import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';

export interface ConversationView {
  id: string;
  kind: 'ORG' | 'SYSTEM' | 'SITE' | 'PROJECT';
  name: string;
  subtitle?: string;
  members: { userId: string; fullName: string; username: string }[];
}

export function useConversations() {
  return useQuery({
    queryKey: ['conversations'],
    queryFn: async () => (await apiClient.get<ConversationView[]>('/conversations')).data,
  });
}
