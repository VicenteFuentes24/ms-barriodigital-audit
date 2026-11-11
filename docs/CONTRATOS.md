# Contratos inspeccionados y decisiones

## React y BFF

Referencias de solo lectura: `frontend-barriodigital/src/pages/AuditPage.tsx`, `services/auditService.ts`, `types/audit.ts`, `services/apiClient.ts`; `AuditProxyController`, `DownstreamProxyService`, `BarriodigitalProperties` y `SecurityConfig` del BFF.

React solicita `GET /api/audit` y opcionalmente `user`, `date` (input HTML `yyyy-MM-dd`) y `eventType`. Espera **un array directo**, no un objeto Page ni un envelope. Cada elemento tiene `id: string` y campos opcionales `user`, `eventType`, `entityType`, `entityId`, `occurredAt`, `detail`. La tabla muestra fecha, usuario, tipo, entidad e información textual. El cliente usa Bearer adquirido por MSAL y muestra `message` de las respuestas JSON de error. Audit siempre produce mensajes controlados, sin trazas.

Audit devuelve esos siete campos. `id` es el UUID del evento; `entityType` es `REQUEST`; `entityId` es el requestId decimal como string; `occurredAt` es ISO-8601 UTC; `detail` se compone solo del nombre real del procedimiento, estados y versión del evento. `user` es **null**, porque no existe actor en Kafka v1. No se atribuye la acción al usuario que consulta el historial ni se adivina desde otras bases.

Consultas:

