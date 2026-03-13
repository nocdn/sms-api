import { createApp } from "./app";
import { createMessageStore } from "./db";
import { createGroqCodeExtractor } from "./groq";
import { readEnv } from "./env";
import { createLogger } from "./logger";

const { DATABASE_URL, PORT, GROQ_API_KEY, LOG_LEVEL } = readEnv();
const logger = createLogger(LOG_LEVEL);
const messageStore = createMessageStore(DATABASE_URL);

await messageStore.ensureSchema();

const app = createApp(messageStore, {
  logger,
  codeExtractor: GROQ_API_KEY ? createGroqCodeExtractor(GROQ_API_KEY) : undefined,
});

const shutdown = async () => {
  logger.info("Shutting down backend");
  await messageStore.close();
};

process.on("SIGINT", () => {
  void shutdown().finally(() => process.exit(0));
});

process.on("SIGTERM", () => {
  void shutdown().finally(() => process.exit(0));
});

logger.info({ port: PORT }, "SMS API backend listening");

export default {
  port: PORT,
  fetch: app.fetch,
};
