export function verificationLinkCode(fragment: string): string | undefined {
  const parameters = new URLSearchParams(fragment.startsWith("#") ? fragment.slice(1) : fragment);
  const values = parameters.getAll("code");
  if (values.length !== 1 || [...parameters.keys()].some((key) => key !== "code") || !/^\d{6}$/.test(values[0])) return;
  return values[0];
}
