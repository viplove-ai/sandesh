import { useState } from 'react';
import {
  Alert, Box, CircularProgress, Divider, List, ListItemButton, ListItemText, Stack,
  TextField, Typography,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useConversations, useDirectory } from './api';
import { useAuth } from '../auth/AuthContext';
import { tokens } from '../../app/theme';
import { directConversationId } from '../../shared/conversationId';

export default function ConversationsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const conversations = useConversations();
  const directory = useDirectory(search);

  const nothingAssigned =
    conversations.isSuccess && conversations.data.length === 0;

  return (
    <Box sx={{ p: 2, maxWidth: 720, mx: 'auto' }}>
      <Typography variant="overline" color="text.secondary">
        Conversations
      </Typography>

      {conversations.isLoading && <CircularProgress size={24} sx={{ mt: 2 }} />}
      {conversations.isError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          Could not load your conversations.
        </Alert>
      )}

      {/*
        A person with no posting — an accountant, an admin, a new hire before they are put on a
        site — has no channels at all. A blank list is how somebody concludes the app is broken
        and goes back to WhatsApp on day one, so it says why and points at the directory.
      */}
      {nothingAssigned && (
        <Alert severity="info" sx={{ mt: 2 }}>
          <strong>You are not posted to a site yet.</strong> Your site&rsquo;s conversation will
          appear here when you are. Meanwhile you can message anyone in your organisation — search
          below.
        </Alert>
      )}

      <List>
        {conversations.data?.map((conversation) => (
          <ListItemButton
            key={conversation.id}
            onClick={() => navigate(`/c/${encodeURIComponent(conversation.id)}`)}
            sx={{ borderBottom: `1px solid ${tokens.line}` }}
          >
            <ListItemText primary={conversation.name} secondary={conversation.subtitle} />
          </ListItemButton>
        ))}
      </List>

      <Divider sx={{ my: 2 }} />

      <Stack spacing={1}>
        <Typography variant="overline" color="text.secondary">
          Find someone
        </Typography>
        <TextField
          placeholder="Name or username"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <List>
          {directory.data
            ?.filter((person) => person.userId !== user?.id)
            .map((person) => (
              <ListItemButton
                key={person.userId}
                onClick={() => {
                  navigate(
                    `/c/${encodeURIComponent(directConversationId(user!.id, person.userId))}`,
                  );
                }}
              >
                <ListItemText primary={person.fullName} secondary={person.username} />
              </ListItemButton>
            ))}
        </List>
      </Stack>
    </Box>
  );
}
