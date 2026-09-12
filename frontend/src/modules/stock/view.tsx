"use client";

/**
 * Vue Stock (V14, I8) — la pharmacie adossée au réel.
 * Solde par médicament, ruptures visibles, réceptions CAMEG/CSD,
 * inventaire mensuel COCOM. La dispensation décrémente automatiquement.
 */

import { useEffect, useState } from "react";
import { PackagePlus, TriangleAlert } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";

export function StockView() {
  const stockItems = useAppStore((s) => s.stockItems);
  const stockMouvements = useAppStore((s) => s.stockMouvements);
  const loadStock = useAppStore((s) => s.loadStock);
  const createStockMouvement = useAppStore((s) => s.createStockMouvement);
  // P0-3 (audit, étape 3) : stock:gerer (vue déjà réservée, défense en
  // profondeur : session expirée / état résiduel → refus explicite ici).
  const peutGerer = usePermission("stock:gerer");
  const { toast } = useToast();

  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [quantite, setQuantite] = useState("");
  const [motif, setMotif] = useState("");

  useEffect(() => { void loadStock(); }, [loadStock]);

  async function mouvement(type: "reception" | "ajustement") {
    if (!peutGerer) {
      toast({
        title: "Action refusée — stock",
        description: "La permission stock:gerer est requise (pharmacien/admin).",
        variant: "destructive",
      });
      return;
    }
    if (!code.trim() || !quantite) {
      toast({ title: "Champs manquants", description: "Code médicament et quantité obligatoires.", variant: "destructive" });
      return;
    }
    const resultat = await createStockMouvement({
      medicationCode: code.trim(),
      medicationLabel: label.trim() || undefined,
      type,
      quantity: Number(quantite),
      motif: motif.trim() || undefined,
    });
    if (resultat.status !== "ok") {
      toast({ title: "Mouvement refusé", description: "message" in resultat ? resultat.message : "Refus", variant: "destructive" });
      return;
    }
    toast({ title: type === "reception" ? "Réception enregistrée" : "Inventaire ajusté" });
    setCode(""); setQuantite(""); setMotif("");
  }

  const ruptures = stockItems.filter((i) => i.quantity <= 0);
  const sousSeuil = stockItems.filter((i) => i.quantity > 0 && i.quantity <= i.seuilAlerte);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Stock de la pharmacie</h1>
        <p className="text-sm text-muted-foreground">
          La dispensation décrémente le solde ; une rupture refuse la dispensation
          suivante (409). Inventaire mensuel COCOM, réceptions trimestrielles.
        </p>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Ruptures</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold text-red-600">{ruptures.length}</p>
            <p className="text-xs text-muted-foreground">médicament(s) épuisé(s)</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Sous le seuil</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold text-amber-600">{sousSeuil.length}</p>
            <p className="text-xs text-muted-foreground">à commander en urgence</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Références suivies</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold">{stockItems.length}</p>
            <p className="text-xs text-muted-foreground">lignes de stock actives</p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Réception / Inventaire</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          <div className="space-y-1.5">
            <Label>Code</Label>
            <Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="AL-ACT" className="rounded-full font-mono" />
          </div>
          <div className="space-y-1.5">
            <Label>Libellé</Label>
            <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Artéméther-Luméfantrine" className="rounded-full" />
          </div>
          <div className="space-y-1.5">
            <Label>{`Quantité (réception) / solde (inventaire)`}</Label>
            <Input type="number" value={quantite} onChange={(e) => setQuantite(e.target.value)} className="rounded-full" />
          </div>
          <div className="space-y-1.5">
            <Label>Motif</Label>
            <Input value={motif} onChange={(e) => setMotif(e.target.value)} placeholder="Réception CAMEG…" className="rounded-full" />
          </div>
          <div className="flex items-end gap-2">
            <Button variant="medical" className="rounded-full" onClick={() => mouvement("reception")} disabled={!peutGerer}>
              <PackagePlus className="h-4 w-4" /> Réception
            </Button>
            <Button variant="outline" className="rounded-full" onClick={() => mouvement("ajustement")} disabled={!peutGerer}>
              Inventaire
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">État du stock</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {stockItems.length === 0 && (
            <p className="text-sm text-muted-foreground">Aucune ligne — enregistrez une réception.</p>
          )}
          {stockItems.map((item) => {
            const ratio = Math.min(100, Math.round((item.quantity / Math.max(item.seuilAlerte * 4, 1)) * 100));
            const enRupture = item.quantity <= 0;
            const alerte = !enRupture && item.quantity <= item.seuilAlerte;
            return (
              <div key={item.id} className="rounded-2xl border p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="text-sm font-medium">{item.medicationLabel}</p>
                    <p className="font-mono text-[11px] text-muted-foreground">{item.medicationCode} · {item.structure}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    {enRupture && (
                      <Badge variant="secondary" className="bg-red-500/10 text-red-700 dark:text-red-400">
                        <TriangleAlert className="mr-1 h-3 w-3" /> RUPTURE
                      </Badge>
                    )}
                    {alerte && (
                      <Badge variant="secondary" className="bg-amber-500/10 text-amber-700 dark:text-amber-400">
                        Sous le seuil
                      </Badge>
                    )}
                    <span className="tnum text-sm font-bold">
                      {item.quantity} <span className="text-xs font-normal text-muted-foreground">/ seuil {item.seuilAlerte}</span>
                    </span>
                  </div>
                </div>
                <Progress value={ratio} className="mt-2 h-1.5" />
              </div>
            );
          })}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="text-base">Mouvements (append-only)</CardTitle></CardHeader>
        <CardContent className="space-y-1.5">
          {stockMouvements.slice(0, 12).map((m) => (
            <div key={m.id} className="flex flex-wrap items-center gap-2 text-xs">
              <span className="text-muted-foreground">{new Date(m.date).toLocaleString("fr-FR")}</span>
              <Badge variant="outline" className="font-mono">{m.type}</Badge>
              <span className="font-mono">{m.medicationCode}</span>
              <span className="tnum font-semibold">{m.quantity}</span>
              <span className="text-muted-foreground">{m.motif}</span>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
