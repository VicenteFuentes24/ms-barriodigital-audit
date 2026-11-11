# Evidencia y validación EV5/EV6

## Entorno y alcance

Java 21.0.5, Spring Boot 4.1.2 y Spring Kafka 4.1.2 resueltos por el BOM existente. Maven Wrapper y la clase principal generada se conservaron. El directorio `ms-audit` no tenía repositorio Git inicializado; no se inicializó uno ni se hicieron commits/push.

Solo se escribieron archivos dentro de `ms-audit`. Los otros proyectos se inspeccionaron en modo de lectura. No se leyeron archivos `.env` reales, no se configuraron credenciales reales y no se ejecutaron migraciones en Supabase. No se cambió ningún tópico, grupo u offset compartido, ni RabbitMQ, BFF, React, Report, Requests, Kafka Admin o AWS.

## Validación automatizada

Comandos ejecutados con Maven Wrapper:

```powershell
.\mvnw.cmd -B -ntp dependency:tree '-Dincludes=org.springframework.kafka:*,org.testcontainers:*,org.springframework.boot:*,tools.jackson.core:*'
.\mvnw.cmd -B -ntp -DskipTests compile
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp verify -Ppostgres-it
```

La primera suite de `verify` tuvo 35 pruebas aprobadas. La suite final ampliada se ejecuta completa dentro del perfil `postgres-it` (Surefire + Failsafe). Las pruebas de integración se ejecutan sobre PostgreSQL 17.6-alpine temporal, Testcontainers 2.0.5, puerto aleatorio y límite de memoria de 256 MiB; no usan Supabase. El listener está deshabilitado durante esos tests.

Resultado final del **11 de noviembre de 2026**: **BUILD SUCCESS, 60 pruebas aprobadas, cero fallos, cero errores, cero omitidas**. Duración Maven: 31,392 s.

| Suite | Pruebas aprobadas |
| --- | ---: |
| AuditControllerTest | 17 |
| EventCodecTest | 15 |
| JwtValidationTest | 6 |
| KafkaErrorHandlerTest | 4 |
| ListenerTest | 4 |
| PostgresIT | 14 |
| **Total** | **60** |

El JAR ejecutable se generó en `target/ms-audit-0.0.1-SNAPSHOT.jar` (83.783.530 bytes). La migración y arranque completo se verificaron únicamente en el contenedor aislado; este resultado no equivale a validación del flujo Kafka/Supabase/BFF/React real.

La revisión inicial de los tests del error handler encontró tres fallos en las aserciones: la excepción de reintento de Spring Kafka 4.1.2 es interna y el mock no inicializaba el timeout de commit del contenedor. Se corrigió el test para modelar las propiedades reales y comprobar la excepción de reintento sin depender de una clase no pública. No se debilitó la garantía de commit después de persistir.

Ver resultados finales por clase en `target/surefire-reports/*.txt` y `target/failsafe-reports/*.txt`; salida resumida de la última ejecución en `target/verify-postgres.log`. Estos archivos de construcción están ignorados por Git.

Cobertura:

- JSON real de creación/cambio, DTO incompleto, contrato futuro, campos desconocidos, incoherencias, key incorrecta, tombstones y UTF-8 inválido.
- Listener confirma después del servicio; duplicados confirman; errores de validación o DB no confirman en el listener.
- Error handler: reintentos acotados, recuperación duradera antes de commit y ausencia de commit si falla cuarentena.
- JWT con RSA real de prueba: firma, issuer, audience y expiración. Pruebas HTTP aisladas: 401, headers falsificados, 403 por rol/scope, Admin/Auditor autorizados, array vacío y contrato React, filtros, errores controlados, 404 y denegación de escrituras.
- PostgreSQL real aislado: migración, Hibernate validate, versiones históricas fuera de orden, duplicados y conflictos, concurrencia, rollback, trigger append-only, filtros por fecha/tipo/solicitud, usuario ausente, caracteres SQL tratados como datos, paginación y medianoche de Santiago.
- Cuarentena durable idempotente, replay transaccional, replay inválido conserva bytes originales, ack observa historial comprometido, health mínimo y 401 HTTP real sin token.

