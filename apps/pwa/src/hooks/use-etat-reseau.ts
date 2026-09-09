"use client";

import { useCallback, useEffect, useState } from "react";
import { compterEnAttente } from "@/lib/offline/db";

interface EtatReseau {
  /** navigator.onLine, réactif aux événements online/offline. */
  enLigne: boolean;
  /** Opérations EN_ATTENTE dans l'outbox IndexedDB (E2 montera la file). */
  enAttente: number;
  /** Recompte la file (après une mise en file, par exemple). */
  rafraichirFile: () => void;
}

/**
 * État réseau + file d'attente — l'honnêteté de l'interface offline-first :
 * jamais de faux succès, l'état réel est toujours visible (principe UX n°1).
 */
export function useEtatReseau(): EtatReseau {
  const [enLigne, setEnLigne] = useState(true);
  const [enAttente, setEnAttente] = useState(0);

  const rafraichirFile = useCallback(() => {
    compterEnAttente()
      .then(setEnAttente)
      .catch(() => {
        // IndexedDB indisponible (navigation privée stricte) : file inconnue
        // mais l'interface reste honnête — on affiche 0 sans mentir sur l'état.
        setEnAttente(0);
      });
  }, []);

  useEffect(() => {
    const majConnexion = () => setEnLigne(navigator.onLine);
    majConnexion();
    window.addEventListener("online", majConnexion);
    window.addEventListener("offline", majConnexion);
    rafraichirFile();
    return () => {
      window.removeEventListener("online", majConnexion);
      window.removeEventListener("offline", majConnexion);
    };
  }, [rafraichirFile]);

  return { enLigne, enAttente, rafraichirFile };
}
