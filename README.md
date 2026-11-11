# ms-audit — BarrioDigital EV5/EV6

Auditoría histórica de solicitudes municipales. Conserva el proyecto Initializr existente, Java 21, Spring Boot **4.1.2**, Maven Wrapper y el paquete real generado `cl.barriodigital.ms_audit` (no se renombró a `msaudit`).

**Estado de integración:** código y contrato REST implementados. El BFF existente elimina el JWT al reenviar solicitudes, por lo que el circuito React/BFF queda bloqueado por la seguridad interna de Audit hasta una futura tarea autorizada de propagación del token. Los eventos actuales tampoco incluyen actor: la API devuelve `user: null`, y filtrar por usuario no encuentra registros. No se modificaron otros proyectos ni se desactivó seguridad para ocultar estas limitaciones.

## Diseño

```text
Requests outbox → bd.requests.events.v1
                         ├─ bd-report-v1 → Report (independiente)
                         └─ bd-audit-v1 → validación → transacción PostgreSQL
                                                     ├─ bd_audit.audit_event
                                                     └─ bd_audit.failed_event (fallos)
GET /api/audit → JWT + scope + Admin/Auditor → consultas JPA de solo lectura
```

- `dto`, `kafka`: contrato real v1, validación y listener; no productor ni tópicos nuevos.
- `service`, `repository`, `entity`: INSERT atómico idempotente con JDBC y consultas JPA; historial append-only, sin proyección de último estado. JPA y JDBC comparten la transacción administrada por Spring.
- `config`: error handler con reintentos/cuarentena y comando explícito de recuperación.
- `security`: Resource Server JWT, issuer v2.0 del tenant, firma, tiempo, audience, scope `access_as_user` y roles Admin/Auditor.
- `controller`, `exception`: array compatible con React y errores JSON sin detalles internos.

El POM solo agrega `testcontainers-postgresql` en scope test y el perfil Failsafe `postgres-it`. Sus versiones siguen el BOM de Boot; `flyway-database-postgresql` ya estaba en Initializr. No se incorporó una base H2 ni dependencias runtime adicionales.

Diagnóstico detallado, DTOs, límites de contrato y consistencia: [docs/CONTRATOS.md](docs/CONTRATOS.md). Resultados de ejecución: [docs/VALIDACION.md](docs/VALIDACION.md).

Validación final: `verify -Ppostgres-it` terminó en **BUILD SUCCESS**, con **46 pruebas unitarias/REST y 14 de integración PostgreSQL**, todas aprobadas.

## API

```http
GET /api/audit
GET /api/audit?date=2026-11-10&eventType=REQUEST_STATUS_CHANGED
GET /api/audit?requestId=123&page=0&size=50
GET /api/audit?user=ana
GET /actuator/health
```

La respuesta de consultas es `AuditEvent[]` con `id`, `user`, `eventType`, `entityType`, `entityId`, `occurredAt`, `detail`. Sin registros: `[]`. JSON documentado y fechas UTC; orden descendente por fecha/UUID. `date` interpreta el día en Santiago, configurable. `page` base cero, `size` 100 por defecto y máximo 200; headers `X-Total-Count` y `X-Has-Next`. El React actual solo muestra la primera página porque no tiene controles de paginación.

400 para filtros inválidos; 401 sin JWT válido; 403 sin scope o rol; 503 si la consulta DB no está disponible; 500 controlado para errores inesperados. Métodos de escritura denegados. Health es público y mínimo, sin detalles de DB. No se exponen payloads de cuarentena por REST.

## Configuración

`.env.example` es una plantilla sin credenciales. **Spring Boot no carga `.env` automáticamente**; exportar variables en la terminal o usar inyección segura en el despliegue. Nunca copiar credenciales a properties, argumentos de comandos o Git.

