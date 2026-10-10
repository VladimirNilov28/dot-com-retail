// Kratos v26.2.0 defaults with the deployed bcrypt hasher; keep policy deployments in sync.
export const registrationPasswordPolicy = { minBytes: 8, maxBytes: 72 } as const;

export function passwordLengthFeedback(value: string) {
  const bytes = new TextEncoder().encode(value).length;
  return {
    bytes,
    minimumMet: bytes >= registrationPasswordPolicy.minBytes,
    maximumMet: bytes <= registrationPasswordPolicy.maxBytes,
  };
}
