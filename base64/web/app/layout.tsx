import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Base64 + Gzip Tool",
  description:
    "Encode and decode text with base64 and gzip compression. Pure frontend, no server needed.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
