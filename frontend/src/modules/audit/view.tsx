"use client";

/**
 * Vue Audit (V14, I16) — la chaîne existait, l'écran manquait.
 * Journal des accès et actions : QUI a fait QUOI, refus DENIED compris.
 */

import { useEffect } from "react";
import { ShieldCheck } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export function AuditView() {
  const auditEntries = useAppStore((s) => s.auditEntries);
  const loadAudit = useAppStore((s) => s.loadAudit);

  useEffect(() => { void loadAudit(); }, [loadAudit]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Journal d&apos;audit</h1>
        <p className="text-sm text-muted-foreground">
          Chaîne append-only scellée par hachage SHA-256 — chaque ligne : qui a
          fait quoi, quand, avec quel résultat (SUCCESS / FAILURE / DENIED).
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <ShieldCheck className="h-4 w-4" /> {auditEntries.length} entrées récentes
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          {auditEntries.length === 0 && (
            <p className="text-sm text-muted-foreground">Journal vide.</p>
          )}
          {auditEntries.map((entree, i) => (
            <div key={i} className="flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2 text-xs">
              <span className="text-muted-foreground">
                {new Date(entree.date).toLocaleString("fr-FR")}
              </span>
              <Badge variant="outline" className="font-mono">{entree.action}</Badge>
              <span className="font-mono text-[10px] text-muted-foreground">
                {entree.acteur.slice(0, 10)}
              </span>
              <span className="text-muted-foreground">{entree.entite}</span>
              {entree.motif && (
                <span className="min-w-0 flex-1 truncate text-muted-foreground">{entree.motif}</span>
              )}
              <Badge
                variant="secondary"
                className={
                  entree.resultat === "DENIED"
                    ? "bg-red-500/10 text-red-700 dark:text-red-400"
                    : entree.resultat === "FAILURE"
                      ? "bg-amber-500/10 text-amber-700 dark:text-amber-400"
                      : "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                }
              >
                {entree.resultat}
              </Badge>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
