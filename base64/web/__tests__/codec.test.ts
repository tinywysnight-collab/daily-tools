import { describe, it, expect } from "vitest";
import {
  convert,
  encode,
  decode,
  encodeBytes,
  decodeToBytes,
} from "@/app/lib/codec";

function base64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function gunzipBytes(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new DecompressionStream("gzip");
  const writer = stream.writable.getWriter();
  const reader = stream.readable.getReader();

  await writer.write(data);
  await writer.close();

  const chunks: Uint8Array<ArrayBuffer>[] = [];
  let totalLength = 0;

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    chunks.push(value);
    totalLength += value.length;
  }

  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

describe("codec", () => {
  describe("encode + decode roundtrip (with gzip)", () => {
    it("should encode and decode plain text", async () => {
      const input = "Hello, World!";
      const encoded = await encode(input);
      const decoded = await decode(encoded);
      expect(decoded).toBe(input);
    });

    it("should roundtrip unicode text", async () => {
      const input = "你好世界 🌍 こんにちは";
      const encoded = await encode(input);
      const decoded = await decode(encoded);
      expect(decoded).toBe(input);
    });

    it("should roundtrip multiline text", async () => {
      const input = "line1\nline2\nline3\nwith special chars: <>&\"'";
      const encoded = await encode(input);
      const decoded = await decode(encoded);
      expect(decoded).toBe(input);
    });

    it("should roundtrip a large string", async () => {
      const input = "a".repeat(10000);
      const encoded = await encode(input);
      const decoded = await decode(encoded);
      expect(decoded).toBe(input);
    });

    it("should produce base64-wrapped standard gzip bytes", async () => {
      const input = "interoperable gzip payload";
      const encoded = await encode(input, true);
      const compressed = base64ToBytes(encoded);

      expect([...compressed.slice(0, 3)]).toEqual([0x1f, 0x8b, 0x08]);

      const decompressed = await gunzipBytes(compressed);
      expect(new TextDecoder().decode(decompressed)).toBe(input);
    });
  });

  describe("encode + decode roundtrip (without gzip)", () => {
    it("should roundtrip plain text without gzip", async () => {
      const input = "Hello, no gzip!";
      const encoded = await encode(input, false);
      const decoded = await decode(encoded, false);
      expect(decoded).toBe(input);
    });

    it("should produce raw base64 when gzip is disabled", async () => {
      const input = "test";
      const encoded = await encode(input, false);
      expect(encoded).toBe(btoa(unescape(encodeURIComponent(input))));
    });

    it("should produce garbage when gzip flag mismatches", async () => {
      const input = "mismatch test";
      const encoded = await encode(input, true);
      const decoded = await decode(encoded, false);
      expect(decoded).not.toBe(input);
    });

    it("should roundtrip unicode without gzip", async () => {
      const input = "你好世界";
      const encoded = await encode(input, false);
      const decoded = await decode(encoded, false);
      expect(decoded).toBe(input);
    });
  });

  describe("encodeBytes + decodeToBytes", () => {
    it("should roundtrip binary data with gzip", async () => {
      const data = new Uint8Array([0, 127, 255, 1, 2, 3]);
      const encoded = await encodeBytes(data, true);
      const decoded = await decodeToBytes(encoded, true);
      expect(decoded).toEqual(data);
    });

    it("should roundtrip binary data without gzip", async () => {
      const data = new Uint8Array([10, 20, 30, 40, 50]);
      const encoded = await encodeBytes(data, false);
      const decoded = await decodeToBytes(encoded, false);
      expect(decoded).toEqual(data);
    });
  });

  describe("convert", () => {
    it("should handle empty string via convert", async () => {
      const result = await convert("", "encode");
      expect(result.success).toBe(false);
      expect(result.error).toBe("Input is empty");
    });

    it("should handle whitespace-only input via convert", async () => {
      const result = await convert("   ", "encode");
      expect(result.success).toBe(false);
    });

    it("should return success for valid encode", async () => {
      const result = await convert("test input", "encode");
      expect(result.success).toBe(true);
      expect(result.output.length).toBeGreaterThan(0);
    });

    it("should return success for valid encode without gzip", async () => {
      const result = await convert("test input", "encode", false);
      expect(result.success).toBe(true);
      expect(result.output.length).toBeGreaterThan(0);
    });

    it("should return error for invalid decode input", async () => {
      const result = await convert("not-valid-base64!!!", "decode");
      expect(result.success).toBe(false);
      expect(result.error).toContain("Decode failed");
    });

    it("should include correct error hint based on gzip flag", async () => {
      const result = await convert("not-valid!!!", "decode", false);
      expect(result.success).toBe(false);
      if (result.error) {
        expect(result.error).toContain("base64");
        expect(result.error).not.toContain("base64-gzip");
      }
    });
  });
});
