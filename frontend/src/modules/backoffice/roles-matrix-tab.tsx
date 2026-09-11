"use client";

/**
 * Module Back-office (E6) — onglet « Rôles et permissions » :
 * la matrice RBAC de référence (miroir exact du backend RolesPermissions /
 * V12), l'état d'application réel (ce qui est gardé aujourd'hui) et les
 * écarts découverts par l'analyse RBAC. Lecture seule — c'est l'outil de
 * vérification continue de la matrice « avant de coder ».
 */

import { motion } from "framer-motion";
import type { Variants } from "framer-motion";
import {
  Check,
  KeyRound,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
  X,
} from "lucide-react";
import {
  BACKEND_ROLES,
  RBAC_ENFORCED_TODAY,
  RBAC_GAPS,
  RBAC_MATRIX,
  RBAC_PERMISSIONS,
  roleHasPermission,
  type BackendRoleCode,
} from "@/lib/rbac";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";

const SECTION_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.22, ease: "easeOut" } },
};

/** Comptage pour le badge d'en-tête (matrice déclarée). */
const TOTAL_GRANTS = Object.values(RBAC_MATRIX).reduce(
  (total, permissions) => total + permissions.length,
  0,
);

export function RolesMatrixTab() {
  return (
    <div className="space-y-4">
      {/* Intro — source de vérité */}
      <motion.div variants={SECTION_VARIANTS}>
        <Card className="gap-0 py-0">
          <CardContent className="space-y-2 px-4 py-4 sm:px-6">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="outline" className="gap-1 text-muted-foreground">
                <KeyRound aria-hidden="true" />
                Matrice V12 — déclarée
              </Badge>
              <Badge variant="outline" className="text-muted-foreground">
                {BACKEND_ROLES.length} rôles · {RBAC_PERMISSIONS.length}{" "}
                permissions · {TOTAL_GRANTS} accords
              </Badge>
            </div>
            <p className="text-sm leading-relaxed text-muted-foreground">
              Miroir exact du domaine backend{" "}
              <span className="font-medium text-foreground">
                RolesPermissions
              </span>{" "}
              et de la table{" "}
              <span className="font-medium text-foreground">
                administration.role_permission
              </span>{" "}
              (égalité verrouillée par BackofficeIT). C&apos;est la réponse à
              « qui peut faire quoi » : à lire avant chaque nouveau code,
              exactement comme la matrice ultime du rapport d&apos;analyse.
            </p>
          </CardContent>
        </Card>
      </motion.div>

      {/* La matrice : permissions x rôles */}
      <motion.div variants={SECTION_VARIANTS}>
        <Card className="gap-0 overflow-hidden py-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="min-w-[190px]">
                    Permission
                  </TableHead>
                  {BACKEND_ROLES.map((role) => (
                    <TableHead
                      key={role.code}
                      className="text-center text-[11px] leading-tight"
                      title={role.mission}
                    >
                      {role.label}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {RBAC_PERMISSIONS.map((permission) => (
                  <TableRow key={permission.code}>
                    <TableCell className="py-2">
                      <p className="font-medium">{permission.label}</p>
                      <p className="font-mono text-[11px] text-muted-foreground">
                        {permission.code}
                      </p>
                    </TableCell>
                    {BACKEND_ROLES.map((role) => {
                      const granted = roleHasPermission(
                        role.code as BackendRoleCode,
                        permission.code,
                      );
                      return (
                        <TableCell
                          key={role.code}
                          className="py-2 text-center"
                          title={
                            granted
                              ? `${role.label} : ${permission.label}`
                              : undefined
                          }
                        >
                          <span
                            className={cn(
                              "inline-flex size-6 items-center justify-center rounded-full",
                              granted
                                ? "bg-primary/10 text-primary"
                                : "bg-muted text-muted-foreground/50",
                            )}
                            aria-hidden="true"
                          >
                            {granted ? (
                              <Check className="size-3.5" />
                            ) : (
                              <X className="size-3.5" />
                            )}
                          </span>
                          <span className="sr-only">
                            {granted ? "Accordé" : "Refusé"}
                          </span>
                        </TableCell>
                      );
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </Card>
      </motion.div>

      {/* État d'application réel */}
      <motion.div variants={SECTION_VARIANTS}>
        <div className="grid gap-3 md:grid-cols-3">
          {RBAC_ENFORCED_TODAY.map((item) => (
            <Card key={item.title} className="gap-0 py-0">
              <CardContent className="space-y-1.5 px-4 py-3.5">
                <div className="flex items-center gap-1.5 text-sm font-medium">
                  <ShieldCheck
                    className="size-4 shrink-0 text-primary"
                    aria-hidden="true"
                  />
                  {item.title}
                </div>
                <p className="text-xs leading-relaxed text-muted-foreground">
                  {item.detail}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </motion.div>

      {/* Écarts découverts (à corriger) */}
      <motion.div variants={SECTION_VARIANTS}>
        <Card className="gap-0 border-amber-500/40 bg-amber-500/5 py-0">
          <CardContent className="space-y-3 px-4 py-4 sm:px-6">
            <div className="flex items-center gap-2 text-sm font-semibold text-amber-700 dark:text-amber-300">
              <ShieldAlert className="size-4 shrink-0" aria-hidden="true" />
              Trois écarts entre la matrice déclarée et le code réel (P0.5)
            </div>
            <ul className="space-y-2.5">
              {RBAC_GAPS.map((gap) => (
                <li key={gap.title} className="flex gap-2.5">
                  <TriangleAlert
                    className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400"
                    aria-hidden="true"
                  />
                  <div>
                    <p className="text-sm font-medium">{gap.title}</p>
                    <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
                      {gap.detail}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
            <p className="border-t border-amber-500/20 pt-2.5 text-[11px] leading-relaxed text-muted-foreground">
              Démonstration sans sécurité réelle : cette simulation affiche la
              matrice cible ; les gardes effectives vivent côté serveur (JWT,
              RLS PostgreSQL, append-only) et se prouvent sur l&apos;API réelle
              — jamais sur la démo.
            </p>
          </CardContent>
        </Card>
      </motion.div>
    </div>
  );
}
