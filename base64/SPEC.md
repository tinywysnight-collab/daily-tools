# Base64 + Gzip Encoder/Decoder Web Tool

## Overview

A single-page web tool that encodes text or files to base64 (with optional gzip compression), and decodes base64 strings back to text or downloadable files.

## Project Structure

```
chapter1/
├── web/                        # Next.js app
│   ├── package.json
│   ├── next.config.ts
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   ├── postcss.config.mjs
│   └── app/
│       ├── layout.tsx          # Root layout with fonts and global styles
│       ├── page.tsx            # Main page (single tool page)
│       ├── globals.css         # Tailwind + custom styles
│       └── components/
│           ├── EncoderDecoder.tsx   # Core encode/decode component
│           └── Header.tsx           # Page header
├── SPEC.md                     # This file
├── AGENTS.md
└── GUIDELINE.md
```

## Features

1. **Encode mode**: Text input → (optional) gzip compress → base64 encode → output (copyable / downloadable)
2. **Decode mode**: Base64 string input → base64 decode → (optional) gunzip decompress → text output (copyable / downloadable)
3. **Gzip toggle**: Checkbox to enable/disable gzip compression step (default: enabled)
4. **File upload**: Upload a file to encode (file → optional gzip → base64 → output)
5. **File download**: Download the encoded/decoded result as a file
6. **Toggle** between Encode/Decode directions with a single click
7. **Copy to clipboard** button for output
8. **Clear** button to reset input/output
9. **Pure frontend** implementation using browser-native APIs

## Behavioral Contracts

- Gzip mode must stay interoperable with standard tools: encode is `gzip(raw bytes) → base64`, decode is `base64 → gunzip(raw bytes)`.
- Gzip encode output must be interoperable: base64-decoding the output must produce standard gzip bytes for the original input, not gzip-compressed base64 text.
- Gzip decode input must be interpreted as `base64(gzip(raw bytes))`.
- Non-gzip mode must remain plain base64 encode/decode of the raw text or file bytes.
- Encode-mode downloads must save the visible encoded output as text (`.gz.b64` when gzip is enabled, `.b64` otherwise).
- Decode-mode downloads should save decoded bytes; if byte decoding fails, fall back to downloading the visible text output.
- Production build must not require external network access for fonts or other static assets; use local assets or system fallbacks.

## Acceptance Criteria

- Core codec tests cover text, unicode, multiline, large input, binary bytes, gzip and non-gzip roundtrips, invalid decode input, and standard gzip interoperability.
- Keep a codec test that proves base64-decoded gzip output starts with the gzip magic bytes `1f 8b 08` and can be decompressed by `DecompressionStream`.
- Component tests cover mode switching, disabled empty input, file upload encode, output copy, encode download, decode download, clear, and error display.
- `npm run lint`, `npx tsc --noEmit`, `npx vitest run`, and `npm run build` must pass before release.

## UI Style

- Blue-green color palette inspired by Standard Chartered branding (Blue `#005EB8`, Green `#00A651`)
- Modern, clean card-based layout using Tailwind CSS
- Dark/light mode support
- Responsive design (mobile + desktop)


## Implementation Steps

1. Initialize Next.js project with `create-next-app`
2. Configure Tailwind CSS with custom color palette
3. Implement `EncoderDecoder` component (core logic + UI)
4. Implement `Header` component
5. Assemble page layout
6. Add optional gzip toggle
7. Add file upload/download support
8. Verify functionality
