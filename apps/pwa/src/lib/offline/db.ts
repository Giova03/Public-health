"use client";

/**
 * Base offline — trois zones IndexedDB (ADR-05, offline-first non négociable).
 *
 * - outbox : opérations créées HORS LIGNE, en attente de montée.
 *   Chacune porte son opId (UUID v7 généré côté client) : le protocole
 *   /sync/batch de l'épique E2 sera idempotent par construction.
 * - mirror : copie locale des données de référence téléchargées
 *   (dossiers patients consultés, référentiels) — lecture hors ligne.
 * - meta   : curseurs de synchronisation par module, filigranes, état appareil.
 *
 * Sprint 0 : la STRUCTURE et les primitives sont en place. Le moteur de
 * synchronisation complet (tentatives, trempe, conflits) arrive avec E2.
 */

const NOM_BASE = "ph-offline";
const VERSION = 1;

export type Zone = "outbox" | "mirror" | "meta";

export interface OperationHorsLigne {
  /** UUID v7 — généré côté client, clé d'idempotence du protocole E2 */
  opId: string;
  /** Type d'opération, ex. "consultation.creee", "observation.ajoutee" */
  type: string;
  /** Identifiant de l'entité visée (UUID v7 côté client) */
  entiteId: string;
  /** Charge utile de l'opération (jamais de secret) */
  charge: unknown;
  etat: "EN_ATTENTE" | "ENVOYEE" | "REJETEE";
  tentatives: number;
  creeLe: number;
}

export interface EntreeMirror<T = unknown> {
  cle: string;
  module: string;
  /** Numéro de version miroir (pour invalider les anciens téléchargements) */
  version: number;
  donnees: T;
}

export interface MetaAppareil {
  deviceId: string;
  curseurs: Record<string, number>;
  dernierContact: number | null;
}

// Généré côté client : clé d'idempotence offline (E1 création, E2 sync).
export function uuidV7(): string {
  // RFC 9562 — 48 bits d'horodatage ms, version 7, variante RFC.
  const octets = crypto.getRandomValues(new Uint8Array(16));
  const ms = Date.now();
  const vue = new DataView(octets.buffer);
  vue.setUint32(0, Math.floor(ms / 2 ** 16));
  vue.setUint16(4, ms & 0xffff);
  vue.setUint16(6, (vue.getUint16(6) & 0x0fff) | 0x7000);
  vue.setUint16(8, (vue.getUint16(8) & 0x3fff) | 0x8000);
  const hex = Array.from(octets, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

let promesseBase: Promise<IDBDatabase> | null = null;

function ouvrirBase(): Promise<IDBDatabase> {
  if (promesseBase) return promesseBase;
  promesseBase = new Promise((resoudre, rejeter) => {
    const requete = indexedDB.open(NOM_BASE, VERSION);
    requete.onupgradeneeded = () => {
      const base = requete.result;
      if (!base.objectStoreNames.contains("outbox")) {
        base.createObjectStore("outbox", { keyPath: "opId" });
      }
      if (!base.objectStoreNames.contains("mirror")) {
        base.createObjectStore("mirror", { keyPath: "cle" });
      }
      if (!base.objectStoreNames.contains("meta")) {
        base.createObjectStore("meta", { keyPath: "deviceId" });
      }
    };
    requete.onsuccess = () => resoudre(requete.result);
    requete.onerror = () => rejeter(requete.error);
  });
  return promesseBase;
}

function transaction<T>(zone: Zone, mode: IDBTransactionMode, fn: (magasin: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return ouvrirBase().then(
    (base) =>
      new Promise<T>((resoudre, rejeter) => {
        const tx = base.transaction(zone, mode);
        const requete = fn(tx.objectStore(zone));
        requete.onsuccess = () => resoudre(requete.result);
        requete.onerror = () => rejeter(requete.error);
      }),
  );
}

// ------------------------------------------------------------------
// Outbox — ce que l'appareil doit faire remonter
// ------------------------------------------------------------------

export async function ajouterALOutbox(
  type: string,
  entiteId: string,
  charge: unknown,
): Promise<OperationHorsLigne> {
  const operation: OperationHorsLigne = {
    opId: uuidV7(),
    type,
    entiteId,
    charge,
    etat: "EN_ATTENTE",
    tentatives: 0,
    creeLe: Date.now(),
  };
  await transaction("outbox", "readwrite", (m) => m.add(operation));
  return operation;
}

export function listerOutbox(): Promise<OperationHorsLigne[]> {
  return transaction("outbox", "readonly", (m) => m.getAll() as IDBRequest<OperationHorsLigne[]>);
}

export async function compterEnAttente(): Promise<number> {
  const operations = await listerOutbox();
  return operations.filter((o) => o.etat === "EN_ATTENTE").length;
}

export function marquerOperation(opId: string, etat: OperationHorsLigne["etat"]): Promise<void> {
  return transaction("outbox", "readonly", (m) => m.get(opId) as IDBRequest<OperationHorsLigne | undefined>).then(
    (operation) => {
      if (!operation) return;
      operation.etat = etat;
      operation.tentatives += 1;
      return transaction("outbox", "readwrite", (m) => m.put(operation)).then(() => undefined);
    },
  );
}

// ------------------------------------------------------------------
// Mirror — ce que l'appareil a téléchargé pour lecture hors ligne
// ------------------------------------------------------------------

export function ecrireDansMirror<T>(entree: EntreeMirror<T>): Promise<IDBValidKey> {
  return transaction("mirror", "readwrite", (m) => m.put(entree as unknown as EntreeMirror));
}

export function lireDepuisMirror<T>(cle: string): Promise<EntreeMirror<T> | undefined> {
  return transaction("mirror", "readonly", (m) => m.get(cle) as IDBRequest<EntreeMirror<T> | undefined>);
}

/** Tout le miroir (E1 : recherche locale des dossiers consultés). */
export function listerMirror(): Promise<EntreeMirror[]> {
  return transaction("mirror", "readonly", (m) => m.getAll() as IDBRequest<EntreeMirror[]>);
}

// ------------------------------------------------------------------
// Meta — curseurs et identité de l'appareil
// ------------------------------------------------------------------

export function lireMeta(deviceId: string): Promise<MetaAppareil | undefined> {
  return transaction("meta", "readonly", (m) => m.get(deviceId) as IDBRequest<MetaAppareil | undefined>);
}

export function ecrireMeta(meta: MetaAppareil): Promise<IDBValidKey> {
  return transaction("meta", "readwrite", (m) => m.put(meta));
}
