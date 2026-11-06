-- LUME · visitas · permisos mínimos (VISITAS A; revisión de seguridad de la integradora sobre VA1, BAJA 9).
-- V1 copió los GRANT del monolito (V3:223-235), pero el código no usa todos:
--   * par_parametros y catalogos: la aplicación solo edita filas existentes (los parámetros y los catálogos los crean las migraciones).
--   * par_festivos: ningún endpoint la escribe (en el monolito tampoco); los ajustes los carga una migración.
REVOKE INSERT ON par_parametros, catalogos FROM ${rol_app};
REVOKE INSERT, UPDATE, DELETE ON par_festivos FROM ${rol_app};
