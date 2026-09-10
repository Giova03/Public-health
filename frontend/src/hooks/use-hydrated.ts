"use client";

import { useSyncExternalStore } from "react";

const emptySubscribe = () => () => {};

/**
 * useIsHydrated — vrai uniquement après l'hydratation client.
 *
 * Pattern canonique React 19 (remplace le couple useState/useEffect qui
 * déclenche la règle react-hooks/set-state-in-effect) : le snapshot serveur
 * est false, le snapshot client devient true après montage, sans setState
 * synchrone dans un effet.
 */
export function useIsHydrated(): boolean {
  return useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false,
  );
}
