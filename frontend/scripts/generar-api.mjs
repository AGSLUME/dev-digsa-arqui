// Genera el cliente Angular desde contratos/ (ADR-007): typescript-angular de openapi-generator 7.26.0 (el mismo del pom), a
// src/app/api/<servicio>/ (no se versiona). Antes verifica el SHA-1 del jar contra el que publica Maven Central (ADR-012):
//   curl -s https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar.sha1
// Uso: npm run api   (necesita Java 21 en el PATH; la CLI de npm baja el jar la primera vez)
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const VERSION = '7.26.0';
const SHA1 = 'b96b148fcf482808037f68fbad99cb4d7e65d40b';
const SERVICIOS = ['seguridad'];

const raiz = fileURLToPath(new URL('..', import.meta.url));
const jar = `${raiz}node_modules/@openapitools/openapi-generator-cli/versions/${VERSION}.jar`;
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
const opciones = { cwd: raiz, stdio: 'inherit', shell: process.platform === 'win32' };

if (!existsSync(jar)) execFileSync(npx, ['openapi-generator-cli', 'version'], opciones);
const sha1 = createHash('sha1').update(readFileSync(jar)).digest('hex');
if (sha1 !== SHA1) {
  console.error(`El jar del generador no es el publicado: SHA-1 ${sha1}, esperado ${SHA1}. Se borra.`);
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
