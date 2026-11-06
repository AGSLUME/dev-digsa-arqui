import { Injectable } from '@angular/core';

/**
 * Recarga de la lista de la pantalla Usuarios, para los modales (formulario, contraseña inicial, nombres de perfiles, roles). Lo provee
 * la pantalla y lo heredan los cuerpos de sus modales (se declaran en su plantilla). No es un `output` del modal a propósito: si la persona
 * cierra el modal mientras guarda, el cuerpo ya no existe cuando llega la respuesta y su `output` no llega a nadie (NG0953); el servicio
 * vive con la pantalla, así que la lista se recarga igual (como el `onSuccess` del monolito).
 */
@Injectable()
export class ListaDeUsuarios {
  private alRecargar: ((usuario: string | null) => void) | null = null;

  /** La pantalla dice qué hacer al recargar. */
  registrar(f: (usuario: string | null) => void): void {
    this.alRecargar = f;
  }

  /** Vuelve a cargar la lista; con `usuario`, lleva a su fila (tras crearlo o editarlo). */
  recargar(usuario: string | null = null): void {
    this.alRecargar?.(usuario);
  }
}
