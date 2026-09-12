/**
 * <p>Épique P0.9 — AUTH INTERNE (correction audit I1/I3).</p>
 *
 * <p>Émission de jetons JWT HS256 par l'API elle-même : la chaîne de
 * connexion est réelle et testable de bout en bout, sans dépendance
 * trou-noir. La porte Supabase (RS256/JWKS) reste possible en parallèle —
 * le décodeur de la SecurityConfig accepte les deux.</p>
 */
package bf.publichealth.modules.auth;
