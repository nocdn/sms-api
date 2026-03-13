import { Hono } from "hono";
import { cors } from "hono/cors";
import { validator } from "hono/validator";
import type { Logger } from "pino";
import { z } from "zod";
import { extractCode } from "./code";
import type { CodeExtractor, MessageStore } from "./types";

type AppBindings = {
  Variables: {
    logger: Logger;
    requestId: string;
  };
};

const receivedAtSchema = z
  .preprocess((value) => {
    if (value instanceof Date) {
      return value;
    }

    if (typeof value == "number") {
      return new Date(value);
    }

    if (typeof value == "string") {
      return new Date(value);
    }

    return value;
  }, z.date())
  .refine((value) => !Number.isNaN(value.getTime()), "receivedAt must be a valid date");

const messageSchema = z.object({
  address: z.string().trim().min(1).max(255),
  body: z.string().trim().min(1).max(10000),
  receivedAt: receivedAtSchema,
});

const lastQuerySchema = z.object({
  last: z
    .string()
    .optional()
    .transform((value) => {
      if (value == undefined || value.trim() == "") {
        return 1;
      }

      const parsedValue = Number(value);

      if (!Number.isInteger(parsedValue)) {
        return Number.NaN;
      }

      return parsedValue;
    })
    .refine((value) => value == -1 || value > 0, "last must be a positive integer or -1"),
});

type CreateAppOptions = {
  logger: Logger;
  codeExtractor?: CodeExtractor;
};

export const createApp = (messageStore: MessageStore, options: CreateAppOptions) => {
  const app = new Hono<AppBindings>();

  app.use("*", async (c, next) => {
    const requestId = crypto.randomUUID();
    const requestLogger = options.logger.child({
      requestId,
      method: c.req.method,
      path: c.req.path,
    });
    const start = performance.now();

    c.set("logger", requestLogger);
    c.set("requestId", requestId);

    requestLogger.info({ query: c.req.query() }, "request started");

    await next();

    requestLogger.info(
      {
        status: c.res.status,
        durationMs: Math.round((performance.now() - start) * 100) / 100,
      },
      "request completed",
    );
  });

  app.use("/api/*", cors({
    origin: "*",
    allowMethods: ["GET", "POST", "OPTIONS"],
    allowHeaders: ["Content-Type"],
  }));

  app.get("/", (c) => {
    return c.json({
      service: "smsapi-backend",
      status: "ok",
    });
  });

  app.get("/health", (c) => {
    return c.json({ ok: true });
  });

  app.get(
    "/api/messages",
    validator("query", (value, c) => {
      const result = lastQuerySchema.safeParse(value);

      if (!result.success) {
        return c.json(
          {
            error: "Invalid query parameter",
            details: result.error.flatten(),
          },
          400,
        );
      }

      return result.data;
    }),
    async (c) => {
      const { last } = c.req.valid("query");
      const messages = await messageStore.listMessages(last);

      return c.json({
        data: messages,
        count: messages.length,
      });
    },
  );

  app.post(
    "/api/messages",
    validator("json", (value, c) => {
      const result = messageSchema.safeParse(value);

      if (!result.success) {
        return c.json(
          {
            error: "Invalid request body",
            details: result.error.flatten(),
          },
          400,
        );
      }

      return result.data;
    }),
    async (c) => {
      const payload = c.req.valid("json");
      const message = await messageStore.insertMessage(payload);

      return c.json({ data: message }, 201);
    },
  );

  app.get("/api/messages/code", async (c) => {
    const requestLogger = c.get("logger");
    const [latestMessage] = await messageStore.listMessages(1);

    if (!latestMessage) {
      requestLogger.info({ latestMessage: null }, "No latest message available for code extraction");
      return c.json({ error: "No messages found" }, 404);
    }

    requestLogger.info({ latestMessage }, "Loaded latest message for code extraction");

    let code: string | null = null;
    let extractionMethod: "groq" | "regex_fallback" | null = null;

    if (options.codeExtractor) {
      try {
        code = await options.codeExtractor(latestMessage, requestLogger);
        if (code) {
          extractionMethod = "groq";
        }
      } catch (error) {
        requestLogger.warn({ err: error }, "Groq code extraction failed, falling back to regex extraction");
      }
    } else {
      requestLogger.info("Groq code extractor is not configured, using regex fallback");
    }

    if (!code) {
      code = extractCode(latestMessage.body);
      requestLogger.info({ fallbackInput: latestMessage.body, fallbackOutput: code }, "Processed regex fallback extraction");
      if (code) {
        extractionMethod = "regex_fallback";
      }
    }

    if (!code) {
      requestLogger.info({ extractionMethod: "none" }, "Code extraction did not find a code");
      return c.json({ error: "No code found in latest message" }, 404);
    }

    requestLogger.info({ extractionMethod }, "Code extracted from latest message");

    return c.json({ code });
  });

  app.notFound((c) => {
    return c.json({ error: "Not found" }, 404);
  });

  app.onError((error, c) => {
    c.get("logger").error({ err: error }, "Unhandled request error");
    return c.json({ error: "Internal server error" }, 500);
  });

  return app;
};
