import axios from 'axios';

/**
 * Sandesh has no login of its own. It posts to Nirman's existing auth API with the same
 * credentials and gets back the same token pair a Nirman handset gets — so there is no second
 * password, no second onboarding, and no user table on this side at all.
 *
 * Storage split, copied from Nirman for the same reasons: the access token stays in memory
 * (it dies with the tab, so XSS gains fifteen minutes at most) while the refresh token sits in
 * localStorage so an installed PWA survives a restart.
 */

/** Sandesh's own API — messages, conversations, media. */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

/** Nirman's API. Auth only; Sandesh asks it nothing else. */
export const NIRMAN_API_BASE_URL: string =
  import.meta.env.VITE_NIRMAN_API_BASE_URL ?? 'http://localhost:8080/api/v1';

const REFRESH_TOKEN_KEY = 'sandesh.refreshToken';
const SESSION_KEY = 'sandesh.session';
const LAST_USER_KEY = 'sandesh.lastUser';

export interface SessionUser {
  id: string;
  username: string;
  fullName: string;
  roles: string[];
  permissions: string[];
  siteIds: string[];
  allSites: boolean;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  refreshToken: string;
  user: SessionUser;
}

export const tokenStorage = {
  getRefreshToken: (): string | null => localStorage.getItem(REFRESH_TOKEN_KEY),
  setRefreshToken: (token: string): void => localStorage.setItem(REFRESH_TOKEN_KEY, token),
  clear: (): void => localStorage.removeItem(REFRESH_TOKEN_KEY),
};

/**
 * Who was last signed in on this handset, kept across the sign-out that follows — so the next
 * sign-in can answer the one question it has to answer: same person, or the next shift? A site
 * phone changes hands, and the device holds the conversations.
 */
export const lastUserStorage = {
  get: (): string | null => localStorage.getItem(LAST_USER_KEY),
  set: (userId: string): void => localStorage.setItem(LAST_USER_KEY, userId),
};

export function readCachedUser(): SessionUser | null {
  const raw = localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as SessionUser;
    return parsed?.id ? parsed : null;
  } catch {
    return null;
  }
}

export function writeCachedUser(user: SessionUser): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(user));
}

export function clearCachedUser(): void {
  localStorage.removeItem(SESSION_KEY);
}

/** True when a request never got an answer — no signal, DNS gone, or a timeout. */
export function isNetworkFailure(error: unknown): boolean {
  return axios.isAxiosError(error) && !error.response;
}

let refreshInFlight: Promise<TokenResponse> | null = null;

/**
 * Exchanges the stored refresh token for a fresh pair, one rotation at a time.
 *
 * Single-flight, and that is load-bearing rather than tidy: rotating the same refresh token
 * twice is what token theft looks like from Nirman's side, and it answers by revoking the whole
 * family. The stream reconnect and a queued send race on exactly the same reconnect event.
 */
export function refreshSession(): Promise<TokenResponse> {
  refreshInFlight ??= runRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function runRefresh(): Promise<TokenResponse> {
  const stored = tokenStorage.getRefreshToken();
  if (!stored) throw new Error('Not signed in');
  const { data } = await axios.post<TokenResponse>(`${NIRMAN_API_BASE_URL}/auth/refresh`, {
    refreshToken: stored,
  });
  tokenStorage.setRefreshToken(data.refreshToken);
  writeCachedUser(data.user);
  return data;
}

export async function signIn(username: string, password: string): Promise<TokenResponse> {
  const { data } = await axios.post<TokenResponse>(`${NIRMAN_API_BASE_URL}/auth/login`, {
    username,
    password,
  });
  tokenStorage.setRefreshToken(data.refreshToken);
  writeCachedUser(data.user);
  return data;
}
