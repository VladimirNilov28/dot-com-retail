export interface BucketPolicy {
  capacity: number;
  refillPerSecond: number;
}

export interface BucketDecision {
  allowed: boolean;
  retryAfterSeconds: number;
}

/**
 * In-process token buckets for BFF-only endpoints that never reach Spring's
 * PostgreSQL-backed limiter (Kratos login/recovery submissions). State is
 * per Next.js process and bounded: full or idle buckets are evicted first.
 */
export class TokenBuckets {
  private readonly buckets = new Map<string, { tokens: number; updated: number }>();

  constructor(private readonly policy: BucketPolicy, private readonly maxKeys = 10_000,
    private readonly now: () => number = Date.now) {
    if (!(policy.capacity >= 1) || !(policy.refillPerSecond > 0)) throw new TypeError("Invalid bucket policy");
  }

  consume(key: string, cost = 1): BucketDecision {
    const now = this.now();
    const current = this.buckets.get(key);
    const elapsed = current ? Math.max(0, now - current.updated) / 1000 : 0;
    const available = current ? Math.min(this.policy.capacity, current.tokens + elapsed * this.policy.refillPerSecond)
      : this.policy.capacity;
    if (available < cost) {
      return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil((cost - available) / this.policy.refillPerSecond)) };
    }
    this.buckets.delete(key);
    this.buckets.set(key, { tokens: available - cost, updated: now });
    if (this.buckets.size > this.maxKeys) this.evict(now);
    return { allowed: true, retryAfterSeconds: 0 };
  }

  get size(): number {
    return this.buckets.size;
  }

  private evict(now: number) {
    const full = this.policy.capacity / this.policy.refillPerSecond * 1000;
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.updated >= full) this.buckets.delete(key);
    }
    // Map iteration order is least-recently-used first.
    for (const key of this.buckets.keys()) {
      if (this.buckets.size <= this.maxKeys) break;
      this.buckets.delete(key);
    }
  }
}
