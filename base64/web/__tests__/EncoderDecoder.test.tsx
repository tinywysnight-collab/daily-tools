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

import { convert } from "@/app/lib/codec";
const mockConvert = vi.mocked(convert);

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
});
