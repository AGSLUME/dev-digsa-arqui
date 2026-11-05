/**
 * Componentes LUME (ADR-013). Las pantallas importan de aquí:
 * `import { Boton, Campo, Entrada, crearValidacion, ... } from '../ui';`
 */
export { Boton, BotonConMotivo, type VarianteBoton } from './boton';
export { Campo, Entrada } from './campo';
export { EncabezadoPagina, tituloDocumento } from './encabezado-pagina';
export { Alerta, Insignia } from './insignia';
export { CuerpoModal, Modal } from './modal';
export { Sonido } from './sonido';
export { etiquetarCeldas, TablaAdaptable } from './tabla-adaptable';
export { APAGADO, BORDE_CAMPO, COMPLEMENTOS, LUME, PLACEHOLDER, VARIANTES, variante, type Variante } from './tema';
export {
  crearValidacion,
  FormularioValidado,
  primerCampoConError,
  ResumenErrores,
  Validacion,
  type Errores,
  type OpcionesValidacion,
} from './validacion';

/** Lo que casi todo formulario usa, para `imports: [...FORMULARIO_LUME]`. */
import { Boton, BotonConMotivo } from './boton';
import { Campo, Entrada } from './campo';
import { Alerta } from './insignia';
import { FormularioValidado, ResumenErrores } from './validacion';
export const FORMULARIO_LUME = [Boton, BotonConMotivo, Campo, Entrada, Alerta, FormularioValidado, ResumenErrores] as const;
