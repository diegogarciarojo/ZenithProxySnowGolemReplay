# ZenithProxySnowGolemReplay

Graba el contexto anterior a la muerte de un golem de nieve y conserva un replay
`.mcpr`, su informe y una marca en el momento de la muerte. Intenta adjuntarlo al
Discord de ZenithProxy y subirlo a file.kiwi; publica el enlace cuando la API
confirma que todos los fragmentos están subidos.

**[Descargar la última release](https://github.com/diegogarciarojo/ZenithProxySnowGolemReplay/releases/latest)**
· [Plugin AntiRompedorDeGranjas](https://github.com/diegogarciarojo/ZenithProxyAntiRompedorDeGranjas)

## Instalación

Compilado para **ZenithProxy 3.7.0, canal java.1.21.4**, con Java 21 o superior.
Usa ReplayMod de Minecraft 1.21.4 para abrir el archivo. La versión interna del
proxy es lo que importa para el plugin, aunque Zenith use ViaVersion para
conectarse a otra versión de servidor.

Coloca `ZenithProxySnowGolemReplay-1.1.0.jar` en `plugins/` y reinicia ZenithProxy.
Sustituye la copia anterior de SnowGolemReplay y deja un solo grabador de golems:
DeathRecorder también registra el alias `golemreplay` y usar ambos duplicaría
grabación y avisos. AntiRompedorDeGranjas puede permanecer instalado.
No necesita Node.js ni una clave API de file.kiwi.

El módulo se activa por defecto. Reutiliza el bot y canal de Discord ya configurados
en ZenithProxy. El bot necesita permiso para enviar mensajes y adjuntar archivos.
Puedes elegir otro canal para las entregas; los comandos siguen entrando por el
canal de control autorizado de ZenithProxy.
Los comandos requieren el rol de propietario de la cuenta configurado en ZenithProxy.

Escribe `.golemreplay` sin argumentos para abrir el menú nativo **Invalid command usage**,
con los ajustes actuales y todos los comandos. Usa `.golemreplay status` para consultar
el estado de la grabación y el último problema detectado.
Las respuestas usan el color principal del tema de ZenithProxy, como AntiRompedor.
La prueba inicia una grabación independiente aunque el búfer anterior esté vacío o
suspendido. Requiere conexión fuera de la cola y espacio en disco suficiente; si
no puede empezar, indica el motivo real y no confirma una grabación inexistente.

## Funcionamiento

- Búfer anterior predeterminado: **60 segundos**. Cola posterior: **10 segundos**.
- Cada **15 segundos** comienza una ventana independiente con el estado inicial
  del mundo. Se conservan ventanas solapadas: cortar simplemente los paquetes
  antiguos perdería los chunks, entidades y configuración necesarios para reproducirlos.
- Ante una muerte, se elige la ventana más reciente que ya tenga todo el historial.
  El replay empieza entre 60 y aproximadamente 75 segundos antes de la muerte con
  los valores predeterminados. Conserva los 10 segundos posteriores. La finalización
  puede esperar a la siguiente rotación si esa ventana todavía sostiene el búfer.
- Una ráfaga de muertes puede compartir replay, con una marca por golem; otros
  incidentes simultáneos pueden producir replays independientes.
- No activa ni desactiva el ReplayMod o VisualRange integrado: pueden coexistir.
- Al comenzar, reconectarse o cambiar ajustes, el búfer necesita calentarse. Una
  muerte anterior a completar ese tiempo conserva lo disponible y se etiqueta
  como historial parcial. Una desconexión corta la cola posterior y queda indicada.

La confirmación usa el estado de muerte, salud cero o pose de muerte recibidos
para un **snow golem**. Daño sin muerte, muerte de otra especie o eliminación de
entidad por descarga de chunks no activan una falsa alerta. El UUID evita confundir
golems distintos cuando el servidor reutiliza un ID numérico.

## Comandos desde Discord

Usa el prefijo configurado en ZenithProxy (por defecto **`.`**). Por ejemplo:

| Comando | Resultado |
|---|---|
| `.golemreplay on` / `.golemreplay off` | Activa o desactiva la vigilancia y grabación. |
| `.golemreplay status` | Estado, historial disponible, configuración y último problema de grabación. |
| `.golemreplay golems` | UUID y coordenadas de los golems cargados dentro del filtro (hasta 30). |
| `.golemreplay clip` | Conserva el historial ya grabado y el intervalo posterior; no simula una muerte ni reinicia el búfer. |
| `.golemreplay test` | Graba **60 segundos desde su activación** y luego hace las entregas configuradas. |
| `.golemreplay buffer 120` | Guarda al menos 120 segundos anteriores; predeterminado 60, rango 10–300. |
| `.golemreplay post 10` | Segundos posteriores a la muerte, rango 1–60. |
| `.golemreplay checkpoint 15` | Intervalo entre nuevas ventanas, rango 10–60 segundos. |
| `.golemreplay discord on` / `off` | Activa o desactiva adjuntos y enlaces a Discord. |
| `.golemreplay kiwi on` / `off` | Activa o desactiva la subida a file.kiwi. |
| `.golemreplay channel 123456789012345678` | Canal de Discord para las entregas. |
| `.golemreplay channel default` | Vuelve al canal configurado en ZenithProxy. |
| `.golemreplay watch all` | Vigila todos los golems cargados; comportamiento predeterminado. |
| `.golemreplay watch add <uuid>` | Limita la vigilancia a los UUID añadidos. |
| `.golemreplay watch remove <uuid>` | Quita un UUID; una lista vacía significa todos. |
| `.golemreplay watch list` | Muestra el filtro de UUID. |
| `.golemreplay minFreeDisk 256` | Espacio libre mínimo en MiB, rango 64–1048576. |
| `.golemreplay maxBufferDisk 2048` | Límite de archivos de búfer en MiB, rango 64–1048576. |
| `.golemreplay retry` | Reactiva las entregas pendientes o fallidas. No vuelve a enviar pasos confirmados. |

Todos los ajustes del plugin se guardan en
`plugins/config/snow-golem-replay.json`. Los cambios de tiempo, disco y filtro
reinician el búfer. Durante una prueba, esos cambios se rechazan hasta que termine;
`off` puede interrumpirla y conservar la parte disponible.

## Prueba de un minuto

Para probar la mecánica de guardar el último minuto, espera a tener historial y
usa `.golemreplay clip`. Mantiene la grabación automática y guarda un marcador
`MANUAL_CLIP`, que no cuenta como muerte. Si el búfer aún se está calentando, el
embed informa del historial parcial. Sin una ventana válida, devuelve un error.

Con ZenithProxy dentro del servidor y el módulo activo, ejecuta `.golemreplay test`.
No necesita esperar a completar el búfer histórico: la prueba graba hacia adelante.
Puedes generar y matar un golem visible durante ese minuto. El mensaje final
indica **cuántos golems murieron**, y el replay contiene sus marcas e informe.
Si no muere ninguno, se envía igualmente y muestra cero muertes. El marcador
`MANUAL_TEST` representa el inicio de la prueba, nunca una muerte.

La vigilancia normal continúa durante la prueba. Una muerte puede originar tanto
su replay automático con historial previo como el replay de prueba. Si tienes un
filtro de UUID activo, el golem de prueba debe estar incluido; `watch all` permite
probar con uno recién generado. Se rechaza una segunda prueba simultánea.

## Informe y diagnóstico

Los archivos quedan en `replays/snow-golem/incidents/`:

- `golem-<id>.mcpr`: replay con `markers.json` y `golem-incident.json` incorporados.
- `.mcpr.incident.json`: informe también legible sin abrir el replay.
- `.mcpr.delivery.json`: progreso y errores de entrega.
- `.mcpr.kiwi.json`: estado para reanudar file.kiwi, incluidos datos de acceso.

El informe registra hora UTC y local, dimensión, coordenadas, UUID, confirmación,
instante dentro del replay y hasta 128 eventos recientes de daño dentro del búfer.
Cuando el servidor lo suministra, identifica el tipo de daño y los IDs, tipos y
UUID de las entidades causante y directa. Incluye observaciones de lluvia,
fuego y bloque a los pies. **El último daño recibido es evidencia, no necesariamente
la causa final**; puede faltar información o morir por un factor que el servidor
no describa completamente.

**ZenithProxy tiene que permanecer conectado y recibir esa zona.** No puede grabar
algo ocurrido mientras estuvo desconectado, en cola o fuera del rango de entidades
que envía el servidor. Encontrar muerto un golem al llegar no permite reconstruir
su pasado. El replay es una grabación de datos de Minecraft, no un video MP4.

## Subidas, almacenamiento y recursos

Las entregas usan `com.zenith.discord.Embed`, el mismo componente de ZenithProxy
y AntiRompedor. La alerta conserva el formato de DeathRecorder: **Snow Golem Death
Detected!**, color naranja rojizo, coordenadas y causa en campos contiguos, nombre
del replay y enlace **File.kiwi Download Link**. El campo de causa muestra el
último daño observado y aclara que no demuestra por sí solo la causa final.
Las pruebas y capturas manuales se identifican como tales. Los comandos y avisos
de estado usan el color principal de ZenithProxy.

Primero se envía el embed con el adjunto. Cuando file.kiwi confirma la subida, se
edita ese mismo embed para añadir el enlace, conservando el adjunto. El ID del
mensaje y del canal se guardan para continuar tras reinicios. Si el mensaje fue
eliminado, se envía otro embed con el enlace. Las entregas pendientes de 1.0.4
siguen siendo compatibles.

Se intenta primero el adjunto en Discord y también file.kiwi. Si el archivo supera
el límite del servidor de Discord, se avisa y continúa la subida a file.kiwi.
Una falla de Discord no bloquea file.kiwi; su enlace se intenta enviar después.
Las entregas tienen hasta cinco intentos con espera creciente, persistentes entre
reinicios; `.golemreplay retry` las reactiva. Cada solicitud y fragmento tienen
tiempo límite; los fragmentos confirmados se omiten al reanudar. Una respuesta
perdida de Discord puede excepcionalmente generar un duplicado al reintentar.

file.kiwi usa API v2, cifrado AES-128-GCM/RFC 8188, claves aleatorias locales,
subida secuencial a URLs firmadas y verificación final. El enlace se publica con
su fragmento `#clave`, necesario para descargar. La API documenta una ventana de
descarga gratuita de 24 horas para archivos menores de 10 GB: **no sustituye una
copia de seguridad local**. [Documentación oficial](https://file.kiwi/api).

Los replays contienen la zona grabada, coordenadas y datos visibles de Minecraft;
compártelos en tu canal previsto para ello. El enlace completo y los archivos de
estado permiten acceder a esa grabación. El informe está dentro del archivo que
se comparte, de modo que quien lo descargue puede investigar el incidente.

Las ventanas se escriben en disco y las que no contienen incidentes se eliminan
al caducar. Los incidentes **se conservan localmente**, incluso tras subirlos.
El tamaño total de incidentes puede crecer: el umbral de espacio libre detiene
temporalmente la grabación si falta disco. Los búferes incompletos de un cierre
brusco o error se conservan para diagnóstico y cuentan para `maxBufferDisk`.
No se garantiza recuperar un `.mcpr` sin finalizar después de matar el proceso
o perder energía. Los apagados normales intentan finalizar las grabaciones.

Hay aproximadamente `ceil(buffer/checkpoint)+1` escritores simultáneos, más
ventanas pendientes por incidentes/prueba. Subir el tiempo de búfer o bajar el
intervalo aumenta CPU y escritura de disco. La cola de cada escritor está limitada;
un error o saturación se señala y no se publica como replay completo.

## Compilación y validación

Proyecto basado en el ejemplo oficial. Gradle 9.7.1, plugin de desarrollo Zenith
1.2.0, dependencia fijada a 3.7.0+1.21.4. JDK 25 para compilar, bytecode Java 21.
`gradlew build` crea el JAR en `build/libs/`. `gradlew build -PrealTimeTest=true`
incluye una prueba adicional de 77 segundos; las demás pruebas son rápidas.

Consulta `VALIDATION.md` para resultados y límites de lo comprobado.
El código adaptado de Zenith conserva su licencia AGPL; consulta LICENSE y
THIRD_PARTY_NOTICES.md.
