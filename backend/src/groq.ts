import type { CodeExtractor, MessageRecord } from "./types";

type GroqChatCompletionResponse = {
  choices?: Array<{
    message?: {
      content?: string | null;
    };
  }>;
};

const GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
const MODEL = "moonshotai/kimi-k2-instruct-0905";

const buildPrompt = (message: MessageRecord) => ({
  messages: [
    {
      role: "system",
      content: "You extract one-time passcodes and verification codes from SMS messages. Return only the exact code from the message with no explanation, no surrounding quotes, no markdown, and no extra text. Preserve letters, digits, dashes, or spaces exactly if they are part of the code. If there is no code in the message, return NONE.",
    },
    {
      role: "user",
      content: [
        `Sender: ${message.address}`,
        `Received at: ${message.receivedAt}`,
        "Message:",
        message.body,
      ].join("\n"),
    },
  ],
  model: MODEL,
  temperature: 0,
});

const normalizeCompletion = (content: string | null | undefined): string | null => {
  const value = content?.trim();

  if (!value || value.toUpperCase() == "NONE") {
    return null;
  }

  return value;
};

export const createGroqCodeExtractor = (apiKey: string): CodeExtractor => {
  return async (message: MessageRecord, logger) => {
    const requestBody = buildPrompt(message);
    logger?.info(
      {
        groqRequest: requestBody,
      },
      "Sending code extraction request to Groq",
    );

    const response = await fetch(GROQ_API_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(requestBody),
    });

    const responseText = await response.text();

    logger?.info(
      {
        groqStatus: response.status,
        groqResponseBody: responseText,
      },
      "Received response from Groq",
    );

    if (!response.ok) {
      throw new Error(`Groq request failed with status ${response.status}`);
    }

    const payload = JSON.parse(responseText) as GroqChatCompletionResponse;
    const normalizedCompletion = normalizeCompletion(payload.choices?.[0]?.message?.content);

    logger?.info(
      {
        groqRawCompletion: payload.choices?.[0]?.message?.content ?? null,
        groqNormalizedCode: normalizedCompletion,
      },
      "Processed Groq code extraction response",
    );

    return normalizedCompletion;
  };
};
