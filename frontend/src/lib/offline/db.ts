/**
 * PUBLIC HEALTH — IndexedDB offline-first (E2).
 *
 * Trois zones, conformes à l'architecture PWA du projet :
 *  - outbox  : opérations en attente de drain (keyPath opId) ;
 *  - mirror  : miroir local des entités serveur (patients, ordonnances,
 *              paiements) — la source d'affichage hors ligne ;
 *  - meta    : curseur delta, deviceId, horodatage de dernière synchro.
 *
 * Aucune PHI ne quitte l'appareil vers un tiers : tout reste local
 * jusqu'au drain vers l'API du ministère.
 */

import type { Patient, PaymentRecord, Prescription, SyncOperation } from "@/lib/types";

const DB_NAME = "public-health";
const DB_VERSION = 1;

export type MirrorKind = "patient" | "prescription" | "payment";

interface MirrorRow {
  id: string;
  kind: MirrorKind;
  data: Patient | Prescription | PaymentRecord;
}

export interface MetaRow {
  key: string;
  value: unknown;
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("outbox")) {
        db.createObjectStore("outbox", { keyPath: "opId" });
      }
      if (!db.objectStoreNames.contains("mirror")) {
        const mirror = db.createObjectStore("mirror", { keyPath: "id" });
        mirror.createIndex("byKind", "kind", { unique: false });
      }
      if (!db.objectStoreNames.contains("meta")) {
        db.createObjectStore("meta", { keyPath: "key" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

function tx<T>(
  storeName: string,
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(storeName, mode);
        const request = run(transaction.objectStore(storeName));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }),
  );
}

/* ------------------------------- Outbox ------------------------------- */

export function putOutbox(op: SyncOperation): Promise<void> {
  return tx("outbox", "readwrite", (s) => s.put(op)).then(() => undefined);
}

export function deleteOutbox(opId: string): Promise<void> {
  return tx("outbox", "readwrite", (s) => s.delete(opId)).then(() => undefined);
}

export function getAllOutbox(): Promise<SyncOperation[]> {
  return tx<SyncOperation[]>("outbox", "readonly", (s) => s.getAll());
}

export function clearOutbox(): Promise<void> {
  return tx("outbox", "readwrite", (s) => s.clear()).then(() => undefined);
}

/* ------------------------------- Mirror ------------------------------- */

export function putMirror(
  kind: MirrorKind,
  data: Patient | Prescription | PaymentRecord,
): Promise<void> {
  const row: MirrorRow = { id: data.id, kind, data };
  return tx("mirror", "readwrite", (s) => s.put(row)).then(() => undefined);
}

export function deleteMirror(id: string): Promise<void> {
  return tx("mirror", "readwrite", (s) => s.delete(id)).then(() => undefined);
}

export function getAllMirror(): Promise<MirrorRow[]> {
  return tx<MirrorRow[]>("mirror", "readonly", (s) => s.getAll());
}

/* -------------------------------- Meta -------------------------------- */

export function getMeta<T>(key: string): Promise<T | undefined> {
  return tx<MetaRow | undefined>("meta", "readonly", (s) => s.get(key)).then(
    (row) => (row ? (row.value as T) : undefined),
  );
}

export function setMeta(key: string, value: unknown): Promise<void> {
  return tx("meta", "readwrite", (s) => s.put({ key, value })).then(
    () => undefined,
  );
}
