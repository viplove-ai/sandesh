import { beforeEach, describe, expect, it, vi } from 'vitest';

const mediaStore = { get: vi.fn(), put: vi.fn(), has: vi.fn(), evict: vi.fn(), usage: vi.fn() };
const apiClient = { get: vi.fn(), post: vi.fn() };

vi.mock('../../offline/mediaStore', () => ({ mediaStore }));
vi.mock('../../shared/apiClient', () => ({
  apiClient,
  apiErrorDetail: (failure: unknown) => String(failure),
}));

const { saveMedia } = await import('./api');

const SITE = 'site:11111111-1111-1111-1111-111111111111';

/** The anchors that were clicked, kept after they removed themselves from the document. */
let clicked: HTMLAnchorElement[] = [];

describe('handing a document to the device', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clicked = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click(
      this: HTMLAnchorElement,
    ) {
      clicked.push(this);
    });
    URL.createObjectURL = vi.fn(() => 'blob:local-copy');
    URL.revokeObjectURL = vi.fn();
  });

  it('saves the copy already on the phone, under the name it was sent with', async () => {
    // No signal is needed for this and none should be asked for: the bytes are already here.
    mediaStore.get.mockResolvedValue(new Blob(['%PDF-1.7']));

    await saveMedia({
      mediaId: 'media-1',
      convId: SITE,
      mediaFileName: 'Beam detail rev C.pdf',
    });

    expect(apiClient.get).not.toHaveBeenCalled();
    expect(clicked).toHaveLength(1);
    expect(clicked[0].getAttribute('download')).toBe('Beam detail rev C.pdf');
    expect(clicked[0].getAttribute('href')).toBe('blob:local-copy');
    // The anchor is a means, not a fixture — it must not be left in the page.
    expect(document.querySelector('a')).toBeNull();
  });

  it('fetches a site channel original that has been evicted', async () => {
    mediaStore.get.mockResolvedValue(null);
    apiClient.get.mockResolvedValue({
      data: { downloadUrl: 'https://store.example/signed', fileName: 'Bill 4821.pdf' },
    });

    await saveMedia({ mediaId: 'media-2', convId: SITE, mediaFileName: 'Bill 4821.pdf' });

    expect(apiClient.get).toHaveBeenCalledWith('/media/media-2/download-url');
    expect(clicked[0].getAttribute('href')).toBe('https://store.example/signed');
  });

  it('says so when a direct message file is gone rather than saving nothing', async () => {
    // A direct message is not retained on the server, so an evicted original is the only copy
    // and there is nothing to fetch. Silence here looks like a broken button.
    mediaStore.get.mockResolvedValue(null);

    await expect(
      saveMedia({ mediaId: 'media-3', convId: 'dm:a:b', mediaFileName: 'Quote.pdf' }),
    ).rejects.toThrow(/not kept on the server/);
    expect(clicked).toHaveLength(0);
  });
});
