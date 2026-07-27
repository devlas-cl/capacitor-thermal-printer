import { WebPlugin } from '@capacitor/core'
import type {
  PrinterDevice,
  DiscoveredNetworkPrinter,
  PrinterStatusResult,
  PrintTarget,
  ThermalPrinterPlugin,
} from './definitions'

/**
 * Stub web: este plugin es Android-nativo. En navegador usa las APIs del
 * browser directamente (WebUSB / Web Serial) — no las envolvemos aquí para
 * no duplicar lo que la plataforma ya expone a la app web.
 */
export class ThermalPrinterWeb extends WebPlugin implements ThermalPrinterPlugin {
  async list(_options: { transport: 'usb' | 'bluetooth' }): Promise<{ devices: PrinterDevice[] }> {
    throw this.unimplemented('Solo Android. En navegador usa WebUSB o Web Serial.')
  }

  async requestPermission(_options: PrintTarget): Promise<{ granted: boolean }> {
    throw this.unimplemented('Solo Android. En navegador usa WebUSB o Web Serial.')
  }

  async print(_options: PrintTarget & { data: string }): Promise<void> {
    throw this.unimplemented('Solo Android. En navegador usa WebUSB o Web Serial.')
  }

  async status(_options: PrintTarget): Promise<PrinterStatusResult> {
    throw this.unimplemented('Solo Android. En navegador usa WebUSB o Web Serial.')
  }

  async discover(): Promise<{ devices: DiscoveredNetworkPrinter[] }> {
    throw this.unimplemented('Solo Android. En navegador el descubrimiento LAN no está disponible.')
  }
}
