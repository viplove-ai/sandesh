import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { API_BASE_URL, clearCachedUser, isNetworkFailure, refreshSession, tokenStorage } from './session';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function currentAccessToken(): string | null {
  return accessToken;
}

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  config.headers['X-Correlation-Id'] = crypto.randomUUID();
  return config;
});

/**
 * A 401 triggers exactly one refresh attempt, shared by every request that raced into the
 * failure — the shared promise lives in refreshSession.
 *
 * How the refresh fails decides whether the session survives. A refusal from Nirman is final and
 * the device is signed out. A refresh that got no answer at all is a phone with no signal, and it
 * must leave the stored token where it is: throwing it away there strands every message still
 * waiting to be sent.
 */
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig & { _retried?: boolean };
    if (error.response?.status !== 401 || original._retried) return Promise.reject(error);
    original._retried = true;
    try {
      const session = await refreshSession();
      setAccessToken(session.accessToken);
      return apiClient(original);
    } catch (refreshError) {
      if (isNetworkFailure(refreshError)) return Promise.reject(refreshError);
      setAccessToken(null);
      tokenStorage.clear();
      clearCachedUser();
      window.location.assign('/login?reason=rejected');
      return Promise.reject(refreshError);
    }
  },
);

export interface ApiErrorBody {
  type: string;
  title: string;
  status: number;
  detail: string;
  correlationId: string;
}

export function apiErrorDetail(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiErrorBody | undefined;
    if (body?.detail) return body.detail;
    if (!error.response) return 'No connection. Check the network and try again.';
  }
  return 'Something went wrong. Try again.';
}
