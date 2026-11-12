# Wiki

A small, server-rendered Markdown wiki. Visit any page path; missing pages, including `/`, display a blank page. Click the pencil to edit, then the floppy disk to save. No accounts, JavaScript, frontend build, external database, or H2 console.

## Run

Requires **JDK 25** (required by the latest Micronaut 5 release). The Gradle wrapper is included and checksum-verified.

```sh
./gradlew test
./gradlew run
```

Open <http://localhost:8080>. The default database is `./data/wiki.mv.db`, relative to the **process working directory**. Restarting the application preserves all pages and password hashes. Use a consistent working directory or an absolute database URL in production.

Create an executable distribution (no Gradle process needed at runtime):

```sh
./gradlew installDist
umask 077
build/install/wiki/bin/wiki
```

Or use `./gradlew distZip` to package it for another machine with JDK 25.

### Docker deployment (default configuration)

Build the deployable image from source (Docker/BuildKit required):

```sh
docker build --pull -t wiki:latest .
```

The multi-stage build uses Java 25, runs the JVM test suite, and copies only runtime JARs/resources into a JRE image. It does **not** activate a Micronaut profile: the application uses `application.yml` defaults, with the same environment-variable overrides described below. Node.js, Playwright, Gradle and application source are absent from the runtime image. `.dockerignore` excludes local databases, build artifacts, secrets and other unrelated files from the build context.

Run it with a persistent named volume:

```sh
docker volume create wiki-data
docker run -d --name wiki \
  --restart unless-stopped \
  --stop-timeout 30 \
  --security-opt no-new-privileges:true \
  --cap-drop ALL \
  -p 127.0.0.1:8080:8080 \
  --mount source=wiki-data,target=/app/data \
  wiki:latest
```

Open <http://localhost:8080>. The default file-backed H2 database is `/app/data/wiki.mv.db`; the volume preserves pages when the container is replaced. Run only one application container per database volume. The image runs as **UID/GID 10001**, not root. Named volumes inherit the prepared data-directory ownership; if using a host bind mount, make that directory writable by UID/GID 10001 first. The application JARs remain root-owned and are not writable by the application user.

The example binds only to the host's loopback interface. Put a trusted HTTPS reverse proxy in front for deployment. Pass runtime settings with `-e WIKI_NAME=...`, `-e WIKI_MASTER_PASSWORD`, and `-e WIKI_SECURE_COOKIES=true` as appropriate; do not bake passwords into the image. Keep secure cookies disabled for plain local HTTP. The container listens on 8080 by default; changing `WIKI_PORT` also requires changing the container side of the port mapping.

Inspect logs with `docker logs -f wiki`; stop cleanly with `docker stop -t 30 wiki`. Java runs directly as PID 1 so termination signals reach Micronaut/H2. For memory tuning, pass JVM options through `JAVA_TOOL_OPTIONS` and size any container memory limit for the configured page limit; 100 MiB Markdown processing is not a small-memory workload (see deployment notes below).

### Password modes

Without a master password (unset, empty, or whitespace-only configuration):

- A blank password creates or updates a publicly editable page.
- Entering a password on a new or currently public page permanently protects its edits.
- Protected pages require that **same password** on every subsequent save. Blank or different passwords cannot remove or replace it.
- Passwords are case-sensitive; spaces are significant. The limit is 1,024 Java characters.

With a master password:

```sh
WIKI_MASTER_PASSWORD='choose-a-long-random-secret' ./gradlew run
```

When the configured master password is non-blank, every save requires that exact master password, including new, blank, public, and previously protected pages. The editor's one password field authenticates the save; it does not set a per-page password in this mode. Existing page hashes are retained, so removing master mode restores the previous per-page protection. Keep production secrets in your service manager's protected environment, not source control or shell history.

**Passwords protect writes only. All page contents and editors are publicly readable.** There is no password recovery UI, revision history, delete endpoint, or page-password rotation feature. A configured master password can authorize edits when a page password is lost.

## Configuration

See [`src/main/resources/application.yml`](src/main/resources/application.yml). Environment overrides:

