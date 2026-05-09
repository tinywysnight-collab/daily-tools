"use client";

import { useState, useRef } from "react";
import {
  convert,
  encodeBytes,
  decodeToBytes,
  fileToUint8Array,
  downloadBytes,
  downloadText,
} from "@/app/lib/codec";
import type { Mode } from "@/app/lib/codec";

export default function EncoderDecoder() {
  const [mode, setMode] = useState<Mode>("encode");
  const [useGzip, setUseGzip] = useState(true);
  const [input, setInput] = useState("");
  const [output, setOutput] = useState("");
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(false);
  const [fileName, setFileName] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [fileData, setFileData] = useState<Uint8Array | null>(null);

  const handleConvert = async () => {
    setLoading(true);
    setError("");
    setOutput("");

    try {
      if (fileData) {
        if (mode === "encode") {
          const result = await encodeBytes(fileData, useGzip);
          setOutput(result);
        } else {
          const bytes = await decodeToBytes(input, useGzip);
          setOutput(new TextDecoder().decode(bytes));
        }
      } else {
        const result = await convert(input, mode, useGzip);
        if (result.success) {
          setOutput(result.output);
        } else {
          setError(result.error ?? "Unknown error");
        }
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  };

  const handleClear = () => {
    setInput("");
    setOutput("");
    setError("");
    setCopied(false);
    setFileData(null);
    setFileName("");
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const handleCopy = async () => {
    if (!output) return;
    await navigator.clipboard.writeText(output);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = async () => {
    if (!output) return;
    if (fileData && mode === "encode") {
      const suffix = useGzip ? ".gz.b64" : ".b64";
      downloadText(output, `${fileName || "file"}${suffix}`);
    } else {
      try {
        const bytes = await decodeToBytes(output, useGzip);
        downloadBytes(bytes, fileName || "decoded-output");
      } catch {
        downloadText(output, "decoded-output.txt");
      }
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFileName(file.name);
    const bytes = await fileToUint8Array(file);
    setFileData(bytes);
    setInput(`[File: ${file.name} (${(file.size / 1024).toFixed(1)} KB)]`);
  };

  const handleModeToggle = () => {
    const next: Mode = mode === "encode" ? "decode" : "encode";
    setMode(next);
    setInput("");
    setOutput("");
    setError("");
    setCopied(false);
    setFileData(null);
    setFileName("");
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const hasInput = fileData || input.trim();
  const inputLabel = mode === "encode" ? "Plain Text or File" : "Base64 String";
  const outputLabel = mode === "encode" ? "Encoded Output" : "Decoded Output";

  return (
    <div className="w-full max-w-3xl mx-auto">
      <div className="bg-card border border-card-border rounded-2xl shadow-lg p-6 sm:p-8 space-y-6">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <h2 className="text-xl font-semibold text-foreground">
            {mode === "encode" ? "Encode" : "Decode"}
          </h2>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-sm text-foreground cursor-pointer select-none">
              <input
                type="checkbox"
                checked={useGzip}
                onChange={(e) => setUseGzip(e.target.checked)}
                className="w-4 h-4 rounded border-input-border text-primary accent-primary cursor-pointer"
              />
              Gzip
            </label>
            <button
              onClick={handleModeToggle}
              className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-white text-sm font-medium hover:opacity-90 transition-opacity cursor-pointer"
            >
              <svg
                className="w-4 h-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"
                />
              </svg>
              Switch to {mode === "encode" ? "Decode" : "Encode"}
            </button>
          </div>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <label className="block text-sm font-medium text-foreground">
              {inputLabel}
            </label>
            {mode === "encode" && (
              <label className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-primary-light text-primary hover:opacity-80 transition-opacity cursor-pointer">
                <svg
                  className="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                  />
                </svg>
                Upload File
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  onChange={handleFileUpload}
                />
              </label>
            )}
          </div>
          <textarea
            className="w-full h-40 p-3 rounded-lg border border-input-border bg-input-bg text-foreground font-mono text-sm resize-y focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
            placeholder={
              mode === "encode"
                ? "Enter text to encode..."
                : "Enter base64 string to decode..."
            }
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              if (fileData) {
                setFileData(null);
                setFileName("");
              }
            }}
            readOnly={!!fileData}
          />
        </div>

        <div className="flex gap-3">
          <button
            onClick={handleConvert}
            disabled={loading || !hasInput}
            className={`flex-1 py-2.5 px-4 rounded-lg font-medium text-sm text-white transition-opacity cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed ${
              mode === "encode"
                ? "bg-primary hover:opacity-90"
                : "bg-secondary hover:opacity-90"
            }`}
          >
            {loading
              ? "Processing..."
              : mode === "encode"
                ? "Encode"
                : "Decode"}
          </button>
          <button
            onClick={handleClear}
            className="px-4 py-2.5 rounded-lg border border-input-border text-sm font-medium text-foreground hover:bg-input-bg transition-colors cursor-pointer"
          >
            Clear
          </button>
        </div>

        {error && (
          <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400 text-sm">
            {error}
          </div>
        )}

        {output && (
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="block text-sm font-medium text-foreground">
                {outputLabel}
              </label>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleDownload}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-secondary-light text-secondary hover:opacity-80 transition-opacity cursor-pointer"
                >
                  <svg
                    className="w-3.5 h-3.5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                    />
                  </svg>
                  Download
                </button>
                <button
                  onClick={handleCopy}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-primary-light text-primary hover:opacity-80 transition-opacity cursor-pointer"
                >
                  {copied ? (
                    <>
                      <svg
                        className="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 13l4 4L19 7"
                        />
                      </svg>
                      Copied
                    </>
                  ) : (
                    <>
                      <svg
                        className="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                        />
                      </svg>
                      Copy
                    </>
                  )}
                </button>
              </div>
            </div>
            <textarea
              readOnly
              className="w-full h-40 p-3 rounded-lg border border-input-border bg-input-bg text-foreground font-mono text-sm resize-y"
              value={output}
            />
          </div>
        )}
      </div>
    </div>
  );
}
