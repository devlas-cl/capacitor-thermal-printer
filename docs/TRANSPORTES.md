# Transportes — matriz, investigación y decisiones de diseño

Documento de referencia del adapter. Actualizar cuando se agregue un transporte o cambie una decisión.

## Matriz de transportes

| Transporte | Estado | Implementación | Notas |
|---|---|---|---|
| **USB (OTG)** | ✅ Implementado | `UsbTransport.kt` — `UsbManager` puro | Requiere adaptador OTG real (no todos los USB-A→C lo son). Permiso por dispositivo vía diálogo del sistema. |
| **TCP / LAN / WiFi** | ✅ Implementado | `TcpTransport.kt` — `Socket` puro | Raw 9100 (JetDirect). "WiFi" e "impresora de red" son el mismo transporte. |
| **Bluetooth SPP** | ✅ Implementado | `BluetoothTransport.kt` — `BluetoothSocket` RFCOMM | Solo dispositivos emparejados. Conexión por trabajo (~1-2 s de latencia); pool keep-alive si molesta en caja. |
| **Serial RS232** | ⏳ Roadmap | Requiere [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | Solo aplica a dongles USB-serial (FTDI/CH340/PL2303). Las "impresoras serie" reales llegan por BT-SPP o USB, ya cubiertos. Agregar solo cuando un cliente tenga ese hardware. |
| **BLE** | ❌ Descartado | — | Las térmicas POS usan BT clásico (SPP), no BLE. Los pocos modelos BLE no justifican la complejidad GATT. |
| **iOS** | ❌ Sin soporte | — | iOS no tiene API de USB host ni SPP (solo MFi/BLE). TCP sería posible si algún día existe app iOS — el diseño del plugin lo permite sin romper la API. |

## Por qué existe este plugin (y no una librería de terceros)

Se evaluaron a fondo (código fuente leído, no solo READMEs) las opciones existentes:

| Librería / plugin | Qué es | Veredicto |
|---|---|---|
| [DantSu/ESCPOS-ThermalPrinter-Android](https://github.com/DantSu/ESCPOS-ThermalPrinter-Android) (1.5k ★, MIT) | Librería Android BT+TCP+USB | La más madura, pero su API es de **texto formateado** (`[C]<b>…`), no bytes crudos; su filtro USB exige clase 7 (USB_CLASS_PRINTER) y deja fuera térmicas genéricas vendor-specific. Se rescataron sus patrones (ver abajo) sin heredar la dependencia. |
| [paystory-de/thermal-printer-cordova-plugin](https://github.com/paystory-de/thermal-printer-cordova-plugin) (Apache-2.0) | Plugin Cordova sobre DantSu | Código limpio y un solo mecanismo de conexión, pero **solo API de texto formateado** — habría obligado a reescribir el encoder de tickets. Permiso USB sin los flags de Android 14+. |
| `@atomsolution/usb-printer-capacitor` (npm) | Plugin Capacitor USB | Descartado tras auditar el `.tgz`: depende de un SDK propietario (`omnidriver`, terminales Landi), doble mecanismo de conexión inconsistente (UsbManager para connect, DantSu para print) y código muerto. |
| [KhairoHumsi/Printer-ktx](https://github.com/KhairoHumsi/Printer-ktx) | Port Kotlin de DantSu | Abandonado (última release 2021). |
| `@e-is/capacitor-bluetooth-serial` | Plugin BT que usa el POS de Devlas hoy | Funciona, pero hubo que **parcharlo a mano en node_modules** (base64 para bytes crudos + pre-poblar scan con emparejados) — parche frágil que se pierde en cada `npm install`. Este adapter lo hace nativo desde el día 1; la migración del POS a este plugin elimina el parche. |
| [WebUSBReceiptPrinter](https://github.com/NielsLeenheer/WebUSBReceiptPrinter) / WebSerialReceiptPrinter | Librerías browser (no Capacitor) | No aplican al WebView (Chromium no expone WebUSB/Web Serial en Android WebView — [issue 41441927](https://issues.chromium.org/issues/41441927)). Sus ideas de identidad de dispositivo (vendorId+productId) sí se adoptaron. |

**Conclusión**: ninguna opción ofrecía bytes crudos + USB sin filtro de clase + permisos Android 14+ + cero dependencias. El costo de mantener ~400 líneas de Kotlin propio es menor que adoptar cualquiera de esos compromisos.

## Qué se rescató de cada una

- **DantSu**: preferencia por interfaz clase impresora (USB_CLASS_PRINTER) antes del fallback; espera proporcional al tamaño del trabajo antes de cerrar (~1 ms / 16 bytes) para no truncar el búfer de la impresora en TCP/BT.
- **WebUSB (validado con hardware real en este proyecto)**: fallback a "primera interfaz con endpoint bulk OUT" para térmicas genéricas que no declaran clase 7.
- **atomsolution**: escritura USB chunked (4096 bytes / timeout 3 s por chunk).
- **paystory**: metadata rica en el listado de dispositivos; flujo de permiso con BroadcastReceiver (corregido aquí para Android 12/14+: `FLAG_MUTABLE`, `setPackage`, `RECEIVER_NOT_EXPORTED` — ninguno de los plugins lo hace bien).
- **Parche de e-is en el POS**: bytes como base64 en el bridge JS↔nativo — aquí es el contrato de la API, no un parche.

## Decisiones de diseño

1. **Bytes crudos, encoder en la app.** El plugin no sabe qué es una boleta. `generarEscPosTicketBoleta()` (o cualquier encoder) vive en la app y se reusa en todas las plataformas.
2. **Stateless por trabajo.** Conectar→escribir→cerrar en cada `print()`. Elimina toda la clase de bugs de "conexión zombie" (la causa del doble mecanismo inconsistente de atomsolution). El costo es latencia de reconexión BT (~1-2 s); si molesta, el roadmap contempla keep-alive opcional.
3. **Un solo hilo ejecutor.** Serializa trabajos (nunca dos impresiones simultáneas al mismo dispositivo) y saca el I/O bloqueante del main thread (obligatorio para TCP: `NetworkOnMainThreadException`).
4. **Códigos de error estables** (`unavailable`, `not_found`, `permission_denied`, `connect_failed`, `write_failed`) — la app mapea a mensajes de usuario sin parsear strings.
5. **Sin auto-prompt de permiso dentro de `print()`.** Si falta permiso USB, rechaza con `permission_denied`; el flujo de configuración (Ajustes) llama a `requestPermission()` explícitamente. Una venta nunca se bloquea en un diálogo inesperado.

## Roadmap

- [ ] **Serial RS232** vía `usb-serial-for-android` — cuando exista un cliente con ese hardware.
- [ ] **Keep-alive opcional BT** — pool de sockets con TTL si la latencia por ticket molesta en caja.
- [ ] **Migrar el BT del POS Devlas** de `@e-is/capacitor-bluetooth-serial` (parchado) a este plugin — elimina el parche de node_modules.
- [ ] **iOS TCP** — solo si algún día hay app iOS.
