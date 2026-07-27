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

/** Impresora de red descubierta en la LAN (mDNS / barrido). */
export interface DiscoveredNetworkPrinter {
  transport: 'tcp';
  host: string;
  port: number;
  /** Nombre de mDNS si se anunció; si no, la propia IP. */
  name: string;
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
/**
 * Estado en tiempo real de la impresora (`DLE EOT`).
 *
 * Todos los campos salvo `supported` son opcionales: la impresora contesta
 * consulta por consulta y puede responder unas y otras no. Un campo ausente
 * significa "no lo sé", nunca "está bien".
 */
export interface PrinterStatusResult {
  /**
   * `false` cuando la impresora no respondió ninguna consulta válida. Suele
   * pasar en clones baratos y en USB sin endpoint bulk IN. Tratalo como
   * "no puedo saber", no como "hay un problema".
   */
  supported: boolean;
  /** `false` = la impresora está offline (tapa abierta, sin papel, error). */
  online?: boolean;
  paperOut?: boolean;
  /** Rollo por acabarse — si la impresora trae ese sensor. */
  paperNearEnd?: boolean;
  coverOpen?: boolean;
  cutterError?: boolean;
  /** Error irrecuperable — requiere apagar y encender. */
  fatalError?: boolean;
  /** Auto-recuperable, típicamente cabezal sobrecalentado. */
  recoverableError?: boolean;
}

export interface ThermalPrinterPlugin {
  /**
   * Lista dispositivos del transporte. USB: todos los conectados con metadata.
   * Bluetooth: emparejados en el sistema (pide BLUETOOTH_CONNECT si falta).
   * TCP no es listable — la IP la ingresa el usuario.
   */
  list(options: { transport: 'usb' | 'bluetooth' }): Promise<{ devices: PrinterDevice[] }>

  /**
   * Descubre impresoras de red en la LAN — el equivalente de red a `list`.
   * Combina mDNS (nombres reales de las que se anuncian) con un barrido del
   * puerto 9100 sobre la subred /24 (cubre las que no anuncian).
   *
   * Sólo el dispositivo que está EN la LAN puede hacerlo; un dashboard remoto
   * no ve la red del local. `timeoutMs` acota la ventana de escaneo (default 4s).
   */
  discover(options: { transport: 'tcp'; timeoutMs?: number }): Promise<{ devices: DiscoveredNetworkPrinter[] }>

  /**
   * Pide el permiso del transporte. USB: diálogo del sistema por dispositivo
   * (persiste hasta desconectar, o siempre si el usuario marca "usar por defecto").
   * Bluetooth: permiso runtime BLUETOOTH_CONNECT (API 31+). TCP: siempre granted.
   */
  requestPermission(options: PrintTarget): Promise<{ granted: boolean }>

  /**
   * Envía bytes ESC/POS crudos codificados en base64.
   * Stateless: conecta → escribe (chunked) → cierra. Sin estado que corromper.
   *
   * Concurrencia: los trabajos se serializan **por impresora de destino**. Dos
   * `print()` al mismo destino nunca se solapan (no se entrelazan dos tickets en
   * el mismo papel), pero destinos distintos imprimen en paralelo — una impresora
   * caída no retrasa a las demás mientras agota su timeout de conexión.
   */
  print(options: PrintTarget & { data: string }): Promise<void>

  /**
   * Consulta el estado en tiempo real: papel, tapa, errores.
   *
   * Se serializa en la misma fila que `print()` al mismo destino — preguntar
   * mientras se escribe un ticket mezclaría los bytes en la línea.
   *
   * ⚠️ **No todas las térmicas implementan `DLE EOT`.** Las que no, o no
   * responden (se resuelve con el timeout y `supported: false`), o interpretan
   * la consulta como datos y **escupen basura en el papel**. Probalo con la
   * impresora real antes de llamarlo en un flujo automático.
   */
  status(options: PrintTarget): Promise<PrinterStatusResult>
}
