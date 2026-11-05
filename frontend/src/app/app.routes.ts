import { Routes } from '@angular/router';
import { conSesion } from './nucleo/guardas';
import { Saliendo, SinAcceso, SinConexion } from './paginas/estados';
import { Inicio } from './paginas/inicio';
import { DefinirClave, OlvideClave } from './paginas/publicas/claves';
import { Ingreso } from './paginas/publicas/ingreso';
import { RUTAS_DE_MODULOS } from './rutas-modulos';
import { Plantilla } from './shell/plantilla';

/**
 * Rutas de LUME (ADR-011, ADR-014):
 * - públicas, sin shell: el ingreso (al que llega el emisor OIDC), «¿Olvidó su contraseña?» y el enlace para definirla (A3);
 * - la aplicación: el shell con sesión (`conSesion`) y, dentro, Inicio y las pantallas de los módulos (`rutas-modulos.ts`).
 */
export const routes: Routes = [
  { path: 'ingreso', component: Ingreso },
  { path: 'olvide-clave', component: OlvideClave },
  { path: 'definir-clave', component: DefinirClave },
  { path: 'sin-conexion', component: SinConexion },
  {
    path: '',
    component: Plantilla,
    canMatch: [conSesion],
    children: [{ path: '', pathMatch: 'full', component: Inicio }, ...RUTAS_DE_MODULOS, { path: '**', component: SinAcceso }],
  },
  // Sin sesión el shell no coincide y la página ya va al ingreso por el gateway: nada que mostrar mientras tanto
  { path: '**', component: Saliendo },
];