| Parámetro | Semántica |
| --- | --- |
| `user` | Subcadena literal, sin distinguir mayúsculas, máximo 250 caracteres; escapa `%`, `_` y `\`. Actualmente no encuentra registros, porque el productor no envía actor |
| `date` | Día del evento en `AUDIT_QUERY_ZONE`, por defecto `America/Santiago`; límite inferior incluido y siguiente medianoche excluida, respetando DST |
| `eventType` | `REQUEST_CREATED` o `REQUEST_STATUS_CHANGED`, normaliza mayúsculas; vacío significa sin filtro |
| `requestId` | Entero positivo opcional, consulta el historial de una solicitud |
| `page` | Base cero, 0 por defecto; máximo 10000 |
| `size` | 100 por defecto, 1..200 |

Los filtros se combinan con AND. Orden: `occurredAt DESC`, después UUID DESC para desempate estable. El cuerpo siempre es array; headers `X-Total-Count` y `X-Has-Next` permiten paginación sin romper React. La pantalla actual no envía page/size ni ofrece navegación entre páginas: ve como máximo los primeros 100 eventos. La paginación por offset puede desplazarse si llegan eventos nuevos; no representa una instantánea histórica de toda la consulta.

No hay endpoints de escritura. Health: `GET /actuator/health`, solo `{ "status": "UP" }` o estado de indisponibilidad, sin componentes ni detalles. La comprobación DB de Actuator no certifica conectividad Kafka ni ausencia de lag.

## Límite de autenticación entre BFF y Audit

El proxy añade el path original a `AUDIT_SERVICE_URL`, conserva la query y códigos/cuerpos downstream. Pero `DownstreamProxyService.BLOCKED_INBOUND_HEADERS` incluye `authorization`; luego solo aplica encabezados `X-User-*`. **No retransmite el JWT.** No hay un mecanismo firmado de servicio a servicio que Audit pueda validar dentro del alcance actual.

Audit exige Bearer Microsoft Entra ID para GET `/api/audit` y subrutas, valida firma RS256 mediante JWKS del tenant, issuer v2.0, vencimiento y audience. Además exige `SCOPE_access_as_user` y `ROLE_Admin` o `ROLE_Auditor`. Otros roles se rechazan. No confía en `X-User-*`, IP de loopback ni headers reenviados; no hay bypass local. CORS no se abre; la API es stateless, solo Bearer y no acepta escrituras. CSRF se deshabilita para este modelo sin autenticación por cookies.

Consecuencia: **el flujo actual React → BFF → Audit responderá 401 del downstream** aunque React tenga sesión Admin. La compatibilidad de ruta y JSON está implementada, pero la integración autenticada requiere una tarea posterior autorizada en BFF: retransmitir el access token ya validado a Audit y conservar su validación de audience, o establecer identidad de servicio verificable. Esto no se puede resolver con seguridad modificando únicamente Audit. No se modificó BFF ni React.

## Kafka v1

Referencias: `RequestKafkaEventV1`, `KafkaEventJson`, `KafkaOutboxWorker`, `KafkaOutboxStore` de Requests; `RequestEventV1`, `EventCodec`, `RequestEventListener`, `KafkaErrorConfig`, `FailedEventService` y migraciones de Report.

Requests publica JSON en `bd.requests.events.v1` con clave decimal `requestId`. Campos: UUID `eventId`; `eventType`; `schemaVersion=1`; `requestId` y `requestVersion` positivos; `procedureId` no vacío hasta 100; `procedureName` no vacío hasta 250; `previousStatus`; `status`; `occurredAt` ISO-8601 con zona. No hay usuario, actor, correo, `createdBy` ni otro envelope.

Creación: versión 1, estado `INGRESADO`, anterior null. Cambio: versión mayor que 1, estado anterior real distinto del nuevo. Los estados válidos son INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO y RECHAZADO. Audit valida estas invariantes, key, JSON, UTF-8, campos desconocidos y versión de contrato. Los ejemplos en `docs/events/` son documentación, no mensajes para insertar en el Kafka compartido.

A diferencia de Report, Audit **no mantiene una proyección ni exige haber visto la versión anterior**: acepta eventos históricos distintos fuera de orden. La retención Kafka puede haber eliminado el inicio del historial; guardar los eventos restantes no fabrica lo perdido. Las versiones faltantes pueden diagnosticarse mediante SQL, pero no se completan automáticamente. El grupo `bd-audit-v1` es independiente de `bd-report-v1`; nunca usar el grupo de Report para Audit.

## Persistencia y recuperación

`V1__audit_history.sql` crea exclusivamente tablas/índices/función/trigger en `bd_audit`; Flyway lleva `bd_audit.flyway_schema_history`. La migración no se ha ejecutado en Supabase compartido.

- `audit_event`: historial y marca duradera de procesamiento en **la misma fila/transacción**, UUID PK, unique `(request_id, request_version)`, evento canónico jsonb, datos del dominio, fecha de evento/procesamiento y coordenadas originales Kafka. `actor` nullable siempre null en v1. No hay FK a Requests ni consultas entre microservicios.
- `failed_event`: cuarentena duradera con bytes originales de key/payload (incluye tombstones e inválidos UTF-8), coordenadas únicas, código controlado y fechas/contador de recuperación. No se publica un DLT ni se crean tópicos.
- Índices: fecha/UUID, tipo/fecha, actor/fecha y solicitud/versión. Las búsquedas por subcadena de actor no se aceleran con un índice B-tree de prefijo; v1 no tiene actores y no justifica agregar extensiones.
- Historial append-only: entidad Hibernate `@Immutable`, no API de escritura, trigger rechaza UPDATE/DELETE. El usuario runtime debe carecer de permisos DDL/TRUNCATE; un propietario/superusuario aún puede desactivar triggers o truncar. Esto no es un registro criptográfico resistente al administrador de la base.

El INSERT usa `ON CONFLICT DO NOTHING` sin modificar filas previas. Si hay conflicto, solo es duplicado legítimo cuando UUID y contenido canónico completo coinciden. UUID reutilizado con distinto contenido o distinta identidad para una misma versión se envía a cuarentena, sin sobrescribir historial. La restricción SQL protege también los consumidores concurrentes. La serialización canónica evita considerar diferente un JSON equivalente por espacios/orden de propiedades.

Auto-commit Kafka deshabilitado y `MANUAL_IMMEDIATE`: el listener confirma después de que retorna el servicio transaccional, ya comprometida la fila. Una caída entre commit DB y commit Kafka genera reentrega y deduplicación. No hay exactamente una vez entre Kafka y PostgreSQL.

Tres reintentos transitorios por defecto (cuatro intentos incluyendo el inicial), pausa de 1 s; rango configurado 0..10 reintentos y 100..10000 ms. Errores permanentes van directamente a cuarentena. El recuperador confirma únicamente después del commit de cuarentena. Si PostgreSQL también impide registrar la cuarentena, la excepción se propaga y el offset no se confirma; se vuelve a intentar, aunque dure la interrupción de DB. Por tanto la cantidad está acotada por ciclo, no se abandona el registro al agotarla. Un fallo de Kafka conserva offsets comprometidos y se recupera al restablecer conectividad; si expira la retención durante una interrupción larga, no se garantiza recuperar mensajes ya eliminados por Kafka.

Recuperación explícita: perfil `recover` procesa **un** ID de cuarentena con listener y web desactivados. Lee los bytes originales, valida y persiste idempotentemente; marca recuperación en la misma transacción. Si falla, el historial y el registro original se conservan y se intenta anotar el intento en otra transacción. Un JSON inválido o versión desconocida seguirá fallando hasta resolver el contrato/causa; el comando no inventa contenido ni corrige payloads. No resetear offsets del grupo para resolver casos puntuales.

Referencias técnicas: [Spring Kafka — DefaultErrorHandler y commitRecovered](https://docs.spring.io/spring-kafka/docs/current/api/org/springframework/kafka/listener/DefaultErrorHandler.html), [Spring Security — validación JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
