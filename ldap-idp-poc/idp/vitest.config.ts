import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: 'unit',
          include: ['test/unit/**/*.test.ts'],
          environment: 'node',
        },
      },
      {
        test: {
          name: 'integration',
          include: ['test/integration/**/*.test.ts'],
          environment: 'node',
          // Integration tests share one LDAP directory, and the criterion-11
          // test restarts the IdP container. Running files concurrently would
          // let that restart yank the server out from under another test, so
          // integration files run strictly one at a time.
          fileParallelism: false,
          testTimeout: 120_000,
          hookTimeout: 120_000,
        },
      },
    ],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      // The HTTP entrypoint is a five-line bootstrap exercised by every
      // integration run but never imported by a test.
      exclude: ['src/server.ts'],
      reporter: ['text', 'html'],
      thresholds: {
        lines: 60,
        functions: 60,
        statements: 60,
        branches: 60,
      },
    },
  },
});
