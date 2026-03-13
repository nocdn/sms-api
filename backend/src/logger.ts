import pino, { stdTimeFunctions, type Logger } from "pino";

export const createLogger = (level: string): Logger => {
  return pino({
    base: undefined,
    name: "smsapi-backend",
    level,
    timestamp: stdTimeFunctions.isoTime,
    formatters: {
      level: () => ({}),
    },
    serializers: {
      err: pino.stdSerializers.err,
    },
  });
};
