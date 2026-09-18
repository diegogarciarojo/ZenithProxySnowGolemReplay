# Validación de SnowGolemReplay 1.1.0

Fecha: **18 de septiembre de 2026**.

## Resultado de esta entrega

- Compilación con Gradle 9.7.1, JDK 25, plugin de desarrollo oficial 1.2.0
  y ZenithProxy publicado **3.7.0+1.21.4**. JAR con bytecode Java 21.
- `build -PrealTimeTest=true --max-workers 2`: **31 pruebas aprobadas**,
  incluida la grabación de **77 segundos reales**. Sin fallos ni omisiones.
- Después se añadió una prueba de cabecera truncada; ejecución específica de
  `ReplayFilesTest`: **3 pruebas aprobadas**. Son **32 casos distintos aprobados**
  entre ambas ejecuciones, no 34.
- Carga del JAR de producción con **Java 21.0.11** y
  `com.zenith.ProxyLaunchWrapper`, usando sólo dependencias publicadas y el JAR
  en `plugins/`: **Plugin Loaded**, ID `snow-golem-replay`, versión **1.1.0**,
  y **ZenithProxy started!**.
- La carga se hizo en un directorio aislado, con servidor entrante, autoconexión,
  Discord y subidas desactivados, sin credenciales reales. El proceso de prueba
  se detuvo al terminar.

## Casos verificados

| Grupo | Casos | Cobertura |
|---|---:|---|
| DeliveryQueueTest | 10 | Embed nativo, adjunto, enlace añadido al mismo mensaje, Discord caído, reintento de edición, mensaje eliminado, límite de adjuntos, reanudación tras reinicio, trabajos antiguos, causa y capturas manuales sin muertes inventadas. |
| GolemModuleIntegrationTest | 11 | Paquetes MCProtocolLib y caché Zenith reales con mundo sintético; muerte/salud cero sin duplicados, otras especies y desaparición, reutilización de ID/UUID, captura manual que conserva paquetes anteriores y dos muertes, prueba de 60 s, disco, comandos y cola de 20.000 paquetes. Incluye la ejecución temporal de 77 s. |
| KiwiCryptoTest | 2 | Vectores independientes, sales aleatorias y formato de clave del enlace. |
| KiwiUploaderTest | 3 | API v2 contra servidor HTTP local: firmas y encabezados, reanudación sin repetir partes y rechazo de finalización no confirmada. |
| ReplayFilesTest | 3 | Metadatos, marcadores anidados, duración, timestamps ordenados y rechazo de contenedor incompleto o cabecera truncada. |
| RollingWindowsTest | 3 | Historial completo con rotaciones y muertes sucesivas, conservación de ventana de respaldo y reinicio por desconexión. |

La prueba temporal emite un golpe a los cinco segundos y una muerte a los 65.
El archivo exportado se decodifica con `ReplayReader` de ZenithProxy y su informe
confirma al menos 60 segundos anteriores. Son datos sintéticos, no una sesión de
Minecraft ni una prueba en 2b2t.

Tiempos observados en esta ejecución: golpe **5.041 s**, muerte **65.023 s**.

La prueba de carga confirma que el JAR se descubre y registra en ZenithProxy.
No demuestra, por sí sola, que el bot reciba datos de una granja real.

## Comprobaciones pendientes en la instalación real

- Envío y edición efectivos en tu canal de Discord con los permisos de tu bot.
  Las pruebas nuevas validan la cola y generan embeds reales de Zenith/JDA,
  usando un transporte simulado para evitar mensajes reales.
- Reproducción visual en el cliente ReplayMod. Se conserva la corrección del
  esquema `realTimestamp -> value -> position` y se decodifican los paquetes,
  pero no se abrió un cliente gráfico de Minecraft.
- Funcionamiento en tu granja de 2b2t: depende de los chunks, entidades y paquetes
  de daño que el servidor envíe a la cuenta de Zenith.
- No se realizó una subida real nueva a file.kiwi en esta entrega. Se contrastó
  la documentación actual y se verificó el uploader con el servidor HTTP local.
- Compatibilidad con otras versiones/canales de ZenithProxy. El objetivo probado
  es **3.7.0+1.21.4**, con ReplayMod de **Minecraft 1.21.4**.

## Entrega

El JAR y el código fuente corresponden a **1.1.0**. Los resultados anteriores
son de validación local; no acreditan el estado de GitHub Actions ni la publicación
de una release.
