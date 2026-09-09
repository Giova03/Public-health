# Registre des hypothèses à valider

Le Sprint 0 pose des hypothèses de travail explicitement réversibles.
Chacune porte unOwner et un déclencheur de vérification. **Aucune n'est
une décision institutionnelle fermée.**

| # | Hypothèse | Portée | Validation prévue | Impact si invalidée |
|---|-----------|--------|-------------------|---------------------|
| H1 | Format de signature des webhooks FedaPay : HMAC-SHA256 hex, préfixe `sha256=`, en-têtes `X-FedaPay-Signature` / `X-Event-Id` | Module payments | Documentation officielle FedaPay + test sandbox (épique E4) | Adaptateur seul à modifier (HmacSha256Verifier isolé derrière le port SignatureVerifier) |
| H2 | Charge utile webhook : `{ "reference", "status" }` avec statuts en minuscules | Module payments | Sandbox FedaPay (E4) | Map de traduction PROVIDER_STATUS_MAP uniquement |
| H3 | La plateforme n'émet pas le NUNP : identifiant interne `PH-AAAA-NNNNNN`, NUNP = identifiant externe nullable (D1) | Module patient | Confirmation juridique/ministérielle **avant tout engagement institutionnel** | Modèle déjà nullable — attacher le NUNP ne casse rien |
| H4 | Données hébergées en UE (Francfort), conformité par contrat d'hébergeur (D4) | Infra | Revue juridique du RGPD + législation burkinabè | Migration de région Supabase + mise à jour ADR-007/010 |
| H5 | SNIS : export CSV d'abord, push API après convention signée (D2) | Module administration | Convention SNIS | Le CSV reste la porte de sortie documentée |
| H6 | RAMU reportée en P2, modèle Coverage construit dès E4 (D3) | Modules payments/patient | Calendrier institutionnel RAMU | Le modèle couverture est indépendant des paiements |
| H7 | Contenu des notifications : titre + type + lien, jamais de donnée clinique | Module notification | Avis CNIL locale / cadre légal | Réduire encore le contenu (aucun lien nominatif) |
| H8 | Postgres 16 pour tous les environnements (CI, compose, production) | Infra | Sans objet — déjà uniformisé | — |

## Décisions institutionnelles restées ouvertes (drapeaux D1/D4)

- **D1 (juridique)** : le droit d'émettre/rattacher le NUNP appartient-il
  à l'État seul ? La plateforme reste neutre : interne d'abord, externe
  ensuite.
- **D4 (juridique)** : la résidence UE des données satisfait-elle le
  cadre burkinabè ? À confirmer avant le pilote.

Les arbitrages D1–D4 de travail (identifiant interne, SNIS CSV, RAMU P2,
UE) sont réversibles par construction — chacun a été pris pour ne pas
bloquer le développement pendant la vérification institutionnelle.
