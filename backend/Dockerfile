FROM oven/bun:1 AS base
WORKDIR /usr/src/app

FROM base AS install
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production

FROM base AS prerelease
COPY package.json bun.lock tsconfig.json ./
RUN bun install --frozen-lockfile
COPY src ./src
RUN bun test
RUN bun run typecheck

FROM base AS release
ENV NODE_ENV=production
COPY --from=install /usr/src/app/node_modules ./node_modules
COPY package.json bun.lock ./
COPY src ./src
USER bun
EXPOSE 6730
ENTRYPOINT ["bun", "run", "start"]
