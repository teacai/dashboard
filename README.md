# Wiki

A small, server-rendered Markdown wiki. Visit any page path; missing pages, including `/`, display a blank page. Click the pencil to edit, then the floppy disk to save. No accounts, frontend build, external database, or H2 console. Reading and ordinary Markdown editing work without JavaScript; a small local editor script enables file/folder pickers and file management.

Pages adapt to narrow and landscape screens. Rendered Markdown wraps long text, scales images, and scrolls wide code blocks/tables locally rather than widening the page. The editor stays within the viewport with its controls visible; only the textarea scrolls as the draft grows. Long application names are truncated in the navbar.

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

**Page/master passwords protect writes only. All pages, editors, file lists, and uploaded files are publicly readable. The administration page at `/_admin` and backup/restore API at `/_backup` require separate administrator credentials.** Uploads and file deletion use the same edit-password rules. There is no password recovery UI, revision history, page deletion, or page-password rotation feature. A configured master password can authorize edits when a page password is lost.

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
| `WIKI_DATA_DIRECTORY` | `./data` | Backup/restore data root; must contain the database and attachment root |
| `WIKI_ADMIN_USERNAME` | `admin` | Basic-auth administrator for `/_admin` and backup/restore (`wiki.admin-username`) |
| `WIKI_ADMIN_PASSWORD` | `admin112` | Basic-auth administrator password (`wiki.admin-password`); **change before deployment** |
| `WIKI_MAX_RESTORE_SIZE` | `21474836480` | Maximum ZIP bytes and total expanded restore bytes (20 GiB), independently of HTTP upload limits |
| `WIKI_FILES_DIRECTORY` | `./data/files` | Persistent attachment root, relative to the process working directory |
| `WIKI_MAX_UPLOAD_SIZE` | `2147483648` | Maximum bytes per file **and** sum of file bytes per upload; may be lowered, never raised above 2 GiB |
| `WIKI_MAX_REQUEST_SIZE` | `2253389824` | Transport limit: 2 GiB files + 100 MiB Markdown + 1 MiB multipart overhead |
| `WIKI_MAX_BUFFER_SIZE` | `314638336` | In-memory/form-attribute buffering cap; not the streamed file-data limit |
| `WIKI_SECURE_COOKIES` | `false` | Set `true` behind an HTTPS-terminating proxy; direct HTTPS also sets Secure automatically |

The page limit is checked independently of `Content-Length`, after form decoding, and includes appended attachment links. Over-limit ordinary saves return **413** without saving. Upload errors handled after form parsing preserve the editor draft; parser/transport failures may not. A URL-encoded UTF-8 byte can occupy three wire bytes (`%XX`); if increasing the page limit, increase the buffer cap to at least `3 × page limit + 65536`, and the transport limit to at least the larger of that value and `max upload size + page limit + multipart overhead`. Adjust reverse-proxy limits too. These are byte counts, not character counts.

### File uploads and management

In the editor, enter the edit password if required, click the **upload icon**, and choose **Upload files** or **Upload folder**. Browsers provide separate native pickers for files and directories. Selecting files immediately submits a multipart upload. A successful upload saves the current Markdown draft, appends a bullet link for every uploaded file, and refreshes the editor in place with the new page version and file list. The entered password stays filled in for subsequent uploads or saves. It remains only in the current password field: the server does not echo it, and it is never placed in URLs, cookies, local storage, or session storage. Reloading or navigating to a fresh editor clears it. Editing controls are temporarily locked while an upload is in progress so newer changes cannot be lost.

- Files keep their original names. `/notes/project` plus `report.pdf` becomes `data/files/notes/project/report.pdf` with default settings.
- Selecting a folder `photos` containing `trip/image.jpg` stores `data/files/notes/project/photos/trip/image.jpg`. The selected top-level folder is included; empty directories are not uploaded.
- Root-page uploads go directly under the configured attachment root. Canonical percent-encoded page segments stay encoded on disk (`/a%2Fb` is not `/a/b`); empty/repeated/trailing page-path segments use a reserved `%00` directory to remain distinct.
- Existing files at the same relative path **owned by the same page** are replaced. Their public download URLs remain unchanged. Cross-page physical path collisions, duplicate paths within a batch, and collisions with unmanaged files are rejected rather than overwritten.
- Click the **folder icon** to list this page's files, download them, or delete individual files. Deletion requires the edit password, CSRF token, and current page version. It preserves the open draft without publishing it and does not remove Markdown links; remove stale links manually. Deletion advances the page version to invalidate older editors/uploads.
- Each file and the combined file data in a request are limited to **2 GiB (2,147,483,648 bytes)**. There are at most 1,000 files per batch and 1 MiB of manifest metadata. The page-content limit applies separately. Files are spooled to disk, then copied in bounded chunks; no multi-gigabyte byte arrays are created.
- Relative filenames are limited to 4,096 characters and individual path segments to 255 UTF-8 bytes, also subject to the host filesystem's limits. Absolute paths, dot segments, empty segments, backslashes, colons, control characters, and symlink targets/ancestors are rejected. Validated original filenames are never silently renamed.
- Downloads use stable UUIDv7 URLs, `application/octet-stream`, `Content-Disposition: attachment` with the original filename, `nosniff`, and a sandbox CSP. Uploaded HTML/SVG/scripts are never served as active same-origin documents.

