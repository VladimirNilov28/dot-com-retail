import { ZxcvbnFactory } from "@zxcvbn-ts/core";
import { adjacencyGraphs, dictionary as common } from "@zxcvbn-ts/language-common";
import { dictionary as english, translations } from "@zxcvbn-ts/language-en";

const estimator = new ZxcvbnFactory({
  graphs: adjacencyGraphs, dictionary: { ...common, ...english }, translations,
});

export function passwordStrength(value: string): number {
  return estimator.check(value).score;
}

export const strengthLabels = ["Weak", "Weak", "Fair", "Good", "Strong"] as const;
