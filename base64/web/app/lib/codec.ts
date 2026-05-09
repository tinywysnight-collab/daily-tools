type Mode = "encode" | "decode";

interface ConversionResult {
  success: boolean;
  output: string;
  error?: string;
}

function uint8ArrayToBase64(bytes: Uint8Array<ArrayBuffer>): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function base64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function gzipCompress(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new CompressionStream("gzip");
  const writer = stream.writable.getWriter();
  const reader = stream.readable.getReader();

  writer.write(data);
  writer.close();

  const chunks: Uint8Array[] = [];
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

async function gzipDecompress(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new DecompressionStream("gzip");
  const writer = stream.writable.getWriter();
  const reader = stream.readable.getReader();

  writer.write(data);
  writer.close();

  const chunks: Uint8Array[] = [];
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

async function encodeBytes(data: Uint8Array<ArrayBuffer>, useGzip: boolean): Promise<string> {
  if (!useGzip) return uint8ArrayToBase64(data);
  const compressed = await gzipCompress(data);
  return uint8ArrayToBase64(compressed);
}

async function decodeToBytes(encoded: string, useGzip: boolean): Promise<Uint8Array<ArrayBuffer>> {
  const bytes = base64ToUint8Array(encoded);
  if (!useGzip) return bytes;
  return gzipDecompress(bytes);
}

async function encode(text: string, useGzip: boolean = true): Promise<string> {
  const textBytes = new TextEncoder().encode(text);
  return encodeBytes(textBytes, useGzip);
}

async function decode(encoded: string, useGzip: boolean = true): Promise<string> {
  const bytes = await decodeToBytes(encoded, useGzip);
  return new TextDecoder().decode(bytes);
}

async function convert(input: string, mode: Mode, useGzip: boolean = true): Promise<ConversionResult> {
  if (!input.trim()) {
    return { success: false, output: "", error: "Input is empty" };
  }

  try {
    if (mode === "encode") {
      const output = await encode(input, useGzip);
      return { success: true, output };
    } else {
      const output = await decode(input, useGzip);
      return { success: true, output };
    }
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : "Unknown error";
    return {
      success: false,
      output: "",
      error: mode === "decode"
        ? `Decode failed: ${message}. Ensure input is a valid base64${useGzip ? "-gzip" : ""} string.`
        : `Encode failed: ${message}`,
    };
  }
}

function fileToUint8Array(file: File): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      resolve(new Uint8Array(reader.result as ArrayBuffer));
    };
    reader.onerror = () => reject(new Error("Failed to read file"));
    reader.readAsArrayBuffer(file);
  });
}

function downloadBytes(bytes: Uint8Array<ArrayBuffer>, filename: string): void {
  const blob = new Blob([bytes]);
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function downloadText(text: string, filename: string): void {
  downloadBytes(new TextEncoder().encode(text), filename);
}

export {
  convert,
  encode,
  decode,
  encodeBytes,
  decodeToBytes,
  fileToUint8Array,
  downloadBytes,
  downloadText,
};
export type { Mode, ConversionResult };
