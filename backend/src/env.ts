import { z } from "zod";

const envSchema = z.object({
  DATABASE_URL: z.string().min(1, "DATABASE_URL is required"),
  PORT: z.coerce.number().int().min(1).max(65535).default(6730),
  GROQ_API_KEY: z.string().min(1).optional(),
  LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]).default("info"),
});

export type AppEnv = z.infer<typeof envSchema>;

export const readEnv = (): AppEnv => {
  const result = envSchema.safeParse({
    DATABASE_URL: process.env.DATABASE_URL,
    PORT: process.env.PORT ?? 6730,
    GROQ_API_KEY: process.env.GROQ_API_KEY,
    LOG_LEVEL: process.env.LOG_LEVEL ?? "info",
  });

  if (!result.success) {
    throw new Error(result.error.issues.map((issue) => issue.message).join("; "));
  }

  return result.data;
};

export const normalizeDatabaseUrl = (value: string): string => {
  let parsed: URL;

  try {
    parsed = new URL(value);
  } catch {
    throw new Error("DATABASE_URL must be a valid PostgreSQL connection string");
  }

  if (parsed.protocol != "postgres:" && parsed.protocol != "postgresql:") {
    throw new Error("DATABASE_URL must use the postgres:// or postgresql:// scheme");
  }

  if (!parsed.searchParams.has("sslmode")) {
    parsed.searchParams.set("sslmode", "verify-full");
  }

  return parsed.toString();
};
