import axios from 'axios';
import { currentAccessToken } from '../../shared/apiClient';
import { NIRMAN_API_BASE_URL } from '../../shared/session';

/**
 * The buttons on a card from Nirman.
 *
 * The rule the whole feature rests on: **Sandesh never performs a Nirman action.** The card is
 * rendered here and the request is made from this device to Nirman with the user's own access
 * token — so @PreAuthorize, SiteAccessGuard and PeriodLockGuard all run exactly as they do in
 * the Nirman app. A service account approving expenses on somebody's behalf would be an
 * authorisation bypass with a friendly name.
 */

export interface CardAction {
  label: string;
  method: string;
  /** A path on Nirman's API. Never a full URL — see joinToNirman. */
  path: string;
  confirm?: string;
  primary?: boolean;
}

export function parseActions(raw: unknown): CardAction[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (a): a is CardAction =>
      typeof a?.label === 'string' && typeof a?.method === 'string' && typeof a?.path === 'string',
  );
}

/**
 * Joins the card's path to the Nirman base this app already knows.
 *
 * The server refuses anything that is not a path, and this refuses it again — because the thing
 * being protected is a token, and the cost of being wrong is handing it to another host. Two
 * cheap checks in different places beat one clever one.
 */
export function joinToNirman(path: string): string {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://') || path.includes('..')) {
    throw new Error('That action is not a valid Nirman path.');
  }
  return `${NIRMAN_API_BASE_URL}${path}`;
}

export interface ActionOutcome {
  ok: boolean;
  message: string;
  /** True when the record moved on before this tap — handled elsewhere, not an error. */
  alreadyHandled: boolean;
}

export async function performAction(action: CardAction): Promise<ActionOutcome> {
  const url = joinToNirman(action.path);
  try {
    await axios.request({
      url,
      method: action.method,
      headers: {
        Authorization: `Bearer ${currentAccessToken() ?? ''}`,
        // Nirman accepts this on transaction-creating POSTs and answers a repeat with the
        // original response — so a double tap on a slow connection cannot approve twice.
        'Idempotency-Key': crypto.randomUUID(),
      },
    });
    return { ok: true, message: 'Done.', alreadyHandled: false };
  } catch (failure) {
    if (axios.isAxiosError(failure)) {
      // Nirman's optimistic locking answers 409 when the record moved. Approvals get handled in
      // two places and that is normal — showing it as an error would train people to ignore
      // errors.
      if (failure.response?.status === 409) {
        return { ok: true, message: 'Already handled by someone else.', alreadyHandled: true };
      }
      if (failure.response?.status === 401) {
        return { ok: false, message: 'Sign in again to act on this.', alreadyHandled: false };
      }
      const detail = (failure.response?.data as { detail?: string } | undefined)?.detail;
      if (detail) return { ok: false, message: detail, alreadyHandled: false };
    }
    return { ok: false, message: 'That could not be done. Try again.', alreadyHandled: false };
  }
}
