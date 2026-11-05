# Origen de `librerias/reglas`

Copia literal de `digsa-java/backend/reglas/src` (rama `develop`, commit del repo 1ff82de; último cambio en `reglas`: 33bcaca), con el mismo paquete `co.com.agsamericas.digsa.reglas` para que la paridad se pueda comparar archivo por archivo.

- No se modifica aquí sin portar el mismo cambio, o justificar la diferencia, contra el monolito.
- Incluye también las reglas de analítica y presupuesto (`Analitica*`, `Presupuesto*`), aunque esos módulos no se migran (ADR-008). Se conservan para que la copia sea fiel; el agente `codigo-muerto-java` decide en el cierre si se retiran.
