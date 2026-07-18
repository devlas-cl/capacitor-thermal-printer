# @devlas/capacitor-thermal-printer

Adaptador universal de impresión térmica **ESC/POS** para Capacitor: **USB (OTG)**, **TCP/LAN/WiFi** y **Bluetooth SPP** en Android; **TCP/LAN/WiFi** en iOS — una sola API de bytes crudos en ambas plataformas.

- **Bytes crudos, no formateo**: la app codifica el ticket ESC/POS (con su propio encoder) y el plugin solo lo transporta. Así el mismo encoder sirve para Electron, Web Serial, WebUSB y esta app nativa.
- **Stateless**: cada `print()` conecta → escribe → cierra. Sin estado de conexión que se corrompa.
- **Sin dependencias nativas**: `UsbManager`/`Socket`/`BluetoothSocket` en Android, `Network.framework` en iOS — nada más.
- **Permisos modernos**: flujo USB compatible con Android 12/14+ (`FLAG_MUTABLE` + intent explícito + `RECEIVER_NOT_EXPORTED`), `BLUETOOTH_CONNECT` runtime en API 31+.
- **Base64 en el bridge**: los bytes viajan como base64 — sin corrupción UTF-8 (el bug clásico de los plugins BT de la comunidad).
- **iOS solo TCP**: USB y Bluetooth SPP requieren certificación MFi de Apple (no es un tema de código) — ver [docs/TRANSPORTES.md](docs/TRANSPORTES.md).

> Matriz de transportes, librerías evaluadas y decisiones de diseño: [docs/TRANSPORTES.md](docs/TRANSPORTES.md)

## Instalación

```bash
npm i @devlas/capacitor-thermal-printer
npx cap sync android
npx cap sync ios
```

Android: sin pasos extra, no requiere JitPack ni repositorios Maven adicionales.
iOS: agrega `NSLocalNetworkUsageDescription` al `Info.plist` de tu app (iOS pide permiso de "red local" la primera vez que se usa TCP).

## API

```ts
import { ThermalPrinter, bytesToBase64 } from '@devlas/capacitor-thermal-printer'
```

### `list({ transport })`

Lista dispositivos disponibles. `transport: 'usb' | 'bluetooth'` (TCP no es listable — la IP la ingresa el usuario).

```ts
const { devices } = await ThermalPrinter.list({ transport: 'usb' })
// [{ transport: 'usb', vendorId: 1155, productId: 22304, name: 'POS58 Printer',
//    canPrint: true, hasPermission: false, deviceId: 1002 }]

const { devices } = await ThermalPrinter.list({ transport: 'bluetooth' })
// [{ transport: 'bluetooth', address: '66:22:...', name: 'PT-210' }]
// (solo emparejados — la impresora se empareja desde Ajustes del sistema)
```

### `requestPermission(target)`

```ts
// USB: diálogo del sistema por dispositivo. El permiso persiste hasta desconectar
// el cable (o para siempre si el usuario marca "usar por defecto").
const { granted } = await ThermalPrinter.requestPermission({
  transport: 'usb', vendorId: 1155, productId: 22304,
})

// Bluetooth: permiso runtime BLUETOOTH_CONNECT (API 31+)
await ThermalPrinter.requestPermission({ transport: 'bluetooth', address: '66:22:...' })

// TCP: siempre { granted: true }
```

### `print(target & { data })`

`data` = bytes ESC/POS en **base64** (usa el helper `bytesToBase64`).

```ts
const bytes: Uint8Array = miEncoderEscPos(ticket)   // el encoder es de la app

// USB — si se omiten vendorId/productId usa la primera impresora detectada
await ThermalPrinter.print({ transport: 'usb', vendorId: 1155, productId: 22304, data: bytesToBase64(bytes) })

// TCP / LAN / WiFi — raw 9100 (JetDirect)
await ThermalPrinter.print({ transport: 'tcp', host: '192.168.1.50', port: 9100, data: bytesToBase64(bytes) })

// Bluetooth SPP
await ThermalPrinter.print({ transport: 'bluetooth', address: '66:22:...', data: bytesToBase64(bytes) })
```

### Códigos de error

`error.code` estable para manejar en JS:

| Código | Significado |
|---|---|
| `unavailable` | El transporte no existe en el equipo (sin USB host / sin adaptador BT) |
| `not_found` | Dispositivo no encontrado (desconectado, MAC inválida) |
| `permission_denied` | Permiso USB o BLUETOOTH_CONNECT denegado |
| `connect_failed` | No se pudo abrir la conexión (timeout TCP, socket BT, claim USB) |
| `write_failed` | La escritura falló a mitad de trabajo |
| `invalid_transport` / `invalid_data` | Parámetros mal formados |

## Plataformas

| Plataforma | Estado |
|---|---|
| Android (Capacitor WebView) | ✅ usb / tcp / bluetooth |
| iOS (Capacitor WebView) | ✅ tcp — ❌ usb / bluetooth (requieren MFi, ver [docs/TRANSPORTES.md](docs/TRANSPORTES.md)) |
| Web | ❌ stub — en navegador usa WebUSB / Web Serial directamente |

## Publicar

```bash
npm publish --access public
```

## Licencia

MIT © [Devlas SpA](https://devlas.cl)
