# Sandesh brand assets

Sandesh is an extension of Nirman, so the mark is the same object with a different letter:
संदेश's **स** set in Kalam 700 inside the inked notebook tile, terracotta rule beneath.
Side by side with Nirman's न the two read as one family — same frame, same ink, same
terracotta, same optical weight. Nothing else changes: paper `#F7F3E9`, ink `#14181D`,
terracotta `#C2410C`, theme-color `#C2410C`.

Wordmark lockup reads **Sandesh / BY NIRMAN** — the endorsement line is what tells users
it is the same company, so keep it; don't ship the wordmark alone.

## Files
| File | Where |
|---|---|
| favicon-16.png, favicon-32.png, favicon.svg | `<link rel="icon">` |
| apple-touch-icon.png (180, opaque) | iOS home screen |
| icon-192.png, icon-512.png | manifest, purpose `any` |
| maskable-512.png | manifest, purpose `maskable` |
| logo-lockup.png | login header, notices, letterhead |
| manifest.webmanifest, HEAD-SNIPPET.html | wiring |

Put the folder at `public/brand/` in the Sandesh app — same paths as Nirman, so shared
components need no changes.

## Regenerating
The glyph is typeset, not drawn: masters come from `_render.html`. Edit it, open it, and
re-capture the `#icon`, `#maskable`, `#favicon` and `#lockup` tiles.
