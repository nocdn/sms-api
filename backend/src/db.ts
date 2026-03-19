import { SQL } from "bun";
import { normalizeDatabaseUrl } from "./env";
import type { MessageRecord, MessageStore, NewMessage } from "./types";

type MessageRow = {
  id: string;
  address: string;
  body: string;
  receivedAt: Date | string;
  createdAt: Date | string;
};

const toIsoString = (value: Date | string): string => {
  return value instanceof Date ? value.toISOString() : new Date(value).toISOString();
};

const mapMessageRow = (row: MessageRow): MessageRecord => {
  return {
    id: row.id,
    address: row.address,
    body: row.body,
    receivedAt: toIsoString(row.receivedAt),
    createdAt: toIsoString(row.createdAt),
  };
};

export const createMessageStore = (databaseUrl: string): MessageStore => {
  const db = new SQL(normalizeDatabaseUrl(databaseUrl), {
    prepare: false,
    max: 5,
    idleTimeout: 30,
    connectionTimeout: 10,
  });

  return {
    async ensureSchema() {
      await db`
        CREATE TABLE IF NOT EXISTS messages (
          id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          address TEXT NOT NULL,
          body TEXT NOT NULL,
          received_at TIMESTAMPTZ NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
      `;

      await db`
        CREATE INDEX IF NOT EXISTS messages_received_at_idx
        ON messages (received_at DESC)
      `;

      await db`
        CREATE UNIQUE INDEX IF NOT EXISTS messages_dedup_idx
        ON messages (address, body, received_at)
      `;
    },

    async insertMessage(message) {
      const rows = (await db`
        INSERT INTO messages (address, body, received_at)
        VALUES (${message.address}, ${message.body}, ${message.receivedAt})
        ON CONFLICT (address, body, received_at) DO NOTHING
        RETURNING
          id::text AS "id",
          address,
          body,
          received_at AS "receivedAt",
          created_at AS "createdAt"
      `) as MessageRow[];

      if (rows.length === 0) {
        const existing = (await db`
          SELECT
            id::text AS "id",
            address,
            body,
            received_at AS "receivedAt",
            created_at AS "createdAt"
          FROM messages
          WHERE address = ${message.address}
            AND body = ${message.body}
            AND received_at = ${message.receivedAt}
          LIMIT 1
        `) as MessageRow[];
        return mapMessageRow(existing[0]);
      }

      return mapMessageRow(rows[0]);
    },

    async listMessages(last) {
      const rows = last == -1
        ? ((await db`
            SELECT
              id::text AS "id",
              address,
              body,
              received_at AS "receivedAt",
              created_at AS "createdAt"
            FROM messages
            ORDER BY received_at DESC, id DESC
          `) as MessageRow[])
        : ((await db`
            SELECT
              id::text AS "id",
              address,
              body,
              received_at AS "receivedAt",
              created_at AS "createdAt"
            FROM messages
            ORDER BY received_at DESC, id DESC
            LIMIT ${last}
          `) as MessageRow[]);

      return rows.map(mapMessageRow);
    },

    async replaceAllMessages(messages) {
      return await db.begin(async (tx) => {
        await tx`DELETE FROM messages`;

        for (const message of messages) {
          await tx`
            INSERT INTO messages (address, body, received_at)
            VALUES (${message.address}, ${message.body}, ${message.receivedAt})
            ON CONFLICT (address, body, received_at) DO NOTHING
          `;
        }

        return messages.length;
      });
    },

    async close() {
      await db.close({ timeout: 5 });
    },
  };
};
