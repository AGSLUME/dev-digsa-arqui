import { EnvironmentProviders } from '@angular/core';
import { provideApi } from '../api/seguridad';

/**
 * Clientes generados desde contratos/ (ADR-007) con ruta base vacía: las rutas del contrato (`/api/...`, `/bff/...`) quedan relativas al
 * mismo origen, que es el gateway. Cada contrato nuevo (visitas, reportes...) agrega aquí su `provideApi('')`.
 */
export function proveerClientesApi(): EnvironmentProviders[] {
  return [provideApi('')];
}
