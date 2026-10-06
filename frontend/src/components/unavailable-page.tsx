import Link from "next/link";

export function UnavailablePage({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="max-w-2xl space-y-4">
      <p className="text-sm font-semibold tracking-widest text-accent uppercase">
        Not available yet
      </p>
      <h1 className="text-2xl leading-tight font-semibold tracking-tight sm:text-3xl">
        {title}
      </h1>
      <p className="text-muted">{description}</p>
      <Link href="/" className="store-link inline-flex py-2">
        Return to home
      </Link>
    </div>
  );
}
