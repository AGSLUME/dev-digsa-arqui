// Genera el cliente Angular desde contratos/ (ADR-007): typescript-angular de openapi-generator 7.26.0 (el mismo del pom), a
// src/app/api/<servicio>/ (no se versiona). Antes verifica el SHA-256 del jar (ADR-012). Maven Central solo publica .sha1 y la firma
// .asc de este artefacto; el SHA-256 fijado aquí es el del jar que pasó las dos (2026-11-05): SHA-1 igual al .sha1 de Central
// (b96b148fcf482808037f68fbad99cb4d7e65d40b) y firma PGP válida de William Cheng, mantenedor de OpenAPI Generator (huella
// AE81 EA0B D1BE 2997 053C C6C5 0D49 7348 00E3 AA0C). Cómo repetirlo está en frontend/README.md.
// Uso: npm run api   (necesita Java 21 en el PATH; la CLI de npm baja el jar la primera vez)
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const VERSION = '7.26.0';
const SHA256 = '1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e';
const SERVICIOS = ['seguridad'];

const raiz = fileURLToPath(new URL('..', import.meta.url));
const jar = `${raiz}node_modules/@openapitools/openapi-generator-cli/versions/${VERSION}.jar`;
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
const opciones = { cwd: raiz, stdio: 'inherit', shell: process.platform === 'win32' };

if (!existsSync(jar)) execFileSync(npx, ['openapi-generator-cli', 'version'], opciones);
const sha256 = createHash('sha256').update(readFileSync(jar)).digest('hex');
if (sha256 !== SHA256) {
  console.error(`El jar del generador no es el verificado: SHA-256 ${sha256}, esperado ${SHA256}. Se borra.`);
  rmSync(jar);
  process.exit(1);
}

for (const servicio of SERVICIOS) {
  const salida = `src/app/api/${servicio}`;
  rmSync(`${raiz}${salida}`, { recursive: true, force: true });
  execFileSync(
    'java',
    [
      '-jar',
      jar,
      'generate',
      '-g',
      'typescript-angular',
      '-i',
      `../contratos/${servicio}.yaml`,
      '-o',
      salida,
      '--additional-properties=ngVersion=21.2.0,providedIn=root,supportsES6=true,withInterfaces=true,serviceSuffix=Api,serviceFileSuffix=.api,modelFileSuffix=,fileNaming=kebab-case',
      '--global-property=apiDocs=false,modelDocs=false,apiTests=false,modelTests=false',
      '--skip-validate-spec',
    ],
    { cwd: raiz, stdio: 'inherit' },
  );
}
