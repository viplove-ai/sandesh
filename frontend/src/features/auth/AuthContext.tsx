import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { setAccessToken } from '../../shared/apiClient';
import {
  clearCachedUser,
  lastUserStorage,
  readCachedUser,
  refreshSession,
  signIn as postLogin,
  tokenStorage,
  type SessionUser,
} from '../../shared/session';
import { forgetEverything } from '../../offline/db';
import { messageStream } from '../../shared/stream';
import { evictIfNeeded } from '../../offline/eviction';
import { queryClient } from '../../app/queryClient';

interface AuthState {
  user: SessionUser | null;
  status: 'loading' | 'signedIn' | 'signedOut';
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [status, setStatus] = useState<AuthState['status']>('loading');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!tokenStorage.getRefreshToken()) {
        setStatus('signedOut');
        return;
      }
      try {
        const session = await refreshSession();
        if (cancelled) return;
        setAccessToken(session.accessToken);
        setUser(session.user);
        setStatus('signedIn');
        messageStream.start();
        // Once per start, and cheap when there is nothing to do. Making room before the browser
        // makes it for us: hitting the quota raises QuotaExceededError in the middle of a write,
        // and a half-written record is worse than a missing one.
        void evictIfNeeded();
      } catch {
        // A refusal signs the device out. A network failure leaves the stored token where it is
        // and opens on the cached profile — a supervisor in a valley still needs the app.
        const cached = readCachedUser();
        if (cancelled) return;
        if (cached) {
          setUser(cached);
          setStatus('signedIn');
        } else {
          setStatus('signedOut');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    const session = await postLogin(username, password);

    // A site handset changes hands. If this is a different person, everything the last one had
    // on this device goes — the conversations are the record, and they are not transferable.
    if (lastUserStorage.get() && lastUserStorage.get() !== session.user.id) {
      await forgetEverything();
    }
    lastUserStorage.set(session.user.id);
    queryClient.clear();

    setAccessToken(session.accessToken);
    setUser(session.user);
    setStatus('signedIn');
    messageStream.start();
  }, []);

  const signOut = useCallback(async () => {
    messageStream.stop();
    setAccessToken(null);
    tokenStorage.clear();
    clearCachedUser();
    setUser(null);
    setStatus('signedOut');
    // After the state is dropped, not before: clearing under a mounted list is a refetch nobody
    // is waiting for, sent with a token that has just been thrown away.
    queryClient.clear();
  }, []);

  const value = useMemo(
    () => ({ user, status, signIn, signOut }),
    [user, status, signIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth outside AuthProvider');
  return context;
}
