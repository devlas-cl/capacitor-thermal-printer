/**
 * @devlas/capacitor-thermal-printer — definiciones públicas.
 *
 * Filosofía: el plugin transporta BYTES CRUDOS (ESC/POS ya codificado por la app).
 * No formatea texto, no conoce comandos — eso permite reusar el mismo encoder
 * en todas las plataformas (Electron, Web Serial, WebUSB y esta app nativa).
 */

export type PrinterTransport = 'usb' | 'tcp' | 'bluetooth'

export interface PrinterDevice {
  transport: PrinterTransport
  /** Nombre legible del dispositivo (productName USB / nombre BT). */
  name?: string
  // ── USB ──
  vendorId?: number
  productId?: number
  /** ID de sesión Android — cambia al reconectar; usar vendorId+productId para persistir. */
  deviceId?: number
  /** true si el dispositivo tiene un endpoint bulk OUT (puede recibir impresión). */
  canPrint?: boolean
  /** true si Android ya otorgó permiso USB a este dispositivo. */
  hasPermission?: boolean
  // ── Bluetooth ──
  /** Dirección MAC del dispositivo emparejado. */
  address?: string
}

export interface UsbTarget {
  transport: 'usb'
  /** Si se omiten vendorId/productId, usa la primera impresora USB detectada. */
  vendorId?: number
  productId?: number
}

export interface TcpTarget {
  transport: 'tcp'
  host: string
  /** Puerto raw/JetDirect. Default: 9100. */
  port?: number
}

export interface BluetoothTarget {
  transport: 'bluetooth'
  /** MAC del dispositivo (debe estar emparejado en Ajustes del sistema). */
  address: string
}

export type PrintTarget = UsbTarget | TcpTarget | BluetoothTarget

/**
 * Códigos de error estables (segundo argumento de reject, `error.code` en JS):
 * `unavailable` | `not_found` | `permission_denied` | `connect_failed`
 * | `write_failed` | `invalid_transport` | `invalid_data`
 */
export interface ThermalPrinterPlugin {
  /**
   * Lista dispositivos del transporte. USB: todos los conectados con metadata.
   * Bluetooth: emparejados en el sistema (pide BLUETOOTH_CONNECT si falta).
   * TCP no es listable — la IP la ingresa el usuario.
   */
  list(options: { transport: 'usb' | 'bluetooth' }): Promise<{ devices: PrinterDevice[] }>

  /**
   * Pide el permiso del transporte. USB: diálogo del sistema por dispositivo
   * (persiste hasta desconectar, o siempre si el usuario marca "usar por defecto").
   * Bluetooth: permiso runtime BLUETOOTH_CONNECT (API 31+). TCP: siempre granted.
   */
  requestPermission(options: PrintTarget): Promise<{ granted: boolean }>

  /**
   * Envía bytes ESC/POS crudos codificados en base64.
   * Stateless: conecta → escribe (chunked) → cierra. Sin estado que corromper.
   * Los trabajos se serializan en un solo hilo — nunca dos impresiones concurrentes.
   */
  print(options: PrintTarget & { data: string }): Promise<void>
}
