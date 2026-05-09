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

1. **Encode mode**: Text input → base64 encode → (optional) gzip compress → output (copyable / downloadable)
2. **Decode mode**: Base64 string input → (optional) gunzip decompress → base64 decode → text output (copyable / downloadable)
3. **Gzip toggle**: Checkbox to enable/disable gzip compression step (default: enabled)
4. **File upload**: Upload a file to encode (file → base64 → optional gzip → output)
5. **File download**: Download the encoded/decoded result as a file
6. **Toggle** between Encode/Decode directions with a single click
7. **Copy to clipboard** button for output
8. **Clear** button to reset input/output
9. **Pure frontend** implementation using browser-native APIs

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
