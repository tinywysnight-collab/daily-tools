import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import EncoderDecoder from "@/app/components/EncoderDecoder";

vi.mock("@/app/lib/codec", () => ({
  convert: vi.fn(),
  encodeBytes: vi.fn(),
  decodeToBytes: vi.fn(),
  fileToUint8Array: vi.fn(),
  downloadBytes: vi.fn(),
  downloadText: vi.fn(),
}));

import { convert, encodeBytes, fileToUint8Array, downloadText, downloadBytes, decodeToBytes } from "@/app/lib/codec";
const mockConvert = vi.mocked(convert);
const mockEncodeBytes = vi.mocked(encodeBytes);
const mockFileToUint8Array = vi.mocked(fileToUint8Array);
const mockDownloadText = vi.mocked(downloadText);
const mockDownloadBytes = vi.mocked(downloadBytes);
const mockDecodeToBytes = vi.mocked(decodeToBytes);

beforeEach(() => {
  vi.clearAllMocks();
});

describe("EncoderDecoder", () => {
  it("matches snapshot", () => {
    const { container } = render(<EncoderDecoder />);
    expect(container).toMatchSnapshot();
  });

  it("renders in encode mode by default", () => {
    render(<EncoderDecoder />);
    expect(screen.getByRole("heading", { name: "Encode" })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Enter text to encode...")).toBeInTheDocument();
  });

  it("toggles to decode mode", () => {
    render(<EncoderDecoder />);
    fireEvent.click(screen.getByRole("button", { name: /Switch to Decode/ }));
    expect(screen.getByPlaceholderText("Enter base64 string to decode...")).toBeInTheDocument();
  });

  it("shows output after successful encode", async () => {
    mockConvert.mockResolvedValue({ success: true, output: "SGVsbG8=" });
    render(<EncoderDecoder />);

    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "Hello" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => {
      expect(screen.getByDisplayValue("SGVsbG8=")).toBeInTheDocument();
    });
  });

  it("shows error on failed encode", async () => {
    mockConvert.mockResolvedValue({ success: false, output: "", error: "Encode failed" });
    render(<EncoderDecoder />);

    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "bad input" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => {
      expect(screen.getByText("Encode failed")).toBeInTheDocument();
    });
  });

  it("clears input, output and error on clear", async () => {
    mockConvert.mockResolvedValue({ success: true, output: "SGVsbG8=" });
    render(<EncoderDecoder />);

    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "Hello" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => screen.getByDisplayValue("SGVsbG8="));

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));

    expect(screen.getByPlaceholderText("Enter text to encode...")).toHaveValue("");
    expect(screen.queryByDisplayValue("SGVsbG8=")).not.toBeInTheDocument();
  });

  it("Encode button is disabled when input is empty", () => {
    render(<EncoderDecoder />);
    expect(screen.getByRole("button", { name: "Encode" })).toBeDisabled();
  });

  it("enables Gzip checkbox when CompressionStream is supported", () => {
    render(<EncoderDecoder />);
    // jsdom 29 supports CompressionStream, so checkbox should be enabled
    expect(screen.getByRole("checkbox")).toBeEnabled();
    expect(screen.queryByText("(not supported in this browser)")).not.toBeInTheDocument();
  });

  it("uploads a file and encodes it", async () => {
    const fakeBytes = new Uint8Array([1, 2, 3]) as Uint8Array<ArrayBuffer>;
    mockFileToUint8Array.mockResolvedValue(fakeBytes);
    mockEncodeBytes.mockResolvedValue("AQID");

    render(<EncoderDecoder />);

    const file = new File(["abc"], "test.txt", { type: "text/plain" });
    const fileInput = document.querySelector("input[type='file']") as HTMLInputElement;
    fireEvent.change(fileInput, { target: { files: [file] } });

    // wait for fileToUint8Array to resolve and fileData state to be set
    await waitFor(() => screen.getByDisplayValue(/File: test\.txt/));

    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => {
      expect(mockEncodeBytes).toHaveBeenCalledWith(fakeBytes, true);
      expect(screen.getByDisplayValue("AQID")).toBeInTheDocument();
    });
  });

  it("downloads encoded text output as .gz.b64 file", async () => {
    mockConvert.mockResolvedValue({ success: true, output: "SGVsbG8=" });

    render(<EncoderDecoder />);
    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "Hello" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => screen.getByDisplayValue("SGVsbG8="));

    fireEvent.click(screen.getByRole("button", { name: "Download" }));

    await waitFor(() => {
      expect(mockDownloadText).toHaveBeenCalledWith("SGVsbG8=", expect.stringMatching(/\.gz\.b64$/));
      expect(mockDownloadBytes).not.toHaveBeenCalled();
    });
  });

  it("shows error when convert throws unexpectedly", async () => {
    mockConvert.mockRejectedValue(new Error("Unexpected crash"));
    render(<EncoderDecoder />);

    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "crash" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => {
      expect(screen.getByText("Unexpected crash")).toBeInTheDocument();
    });
  });

  it("copies output to clipboard", async () => {
    mockConvert.mockResolvedValue({ success: true, output: "SGVsbG8=" });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<EncoderDecoder />);
    fireEvent.change(screen.getByPlaceholderText("Enter text to encode..."), {
      target: { value: "Hello" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Encode" }));

    await waitFor(() => screen.getByDisplayValue("SGVsbG8="));

    fireEvent.click(screen.getByRole("button", { name: /Copy/ }));

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith("SGVsbG8=");
      expect(screen.getByRole("button", { name: /Copied/ })).toBeInTheDocument();
    });
  });

  it("downloads decoded file output", async () => {
    const decodedBytes = new Uint8Array([1, 2, 3]) as Uint8Array<ArrayBuffer>;
    mockConvert.mockResolvedValue({ success: true, output: "SGVsbG8=" });
    mockDecodeToBytes.mockResolvedValue(decodedBytes);

    render(<EncoderDecoder />);
    fireEvent.click(screen.getByRole("button", { name: /Switch to Decode/ }));

    fireEvent.change(screen.getByPlaceholderText("Enter base64 string to decode..."), {
      target: { value: "SGVsbG8=" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Decode" }));

    await waitFor(() => screen.getByDisplayValue("SGVsbG8="));

    fireEvent.click(screen.getByRole("button", { name: "Download" }));

    await waitFor(() => {
      expect(mockDownloadBytes).toHaveBeenCalledWith(decodedBytes, expect.any(String));
    });
  });

  it("falls back to downloadText in decode mode when decodeToBytes throws", async () => {
    mockConvert.mockResolvedValue({ success: true, output: "not-bytes" });
    mockDecodeToBytes.mockRejectedValue(new Error("bad base64"));

    render(<EncoderDecoder />);
    fireEvent.click(screen.getByRole("button", { name: /Switch to Decode/ }));

    fireEvent.change(screen.getByPlaceholderText("Enter base64 string to decode..."), {
      target: { value: "not-bytes" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Decode" }));

    await waitFor(() => screen.getByDisplayValue("not-bytes"));

    fireEvent.click(screen.getByRole("button", { name: "Download" }));

    await waitFor(() => {
      expect(mockDownloadText).toHaveBeenCalledWith("not-bytes", "decoded-output.txt");
    });
  });
});
