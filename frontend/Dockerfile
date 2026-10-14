# syntax=docker/dockerfile:1
# Review/production image for the Next.js storefront (BFF). Local development
# keeps using `bun dev` on the host; this image is only built by
# infrastructure/compose.review.yml.
FROM oven/bun:1.4.2-alpine@sha256:d888c0ae6c86d7866ff10c5aafdd9077b36aee6455b33dd270fb93c0dd5cef6f AS bun

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS builder
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
WORKDIR /app
COPY package.json bun.lock ./
RUN --mount=type=cache,target=/root/.bun/install/cache bun install --frozen-lockfile
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1 \
    NEXT_OUTPUT=standalone
RUN node node_modules/next/dist/bin/next build

# BFF session maintenance (expired-session cleanup and durable Hydra grant
# revocation retries), run once a minute by the review stack's
# `auth-maintenance` service. Sequential loop, so runs never overlap.
FROM builder AS maintenance
ENV NODE_ENV=production
USER node
CMD ["sh", "-c", "while :; do bun --conditions=react-server scripts/auth-maintenance.ts; sleep 60; done"]

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000
WORKDIR /app
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
COPY --from=builder --chown=node:node /app/public ./public
USER node
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/',{redirect:'manual'}).then(r=>process.exit(r.status<500?0:1),()=>process.exit(1))"]
CMD ["node", "server.js"]
