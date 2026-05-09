import Header from "@/app/components/Header";
import EncoderDecoder from "@/app/components/EncoderDecoder";

export default function Home() {
  return (
    <div className="flex flex-col flex-1 items-center justify-start bg-background font-sans px-4">
      <Header />
      <main className="flex w-full flex-1 flex-col items-center pb-16">
        <EncoderDecoder />
      </main>
    </div>
  );
}