| Variable | Default | Meaning |
| --- | --- | --- |
| `WIKI_PORT` | `8080` | HTTP port |
| `WIKI_NAME` | `Wiki` | Escaped application name in the navbar |
| `WIKI_DB_URL` | `jdbc:h2:file:./data/wiki;DB_CLOSE_ON_EXIT=FALSE;CACHE_SIZE=8192` | Embedded H2 JDBC URL |
| `WIKI_DB_PASSWORD` | empty | H2 connection password; not a page password or file-encryption key |
| `WIKI_MASTER_PASSWORD` | empty | Unset/blank enables per-page mode; non-blank overrides page passwords for all saves |
| `WIKI_MAX_PAGE_SIZE` | `104857600` | Maximum **decoded Markdown UTF-8 bytes** (100 MiB) |
| `WIKI_MAX_REQUEST_SIZE` | `314638336` | Transport/form buffering limit: 3 × 100 MiB plus 64 KiB |
| `WIKI_SECURE_COOKIES` | `false` | Set `true` behind an HTTPS-terminating proxy; direct HTTPS also sets Secure automatically |

The page limit is checked independently of `Content-Length`, after form decoding. Over-limit pages return **413**, never save partially, and are not echoed in an error response. A URL-encoded UTF-8 byte can occupy three wire bytes (`%XX`); if increasing the page limit, increase the transport limit to at least `3 × page limit + 65536` and adjust the reverse proxy's limit too. These are byte counts, not character counts.

### URL behavior

- `GET /anything/nested` displays the page, or a blank page without inserting a database row.
- `GET /anything/nested/editor` displays the full-page editor.
- `POST /anything/nested` saves a URL-encoded form and returns **303** to the page (Post/Redirect/Get).
- `/editor` edits `/`; posting to `/` saves the root page.
- The final `/editor` suffix and `/_assets` namespace are reserved. Paths ending in `/editor` cannot themselves be content pages.
- Slashes inside paths, repeated slashes, trailing slashes, Unicode, spaces, and escaped reserved characters are supported. `/a`, `/a/`, `/a/b`, and `/a%2Fb` are distinct pages. Literal reserved delimiters must be percent-encoded where appropriate: `%23` for `#`, `%3F` for `?`.
- Percent escape hex is canonicalized to uppercase; escaped unreserved characters are decoded. For example `/a%7Eb` and `/a~b` refer to the same page. Encoded slashes are **not** decoded into path separators.
- Queries do not identify a page; fragments never reach the server. Dot segments, control characters, and backslashes are rejected. Browsers can normalize dot segments before sending a request.
- Paths support up to 16,000 encoded characters, subject to the server/proxy request-line limit. Leading `//` is safely represented in generated local links/forms/redirects, never as an external protocol-relative URL.
- Mount the application at the origin root. Configure proxies to preserve encoded and repeated slashes rather than silently rewriting them.

## Design and implementation plan

The implementation follows these steps:

1. **Minimal JVM application:** Micronaut Netty, constructor injection, blocking work on Micronaut's blocking/virtual-thread executor, one small Hikari pool, file-backed H2, and immutable Flyway migrations. No ORM or runtime classpath scanning.
2. **Explicit storage:** Markdown and a salted password hash are stored together with a page version. The unique indexed path is the lookup key; numeric database IDs are never exposed. Reads do not create rows, and editors do not parse/render Markdown unnecessarily.
3. **Server-rendered UI:** one Thymeleaf template, escaped editor content, accessible icon-only controls, and a small local stylesheet. The password field wraps below the navbar on narrow screens. CommonMark renders Markdown, tables, strikethrough and autolinks; jsoup sanitizes the resulting HTML.
4. **Safe writes:** check UTF-8 size, authenticate against the current row, then atomically update with `WHERE path = ? AND version = ?`. Concurrent creation is protected by the unique constraint. A stale editor returns **409**, never overwriting a newer revision or bypassing newly installed password protection.
5. **Verification and operations:** unit tests for parsing, paths, passwords, CSRF and admission control; HTTP integration tests against real Netty/H2/Flyway; and close/reopen tests against a temporary file database.

Key code:

