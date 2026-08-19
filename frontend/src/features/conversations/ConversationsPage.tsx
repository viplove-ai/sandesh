import {
  Alert, Box, CircularProgress, List, ListItemButton, ListItemText, Stack, Typography,
} from '@mui/material';
import SettingsIcon from '@mui/icons-material/Settings';
import { IconButton } from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { useNavigate } from 'react-router-dom';
import { useConversations } from './api';
import { useAuth } from '../auth/AuthContext';
import { unreadCounts } from '../../offline/db';
import { tokens } from '../../app/theme';

/** A stable default, so the list renders on the first paint rather than after the first query. */
const NOTHING_UNREAD: Record<string, number> = {};

export default function ConversationsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { conversations, isLoading, isError, isEmpty } = useConversations();

  // From the device's own store, live: a message arriving on the stream while this list is on
  // screen moves the badge without a refetch, and the count is right with no signal at all.
  const unread = useLiveQuery(
    () => (user ? unreadCounts(user.id) : Promise.resolve(NOTHING_UNREAD)),
    [user?.id],
    NOTHING_UNREAD,
  );

  return (
    <Box sx={{ p: 2, maxWidth: 720, mx: 'auto' }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between">
        <Stack direction="row" alignItems="center" spacing={1}>
          {/* The mark on its own, not the lockup cropped: the lockup's endorsement line is the
              half that matters, so it is shown whole or not at all. */}
          <Box
            component="img"
            src="/brand/icon-512.png"
            alt=""
            sx={{
              width: 34,
              height: 34,
              borderRadius: '9px 6px 10px 7px/7px 10px 6px 9px',
            }}
          />
          <Typography variant="overline" color="text.secondary">
            Conversations
          </Typography>
        </Stack>
        <IconButton onClick={() => navigate('/settings')} aria-label="Settings">
          <SettingsIcon />
        </IconButton>
      </Stack>

      {isLoading && <CircularProgress size={24} sx={{ mt: 2 }} />}
      {isError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          Could not load your conversations.
        </Alert>
      )}

      {/*
        A person with no posting — an accountant, an admin, a new hire before they are put on a
        site — has no site channels. Announcements and Nirman's channel are still here, so the
        list is never blank, but it says why the sites are missing.
      */}
      {isEmpty && (
        <Alert severity="info" sx={{ mt: 2 }}>
          <strong>You are not posted to a site yet.</strong> Your site&rsquo;s conversation will
          appear here when you are.
        </Alert>
      )}

      <List>
        {conversations.map((conversation) => {
          const count = unread[conversation.convId] ?? 0;
          return (
            <ListItemButton
              key={conversation.convId}
              onClick={() => navigate(`/c/${encodeURIComponent(conversation.convId)}`)}
              sx={{ borderBottom: `1px solid ${tokens.line}`, gap: 1 }}
            >
              <ListItemText
                primary={conversation.name}
                secondary={conversation.subtitle}
                // The name carries the weight as well as the badge. A count alone is a small
                // orange dot on a cracked screen in sunlight; the bolder line is what is
                // actually read at arm's length.
                primaryTypographyProps={{ fontWeight: count > 0 ? 700 : 400 }}
              />
              {count > 0 && <UnreadBadge count={count} name={conversation.name} />}
            </ListItemButton>
          );
        })}
      </List>
    </Box>
  );
}

/**
 * The count of messages nobody on this device has read yet.
 *
 * <p>Written out for the screen reader rather than left as a bare numeral, and capped at 99+ so
 * a channel left alone for a month does not widen the row it sits in.</p>
 */
function UnreadBadge({ count, name }: { count: number; name: string }) {
  return (
    <Box
      aria-label={`${count} unread in ${name}`}
      sx={{
        flexShrink: 0,
        minWidth: 26,
        height: 26,
        px: 0.75,
        borderRadius: '13px',
        display: 'grid',
        placeItems: 'center',
        bgcolor: tokens.signal,
        color: tokens.surface,
        fontFamily: '"IBM Plex Mono", monospace',
        fontSize: '0.8rem',
        fontWeight: 700,
        lineHeight: 1,
      }}
    >
      {count > 99 ? '99+' : count}
    </Box>
  );
}
