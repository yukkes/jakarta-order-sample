# AGENTS.md

Jakarta EE 11 sample. One core (`order-core`) runs on Quarkus, Liberty, WildFly, GlassFish.
Keep it portable.

## Rules
- `order-core` = Jakarta EE API + `order-api` + `order-domain` only.
  No Quarkus/Spring/MicroProfile/Hibernate-specific code or deps.
- Runtime code only in `order-quarkus` / `order-liberty` / `order-wildfly` / `order-glassfish`.
- `web -> service -> repository -> domain`; `EntityManager`/JPQL only in `repository`.
- DTOs: edit `order-api/src/main/openapi/order-api.yaml`; never edit `target/generated-sources`.
- List APIs return `PageResponse<T>`: `.content`, `page` 0-based, `size` 1..100,
  `sort=field,asc|desc` repeatable and whitelisted. Never arrays.
- Errors: default `ErrorResponse`; `Accept: application/problem+json` -> RFC 9457.
- No `jta-data-source`; no Jakarta Data repos in core.
- Auth lives in `core/web/auth`: JAX-RS filter + stateless HS256 access token (JWT) + opaque refresh token
  (hashed in DB, used only by `/api/auth/refresh`). No Spring/Quarkus Security in core.
  Use `@RequireRole` (not `@RolesAllowed`) on JAX-RS resources; GlassFish container auth intercepts otherwise.
  Deny by default: every resource method needs `@RequireRole`, `@PublicEndpoint` or `@OptionalAuth`
  (none -> 403). Public: `login`/`session` (`@PublicEndpoint`), `status` (`@OptionalAuth`).
- `order-core/.../resources/index.html` must work with `PageResponse`, sort, batch.
- UI must stay CSP-safe (`script-src 'self'`, `style-src 'self'`): JS/CSS in external files only,
  no inline `<script>`/`<style>`, no `onclick`, no `style="..."`. Use `data-action` + event delegation.
  Security headers live in each runtime (Quarkus `quarkus.http.header.*`, others `SecurityHeadersFilter`).
- Dynamic markup is rendered with the vendored lit-html (`resources/vendor/lit-html`, 3.3.3, BSD-3-Clause);
  do not build HTML strings or set `innerHTML` for data. Static buttons keep `data-action`.
- Floating labels and focus styling are CSS-only; do not add JS class toggles for them.

## Commands
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -pl order-quarkus -am test   # required
mvn -DskipTests package          # all runtimes
```
DB: `localhost:5432/orders`, `orders/orders`.

## Gotchas
- UI: use `response.content`, not `.length` / `.map`.
- Cookie auth POSTs need `X-XSRF-TOKEN`; Bearer skips CSRF.
- `TransactionPhase` is `jakarta.enterprise.event`, not `jakarta.transaction`.
- Error-capable resources need `@Produces(..., "application/problem+json")` or 406.
- New sortable field -> update that endpoint's `SortSpec`.
- Access-token TTL (default 10 min) bounds revocation: disabling a user revokes refresh immediately but the
  access token keeps working until it expires. Refresh tokens rotate on every use.
- Quarkus only validates nested DTOs (`lines[].quantity`) if `order-api` carries a Jandex index;
  keep `jandex-maven-plugin` in `order-api/pom.xml`.
- WildFly/GlassFish CSV import needs writable `data/`.
- Batch `inputFile` = bare `.csv` name inside `data/import/` (`ImportFileResolver`); never accept paths.
- Tests as non-root; do not commit `target/` or driver JARs.

## Docs
`README.md` · `docs/runtime-setup.md` · `docs/jakarta-data-assessment.md` ·
`docs/spring-parity-assessment.md`
