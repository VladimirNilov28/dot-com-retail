// Must match the Kratos minimum and deployed bcrypt byte limit.
export const registrationPasswordPolicy = { minBytes: 8, maxBytes: 72 } as const;
export const passwordGuidance = `Recommended: Use lowercase and uppercase letters, numbers and symbols. Minimum ${registrationPasswordPolicy.minBytes} UTF-8 bytes.`;

export type PasswordErrorCode = "minimum" | "maximum";

export function passwordErrorMessage(code: unknown): string | undefined {
  if (code === "minimum") return `Use at least ${registrationPasswordPolicy.minBytes} UTF-8 bytes.`;
  if (code === "maximum") return `Use no more than ${registrationPasswordPolicy.maxBytes} UTF-8 bytes.`;
}

export function passwordLengthFeedback(value: string) {
  const bytes = new TextEncoder().encode(value).length;
  return {
    bytes,
    minimumMet: bytes >= registrationPasswordPolicy.minBytes,
    maximumMet: bytes <= registrationPasswordPolicy.maxBytes,
  };
}

export function registrationPasswordError(value: string): PasswordErrorCode | undefined {
  const feedback = passwordLengthFeedback(value);
  if (!feedback.minimumMet) return "minimum";
  if (!feedback.maximumMet) return "maximum";
}
