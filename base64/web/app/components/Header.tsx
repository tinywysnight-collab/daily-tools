export default function Header() {
  return (
    <header className="w-full py-6 px-4">
      <div className="max-w-3xl mx-auto flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary to-secondary flex items-center justify-center">
          <svg
            className="w-5 h-5 text-white"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"
            />
          </svg>
        </div>
        <div>
          <h1 className="text-xl font-bold text-foreground">
            Base64 + Gzip Tool
          </h1>
          <p className="text-sm text-foreground/60">
            Encode and decode text with base64 and gzip compression
          </p>
        </div>
      </div>
    </header>
  );
}
