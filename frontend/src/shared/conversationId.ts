/**
 * The client half of the conversation naming rule. The server sorts the pair too, and the two
 * must agree — if they ever disagree, two people are in what they each believe is the same
 * direct conversation and neither sees the other's messages.
 */
export function directConversationId(one: string, other: string): string {
  const pair = [one, other].sort();
  return `dm:${pair[0]}:${pair[1]}`;
}

export function isDirect(convId: string): boolean {
  return convId.startsWith('dm:');
}