- [`WikiController`](src/main/java/com/teacai/wiki/controller/WikiController.java): HTTP flows and view models.
- [`PageService`](src/main/java/com/teacai/wiki/service/PageService.java): write policy, authentication and limits.
- [`PageRepository`](src/main/java/com/teacai/wiki/repository/PageRepository.java): parameterized JDBC and atomic version checks.
- [`page.html`](src/main/resources/views/page.html): shared page/editor UI.

### Security boundaries

- PBKDF2-HMAC-SHA-256, 600,000 iterations, random 128-bit salts, constant-time derived-key comparison. The configured master password is hashed once in memory; page plaintext passwords are never persisted or logged.
- Signed, expiring double-submit CSRF tokens; HttpOnly, SameSite=Strict cookies; cross-origin fetch rejection (including sibling subdomains). Secure cookies use the `__Host-` prefix to prevent subdomain cookie injection. No persistent sessions. Tokens expire after eight hours or process restart; reload the editor to obtain a fresh token.
- Only sanitized output is inserted as HTML. Embedded scripts, event handlers, forms, frames, styles and unsafe URLs are stripped. CSP, `nosniff`, frame denial, same-origin referrer policy and no-store headers provide additional protection. Rendering does not fetch linked resources; visitors' browsers may load remote HTTP(S) images.
- Two concurrent dynamic requests are admitted **before body binding**; excess requests return **503** with `Retry-After`. Permits are released when requests terminate, including failed binding and disconnection. This bounds application work, not the number of network connections or every transport allocation.
- Password work has a process-wide token bucket (burst 10, refill one/second), returning **429** when exhausted. It is intentionally bounded and does not allocate attacker-controlled IP/path maps.
- Incorrect-password, conflict and throttling responses preserve the escaped draft, but never echo the password. Copy the draft before reloading after a conflict. Invalid CSRF and size/transport errors do not return a full draft.
- Per-page mode intentionally permits anonymous creation, editing of public pages, and claiming public pages with a password. Use master mode to prevent this. Read privacy, application-level storage quotas and distributed abuse prevention are outside this application.

### Deployment, memory and backup

Use **HTTPS** in production. At a trusted reverse proxy, enable `WIKI_SECURE_COOKIES=true`, enforce a body limit consistent with the configuration, constrain connections/request rates, and set header/body/response timeouts. Keep the application port private. Do not enable unauthenticated remote JMX. For hostile public deployments, use master mode; even with internal concurrency limits, slow clients can monopolize the two admission slots.

The installed runtime is approximately **23 MiB** of application/dependency JARs. Idle usage is kept small by avoiding a frontend runtime, sessions, rendered-page caches, an ORM and an external database; Hikari has at most three connections and H2's page cache is 8 MiB.

**A 100 MiB page is not a low-memory workload.** Request/form decoding, Markdown ASTs, sanitization and response construction require multiple copies and node allocations, on and off heap. Memory usage depends heavily on Markdown structure and concurrency. Reduce `WIKI_MAX_PAGE_SIZE` and `WIKI_MAX_REQUEST_SIZE` for a small-memory host. Do not assume the page byte limit is a heap limit.

A separate packaged-app smoke test verified: 100 MiB + 1 byte returns 413, exactly 100 MiB saves with 303, and that page renders with 200. That test used a simple single-line page with `JAVA_OPTS='-Xms32m -Xmx1536m -XX:MaxDirectMemorySize=768m'`; process RSS reached about 2.5 GiB. Those settings are an observed test configuration, **not a guarantee** for arbitrary 100 MiB Markdown or two simultaneous large requests. Too-small direct-memory limits can fail during form decoding before the application can check the page size.

Run one JVM per database file on a local persistent disk (not a shared network filesystem). Protect the data directory with OS permissions; page passwords do **not** encrypt content at rest. For a straightforward consistent backup, stop the app cleanly and copy `wiki.mv.db`; do not copy a live file without using H2's supported backup mechanism. Back up before upgrading. Flyway runs on startup and validates migration checksums; V1 is preserved and V2 upgrades the scaffold's schema. Legacy scaffold SHA-256 password hashes are deliberately not accepted as PBKDF2 hashes; use master mode to authorize writes to such pages if needed.

