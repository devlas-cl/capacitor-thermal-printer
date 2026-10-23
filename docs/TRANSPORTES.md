# Transportes — matriz, investigación y decisiones de diseño

Documento de referencia del adapter. Actualizar cuando se agregue un transporte o cambie una decisión.

## Matriz de transportes

| Transporte | Estado | Implementación | Notas |
|---|---|---|---|
| **USB (OTG)** | ✅ Validado en hardware real (2026-07-06) | `UsbTransport.kt` — `UsbManager` puro | Requiere adaptador OTG real (no todos los USB-A→C lo son). Permiso por dispositivo vía diálogo del sistema — Android lo revoca al re-enchufar o reinstalar la app; el consumidor debe re-pedirlo y reintentar (ver useAndroidUsbPrinter del POS). |
| **TCP / LAN / WiFi** | ✅ Implementado (sin validar en hardware) | `TcpTransport.kt` — `Socket` puro | Raw 9100 (JetDirect). "WiFi" e "impresora de red" son el mismo transporte. |
| **Bluetooth SPP** | ✅ Validado en hardware real (2026-07-06) | `BluetoothTransport.kt` — `BluetoothSocket` RFCOMM | Solo dispositivos emparejados. Conexión por trabajo (~1-2 s de latencia); pool keep-alive si molesta en caja. Escritura pausada (512 B cada 20 ms, ~25 KB/s), 150 ms de espera tras conectar y espera final proporcional al tamaño (0,5–5 s): sin esto una TELHOME 80 mm imprimía la imagen de la boleta como texto. |
| **Serial RS232 (impresora)** | ⏳ Roadmap | Requiere [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | Solo aplica a dongles USB-serial (FTDI/CH340/PL2303). Las "impresoras serie" reales llegan por BT-SPP o USB, ya cubiertos. Agregar solo cuando un cliente tenga ese hardware. **Nota:** la librería ya es dependencia del módulo (ver fila de abajo) — extender `PrinterTransport` con `'serial'` es ahora trabajo de wiring, no de investigación. |
| **BLE** | ❌ Descartado | — | Las térmicas POS usan BT clásico (SPP), no BLE. Los pocos modelos BLE no justifican la complejidad GATT. |
| **iOS — TCP** | ✅ Implementado (2026-07-18) | `TcpTransport.swift` — `Network.framework` (`NWConnection`) | Mismo contrato de API que Android (`print({transport:'tcp', host, port, data})`). Requiere `NSLocalNetworkUsageDescription` en el Info.plist de la app host (iOS pide permiso de "red local" la primera vez). |
| **iOS — USB / Bluetooth SPP** | ❌ Sin soporte | — | Requieren el programa **MFi** (Made for iPhone) de Apple vía `ExternalAccessory` — es una certificación de hardware del fabricante de la impresora, no algo que se resuelva con código ni con la membresía de Developer Program. `list`/`requestPermission`/`print` devuelven `unavailable` explícito para estos transportes en iOS. |

## Plugin `BalanzaSerial` (v0.8.0) — balanza en vivo, no impresora

Segundo plugin `@CapacitorPlugin` en este mismo módulo nativo (`BalanzaSerialPlugin.kt` /
`BalanzaSerialTransport.kt`), agregado para el POS de Devlas (Fase 5 de
`docs/BALANZAS_DIGITALES_PLAN.md` en el repo `pos`) — **no es un transporte de impresión**,
es lectura continua de un adaptador USB-serie (FTDI/CH340/CP210x/PL2303) conectado a una
balanza digital. Comparte el flujo de permiso USB con `UsbTransport.kt` (mismo patrón
PendingIntent/BroadcastReceiver, ya validado con hardware real) pero usa
[mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) para el
protocolo de cada chip en vez de `bulkTransfer` crudo — un adaptador USB-serie necesita
configurar baudios/framing propios del chip, a diferencia de una "impresora USB" donde los
bytes que se mandan/reciben ya son el stream ESC/POS sin más.

- **Dependencia nueva**: `com.github.mik3y:usb-serial-for-android:3.11.0` (JitPack, no está
  en Maven Central — repo agregado en `android/build.gradle`).
- **Bump de Kotlin 1.9.22 → 2.2.10** (compilador y `kotlin-gradle-plugin`, en los DOS lugares
  del `build.gradle` — el classpath de `buildscript{}` no puede leer la property `ext.kotlinVersion`
  porque Gradle evalúa ese bloque antes, así que van literales sincronizados a mano):
  `usb-serial-for-android:3.11.0` declara `kotlin-stdlib:2.2.10` como dependencia directa
  (confirmado con `./gradlew dependencyInsight`, no una suposición), y un compilador Kotlin
  solo puede leer metadata de su propia versión hacia atrás, nunca hacia adelante.
- **`SerialInputOutputManager` no implementa `Runnable`** pese a lo que sugiere el nombre —
  administra su propio hilo con `start()`/`stop()` (confirmado contra el código fuente real
  de la librería en GitHub, tag 3.11.0, no contra la documentación). Un primer intento
  pasándolo a un `ExecutorService.submit()` fallaba en compilación.
- **Compilado y verificado con `./gradlew :devlas-capacitor-thermal-printer:assembleDebug`
  y `test`** (debug y release, 2026-08-28, apuntando este módulo local desde `pos/android`
  temporalmente vía `capacitor.settings.gradle`, revertido después de la verificación) —
  no contra hardware real, no hay balanza física para probar el protocolo de trama en sí
  (`src/utils/balanzaSerial.ts` en `pos/`, configurable para calibrarlo cuando llegue).
- **Verificado también con la APK completa de `pos`** (`./gradlew :app:assembleDebug`,
  139 MB, generada y luego borrada — no era para quedarse), no solo el módulo aislado: con
  SumUp Tap to Pay, Mercado Pago y el resto de los plugins nativos resolviendo juntos. Esto
  encontró un problema real que el chequeo del módulo aislado no detectaba: **JitPack no
  está declarado a nivel de la app consumidora**, solo dentro de `android/build.gradle` de
  este plugin — un `repositories{}` de un módulo no es visible para la resolución de
  dependencias de quien lo consume. `:app:assembleDebug` fallaba con "Could not find
  com.github.mik3y:usb-serial-for-android" hasta agregar
  `maven { url = 'https://jitpack.io' }` al bloque `allprojects { repositories {} }` del
  `android/build.gradle` RAÍZ de la app (no de este plugin). **Quien bumpee la dependencia
  en `pos` tiene que agregar esa línea también** — sin eso, la app entera deja de compilar,
  no solo esto.
- **`pos/package.json` sigue apuntando a `^0.2.0`** — este salto (0.2.0 → 0.8.0) arrastra
  varias versiones de cambios reales no relacionados (TCP/LAN iOS, fix de hotplug, barrido de
  subred) que ameritan su propia revisión antes de subir la dependencia del POS, no un bump
  automático solo porque este plugin nuevo ya está listo.

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
3. **Un ejecutor de un hilo por destino.** Serializa trabajos **a la misma impresora** (nunca dos impresiones simultáneas al mismo dispositivo) y saca el I/O bloqueante del main thread (obligatorio para TCP: `NetworkOnMainThreadException`).

   La clave de destino es `tcp:host:port` / `usb:vid:pid` / `bt:MAC`. Un ejecutor **global** sería incorrecto en escenarios multi-impresora: la comanda a una cocina apagada retendría la boleta del cliente durante los 4 s de `CONNECT_TIMEOUT_MS`, más el `Thread.sleep` post-escritura (hasta 2 s). Con USB sin `vendorId`/`productId` el destino es ambiguo ("la primera detectada"), así que esas llamadas comparten una fila (`usb:auto`).
4. **Códigos de error estables** (`unavailable`, `not_found`, `permission_denied`, `connect_failed`, `write_failed`) — la app mapea a mensajes de usuario sin parsear strings.
5. **Sin auto-prompt de permiso dentro de `print()`.** Si falta permiso USB, rechaza con `permission_denied`; el flujo de configuración (Ajustes) llama a `requestPermission()` explícitamente. Una venta nunca se bloquea en un diálogo inesperado.

## Roadmap

- [ ] **Serial RS232** vía `usb-serial-for-android` — cuando exista un cliente con ese hardware.
- [ ] **Keep-alive opcional BT** — pool de sockets con TTL si la latencia por ticket molesta en caja.
- [x] **Migrar el BT del POS Devlas** de `@e-is/capacitor-bluetooth-serial` (parchado) a este plugin — hecho (2026-07-06): el POS eliminó esa dependencia y su parche de node_modules; `useAndroidBtPrinter` ahora usa el transporte `bluetooth` de este plugin con las mismas keys de configuración.
- [x] **iOS TCP** — hecho (2026-07-18): `TcpTransport.swift` vía `Network.framework`. USB/Bluetooth SPP en iOS quedan fuera de alcance (requieren certificación MFi, no es un tema de código).
