import pino, { stdTimeFunctions, type Logger } from "pino";

export const createLogger = (level: string): Logger => {
  return pino({
    base: undefined,
    name: "smsapi-backend",
    level,
    timestamp: stdTimeFunctions.isoTime,
    formatters: {
      level: (label: string) => ({ level: label }),
    },
    serializers: {
      err: pino.stdSerializers.err,
    },
  });
};
