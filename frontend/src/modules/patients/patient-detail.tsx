"use client";

/**
 * Module Patients (E1) — fiche patient (vue dossier).
 *
 * En-tête identitaire (avatar initiales, badges), bloc identité complet,
 * callouts d'honnêteté offline (référence à la synchronisation, création
 * forcée tracée, dossier fusionné 410), puis historique ordonnances /
 * paiements. Actions contextuelles vers consultation et paiement.
 */

import { motion } from "framer-motion";
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  CreditCard,
  FileWarning,
  IdCard,
  Phone,
  Stethoscope,
  UserX,
} from "lucide-react";
import type { ReactNode } from "react";
import type { Patient, PatientIdentifier } from "@/lib/types";
import { formatDate } from "@/lib/types";
import { useAppStore } from "@/lib/store";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { ageLabel, avatarTone, fullName, initials } from "./patient-utils";
import { AgeBadge, GenderBadge, PendingSyncBadge, RefSyncBadge } from "./patient-badges";
import { PatientHistory } from "./patient-history";

export function PatientDetail({ patientId }: { patientId: string }) {
  const patient = useAppStore((s) => s.patients.find((p) => p.id === patientId));
  const selectPatient = useAppStore((s) => s.selectPatient);
  const goTo = useAppStore((s) => s.goTo);

  if (!patient) {
    return (
      <Card className="p-0">
        <CardContent className="flex flex-col items-center gap-3 px-6 py-12 text-center">
          <span
            className="flex h-12 w-12 items-center justify-center rounded-full bg-muted"
            aria-hidden="true"
          >
            <UserX className="h-6 w-6 text-muted-foreground" />
          </span>
          <div>
            <p className="font-medium">Dossier introuvable</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Ce dossier n&apos;est pas (ou plus) dans le miroir local.
            </p>
          </div>
          <Button
            variant="outline"
            className="min-h-11"
            onClick={() => selectPatient(null)}
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
            Retour à la liste
          </Button>
        </CardContent>
      </Card>
    );
  }

  const nunp = patient.identifiers.find((i) => i.type === "NUNP");
  const otherIdentifiers = patient.identifiers.filter((i) => i.type !== "NUNP");
  const masterId = patient.masterId;

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.18, ease: "easeOut" }}
      className="space-y-4"
    >
      {/* Retour */}
      <Button
        variant="ghost"
        className="min-h-11 -ml-2 px-2 text-muted-foreground"
        onClick={() => selectPatient(null)}
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Tous les patients
      </Button>

      {/* En-tête identitaire */}
      <Card className="overflow-hidden">
        <div className="bg-hero-teal flex flex-wrap items-center gap-4 p-4 sm:p-6">
          <Avatar className="h-14 w-14 border shadow-xs">
            <AvatarFallback
              className={`text-base font-semibold ${avatarTone(patient.id)}`}
            >
              {initials(patient.name)}
            </AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-lg font-semibold tracking-tight">
              {fullName(patient.name)}
            </h2>
            {patient.phReference ? (
              <p className="tnum font-mono text-xs text-muted-foreground">
                {patient.phReference}
              </p>
            ) : (
              <div className="flex flex-wrap items-center gap-1.5">
                <p className="font-mono text-xs text-muted-foreground">—</p>
                <RefSyncBadge />
              </div>
            )}
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <GenderBadge gender={patient.gender} />
              <AgeBadge birthDate={patient.birthDate} />
              <Badge variant="secondary" className="max-w-full truncate">
                {patient.facility}
              </Badge>
              {patient.pendingSync && <PendingSyncBadge />}
            </div>
          </div>
        </div>

        {/* Création forcée : motif tracé en audit */}
        {patient.forcedReason && (
          <div className="flex items-start gap-2 border-t border-amber-500/40 bg-amber-500/10 px-4 py-3 text-xs text-amber-700 dark:text-amber-400 sm:px-6">
            <FileWarning className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <p>
              Création forcée&nbsp;: motif tracé — «&nbsp;{patient.forcedReason}
              &nbsp;»
            </p>
          </div>
        )}

        {/* Dossier fusionné (HTTP 410) */}
        {!patient.active && (
          <div className="flex flex-wrap items-center gap-2 border-t border-destructive/30 bg-destructive/5 px-4 py-3 text-xs text-destructive sm:px-6">
            <UserX className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <p className="flex-1">
              Dossier fusionné (HTTP 410) — ce dossier n&apos;est plus actif.
            </p>
            {masterId && (
              <Button
                variant="outline"
                size="sm"
                className="min-h-9 border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => selectPatient(masterId)}
              >
                Ouvrir le dossier maître
                <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
              </Button>
            )}
          </div>
        )}
      </Card>

      {/* Identité */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <IdCard className="h-4 w-4 text-primary" aria-hidden="true" />
            Identité
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <IdentityItem label="Naissance">
              <span className="tnum">
                {formatDate(patient.birthDate)}{" "}
                <span className="font-normal text-muted-foreground">
                  ({ageLabel(patient.birthDate)})
                </span>
              </span>
            </IdentityItem>
            <IdentityItem label="Téléphone">
              {patient.phone ? (
                <a
                  href={`tel:${patient.phone.replace(/\s/g, "")}`}
                  className="inline-flex items-center gap-1.5 text-primary underline-offset-2 hover:underline"
                >
                  <Phone className="h-3.5 w-3.5" aria-hidden="true" />
                  <span className="tnum">{patient.phone}</span>
                </a>
              ) : (
                <span className="text-muted-foreground">Non renseigné</span>
              )}
            </IdentityItem>
            <IdentityItem label="Identifiant national">
              {patient.identifiers.length === 0 ? (
                <span className="text-muted-foreground">
                  Aucun (NUNP à attribuer)
                </span>
              ) : (
                <span className="flex flex-wrap gap-1.5">
                  {nunp && <IdentifierBadge identifier={nunp} highlight />}
                  {otherIdentifiers.map((id) => (
                    <IdentifierBadge key={`${id.type}-${id.value}`} identifier={id} />
                  ))}
                </span>
              )}
            </IdentityItem>
            <IdentityItem label="Village / quartier">
              {patient.village ?? (
                <span className="text-muted-foreground">Non renseigné</span>
              )}
            </IdentityItem>
            <IdentityItem label="Structure de recensement">
              {patient.facility}
            </IdentityItem>
            <IdentityItem label="Dossier créé le">
              <span className="tnum">{formatDate(patient.createdAt)}</span>
            </IdentityItem>
          </dl>

          <Separator className="my-4" />

          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <BadgeCheck className="h-3.5 w-3.5 text-primary" aria-hidden="true" />
            Version MPI&nbsp;
            <span className="tnum font-medium text-foreground">
              v{patient.version}
            </span>
            {patient.clientRequestId && (
              <>
                · demande idempotente{" "}
                <span className="tnum font-mono text-[10px]">
                  {patient.clientRequestId.slice(0, 8)}
                </span>
              </>
            )}
          </p>
        </CardContent>
      </Card>

      {/* Actions contextuelles */}
      <div className="grid grid-cols-1 gap-3 sm:flex">
        <Button
          variant="medical"
          className="min-h-11 flex-1"
          onClick={() => {
            selectPatient(patient.id);
            goTo("consultation");
          }}
        >
          <Stethoscope className="h-4 w-4" aria-hidden="true" />
          Nouvelle consultation
        </Button>
        <Button
          variant="outline"
          className="min-h-11 flex-1"
          onClick={() => {
            selectPatient(patient.id);
            goTo("payments");
          }}
        >
          <CreditCard className="h-4 w-4" aria-hidden="true" />
          Initier un paiement
        </Button>
      </div>

      {/* Historique ordonnances / paiements */}
      <PatientHistory patientId={patient.id} />
    </motion.div>
  );
}

function IdentityItem({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="rounded-lg border bg-muted/30 p-3">
      <dt className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </dt>
      <dd className="mt-1.5 text-sm font-medium">{children}</dd>
    </div>
  );
}

function IdentifierBadge({
  identifier,
  highlight = false,
}: {
  identifier: PatientIdentifier;
  highlight?: boolean;
}) {
  return (
    <Badge
      variant={highlight ? "default" : "secondary"}
      className="gap-1 font-mono text-[11px] normal-case"
    >
      <span className="font-sans font-semibold">{identifier.type}</span>
      <span className="tnum">{identifier.value}</span>
    </Badge>
  );
}
