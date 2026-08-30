import type { AuthState, OutboxEntry } from "./types";

const DATABASE_NAME = "readthat-web";
const DATABASE_VERSION = 1;

interface CacheRecord<T = unknown> {
  key: string;
  value: T;
  updatedAt: number;
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("cache")) {
        database.createObjectStore("cache", { keyPath: "key" });
      }
      if (!database.objectStoreNames.contains("outbox")) {
        const store = database.createObjectStore("outbox", { keyPath: "id" });
        store.createIndex("account-created", ["accountId", "createdAt"]);
      }
      if (!database.objectStoreNames.contains("session")) {
        database.createObjectStore("session", { keyPath: "key" });
      }
      if (!database.objectStoreNames.contains("video-cache")) {
        const store = database.createObjectStore("video-cache", { keyPath: "url" });
        store.createIndex("last-accessed", "lastAccessed");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Could not open offline database"));
  });
}

async function run<T>(
  storeName: "cache" | "outbox" | "session" | "video-cache",
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const database = await openDatabase();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, mode);
    const request = action(transaction.objectStore(storeName));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error(`IndexedDB ${storeName} operation failed`));
    transaction.oncomplete = () => database.close();
    transaction.onabort = () => reject(transaction.error ?? new Error(`IndexedDB ${storeName} transaction aborted`));
  });
}

export async function readCache<T>(key: string): Promise<CacheRecord<T> | null> {
  const record = await run<CacheRecord<T> | undefined>("cache", "readonly", (store) => store.get(key));
  return record ?? null;
}

export async function writeCache<T>(key: string, value: T): Promise<void> {
  await run<IDBValidKey>("cache", "readwrite", (store) => store.put({ key, value, updatedAt: Date.now() }));
}

export async function deleteCache(key: string): Promise<void> {
  await run<undefined>("cache", "readwrite", (store) => store.delete(key));
}

export async function saveAuthState(state: AuthState | null): Promise<void> {
  if (state) {
    await run<IDBValidKey>("session", "readwrite", (store) => store.put({ key: "current", value: state }));
  } else {
    await run<undefined>("session", "readwrite", (store) => store.delete("current"));
  }
}

export async function loadAuthState(): Promise<AuthState | null> {
  const record = await run<{ key: string; value: AuthState } | undefined>(
    "session",
    "readonly",
    (store) => store.get("current"),
  );
  return record?.value ?? null;
}

export async function putOutbox(entry: OutboxEntry): Promise<void> {
  await run<IDBValidKey>("outbox", "readwrite", (store) => store.put(entry));
}

export async function removeOutbox(id: string): Promise<void> {
  await run<undefined>("outbox", "readwrite", (store) => store.delete(id));
}

export async function listOutbox(accountId?: string): Promise<OutboxEntry[]> {
  const all = await run<OutboxEntry[]>("outbox", "readonly", (store) => store.getAll());
  return all
    .filter((entry) => !accountId || entry.accountId === accountId)
    .sort((left, right) => left.createdAt - right.createdAt);
}

export async function clearApplicationData(): Promise<void> {
  const database = await openDatabase();
  await Promise.all(["cache", "outbox", "session", "video-cache"].map((storeName) => new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(storeName, "readwrite");
    const request = transaction.objectStore(storeName).clear();
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
  })));
  database.close();
}
