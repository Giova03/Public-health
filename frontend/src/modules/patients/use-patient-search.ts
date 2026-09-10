"use client";

/**
 * Module Patients (E1) — recherche combinée miroir + serveur.
 *
 * Stratégie offline-first :
 *  1. filtre instantané sur le miroir local (source d'affichage) —
 *     insensible aux diacritiques, calculée en useMemo sur une valeur
 *     différée pour garder la saisie fluide ;
 *  2. si le réseau simulé est actif et la requête ≥ 2 caractères, appel
 *     serveur debouncé (~300 ms) : le serveur voit plus que le miroir
 *     (structures hors du dernier delta). Les résultats sont fusionnés
 *     par id. En cas d'échec (NetworkError) : échec silencieux, le
 *     miroir suffit.
 *
 * Aucun setState synchrone dans l'effet : l'état serveur n'est mis à
 * jour que dans la continuation asynchrone de la promesse.
 */

import { useEffect, useMemo, useState, useDeferredValue } from "react";
import type { Patient } from "@/lib/types";
import { searchPatients } from "@/lib/api-client";
import { activePatients, filterMirrorPatients } from "./patient-utils";

const SERVER_DEBOUNCE_MS = 300;
const SERVER_MIN_QUERY = 2;

interface ServerSearch {
  /** Requête normalisée à laquelle ces résultats se rapportent. */
  q: string;
  patients: Patient[];
}

export interface PatientSearch {
  query: string;
  setQuery: (q: string) => void;
  /** Résultats fusionnés (miroir + serveur), triés updatedAt décroissant. */
  results: Patient[];
  /** Ids des patients connus du seul serveur (non encore dans le miroir). */
  serverOnlyIds: Set<string>;
  /** Un appel serveur est en cours pour la requête courante. */
  searching: boolean;
  /** Taille du miroir actif (pour le compteur « sur N dossiers »). */
  mirrorCount: number;
}

export function usePatientSearch(
  patients: Patient[],
  online: boolean,
): PatientSearch {
  const [query, setQuery] = useState("");
  const deferred = useDeferredValue(query);
  const [serverSearch, setServerSearch] = useState<ServerSearch | null>(null);

  const trimmed = deferred.trim();
  const eligible = online && trimmed.length >= SERVER_MIN_QUERY;

  useEffect(() => {
    if (!eligible) return;
    let active = true;
    const handle = setTimeout(() => {
      searchPatients({ q: trimmed })
        .then((res) => {
          if (active) setServerSearch({ q: trimmed, patients: res.patients });
        })
        .catch(() => {
          // Hors ligne réel ou erreur serveur : le miroir reste la source.
          if (active) setServerSearch({ q: trimmed, patients: [] });
        });
    }, SERVER_DEBOUNCE_MS);
    return () => {
      active = false;
      clearTimeout(handle);
    };
  }, [eligible, trimmed]);

  const searching = eligible && serverSearch?.q !== trimmed;

  const { results, serverOnlyIds } = useMemo(() => {
    const mirror = activePatients(patients);
    const local = filterMirrorPatients(mirror, deferred);
    if (!eligible || serverSearch?.q !== trimmed) {
      return { results: local, serverOnlyIds: new Set<string>() };
    }
    const localIds = new Set(local.map((p) => p.id));
    const extras = serverSearch.patients.filter((p) => !localIds.has(p.id));
    const merged = [...local, ...extras].sort(
      (a, b) =>
        b.updatedAt.localeCompare(a.updatedAt) ||
        a.name.family.localeCompare(b.name.family, "fr"),
    );
    return {
      results: merged,
      serverOnlyIds: new Set(extras.map((p) => p.id)),
    };
  }, [patients, deferred, eligible, serverSearch, trimmed]);

  const mirrorCount = useMemo(() => activePatients(patients).length, [patients]);

  return {
    query,
    setQuery,
    results,
    serverOnlyIds,
    searching,
    mirrorCount,
  };
}
