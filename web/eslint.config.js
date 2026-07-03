import js from '@eslint/js'
import ts from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  {
    name: 'app/files-to-lint',
    files: ['src/**/*.{ts,tsx,vue}']
  },
  {
    name: 'app/files-to-ignore',
    ignores: ['dist/**', 'node_modules/**', 'public/**']
  },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    name: 'app/globals',
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2021
      }
    }
  },
  {
    name: 'app/vue-typescript',
    files: ['src/**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: '@typescript-eslint/parser'
      }
    }
  },
  {
    name: 'app/rules',
    rules: {
      'no-console': ['warn', { allow: ['error', 'warn'] }],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'vue/multi-word-component-names': 'off',
      'vue/require-default-prop': 'off',
      'vue/no-v-html': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/attributes-order': 'off'
    }
  }
]
