# Backlog P0 — 8 épiques, une seule porte de sortie

Le Sprint 0 (socle : monorepo, 15 modules, migrations V1→V5, tranche
paiements, CI, PWA offline-first) est **livré**. Les 8 épiques suivantes
construisent le produit minimum plausible, dans cet ordre. Chaque user
story suit la chaîne complète du DoD : besoin → implémentation → tests →
sécurité → observabilité → documentation → vérification.

## Épiques

| # | Épique | Scénario critique couvert | Contenu |
|---|--------|---------------------------|---------|
| E1 | Identité & MPI | Patient dupliqué | Recherche miroir locale, création avec détection de doublons (409 = contrat UX), file de revue des rapprochements, fusion tracée et irréversible |
| E2 | Consultation offline + sync | Consultation hors ligne, reprise réseau | UUID v7 côté client, append-only, outbox IndexedDB → /sync/batch idempotent (opId), trois régimes de conflit, curseurs par appareil |
| E3 | Ordonnance & dispensation | Dispensation multi-sources | Ordonnance (interne + HUB simulé), dispensation append-only, enveloppe HMAC signée, séquences + filigrane, DLQ |
| E4 | Paiements FedaPay complets | Webhook dupliqué, réseau coupé pendant le paiement | Réconciliation nocturne (fait foi), vérification HMAC réelle, gestion des orphelins, rapprochement factures/transactions |
| E5 | Audit & sécurité | Accès d'urgence (break-the-glass), accès hors périmètre | Audit six dimensions chaîné complet, RLS intégrale (INSERT/UPDATE policies + FORCE), JWT Supabase, Bucket4j, revue OWASP ASVS L2 |
| E6 | Back-office minimal | Gouvernance de base | Utilisateurs, structures sanitaires, rôles, MFA administrateur |
| E7 | Façade FHIR lecture/recherche | Interopérabilité sortante | 19 ressources en lecture/recherche, validateur HAPI en CI (déjà branché), CapabilityStatement complet |
| E8 | Observabilité & durcissement | SLO respectés, restauration testée | Dashboards SLO, alertes, sauvegarde 3-2-1 avec exercice de restauration, supervision des quotas |

## Définition de terminé (DoD) — chaque story

1. **Besoin** : scénario d'usage écrit, en français, avec l'écran associé.
2. **Implémentation** : code dans le bon module, hexagonal, sans shortcut inter-module.
3. **Tests** : unitaires + intégration (Testcontainers en CI, zonky en local).
4. **Sécurité** : RLS à jour si données sensibles, validation d'entrée, pas de secret en clair.
5. **Observabilité** : logs structurés + métrique métier pertinente.
6. **Documentation** : ADR si décision, README/endpoint à jour.
7. **Vérification** : CI verte sur les deux jobs (api + pwa).

## Porte de sortie : 10 scénarios critiques E2E

Le P0 n'est **pas livrable** tant que les 10 scénarios ne passent pas de
bout en bout (API + base + PWA), en environnement de staging :

1. Créer un patient en ligne, le retrouver au CSPS voisin (MPI national).
2. Créer un patient dont un doublon probable existe → 409, file de revue, décision humaine tracée.
3. Fusionner deux dossiers avec raison obligatoire → journal de fusion consultable.
4. Ouvrir une consultation **hors ligne**, saisir observations, fermer, resynchroniser → aucune perte, aucun conflit.
5. Prescrire à l'hôpital A, dispenser à la pharmacie B (connecteur HUB simulé) → traçabilité complète.
6. Initier un paiement, coupure réseau avant retour → idempotence, un seul paiement créé.
7. Webhook FedaPay **dupliqué** → acquitté, aucune seconde transition.
8. Tentative de rétrogradation d'état (SUCCEEDED → PENDING) → 409, historique intact, audit DENIED.
9. Accès d'urgence break-the-glass → accès accordé, raison obligatoire, audit SUCCESS + examen a posteriori.
10. Lecture FHIR d'un patient par un partenaire via la façade → ressource R4 valide (validateur HAPI).

## Aperçu de l'après-P0

RAMU/Couverture (P2, modèle prêt dès E4), rendez-vous, laboratoire,
push SNIS (P1 : export CSV d'abord — D2), notifications patient riches.
