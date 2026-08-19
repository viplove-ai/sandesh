import { describe, expect, it } from 'vitest';
import { joinToNirman, parseActions } from './actions';

/**
 * A card's buttons are executed by a signed-in phone carrying the user's Nirman token. The path
 * check is therefore not tidiness — it is the difference between a convenience and a route for
 * handing that token to somebody else's host.
 */
describe('a card action path', () => {
  it('joins an ordinary path to the Nirman base', () => {
    expect(joinToNirman('/expenses/abc/approve')).toContain('/expenses/abc/approve');
  });

  it('refuses an absolute URL pointing anywhere at all', () => {
    expect(() => joinToNirman('https://evil.example/steal')).toThrow();
    expect(() => joinToNirman('http://evil.example/steal')).toThrow();
  });

  it('refuses a protocol-relative URL, which is an absolute URL in disguise', () => {
    // //evil.example resolves against the current scheme and is the classic way past a check
    // that only looks for "://".
    expect(() => joinToNirman('//evil.example/steal')).toThrow();
  });

  it('refuses traversal', () => {
    expect(() => joinToNirman('/../../somewhere')).toThrow();
  });

  it('refuses anything that is not a path at all', () => {
    expect(() => joinToNirman('expenses/abc')).toThrow();
    expect(() => joinToNirman('')).toThrow();
  });
});

describe('parsing the actions off a card', () => {
  it('keeps well-formed entries', () => {
    const actions = parseActions([
      { label: 'Approve', method: 'POST', path: '/expenses/1/approve', primary: true },
      { label: 'Reject', method: 'POST', path: '/expenses/1/reject' },
    ]);
    expect(actions).toHaveLength(2);
    expect(actions[0].label).toBe('Approve');
  });

  it('drops malformed entries rather than rendering a button that cannot work', () => {
    const actions = parseActions([
      { label: 'Approve', method: 'POST', path: '/expenses/1/approve' },
      { label: 'Broken' },
      'not an object',
      null,
    ]);
    expect(actions).toHaveLength(1);
  });

  it('treats a missing or non-array actions field as no buttons', () => {
    expect(parseActions(undefined)).toEqual([]);
    expect(parseActions(null)).toEqual([]);
    expect(parseActions({ label: 'Approve' })).toEqual([]);
  });
});
