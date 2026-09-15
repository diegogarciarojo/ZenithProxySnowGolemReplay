# Validación

Fecha: 14 de septiembre de 2026 (America/Mexico_City; 15 de septiembre UTC).

## Resultado

- Compilación con el plugin de desarrollo oficial **1.2.0**, **Gradle 9.7.1**,
  **JDK 25** y dependencia publicada **ZenithProxy 3.7.0+1.21.4**. Bytecode Java 21.
- **14 pruebas aprobadas**, incluida una prueba en tiempo real de 77 segundos.
  La ejecución normal sin `-PrealTimeTest=true` realiza 13 y omite esa prueba larga.
- Carga del JAR mediante `com.zenith.ProxyLaunchWrapper`: mensajes **Plugin Loaded**
  para `snow-golem-replay`, versión `1.0.0`, y **ZenithProxy started!**.
  Proceso aislado, sin cuenta real ni conexión a Minecraft/Discord.
- Subida real de un archivo de texto sintético sin datos personales mediante
  `https://api.file.kiwi/v2/folders`, PUT cifrado y verificación de finalización:
  **FILE_KIWI_LIVE_UPLOAD_VERIFIED**. El estado y enlace de esa prueba se guardaron
  en el directorio de trabajo, no dentro del paquete distribuido.

## Qué comprueban las pruebas

1. Rotaciones, calentamiento, desconexión y muertes repetidas conservan el historial
   anterior completo cuando ya está disponible.
2. Paquetes reales de MCProtocolLib de muerte y salud cero generan un único
   incidente de golem. Un golpe, desaparición o muerte de shulker no lo generan.
3. Un informe incluye el daño previo, y el replay se lee con el `ReplayReader`
   de ZenithProxy, incluida la secuencia de login, configuración y mundo.
4. La prueba manual dura 60 segundos de replay, marca su inicio, cuenta una muerte
   sin duplicarla y añade la marca de muerte. El test rápido adelanta la condición
   temporal de cierre; la codificación y exportación usan las clases reales.
5. Los comandos de configuración se ejecutan con el dispatcher Brigadier real;
   guardan tiempos, canal y límites, y rechazan un tamaño de búfer inválido.
6. La prueba temporal completa mantiene el módulo activo **77 segundos** y rota
   ventanas mientras ocurre un golpe aproximadamente a los 5 s y la muerte a los
   65 s. El replay exportado y decodificado contiene:

   - `[5024] ... event=LIVING_HURT`
   - `[65005] ... event=LIVING_DEATH`

   El informe confirma al menos 60 segundos anteriores. Los tiempos pueden variar
   ligeramente al repetirla. Son entidades y paquetes sintéticos en la caché de
   ZenithProxy, no una sesión en un servidor de Minecraft.
7. Siete vectores independientes de `wormhole-crypto` 0.3.1 coinciden con el cifrado
   Java, incluidos límites de registro y contenidos de varios registros.
8. Un servidor HTTP de prueba verifica API v2, encabezados firmados, orden de
   fragmentos, reanudación sin repetir fragmentos confirmados y rechazo de un
   enlace cuando falta confirmación de finalización.
9. Exportación con marcas e informe, duración exacta de la prueba, timestamps
   ordenados y rechazo de archivos incompletos.

## Corrección del cierre de replay

La implementación publicada de `ReplayRecording.close()` espera al ejecutor
mientras mantiene el monitor que necesita `writePacket0()`. La prueba de lectura
del paquete final falló con esa implementación. El adaptador local evita mantener
ese monitor mientras vacía el ejecutor; con la corrección, la muerte aparece en
el replay exportado. También limita la cola y propaga errores de serialización.

## Pendiente de comprobar en tu instalación

- Entrega efectiva con tu bot, canal y permisos de Discord. No se proporcionó una
  sesión autenticada para probarla. Se integra con JDA de la versión publicada.
- Comportamiento en tu granja de 2b2t, incluyendo alcance de entidades y datos que
  el servidor realmente envía para la causa del daño.
- Reproducción visual en el cliente ReplayMod. Se comprobó el contenedor, registros,
  metadatos y decodificación de los paquetes, no una sesión gráfica del cliente.
- Descarga del archivo a través de la interfaz web de file.kiwi. La subida real fue
  confirmada por su API y el cifrado se contrastó independientemente con su SDK.
- Compatibilidad con otros canales o versiones de ZenithProxy; el objetivo es
  `3.7.0+1.21.4` y no se anuncia compatibilidad binaria universal.

## Fuentes consultadas

- https://github.com/rfresh2/ZenithProxy
  (revisión consultada `ef9bc8220423b0557ed7605a1ce85bb8debae483`; compilación y
  adaptación de replay usan el artefacto publicado 3.7.0+1.21.4).
- https://github.com/rfresh2/ZenithProxyExamplePlugin
  (`a23ff491cbadb65bb8a442a217e4fa44ac0ded7e`).
- https://github.com/diegogarciarojo/ZenithProxyAntiRompedorDeGranjas
- https://github.com/IceTank/ZenithProxyRedstoneNotify
- https://file.kiwi/api
- https://file.kiwi/api/v1
- https://github.com/file-kiwi/node
  (`694836977baf85e8c5f6a879a28c1c0a6ca13ec7`).