Page content/version and file ownership metadata commit in one database transaction. Files are staged before a short serialized installation step; replacements/deletions have temporary backups and are rolled back on handled filesystem/DB failures. Authorization and version checks precede installation, and a version change during staging rejects the upload. Bulk file copying does not hold the page-write monitor. This is **not a crash-atomic transaction across the database and filesystem**: after a process/machine failure during installation, inspect sibling `.wiki-upload-*` staging directories for recoverable backups before removing them. Keep the storage directory private to the application, and do not edit files behind its back.

Provide sufficient temporary-disk capacity: Micronaut's multipart spool uses the JVM temporary directory (or `micronaut.server.multipart.location`); staging/backups use a sibling temporary directory beside the attachment root. Account for two concurrent requests, staged copies, replacements, and backups. Invalid credentials are checked before permanent installation, but the HTTP body may already have been spooled. Apply gateway authentication/rate limits and disk quotas for untrusted deployments.

### URL behavior

- `GET /anything/nested` displays the page, or a blank page without inserting a database row.
- `GET /anything/nested/editor` displays the full-page editor.
- `POST /anything/nested` saves a URL-encoded form and returns **303** to the page (Post/Redirect/Get).
- `/editor` edits `/`; posting to `/` saves the root page.
- `POST /_files/upload` accepts indexed multipart files and a relative-path manifest; `POST /_files/delete` deletes an owned file; `GET /_files/{uuid}` downloads a file.
- `GET /_backup` downloads a ZIP of the data directory, including a consistent live database snapshot; `POST /_backup` restores a raw ZIP or a multipart `file` field. Both require administrator Basic authentication (see below).
- `GET /_admin` displays the authenticated administration page; `POST /_admin/restore` restores a multipart archive with a CSRF token and explicit confirmation.
- `/_backup`, the final `/editor` suffix and `/_assets` / `/_files` / `/_admin` namespaces are reserved. Paths ending in `/editor` cannot themselves be content pages.
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
- [`FileController`](src/main/java/com/teacai/wiki/controller/FileController.java), [`FileService`](src/main/java/com/teacai/wiki/service/FileService.java), [`AttachmentStorage`](src/main/java/com/teacai/wiki/service/AttachmentStorage.java): multipart handling, authorization/ownership orchestration, and bounded filesystem operations.
- [`V3__page_files.sql`](src/main/resources/db/migration/V3__page_files.sql): indexed file ownership and stable download identifiers.

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

Run one JVM per database file on a local persistent disk (not a shared network filesystem). Protect the data directory with OS permissions; page passwords do **not** encrypt content at rest. For a straightforward consistent backup, stop the app cleanly and copy **both** `wiki.mv.db` and the configured files directory together; metadata and attachments must be restored as a pair. Include any staging directories when investigating an interrupted installation. Do not copy a live database without using H2's supported backup mechanism. Back up before upgrading. Flyway runs on startup and validates migration checksums; V1 is preserved, V2 upgrades the scaffold's schema, and V3 adds file ownership metadata. Legacy scaffold SHA-256 password hashes are deliberately not accepted as PBKDF2 hashes; use master mode to authorize writes to such pages if needed.

#### Downloading and restoring a backup

**The `/_admin` page, its restore action, and both `GET /_backup` and `POST /_backup` require HTTP Basic authentication.** The default administrator is **`admin` / `admin112`**. This is a configuration-based administrator, not a wiki page account, and is independent of `WIKI_MASTER_PASSWORD`. Missing/incorrect credentials return **401** with a Basic-auth challenge. A shared request filter protects both `/_admin` and `/_backup` namespaces, including subpaths, query-string variants, and encoded aliases, for every HTTP method before request-body binding. Neither cookies nor a previous successful request grants access without valid Basic credentials. Unsupported paths in these namespaces cannot fall through to public wiki pages. Set a strong password before exposing the application, use **HTTPS**, and apply proxy access/rate limits. The application warns when the default credentials are in use.

