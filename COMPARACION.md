# Comparación y decisión — 18 de septiembre de 2026

La base elegida es **SnowGolemReplay**, mejorada como **1.1.0**. Su grabación y
entrega ya resuelven más casos que DeathRecorder. Se conserva el formato de embeds
de DeathRecorder y la integración nativa con los comandos y el bot de ZenithProxy.
No es necesario crear otro plugin ni perder la configuración anterior.

## Papel de cada proyecto

| Proyecto | Qué se tomó de la revisión |
|---|---|
| ZenithProxy | Caché de mundo y entidades, códecs de paquetes, conversión a ReplayMod y componente `Embed`. Su grabador necesita una instantánea inicial; una lista con sólo los últimos paquetes no basta para reconstruir el mundo. |
| ExamplePlugin | Registro oficial de plugin, módulo, configuración y comandos; integración de Gradle. |
| RedstoneNotify | Patrón de observar eventos del mundo y enviar notificaciones mediante el bot de ZenithProxy. |
| AntiRompedorDeGranjas | Respuestas de comandos con el tema principal y notificaciones con título, descripción y campos nativos. Su lógica de palancas no se modifica. |
| DeathRecorder | Referencia visual para la alerta de muerte: título, color naranja rojizo, coordenadas y causa en campos contiguos, archivo y enlace. |
| SnowGolemReplay | Ventanas solapadas, detección por confirmación, historial de daño, exportación compatible y cola persistente de entregas. |

## Diferencias que afectan a la granja

En la revisión de DeathRecorder se encontraron estos riesgos:

- `handleRemoveEntitiesPacket` llama a la captura de muerte si todavía encuentra
  al golem en caché. Retirar una entidad también puede significar descarga de zona;
  ese paquete por sí solo no confirma una muerte. El resultado depende del orden
  en que el resto de Zenith actualiza la caché.
- `isSaving` descarta otros disparadores mientras hay una captura en curso.
- Al guardar se retira la ventana más antigua. Otra muerte poco después puede
  encontrar únicamente una ventana que aún no acumula el minuto anterior.
- `stop()` elimina las sesiones temporales, incluso si estaba pendiente una
  captura durante la espera posterior a la muerte.
- El uploader usa API v1 y una única firma/parte (`00001`); además ignora el cuerpo
  de la respuesta de verificación. Eso no acredita una subida multipart completa.

SnowGolemReplay conserva la ventana que sostiene el historial hasta que otra
pueda sustituirla. Agrupa muertes con una marca por golem, acepta confirmación de
muerte, salud cero o pose de muerte y conserva los incidentes al desconectarse.
Su uploader v2 reanuda fragmentos y sólo entrega el enlace cuando recibe
`complete: true`. La [API oficial](https://file.kiwi/api) documenta ese flujo.

## Cambios implementados en 1.1.0

- Entregas y avisos convertidos a embeds nativos. El enlace confirmado de file.kiwi
  se añade al mensaje que contiene el adjunto; se persisten mensaje y canal para
  reintentar después de un reinicio. Si el mensaje desapareció, se crea otro embed.
- Se conserva el esquema visual de DeathRecorder. La causa se presenta como
  último daño observado, incluyendo causante y entidad directa cuando se conocen,
  con su antigüedad. No se afirma una causa final que el servidor no confirmó.
- Nuevo `.golemreplay clip`: guarda el historial disponible sin simular una muerte
  ni consumir el búfer. `.golemreplay test` conserva su prueba hacia adelante.
- Corrección de una referencia de golem obsoleta cuando un ID corresponde después
  a otra especie o a una entidad excluida por el filtro.
- La exportación rechaza cabeceras de paquetes truncadas, conservando el original
  para diagnóstico. Se mantiene el formato anidado de marcadores de ReplayMod.
- Compatibilidad con entregas pendientes de 1.0.4 y la configuración existente.

## Qué significa «el último minuto»

Con los valores predeterminados y el búfer ya lleno, el replay incluye **60 a unos
75 segundos anteriores** y **10 posteriores**. Las instantáneas cada 15 segundos
permiten reproducir el mundo desde un estado completo. En el calentamiento se
guarda lo disponible y se indica que es parcial. Una ráfaga puede extender la
grabación hasta 10 segundos después de la última muerte agrupada.

Zenith debe permanecer conectado, fuera de la cola y recibiendo la zona del golem.
No puede reconstruir lo sucedido antes de conectarse. El replay y el informe
aportan evidencia; lluvia global, fuego o daño reciente no prueban por sí solos
qué mató al golem.

## Fuentes de código revisadas

- [ZenithProxy, 4fe4508](https://github.com/rfresh2/ZenithProxy/tree/4fe4508c3c4431c0fba79918f33b37381faa779c).
- [ExamplePlugin, d0c22e3](https://github.com/rfresh2/ZenithProxyExamplePlugin/tree/d0c22e35b731f376f04e21a3adb44102e64ea3cc).
- [RedstoneNotify, 20386a4](https://github.com/IceTank/ZenithProxyRedstoneNotify/tree/20386a4669e90d9d5d1eca8f92330a65ca5726f4).
- [AntiRompedor, 785f329](https://github.com/diegogarciarojo/ZenithProxyAntiRompedorDeGranjas/tree/785f329b949982cfadc108fcef76b9463522a975).
- [DeathRecorder, 29a7f17](https://github.com/diegogarciarojo/ZenithProxySnowGolemDeathRecorder/tree/29a7f17388c207f4ecc0b0b2c0104f87164d580f).
- [Base SnowGolemReplay, 3dafeae](https://github.com/diegogarciarojo/ZenithProxySnowGolemReplay/tree/3dafeae).

La compatibilidad binaria y las pruebas de esta entrega usan el artefacto publicado
**ZenithProxy 3.7.0+1.21.4**, no una compilación del HEAD actual. Consulta
[VALIDATION.md](VALIDATION.md) para los resultados y sus límites.