| Variable | Valor predeterminado / uso |
| --- | --- |
| `SERVER_PORT` | `8084` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Obligatorios, conexión PostgreSQL existente; no crea otra base |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `2` |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | `1` |
| `AUDIT_MIGRATIONS_ENABLED` | `false`; activación explícita después de revisión/autorización |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092,localhost:29092,localhost:39092` |
| `KAFKA_AUDIT_GROUP_ID` | `bd-audit-v1`; nunca compartir con Report |
| `KAFKA_LISTENER_ENABLED` | `true`; poner false para preparar esquema/verificar HTTP |
| `KAFKA_CONSUMER_RETRIES` | `3`, admite 0..10 |
| `KAFKA_RETRY_DELAY_MS` | `1000`, admite 100..10000 |
| `AUDIT_QUERY_ZONE` | `America/Santiago` |
| `AZURE_TENANT_ID` | UUID obligatorio del tenant existente |
| `AZURE_API_AUDIENCE` | Audience exacta del access token de la API/BFF, obligatoria |

Conexiones Hikari: espera máxima 10 s, validación 3 s; driver PostgreSQL connect timeout 10 s y socket timeout 30 s. El total de conexiones de todos los microservicios debe respetar el límite del Session Pooler de Supabase. Usar su endpoint/usuario correctos, sin adivinar hosts ni cambiar a transaction pooler de forma implícita. La URL debe incluir TLS; preferir `sslmode=verify-full` con la cadena CA confiable configurada según Supabase/driver.

No iniciar con DEBUG/TRACE, SQL bind logging ni request-header logging en entornos con datos reales. El código registra IDs/coordenadas y códigos fijos, no payloads, tokens o contraseñas. La cuarentena sí contiene datos originales sensibles: requiere acceso DB restringido y una política de conservación.

## Compilar y probar

```powershell
Set-Location 'G:\Desarrollo EP3-EP4\ms-audit'
.\mvnw.cmd -B -ntp verify
# Opcional, requiere Docker Desktop; PostgreSQL temporal, puerto aleatorio, 256 MiB:
.\mvnw.cmd -B -ntp verify -Ppostgres-it
```

`verify` ejecuta pruebas unitarias/REST sin DB, Kafka ni Azure reales. `postgres-it` usa Testcontainers con PostgreSQL 17.6-alpine, sobreescribe siempre la conexión con la del contenedor y desactiva listeners; jamás usa las variables DB del entorno para conectarse. El contenedor y sus datos de prueba se eliminan al finalizar. No se publican mensajes en los tópicos compartidos.

## Preparar ejecución local y migraciones

No se ejecutaron migraciones en Supabase compartido. Revisar primero [V1__audit_history.sql](src/main/resources/db/migration/V1__audit_history.sql). Flyway usa exclusivamente `bd_audit` y su propia tabla de historial. No habilita baseline ni clean; Hibernate usa `validate`. Con migraciones desactivadas y esquema aún ausente, el arranque falla: es deliberado.

Configurar en PowerShell sin imprimir secretos:

```powershell
Set-Location 'G:\Desarrollo EP3-EP4\ms-audit'
$env:DB_URL = Read-Host 'URL JDBC del Session Pooler con TLS, sin credenciales en la URL'
$env:DB_USERNAME = Read-Host 'Usuario PostgreSQL existente'
$auditPassword = Read-Host 'Contraseña PostgreSQL' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $auditPassword).Password
$env:AZURE_TENANT_ID = Read-Host 'UUID del tenant Entra existente'
$env:AZURE_API_AUDIENCE = Read-Host 'Audience de la API existente'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:19092,localhost:29092,localhost:39092'
$env:KAFKA_AUDIT_GROUP_ID = 'bd-audit-v1'
$env:SERVER_PORT = '8084'
$env:KAFKA_LISTENER_ENABLED = 'false'
$env:AUDIT_MIGRATIONS_ENABLED = 'false'
```

**Solo después de revisar y autorizar aplicar V1 a la base elegida**, ejecutar este paso manual:

```powershell
$env:AUDIT_MIGRATIONS_ENABLED = 'true'
java -jar .\target\ms-audit-0.0.1-SNAPSHOT.jar
```

Ese arranque aplica Flyway y valida JPA, con el listener deshabilitado. Verificar en otra terminal `Invoke-RestMethod http://localhost:8084/actuator/health` y consultar `bd_audit.flyway_schema_history` en Supabase. Detener solo esta aplicación con Ctrl+C. Luego iniciar normalmente:

```powershell
$env:AUDIT_MIGRATIONS_ENABLED = 'false'
$env:KAFKA_LISTENER_ENABLED = 'true'
java -jar .\target\ms-audit-0.0.1-SNAPSHOT.jar
```

Alternativa de desarrollo después de configurar las variables: `.\mvnw.cmd -B -ntp spring-boot:run`.

El primer arranque del grupo consume desde los offsets más antiguos **aún retenidos** (`earliest`), no desde el inicio absoluto del sistema. No resetear el grupo de Report ni otros grupos.

## Validar con datos reales

