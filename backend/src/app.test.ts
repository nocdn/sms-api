import { describe, expect, it } from "bun:test";
import { createApp } from "./app";
import { createLogger } from "./logger";
import type { CodeExtractor, MessageRecord, MessageStore, NewMessage } from "./types";

const testLogger = createLogger("silent");

const createInMemoryStore = (): MessageStore => {
  const messages: MessageRecord[] = [];
  let currentId = 1;

  return {
    async ensureSchema() {},

    async insertMessage(message: NewMessage) {
      const storedMessage: MessageRecord = {
        id: String(currentId++),
        address: message.address,
        body: message.body,
        receivedAt: message.receivedAt.toISOString(),
        createdAt: new Date("2026-01-01T00:00:00.000Z").toISOString(),
      };

      messages.unshift(storedMessage);
      return storedMessage;
    },

    async listMessages(last: number) {
      return last == -1 ? messages : messages.slice(0, last);
    },

    async close() {},
  };
};

describe("backend API", () => {
  it("stores and returns the latest message by default", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    const firstCreateResponse = await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Your one-time code is 123456",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    const secondCreateResponse = await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+449999999999",
        body: "Your second code is 999999",
        receivedAt: "2026-03-13T12:05:00.000Z",
      }),
    });

    expect(firstCreateResponse.status).toBe(201);
    expect(secondCreateResponse.status).toBe(201);

    const listResponse = await app.request("/api/messages");
    const payload = await listResponse.json();

    expect(listResponse.status).toBe(200);
    expect(payload.count).toBe(1);
    expect(payload.data[0].address).toBe("+449999999999");
  });

  it("supports the last query parameter", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    const messages = [
      {
        address: "+441111111111",
        body: "First",
        receivedAt: "2026-03-13T12:00:00.000Z",
      },
      {
        address: "+442222222222",
        body: "Second",
        receivedAt: "2026-03-13T12:01:00.000Z",
      },
      {
        address: "+443333333333",
        body: "Third",
        receivedAt: "2026-03-13T12:02:00.000Z",
      },
    ];

    for (const message of messages) {
      const response = await app.request("/api/messages", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(message),
      });

      expect(response.status).toBe(201);
    }

    const latestResponse = await app.request("/api/messages?last=1");
    const latestPayload = await latestResponse.json();
    expect(latestResponse.status).toBe(200);
    expect(latestPayload.count).toBe(1);
    expect(latestPayload.data[0].address).toBe("+443333333333");

    const allResponse = await app.request("/api/messages?last=-1");
    const allPayload = await allResponse.json();
    expect(allResponse.status).toBe(200);
    expect(allPayload.count).toBe(3);
    expect(allPayload.data[2].address).toBe("+441111111111");
  });

  it("rejects invalid last query values", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    const response = await app.request("/api/messages?last=0");

    expect(response.status).toBe(400);
  });

  it("rejects invalid request bodies", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    const response = await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "",
        body: "",
      }),
    });

    expect(response.status).toBe(400);
  });

  it("extracts a code from the latest message", async () => {
    const codeExtractor: CodeExtractor = async (message) => {
      expect(message.body).toBe("Your code is 654321");
      return "654321";
    };

    const app = createApp(createInMemoryStore(), { logger: testLogger, codeExtractor });

    const createResponse = await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Your code is 654321",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    expect(createResponse.status).toBe(201);

    const response = await app.request("/api/messages/code");

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ code: "654321" });
  });

  it("returns 404 when the latest message has no extractable code", async () => {
    const codeExtractor: CodeExtractor = async () => null;
    const app = createApp(createInMemoryStore(), { logger: testLogger, codeExtractor });

    await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Hello there",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    const response = await app.request("/api/messages/code");

    expect(response.status).toBe(404);
  });

  it("falls back to regex extraction when Groq is not configured", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Your code is 112233",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    const response = await app.request("/api/messages/code");

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ code: "112233" });
  });

  it("falls back to regex extraction for passcodes-style messages", async () => {
    const app = createApp(createInMemoryStore(), { logger: testLogger });

    await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Duo SMS passcodes: 7581584",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    const response = await app.request("/api/messages/code");

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ code: "7581584" });
  });

  it("falls back to regex extraction when Groq fails", async () => {
    const codeExtractor: CodeExtractor = async () => {
      throw new Error("temporary upstream failure");
    };
    const app = createApp(createInMemoryStore(), { logger: testLogger, codeExtractor });

    await app.request("/api/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        address: "+441234567890",
        body: "Your code is: 5WGU8G",
        receivedAt: "2026-03-13T12:00:00.000Z",
      }),
    });

    const response = await app.request("/api/messages/code");

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ code: "5WGU8G" });
  });
});