### Observability

Logs are structured JSON on stdout and include startup, migrations, successful saves and invalid-password failures. Save logs include size/version/protection metadata, never content or passwords. Health is naturally checked with `GET /` (which can intentionally contain user content).

Micrometer publishes metrics to the **local JVM's JMX MBean server** (domain `metrics`), avoiding a public HTTP management namespace. Inspect via local JConsole/JMC. Important metrics include:

- `wiki.database.read` / `wiki.database.write` timers
- `wiki.markdown.render` timer
- `wiki.saves` counters, tagged by bounded result values
- `wiki.work.active` and `wiki.requests.rejected`
- Micronaut HTTP and JVM metrics

### Verification

```sh
./gradlew clean test installDist
# HTML test report: build/reports/tests/test/index.html
```

Tests cover root/blank pages; nested, encoded, repeated- and trailing-slash paths; escaped textarea round-trips including leading newlines; create/update/protection; master precedence; invalid CSRF; malicious HTML; UTF-8 byte boundaries; forms exceeding framework default buffers; stale edits; upload admission/disconnection cleanup; and file-database restart persistence. Tests use isolated in-memory/temporary file databases, never `./data/wiki`.

#### Playwright end-to-end test

[`tests/e2e/edit-page.spec.cjs`](tests/e2e/edit-page.spec.cjs) exercises the complete user journey:

1. Navigate to a random nested path and verify the page is blank.
2. Click the pencil (`Edit page`) and verify the editor opens at `<path>/editor`.
3. Enter Markdown with a heading, bold/emphasized text, inline code and a list; leave the password empty.
4. Click the floppy disk (`Save page`), verify the real form POST returns 303, and verify navigation back to the original path.
5. Assert the rendered HTML elements, absence of the editor, and persistence after a full reload.

Requires Node.js 22+ and JDK 25. Node/Playwright are test tooling only, not application runtime dependencies:

```sh
npm ci
npx playwright install chromium
npm run test:e2e
```

Playwright automatically builds the application with Gradle, starts it on `127.0.0.1:18089` using a fresh temporary file-backed H2 database, then stops it and removes that database. It never reuses a running application or touches `./data`. Set `WIKI_E2E_PORT` if the test port is occupied. Failures retain screenshots and traces under `test-results/`; the HTML report is under `playwright-report/` (`npx playwright show-report`). For interactive debugging, run `npm run test:e2e:ui`.

If Google Chrome is already installed, skip the browser download and run:

```sh
WIKI_BROWSER_CHANNEL=chrome npm run test:e2e
```

The broader optional regression script (`./gradlew installDist && node scripts/browser-save-smoke.cjs`) also checks anonymous root/nested saves on both `localhost` and `127.0.0.1`, using its own temporary database. It honors the same `WIKI_BROWSER_CHANNEL=chrome` option.

The editor must use `Referrer-Policy: same-origin`, **not `no-referrer`**: Chrome submits `Origin: null` for a form under `no-referrer`, and Micronaut rejects that request with 403 before the application's authorization code runs. The HTTP suite asserts this policy and sends browser-style origin/fetch headers; the browser test verifies the actual click/save/redirect flow. After an application update, reload any already-open editor to obtain current headers and CSRF tokens.

### Dependency baseline

Stable versions verified against Maven Central and the Gradle plugin portal:

| Component | Version |
| --- | --- |
| Java | 25 |
| Gradle | 9.9.0 |
| Micronaut Gradle plugin | 5.0.2 |
| Micronaut platform / core | 5.2.6 / 5.2.23 |
| H2 | 2.5.252 |
| Flyway | 13.12.1 |
| CommonMark and extensions | 0.32.0 |
| jsoup | 1.24.1 |
| Logback | 1.6.6 |
| SnakeYAML | 2.7 |

Micronaut integrations and their compatible transitive dependencies use the current platform BOM. `gradle.lockfile` records resolved versions; no dynamic `latest` selectors are used. To upgrade, verify current stable releases, update version pins/platform, run `./gradlew clean test installDist --write-locks`, and review lockfile changes.
