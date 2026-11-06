// @ts-check
// ESLint del frontend: lo recomendado de Angular para TypeScript y, en las plantillas, lo recomendado más el conjunto de accesibilidad
// (el equivalente de jsx-a11y del monolito: alt-text, button-has-type, elements-content, interactive-supports-focus,
// label-has-associated-control, valid-aria...). Un hallazgo que choque con la paridad del monolito no se «arregla» cambiando el
// comportamiento: se anota en docs/sesiones/DESVIACIONES_A11Y.md y se desactiva aquí, en su línea, con el motivo.
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = tseslint.config(
  {
    // Lo generado desde contratos/ no se revisa (ADR-007: no se escribe a mano)
    ignores: ['src/app/api/**', 'dist/**', '.angular/**', 'mock/**', 'scripts/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [...tseslint.configs.recommended, ...angular.configs.tsRecommended],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 'lume', style: 'camelCase' }],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'lume', style: 'kebab-case' }],
      // Desestructurar para quitar una clave (validacion.ts: limpiar) deja la variable sin usar a propósito
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {},
  },
);
