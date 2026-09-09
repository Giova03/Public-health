import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const eslintConfig = [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    rules: {
      // TypeScript — tolérances de démarrage, à resserrer épique par épique
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-unused-vars": "warn",
      // React
      "react-hooks/exhaustive-deps": "warn",
      "react/no-unescaped-entities": "off",
      // Next.js — la page d'accueil affiche des SVG de marques en <img>
      "@next/next/no-img-element": "off",
      // Général
      "no-console": ["warn", { allow: ["warn", "error"] }],
    },
  },
  {
    ignores: [
      "node_modules/**",
      ".next/**",
      "out/**",
      "next-env.d.ts",
      "public/brands/**",
    ],
  },
];

export default eslintConfig;
