import { apiClient } from '../../shared/apiClient';
import { db, markSent, type StoredMessage } from '../../offline/db';
import { preparePhoto } from '../../shared/uploads';

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

/**
 * Compress on the device, upload straight to storage, then send the message that points at it.
 *
 * The bytes never pass through the API — a presigned PUT goes to the object store directly,
 * which is what keeps a 700 KB photograph off the backend's heap and out of its request timeout
 * on a connection that takes a minute to move it.
 */
export async function sendImage(convId: string, file: File, me: { id: string; fullName: string }) {
  const clientMsgId = crypto.randomUUID();
  const photo = await preparePhoto(file);

  // Shown immediately, from the thumbnail we just made — the upload can take a minute on 2G and
  // a supervisor should not be looking at a spinner wondering whether it worked.
  const optimistic: StoredMessage = {
    msgId: clientMsgId,
    clientMsgId,
    convId,
    from: me.id,
    fromName: me.fullName,
    kind: 'IMAGE',
    mediaFileName: photo.fileName,
    mediaContentType: photo.contentType,
    thumbnail: photo.thumbnail,
    sentAt: new Date().toISOString(),
    state: 'pending',
    mine: true,
  };
  await db.messages.put(optimistic);

  try {
    const upload = await requestUploadUrl(
      photo.fileName, photo.contentType, photo.blob.size, convId,
    );

    // Deliberately fetch and not apiClient: this goes to the object store, not to our API, and
    // must not carry the Authorization header — the presigned URL is the credential, and
    // sending a bearer token to a third party is how one leaks.
    const put = await fetch(upload.uploadUrl, {
      method: 'PUT',
      body: photo.blob,
      headers: { 'Content-Type': photo.contentType },
    });
    if (!put.ok) throw new Error(`upload failed: ${put.status}`);

    const { data } = await apiClient.post<SendResponse>('/messages', {
      clientMsgId,
      convId,
      kind: 'IMAGE',
      media: {
        mediaId: upload.mediaId,
        fileName: photo.fileName,
        contentType: photo.contentType,
        sizeBytes: photo.blob.size,
      },
    });

    await markSent(clientMsgId, data.msgId, data.sentAt);
    await db.messages.update(data.msgId, { mediaId: upload.mediaId });
    return data;
  } catch (failure) {
    await db.messages.put({ ...optimistic, state: 'failed' });
    throw failure;
  }
}