Configure the credentials in `application.yml` (or equivalent application properties):

```yaml
wiki:
  admin-username: admin
  admin-password: replace-with-a-long-random-password
```

Prefer protected environment overrides `WIKI_ADMIN_USERNAME` / `WIKI_ADMIN_PASSWORD` in production; never commit real credentials. The commands below prompt for the password instead of placing it in shell history. Replace `admin` if you configured a different username.

##### Use the administration page

Open **`/_admin`** in your browser and authenticate with the configured administrator username/password (defaults: `admin` / `admin112`). The page works without JavaScript. Every request requires a valid Basic `Authorization` header; a previous visit or CSRF cookie does not authenticate subsequent requests. Missing/incorrect credentials receive **401** with `WWW-Authenticate: Basic` before the page or restore action is served.

Browsers may cache Basic credentials and send them automatically, so a repeat visit might not show another login prompt. To check unauthenticated access, use a fresh private browser session or `curl -i https://wiki.example.com/_admin` without `--user` (expect **401**). With `curl --user admin https://wiki.example.com/_admin`, enter the configured administrator password when prompted (expect **200**). Restart/redeploy the application after changing code or administrator configuration.

- **Download backup** downloads the dated ZIP through the existing `/_backup` endpoint.
- To restore, choose a previously downloaded **Backup ZIP archive**, check the confirmation that the current database and files will be replaced, and click **Restore backup**. Download a fresh backup first and allow the operation to finish without closing the page.
- The restore form posts to `/_admin/restore` and requires both administrator Basic authentication and a signed, expiring CSRF token tied to a SameSite cookie. Cross-site submissions, missing/invalid tokens, and missing confirmation are rejected. Forms expire after eight hours or application restart; reload the administration page to get a fresh token.
- Validation/restore errors are shown on the page; select the archive again before retrying. Success redirects back to `/_admin?restored=true`, so refreshing does not repeat the restore. Reload any open wiki editors after a restore.

Older backup databases are automatically migrated to the current schema in staging. A backup with a newer database schema is rejected with an explanatory error on the page; the current data is not changed.

The UI uses the same archive validation, maintenance, replacement, and rollback services as the API described below. It does not change administrator credentials or deployment configuration.

##### Download with curl or wget

Navigating to `/_backup` prompts for administrator credentials and downloads **`backup_yyyy_mm_dd.zip`**, using the server's local date (for example, `backup_2026_11_12.zip`). To download from a terminal:

```sh
BASE_URL='https://wiki.example.com'

# curl: use the filename supplied in Content-Disposition.
curl --fail --show-error --user admin --remote-name --remote-header-name \
  "$BASE_URL/_backup"

# GNU wget: use the same server-supplied filename.
wget --user=admin --ask-password --content-disposition "$BASE_URL/_backup"
```

Treat the ZIP as sensitive: it includes all content, uploaded files, password hashes, and unmanaged files in the data directory. Archive paths are relative to `WIKI_DATA_DIRECTORY`, including nested files, empty directories, and existing backups. The default root is `./data` (`/app/data` in Docker). For a server storing data at `/data`, configure:

```sh
WIKI_DATA_DIRECTORY=/data
WIKI_DB_URL='jdbc:h2:file:/data/wiki;DB_CLOSE_ON_EXIT=FALSE;CACHE_SIZE=8192'
WIKI_FILES_DIRECTORY=/data/files
```

`WIKI_DATA_DIRECTORY` selects the backup/restore root; it does not relocate existing data. The file-backed H2 database and attachments must both be inside it. Symbolic links and special files cause backup failure rather than exposing external data. The active H2 process-lock file and internal `.wiki-restore-*` / `.wiki-previous-*` recovery directories are omitted.

The application uses H2's supported `BACKUP TO` command instead of copying the live database. Page saves and attachment installations/deletions pause during archive generation; ordinary reads can continue. Only one backup generation or restore runs at a time; concurrent operations return **503**. Do not modify the data directory outside the application. Upload-staging directories may be included if present; an active upload can cause generation to fail, in which case retry when it finishes.

Generation/download use bounded-memory streams. Allow free space in the JVM temporary directory (outside the data root) for the H2 snapshot and full ZIP. Temporary backup files are removed after generation failure or download-stream closure. The generated ZIP is not stored in the data root.

##### Restore with curl or wget

