import { describe, expect, it } from 'vitest';
import { directConversationId, isDirect } from './conversationId';

describe('naming a direct conversation', () => {
  it('is the same whichever party opens it', () => {
    const a = '00000000-0000-0000-0000-0000000000a1';
    const b = '00000000-0000-0000-0000-0000000000b2';
    expect(directConversationId(a, b)).toBe(directConversationId(b, a));
  });

  it('sorts the pair, matching ConversationId.direct on the server', () => {
    const a = '00000000-0000-0000-0000-0000000000a1';
    const b = '00000000-0000-0000-0000-0000000000b2';
    expect(directConversationId(b, a)).toBe(`dm:${a}:${b}`);
  });

  it('tells a direct conversation from a site one', () => {
    expect(isDirect('dm:a:b')).toBe(true);
    expect(isDirect('site:11111111-1111-1111-1111-111111111111')).toBe(false);
  });
});
