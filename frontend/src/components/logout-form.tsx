"use client";

import { Button } from "@heroui/react";
import { useState } from "react";
import { flushSync } from "react-dom";

export function LogoutForm({ csrf }: { csrf: string }) {
  const [pending, setPending] = useState(false);
  return (
    <form method="post" action="/auth/logout" aria-busy={pending} onSubmit={(event) => {
      if (pending) event.preventDefault();
      else flushSync(() => setPending(true));
    }}>
      <input type="hidden" name="_csrf" value={csrf} />
      <Button type="submit" variant="secondary" isDisabled={pending}>{pending ? "Signing out..." : "Sign out"}</Button>
    </form>
  );
}