Advertencias no bloqueantes observadas: consultas de metadatos de repositorios auxiliares Flyway respondieron 401, aunque los artefactos se resolvieron y Maven terminó; Mockito advirtió sobre carga dinámica del agente en futuras versiones JDK. No son resultados de acceso a Supabase ni pruebas de identidad Entra.

## Verificaciones que NO se ejecutaron

- Consumo desde Kafka compartido, prueba de caída real de brokers o un clúster Kafka de pruebas. El comportamiento de error handler/listener fue probado con mocks y persistencia aislada, no se presenta como un circuito Kafka real.
- Migraciones o consultas en Supabase compartido.
- Autenticación contra Microsoft Entra ID real.
- Flujo completo BFF → Audit → React, bloqueado además porque el proxy descarta Authorization.
- Construcción/ejecución de Dockerfile o despliegue AWS.

## Consultas de verificación real, después de autorizar migración y ejecución

Ejecutar con un usuario autorizado de Supabase. Son lecturas; no publican mensajes ni cambian offsets.

```sql
SELECT installed_rank, version, description, success
FROM bd_audit.flyway_schema_history ORDER BY installed_rank;

SELECT event_id, request_id, request_version, event_type, actor,
       previous_status, status, occurred_at, processed_at,
       source_partition, source_offset
FROM bd_audit.audit_event
ORDER BY occurred_at DESC, event_id DESC LIMIT 100;

-- Sustituir 123 por una solicitud REAL conocida, sin inventar eventos.
SELECT event_id, request_version, event_type, previous_status, status, occurred_at
FROM bd_audit.audit_event WHERE request_id = 123
ORDER BY request_version;

SELECT event_id, count(*) FROM bd_audit.audit_event
GROUP BY event_id HAVING count(*) > 1;

SELECT id, source_topic, source_partition, source_offset, failure_code,
       failed_at, recovery_attempts, last_attempt_at, recovered_at
FROM bd_audit.failed_event ORDER BY id DESC LIMIT 100;

-- Alertas de historial incompleto; no implica que Audit deba inventar el faltante.
SELECT request_id, min(request_version) AS first_version,
       max(request_version) AS last_version, count(*) AS versions_seen
FROM bd_audit.audit_event GROUP BY request_id
HAVING min(request_version) <> 1 OR count(*) <> max(request_version);
```

No mostrar payloads de cuarentena en una presentación sin revisión de datos sensibles. Su conservación es para recuperación restringida. La ausencia de huecos no certifica que se hayan retenido todos los eventos originales si el productor dejó solicitudes antiguas sin publicar.

En Kafbat: comprobar grupo Audit distinto a Report, asignación de particiones y offsets avanzando. Comparar los UUID reales de Kafka con la tabla de historial, sin usar Reset offsets ni Produce message para generar evidencia ficticia.

En HTTP: comprobar `GET :8084/actuator/health`, 401 directo sin token y GET directo con JWT válido de Admin/Auditor. El paso BFF/React solo podrá validarse exitosamente después de una modificación de autenticación expresamente autorizada en BFF. `user: null` es fiel al contrato actual; requiere una evolución del productor para poder auditar quién realizó la acción.

## Inventario de cambios

- Modificados: `pom.xml` (Testcontainers de test y perfil Failsafe), `.gitignore`, `src/main/resources/application.properties`.
- Nuevo código: paquetes `config`, `controller`, `dto`, `entity`, `exception`, `kafka`, `repository`, `security`, `service` bajo el paquete existente; 18 clases nuevas.
- Nueva migración: `src/main/resources/db/migration/V1__audit_history.sql`.
- Nuevo perfil: `src/main/resources/application-recover.properties`.
- Tests: se reemplazó el `contextLoads` generado, que dependería de credenciales reales, por suites unitarias/REST y `PostgresIT`, que sí arranca el contexto completo con DB aislada.
- Documentos/configuración de entrega: `README.md`, `.env.example`, `Dockerfile`, `.dockerignore`, `docs/CONTRATOS.md`, `docs/VALIDACION.md` y dos ejemplos JSON en `docs/events/`.
- Sin cambios: `MsAuditApplication.java`, Maven Wrapper, versión Java, versión Spring Boot y proyectos de referencia.
