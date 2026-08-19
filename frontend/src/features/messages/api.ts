import { apiClient } from '../../shared/apiClient';
import { db, markSent, type StoredMessage } from '../../offline/db';
import { mediaStore } from '../../offline/mediaStore';
import { evictIfNeeded } from '../../offline/eviction';
import { DOCUMENT_MAX_BYTES, preparePhoto } from '../../shared/uploads';

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
    mediaSizeBytes: photo.blob.size,
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
    // Our own copy, so the sender is not re-downloading a photograph they took.
    await mediaStore.put(upload.mediaId, photo.blob);
    await evictIfNeeded();
    return data;
  } catch (failure) {
    await db.messages.put({ ...optimistic, state: 'failed' });
    throw failure;
  }
}

/**
 * A document goes up as it is — there is nothing to resize and re-encoding a PDF would only
 * damage it. The size cap is checked here so a file that cannot be sent is refused before it
 * has spent a minute of somebody's connection going nowhere.
 */
export async function sendDocument(
  convId: string,
  file: File,
  me: { id: string; fullName: string },
) {
  if (file.size > DOCUMENT_MAX_BYTES) {
    throw new Error('That file is larger than 25 MB.');
  }
  const clientMsgId = crypto.randomUUID();
  const optimistic: StoredMessage = {
    msgId: clientMsgId,
    clientMsgId,
    convId,
    from: me.id,
    fromName: me.fullName,
    kind: 'DOC',
    mediaFileName: file.name,
    mediaContentType: file.type,
    mediaSizeBytes: file.size,
    sentAt: new Date().toISOString(),
    state: 'pending',
    mine: true,
  };
  await db.messages.put(optimistic);

  try {
    const upload = await requestUploadUrl(file.name, file.type, file.size, convId);

    const put = await fetch(upload.uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type },
    });
    if (!put.ok) throw new Error(`upload failed: ${put.status}`);

    const { data } = await apiClient.post<SendResponse>('/messages', {
      clientMsgId,
      convId,
      kind: 'DOC',
      media: {
        mediaId: upload.mediaId,
        fileName: file.name,
        contentType: file.type,
        sizeBytes: file.size,
      },
    });

    await markSent(clientMsgId, data.msgId, data.sentAt);
    await db.messages.update(data.msgId, { mediaId: upload.mediaId });
    await mediaStore.put(upload.mediaId, file);
    await evictIfNeeded();
    return data;
  } catch (failure) {
    await db.messages.put({ ...optimistic, state: 'failed' });
    throw failure;
  }
}

/**
 * Where the bytes are: on the device if they are still here, from the server if they are not.
 *
 * The re-fetch is what makes the eviction ladder safe: dropping a site channel's original costs
 * a download rather than the file. A direct message has no server copy, so an evicted one is
 * genuinely gone and the caller is told rather than shown a broken image.
 */
async function locateMedia(message: {
  mediaId?: string;
  convId: string;
  mediaEvicted?: boolean;
}): Promise<{ href: string; fileName?: string; isLocalObjectUrl: boolean }> {
  if (!message.mediaId) throw new Error('That message has no file.');

  const local = await mediaStore.get(message.mediaId);
  if (local) return { href: URL.createObjectURL(local), isLocalObjectUrl: true };

  if (message.convId.startsWith('dm:')) {
    throw new Error(
      'This file was removed from the phone to free space, and direct messages are not kept on the server.',
    );
  }

  const { downloadUrl, fileName } = await requestDownloadUrl(message.mediaId);
  return { href: downloadUrl, fileName, isLocalObjectUrl: false };
}

/** Open a photograph full-size. Images are looked at, not filed. */
export async function openMedia(message: {
  mediaId?: string;
  convId: string;
  mediaEvicted?: boolean;
}): Promise<string> {
  return (await locateMedia(message)).href;
}

/**
 * Put a document in the phone's downloads rather than opening it inside the app.
 *
 * <p>A drawing or a bill is a thing somebody keeps, forwards and opens in the app that reads it
 * — an in-app tab is a dead end on a phone, and in an installed PWA it throws the person out
 * into a browser window with no way back to the thread. So the file goes to the device and the
 * conversation stays where it was.</p>
 *
 * <p>The `download` attribute carries the file's real name for the copy already on the device.
 * A cross-origin presigned URL ignores that attribute, which costs nothing here: the server
 * presigns every download with `Content-Disposition: attachment`, so the browser files it
 * rather than rendering it either way.</p>
 */
export async function saveMedia(message: {
  mediaId?: string;
  convId: string;
  mediaFileName?: string;
  mediaEvicted?: boolean;
}): Promise<void> {
  const located = await locateMedia(message);

  const anchor = document.createElement('a');
  anchor.href = located.href;
  anchor.download = message.mediaFileName ?? located.fileName ?? 'file';
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();

  // Revoked late and not immediately: the browser reads the blob after the click returns, and
  // releasing it in the same tick cancels the save on a phone slow enough to matter.
  if (located.isLocalObjectUrl) {
    setTimeout(() => URL.revokeObjectURL(located.href), 60_000);
  }
}
