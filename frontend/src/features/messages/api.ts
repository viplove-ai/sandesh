import { apiClient } from '../../shared/apiClient';
import { db, markSent, type StoredMessage } from '../../offline/db';

export interface SendResponse {
  clientMsgId: string;
  msgId: string;
  sentAt: string;
}

/**
 * Write it down and show it first, then send.
 *
 * The client id is minted here rather than by the server, so a phone on a bad link can re-send
 * the same message three times without producing three messages — the server answers a repeat
 * with the original receipt.
 */
export async function sendText(convId: string, body: string, me: { id: string; fullName: string }) {
  const clientMsgId = crypto.randomUUID();
  const optimistic: StoredMessage = {
    msgId: clientMsgId,
    clientMsgId,
    convId,
    from: me.id,
    fromName: me.fullName,
    kind: 'TEXT',
    body,
    sentAt: new Date().toISOString(),
    state: 'pending',
    mine: true,
  };
  await db.messages.put(optimistic);

  try {
    const { data } = await apiClient.post<SendResponse>('/messages', {
      clientMsgId,
      convId,
      kind: 'TEXT',
      body,
    });
    await markSent(clientMsgId, data.msgId, data.sentAt);
    return data;
  } catch (failure) {
    await db.messages.put({ ...optimistic, state: 'failed' });
    throw failure;
  }
}

export async function requestUploadUrl(fileName: string, contentType: string, sizeBytes: number,
                                       convId: string) {
  const { data } = await apiClient.post<{ mediaId: string; uploadUrl: string }>(
    '/media/upload-url',
    { fileName, contentType, sizeBytes, convId },
  );
  return data;
}

export async function requestDownloadUrl(mediaId: string) {
  const { data } = await apiClient.get<{ downloadUrl: string; fileName: string }>(
    `/media/${mediaId}/download-url`,
  );
  return data;
}
