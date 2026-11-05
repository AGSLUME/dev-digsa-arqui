import { defineConfig, devices } from '@playwright/test';

/**
 * Pruebas de punta a punta de LUME en Angular (ADR-011): los casos de digsa-java/e2e/pruebas con los selectores de Angular, en Chromium,
 * Firefox y WebKit. Puertos de la sesión (ADR-010): Angular 4220 (`ng serve`) y gateway 9200.
 *
 * Por omisión levanta el mock de seguridad (frontend/mock, que hace de gateway + seguridad) con 1 minuto de inactividad, y `ng serve`,
 * que le pasa /api y /bff. Con `DIGSA_E2E_GATEWAY=real` no levanta el mock: usa el gateway que ya esté en DIGSA_GATEWAY_PUERTO.
 * Los correos (código MFA, enlaces) se leen de Mailpit (127.0.0.1:8025), que debe estar corriendo (globalSetup lo comprueba).
 *
 * Corre igual en Windows (equipo de desarrollo) y en Linux (CI).
 */
process.env.TZ ??= 'America/Bogota';

const PUERTO = Number(process.env.DIGSA_E2E_PUERTO ?? 4220);
const GATEWAY = Number(process.env.DIGSA_GATEWAY_PUERTO ?? 9200);
process.env.DIGSA_E2E_PUERTO = String(PUERTO);
process.env.DIGSA_GATEWAY_PUERTO = String(GATEWAY);
const conMock = process.env.DIGSA_E2E_GATEWAY !== 'real';

export default defineConfig({
  testDir: './pruebas',
  globalSetup: './preparacion.ts',
  timeout: 120_000,
  expect: { timeout: 10_000 },
  // Un solo trabajador: las pruebas comparten el mock (sesión única por usuario, como el servidor del monolito)
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]],
  use: {
    baseURL: `http://localhost:${PUERTO}`,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'es-CO',
    timezoneId: 'America/Bogota',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: [
    ...(conMock
      ? [
          {
            command: 'node ../frontend/mock/servidor.mjs',
            url: `http://127.0.0.1:${GATEWAY}/api/auth/inicio`,
            timeout: 30_000,
            reuseExistingServer: !process.env.CI,
            env: {
              DIGSA_MOCK_PUERTO: String(GATEWAY),
              DIGSA_MOCK_MINUTOS: '1',
              DIGSA_MOCK_CODIGOS_POR_HORA: '1000',
              DIGSA_MOCK_URL_PUBLICA: `http://localhost:${PUERTO}`,
            },
            stdout: 'ignore' as const,
            stderr: 'pipe' as const,
          },
        ]
      : []),
    {
      command: `npx --prefix ../frontend ng serve --port ${PUERTO} --host localhost`,
      cwd: '../frontend',
      url: `http://localhost:${PUERTO}`,
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
      env: { DIGSA_GATEWAY_PUERTO: String(GATEWAY), NG_CLI_ANALYTICS: 'false' },
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
});