1. Comprobar health y el arranque sin errores de esquema/JWT. Si falta tenant o audience, no iniciar con valores ficticios para la prueba real.
2. En Kafbat UI `http://localhost:8090`, comprobar el grupo `bd-audit-v1`, asignaciones de `bd.requests.events.v1`, offsets y lag. Comparar independientemente con `bd-report-v1`. No cambiar tópicos/offsets.
3. Consultar una solicitud real conocida en Supabase mediante las consultas de [docs/VALIDACION.md](docs/VALIDACION.md). Verificar UUID, versiones distintas, estados y fechas contra los eventos reales existentes.
4. Consultar directamente Audit con un access token real de la API obtenido mediante el flujo autorizado existente. No pegarlo en logs ni guardarlo en archivos:

```powershell
$auditTokenInput = Read-Host 'Access token autorizado de Admin o Auditor' -AsSecureString
$auditToken = [System.Net.NetworkCredential]::new('', $auditTokenInput).Password
try {
    $auditHeaders = @{ Authorization = "Bearer $auditToken" }
    Invoke-RestMethod -Uri 'http://localhost:8084/api/audit?size=20' -Headers $auditHeaders
    # Actualmente devuelve 401: el BFF descarta Authorization al reenviar.
    Invoke-RestMethod -Uri 'http://localhost:8080/api/audit?size=20' -Headers $auditHeaders
} finally {
    Remove-Variable auditToken,auditHeaders,auditTokenInput -ErrorAction SilentlyContinue
}
```

5. En React `http://localhost:5173/audit`, iniciar sesión con Admin/Auditor. **Actualmente se espera el error 401 downstream**: no declarar integración completa hasta resolver la propagación en una tarea BFF autorizada. Después de esa tarea, comprobar array, filtros por fecha/tipo y fechas. Usuario seguirá ausente hasta evolucionar el evento productor.
6. Para validar cambios futuros de solicitudes usar el flujo de negocio autorizado, no insertar mensajes falsos ni manipular el outbox. Report y RabbitMQ continúan independientes.

Al terminar la sesión local, eliminar solo las variables secretas de esa terminal: `Remove-Item Env:DB_PASSWORD`; cerrar PowerShell también elimina su entorno. No compartir capturas con tokens o datos de auditoría sensibles.

## Recuperación controlada

Consultar `bd_audit.failed_event` con permisos DB restringidos, identificar la causa y un ID. Conservar el payload original. No hay DLT ni endpoint HTTP de modificación. Después de corregir la causa o desplegar soporte de contrato compatible:

```powershell
# Usa las variables DB de la sesión; no inicia web ni Kafka ni aplica migraciones.
java -jar .\target\ms-audit-0.0.1-SNAPSHOT.jar --spring.profiles.active=recover --audit.recovery-id=123
```

Reprocesa solo el ID indicado, marca éxito atómicamente y termina. Si ya está recuperado/no existe, no inserta filas. Si continúa siendo inválido, conserva la cuarentena y falla; no inventa eventos ni realiza sincronizaciones masivas. Verificar `recovered_at` y la fila histórica. Los permisos DB de esta operación deben limitarse al esquema Audit.

## Docker y AWS

El Dockerfile usa JRE Java 21 explícito y usuario no root. Construir después de `verify`: `docker build -t barriodigital/ms-audit:local .`. La imagen no incorpora `.env` ni credenciales; el contexto solo incluye JAR/Dockerfile. No se ha desplegado ni construido la imagen en esta tarea.

Dentro de un contenedor, `localhost` apunta al propio contenedor. Usar la red Docker ya existente y los listeners anunciados internos de los brokers, verificados en `infra-barriodigital`, no los puertos Windows como si fueran internos. La conexión a Supabase sigue usando TLS. No se agrega Compose ni se modifica infraestructura.

AWS: puerto 8084 privado/restringido, TLS en ingreso, JWT validado también por Audit, credenciales desde un gestor de secretos, límites de conexiones agregadas y permisos DB mínimos. Separar usuario de migraciones del usuario runtime; este último necesita SELECT/INSERT en historial, operaciones de recuperación necesarias en cuarentena y secuencia, nunca DDL/TRUNCATE ni permisos sobre otros esquemas. Aplicar cambios Flyway mediante una ejecución autorizada antes del despliegue runtime. No exponer el esquema de auditoría por PostgREST público; revisar grants/RLS según la política Supabase. No se modificó AWS.