**Restore replaces the current database and all ordinary files in the data root. Changes made since the backup will be lost. Download a fresh backup before proceeding.** Restore only trusted application-generated archives, using the same relative database/attachment paths, database credentials, and a compatible H2 file format. Older databases are supported when their applied migrations form a successful, unmodified prefix of the current application's Flyway history; pending migrations run automatically against the extracted staging copy. Archives with missing/corrupt databases or missing/incorrectly sized attachments are rejected before current data is changed.

Compatibility is determined from **database migration versions and checksums**, not the ZIP's filename/date or an application release label. A backup containing a migration newer than those shipped by the running application returns **400** with `Backup was created with a newer application database version. Upgrade the application before restoring it.` Unknown, missing, failed, or checksum-modified migrations are also rejected. There is no automatic downgrade, baseline, repair, or schema cleaning. Unversioned databases without Flyway history are not accepted. Releases sharing the same migration history remain schema-compatible.

Upload the ZIP as a raw request body (`application/zip` or `application/octet-stream`):

```sh
BASE_URL='https://wiki.example.com'
BACKUP='backup_2026_11_12.zip'

# curl: --data-binary is essential; do not use text/form encoding for ZIP bytes.
curl --fail --show-error --user admin \
  --header 'Content-Type: application/zip' \
  --data-binary "@$BACKUP" "$BASE_URL/_backup"

# GNU wget: send the ZIP directly, writing the server's result to a separate file.
wget --user=admin --ask-password --auth-no-challenge \
  --method=POST --header='Content-Type: application/zip' \
  --body-file="$BACKUP" --output-document=restore-result.txt "$BASE_URL/_backup"
```

Alternatively, curl can submit a multipart upload in the **`file`** field:

```sh
curl --fail --show-error --user admin \
  --form "file=@$BACKUP;type=application/zip" "$BASE_URL/_backup"
```

Successful restore returns **200** and `Backup restored successfully.` Invalid archives return **400** without changing current data. Size/transport limits can return **413**; authentication failures return **401**; maintenance contention returns **503**. An installation/reopen failure returns **500** after attempting rollback. The `POST /_backup` API remains CLI-only: browser-origin POSTs (`Origin` or non-`none` `Sec-Fetch-Site`) are rejected with **403** to prevent cached Basic credentials from enabling CSRF. Browser restores must use the CSRF-protected form at `/_admin` instead.

Restore works as follows:

1. Stream the upload into a private `.wiki-restore-*` directory inside the data root, then extract it there. Reject traversal/absolute paths, duplicate/conflicting paths, invalid ZIP checksums, reserved recovery paths, more than 100,000 entries, and archives exceeding `WIKI_MAX_RESTORE_SIZE` (20 GiB compressed or expanded by default).
2. Open the staged H2 database, reject future/incompatible migration histories, and use Flyway to validate applied scripts and run pending migrations from the application resources. Then read page/CLOB data and verify attachment ownership, paths, existence, and byte sizes against the upgraded schema. Only the private staging database is modified: a migration/validation failure discards staging and leaves the current database/files untouched. Backups predating attachment storage may omit the files directory; restore creates it when upgrading, but any tracked attachments must still exist and match their metadata.
3. Reject new normal HTTP requests with **503**, and wait up to 30 seconds for active requests (including file downloads/uploads) to finish. If they do not drain, abort without replacing data.
4. Suspend/evict the Hikari pool and close H2. Rename current files/directories into `.wiki-previous-*`, then move the validated staged data into place. The root directory itself is not renamed, so mounted `/data` volumes are supported.
5. Reopen the configured application database and repeat validation. On failure, close the replacement database, move the previous database/files back, reopen and validate them, and resume service. On success, remove the previous-data directory and staging area. No application restart is normally needed.

Allow enough free disk for the uploaded ZIP, expanded backup, and retained current data during replacement. The default HTTP request limit is approximately 2.1 GiB and multipart ZIP parts are limited to 2 GiB; `WIKI_MAX_RESTORE_SIZE` does not override transport/proxy limits. For larger archives, use raw ZIP uploads and raise `WIKI_MAX_REQUEST_SIZE`, `WIKI_MAX_RESTORE_SIZE`, and proxy limits appropriately. Keep `datasources.default.allow-pool-suspension=true` (the application default).

Rollback handles ordinary filesystem/database errors; this is **not a crash-atomic filesystem transaction**. If the process/machine stops during replacement, or rollback itself fails, preserve `.wiki-previous-*` and `.wiki-restore-*` and follow the recovery-directory paths in the logs. On an unrecoverable rollback, normal requests remain unavailable until an operator repairs the data and restarts. Never delete recovery directories until you have verified which database/files to retain. Cleanup failures are logged for manual attention.

