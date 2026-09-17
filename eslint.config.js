// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    // Cartelle generate o non sorgente: mai passate al linter.
    ignores: ['**/dist/**', '**/node_modules/**', '**/coverage/**'],
  },
  eslint.configs.recommended,
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        // projectService usa i tsconfig.json dei workspace senza elencarli qui.
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // I contratti dei pattern seguono l'ordine del catalogo del design system e i moduli
      // tengono in fondo le funzioni di supporto: nessuno dei due è un errore.
      '@typescript-eslint/no-use-before-define': ['error', { functions: false, typedefs: false }],
      // `Array<{ label: string }>` resta più leggibile di `{ label: string }[]`.
      '@typescript-eslint/array-type': ['error', { default: 'array-simple' }],
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // Le props dei pattern sono callback che possono restituire Promise ignorate dalla UI.
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { arguments: false } }],
    },
  },
  {
    files: ['**/*.test.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
  {
    files: ['eslint.config.js'],
    ...tseslint.configs.disableTypeChecked,
  },
);
