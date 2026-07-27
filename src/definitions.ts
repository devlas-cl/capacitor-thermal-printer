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

/**
 * Tunel a otra terminal de la LAN que corre en modo host (ver `startPrintHost`).
 * Este dispositivo no imprime: manda los bytes al host y el host los imprime en
 * su impresora local. Permite, p. ej., imprimir en una impresora USB de otra
 * terminal, imposible de alcanzar directo.
 *
 * A diferencia de wss (que exigiria un cert de confianza), el cliente es nativo
 * y abre un socket LAN plano: sin TLS ni cert instalado. La autorizacion es un
 * `token` a nivel app con scope de negocio.
 */
export interface TunnelTarget {
  transport: 'tunnel'
  /** Direccion LAN actual del host. */
  host: string
  /** Puerto del host de impresion. Default: 9110. */
  port?: number
  /** Id estable del host (sobrevive a cambios de IP). Opcional para imprimir. */
  hostId?: string
  /** Que impresora del host: coincide con `PublishedHostPrinter.id`. */
  printerId: string
  /** Token de autorizacion que espera el host. */
  token?: string
}

export type PrintTarget = UsbTarget | TcpTarget | BluetoothTarget | TunnelTarget

/** Una impresora local que este dispositivo publica al correr como host. */
export interface PublishedHostPrinter {
  /** Id estable con que los clientes la referencian (p. ej. el rol). */
  id: string
  /** Nombre para mostrar en el cliente ("Boletas", "Comandas"). */
  label: string
  /** El destino local concreto donde el host imprime este id (usb/tcp/bt). */
  target: UsbTarget | TcpTarget | BluetoothTarget
}

/** Un host de impresion descubierto en la LAN por mDNS. */
export interface DiscoveredHost {
  hostId: string
  name: string
  host: string
  port: number
  /** Version del protocolo del tunel que habla el host. */
  proto: number
  printers: Array<{ id: string; label: string }>
}

/** Estado del modo host de este dispositivo. */
export interface PrintHostStatus {
  running: boolean
  hostId?: string
  host?: string
  port?: number
  /** Clientes con socket abierto en este momento. */
  clients?: number
}

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

  // ── Modo host: compartir las impresoras locales en la LAN ────────────────

  /**
   * Levanta el servidor de impresion: escucha en la LAN, se anuncia por mDNS
   * (`_wbprint._tcp`) y, al recibir un trabajo, lo imprime en la impresora local
   * mapeada por `printerId` — todo nativo, sin volver al WebView, asi funciona
   * aunque la app este en segundo plano.
   *
   * Idempotente: volver a llamarlo con otra config reinicia el servidor. El
   * mapeo `printers` es la fuente de verdad de que se publica y a donde imprime.
   */
  startPrintHost(options: {
    /** Puerto donde escuchar. Default: 9110. */
    port?: number
    /** Token que los clientes deben presentar. Si se omite, no exige auth. */
    token?: string
    /** Nombre con que se anuncia el host en mDNS. */
    name: string
    /** Id estable del host. Si se omite, el plugin genera uno persistente. */
    hostId?: string
    /** Impresoras que se publican y su destino local. */
    printers: PublishedHostPrinter[]
  }): Promise<{ hostId: string; host: string; port: number }>

  /** Apaga el servidor de impresion y deja de anunciarse. */
  stopPrintHost(): Promise<void>

  /** Estado actual del modo host. */
  printHostStatus(): Promise<PrintHostStatus>

  /**
   * Descubre hosts de impresion en la LAN (mDNS `_wbprint._tcp`). Solo el
   * dispositivo que esta EN la red los ve. `timeoutMs` acota el escaneo (4s).
   */
  discoverHosts(options?: { timeoutMs?: number }): Promise<{ hosts: DiscoveredHost[] }>

  /**
   * Evento por cada trabajo que el host recibe e imprime — para el log en vivo
   * de la pantalla de Ajustes. `ok` refleja si la impresion local salio bien.
   */
  addListener(
    eventName: 'printHostJob',
    listener: (job: { printerId: string; ok: boolean; error?: string; from?: string }) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>
}
