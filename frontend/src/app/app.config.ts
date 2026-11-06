import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { interceptorApi } from './nucleo/interceptor-api';
import { proveerClientesApi } from './nucleo/proveedores';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Los parámetros de la ruta y de la consulta (?tkn=) llegan como entradas del componente
    provideRouter(routes, withComponentInputBinding()),
    // Sin tokens en el navegador: la cookie del BFF y el CSRF de la cookie XSRF-TOKEN en X-XSRF-TOKEN (ADR-003)
    provideHttpClient(withInterceptors([interceptorApi]), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
    // Clientes generados desde contratos/ (ADR-007), relativos al mismo origen
    ...proveerClientesApi(),
  ],
};
