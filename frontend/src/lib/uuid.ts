/**
 * UUID v7 côté client — RFC 9562 (identifiants triables par creation,
 * indispensables à l'offline-first : l'ordre existe même sans serveur).
 *
 * Implémentation manuelle : crypto.randomUUID() écrase les bits de
 * version/variante du champ time_hi_and_version ; on pose donc les bits
 * v7 (0111) après génération, comme côté backend Java (bug réel corrigé
 * au Sprint 0 — voir worklog, tâche 3).
 */
export function uuidV7(): string {
  const raw = crypto.randomUUID();
  const bytes = new Uint8Array(
    (raw.match(/[0-9a-f]{2}/g) ?? []).map((h) => parseInt(h, 16)),
  );

  // time_hi_and_version : bits 12-15 = 0111 (version 7)
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  // clock_seq_hi_and_reserved : variante RFC 4122
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join(
    "",
  );
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}