For offline recovery, stop the application, extract a known-good ZIP into a clean data directory, check ownership/permissions, and restart. Database metadata and attachments must always be restored together. Configuration and environment secrets outside the data root are not archived or changed by restore; retain those separately.

### Observability

Logs are structured JSON on stdout and include startup, migrations, successful saves and invalid-password failures. Save logs include size/version/protection metadata, never content or passwords. Health is naturally checked with `GET /` (which can intentionally contain user content).

Micrometer publishes metrics to the **local JVM's JMX MBean server** (domain `metrics`), avoiding a public HTTP management namespace. Inspect via local JConsole/JMC. Important metrics include:

- `wiki.database.read` / `wiki.database.write` timers
- `wiki.markdown.render` timer
- `wiki.saves` counters, tagged by bounded result values
- `wiki.work.active` and `wiki.requests.rejected`
- `wiki.files.upload` timer, `wiki.files.uploaded`, `wiki.files.bytes`, `wiki.files.deleted`, and `wiki.files.failures` counters
- `wiki.backup.duration` timer and `wiki.backups` counters tagged by `success` / `failure`
- `wiki.restore.duration` timer and `wiki.restores` counters tagged by `success` / `failure` / `rollback`
- `wiki.restore.migrations` counter for migrations successfully executed in staging (even if later file validation rejects the restore); upgrades also log the migration count and target version
- `wiki.admin.authentication` failed-credential counter
- Micronaut HTTP and JVM metrics

### Verification

```sh
./gradlew clean test installDist
# HTML test report: build/reports/tests/test/index.html
```

Tests cover root/blank pages; nested, encoded, repeated- and trailing-slash paths; escaped textarea round-trips including leading newlines; create/update/protection; master precedence; invalid CSRF; malicious HTML; UTF-8 byte boundaries; forms exceeding framework default buffers; stale edits; upload admission/disconnection cleanup; and file-database restart persistence. Backup/restore tests additionally verify default/custom administrator authentication, the authenticated admin page, browser-navigation downloads, dated filenames, nested/Unicode files, empty directories, raw/multipart HTTP restores, CSRF/confirmation enforcement for browser restores, Post/Redirect/Get, escaped error/view output, complete data replacement, live-database restoration with attachment metadata, real V1/V2 backup upgrades and restart persistence, rejection of newer schemas (including numeric/dotted versions), failed migration isolation, modified-history rejection, rollback after an installed-database read failure, symlink/traversal/size-limit rejection, incomplete-configuration rejection, and maintenance admission/draining. Tests use isolated in-memory/temporary file databases, never `./data/wiki`.

#### Playwright end-to-end test

[`tests/e2e/edit-page.spec.cjs`](tests/e2e/edit-page.spec.cjs) exercises the complete user journey:

1. Navigate to a random nested path and verify the page is blank.
2. Click the pencil (`Edit page`) and verify the editor opens at `<path>/editor`.
3. Enter Markdown with a heading, bold/emphasized text, inline code and a list; leave the password empty.
4. Click the floppy disk (`Save page`), verify the real form POST returns 303, and verify navigation back to the original path.
5. Assert the rendered HTML elements, absence of the editor, and persistence after a full reload.

[`tests/e2e/responsive-pages.spec.cjs`](tests/e2e/responsive-pages.spec.cjs) also checks eight viewport sizes (320×240 through 1280×720), including the mobile layout breakpoint. It covers long names/passwords, growing drafts, textarea-only scrolling, server-rendered conflict errors, and wide Markdown text, code, tables and images.

[`tests/e2e/files.spec.cjs`](tests/e2e/files.spec.cjs) exercises file and directory selection, saved drafts and appended links, Unicode/original filenames, replacements with stable download URLs, actual browser downloads, password retention across uploads and a subsequent save without browser storage, password failures, and deletion that preserves unsaved edits. JVM tests additionally cover exact/over-limit file and aggregate byte counts using lowered limits, 64-bit size guards without allocating 2 GiB, traversal/symlink rejection, cross-page ownership, stale writes during staging, and rollback after a partial batch failure.

[`tests/e2e/admin.spec.cjs`](tests/e2e/admin.spec.cjs) verifies the authenticated administration flow without JavaScript: download a backup, change a page, upload/confirm the restore, and verify the previous content returns. The test server uses isolated data storage and explicit test-only administrator credentials.

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
