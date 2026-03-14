import type { Logger } from "pino";

export type MessageRecord = {
  id: string;
  address: string;
  body: string;
  receivedAt: string;
  createdAt: string;
};

export type NewMessage = {
  address: string;
  body: string;
  receivedAt: Date;
};

export interface MessageStore {
  ensureSchema(): Promise<void>;
  insertMessage(message: NewMessage): Promise<MessageRecord>;
  listMessages(last: number): Promise<MessageRecord[]>;
  replaceAllMessages(messages: NewMessage[]): Promise<number>;
  close(): Promise<void>;
}

export type CodeExtractor = (message: MessageRecord, logger?: Logger) => Promise<string | null>;
