import { Routes } from '@angular/router';

/**
 * Pantallas de los módulos dentro del shell (con sesión). Cada sesión de módulo agrega aquí SUS rutas, una por pantalla de
 * `reglas/Pantallas` del monolito, con la guarda `pantalla('<ruta>')` y carga perezosa (ver frontend/README.md, «Agregar pantallas»):
 *
 * ```ts
 * { path: 'usuarios', canMatch: [pantalla('usuarios')], loadComponent: () => import('./paginas/seguridad/usuarios').then((m) => m.Usuarios) },
 * ```
 *
 * El orden no importa para el menú (lo da el servidor y `nucleo/menu.ts`), pero una ruta con parámetros (`agenda/visita/:id`) va antes
 * que una más general si comparten prefijo.
 */
export const RUTAS_DE_MODULOS: Routes = [
  // --- seguridad (A5: usuarios, roles, logs) ---
  // --- visitas ---
  // --- reportes ---
];
