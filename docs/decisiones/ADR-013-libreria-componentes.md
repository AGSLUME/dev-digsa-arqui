# ADR-013 · Librería de componentes del frontend: Angular CDK + componentes LUME propios

- Estado: Aceptado (usuario, 2026-11-05) · Fecha: 2026-11-05 · Propone: angular-base · Decide: usuario (vía integradora)
- Desarrolla: ADR-011

## Contexto
ADR-011 porta el design system LUME como tokens CSS y una capa propia en `frontend/src/app/ui/`, y deja abierta una librería de componentes «si la guía lo justifica». El prompt de la sesión pedía elegir entre Angular Material con tema LUME y PrimeNG. Lo que hay que reproducir es la interfaz del monolito: React + Mantine con `tema.ts` y `estilos.css` (330 líneas), de las cuales la mayoría son **parches sobre el marcado de Mantine** (bordes 3:1, foco visible con error, insignia con punto, modal a pantalla completa, botón deshabilitado, listas a 360 px). Además: AA en todo, 360 px sin desplazamiento horizontal, textos y `data-testid` idénticos para que los e2e se porten cambiando solo selectores.

## Opciones
| Criterio | Angular Material (tema LUME) | PrimeNG | Angular CDK + componentes LUME |
|---|---|---|---|
| Aspecto LUME (píldoras, sidebar, campos, tablas) | Material 3: forma, densidad y estados propios; LUME se logra sobreescribiendo tokens `--mat-*` y clases internas | Tema propio (presets); se sobreescribe igual, con más superficie | Se escribe directo con `--lume-*`: sin capa que pelear |
| Accesibilidad difícil (trampa de foco, anuncios, overlays, teclado) | Sí (la trae del CDK) | Sí, desigual entre componentes | Sí: es el mismo CDK que usa Material (`A11yModule`, `Dialog`, `Overlay`, `LiveAnnouncer`, `BreakpointObserver`) |
| Marcado estable para los e2e (roles, etiquetas, testid) | Marcado propio de cada componente | Marcado propio | El que pongamos, igual al del monolito |
| Peso y superficie de dependencias | Media | Alta (muchos componentes, iconos y estilos) | Baja: un paquete del equipo de Angular, misma versión que el core |
| Lo que hay que escribir | Poco, más los parches de tema | Poco, más los parches de tema | ~10 componentes base (botón, insignia, alerta, campo, resumen de validación, botón con motivo, tabla adaptable, modal, encabezado, shell) |
| Riesgo de repetir `estilos.css` | Alto | Alto | Nulo |

## Decisión
**Solo `@angular/cdk` (21.2.14, exacta, alineada con `@angular/core` 21.2.x) y componentes LUME propios en `frontend/src/app/ui/`.** Ni Angular Material ni PrimeNG.
- Del CDK se usan: `Dialog` (modales, a pantalla completa por debajo de 48em), `A11y` (`cdkTrapFocus`, `FocusMonitor`, `LiveAnnouncer`), `Overlay` (ayudas del sidebar colapsado) y `Layout` (`BreakpointObserver` para 48em y 900 px).
- Los tokens `--lume-*` son la única fuente de color (`src/styles/lume.css`); `ui/tema.ts` repite los valores y una prueba comprueba que coinciden y que todo llega a AA, como `tema.test.ts` del monolito.
- Fuente Inter alojada (`@fontsource/inter` 5.3.0, la del monolito). Íconos como SVG en línea propios (sin librería de íconos).
- Un componente nuevo se agrega a `ui/` solo si dos pantallas lo necesitan; si no, vive en su módulo.

## Consecuencias
- (+) El design system se ve y se comporta como el del monolito sin hojas de parches; el marcado lo controlamos y los e2e se portan con los mismos selectores.
- (+) Una sola dependencia de UI, del mismo equipo y ciclo de versiones que Angular.
- (−) Componentes complejos que aún no existen (selector con búsqueda, selector múltiple, calendario) los escribe la sesión que los necesite sobre `Overlay`/`Listbox` del CDK, o pide aquí una dependencia nueva con su justificación.
