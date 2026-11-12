// Optional real-browser regression test. Playwright is test tooling, not an application dependency.
// See README.md for installation. Runs only against a fresh temporary database.
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { mkdtemp, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const path = require('node:path');
const net = require('node:net');
const { once } = require('node:events');
const { chromium } = require('playwright');

async function freePort() {
    const socket = net.createServer();
    socket.listen(0, '127.0.0.1');
    await once(socket, 'listening');
    const port = socket.address().port;
    await new Promise(resolve => socket.close(resolve));
    return port;
}

async function main() {
    const root = path.resolve(__dirname, '..');
    const directory = await mkdtemp(path.join(tmpdir(), 'wiki-browser-'));
    const port = await freePort();
    const server = spawn(path.join(root, 'build/install/wiki/bin/wiki'), [], {
        cwd: root,
        env: {
            ...process.env,
            WIKI_PORT: String(port),
            WIKI_DB_URL: `jdbc:h2:file:${directory}/wiki`,
            WIKI_DB_PASSWORD: '',
            WIKI_FILES_DIRECTORY: path.join(directory, 'files'),
            WIKI_MASTER_PASSWORD: '',
            WIKI_SECURE_COOKIES: 'false',
            JAVA_OPTS: '-Xms32m -Xmx256m'
        },
        stdio: ['ignore', 'pipe', 'pipe']
    });
    let logs = '';
    server.stdout.on('data', data => { logs = (logs + data).slice(-16384); });
    server.stderr.on('data', data => { logs = (logs + data).slice(-16384); });
    let launchError;
    server.on('error', error => { launchError = error; });
    let browser;
    try {
        let ready = false;
        for (let attempt = 0; attempt < 100; attempt++) {
            if (launchError) throw launchError;
            if (server.exitCode !== null) throw new Error(`Application exited: ${logs}`);
            try {
                const response = await fetch(`http://127.0.0.1:${port}/editor`, { signal: AbortSignal.timeout(2000) });
                await response.arrayBuffer();
                ready = response.ok;
            } catch { /* Server is still starting. */ }
            if (ready) break;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        assert.ok(ready, `Application did not start: ${logs}`);
        browser = await chromium.launch({
            headless: true,
            ...(process.env.WIKI_BROWSER_CHANNEL ? { channel: process.env.WIKI_BROWSER_CHANNEL } : {})
        });
        const page = await browser.newPage();
        for (const host of ['localhost', '127.0.0.1']) {
            const origin = `http://${host}:${port}`;
            for (const pagePath of ['/', '/nested/public-page']) {
                for (const action of ['create', 'update']) {
                    const editorPath = pagePath === '/' ? '/editor' : pagePath + '/editor';
                    const editor = await page.goto(origin + editorPath);
                    assert.equal(editor.headers()['referrer-policy'], 'same-origin');
                    const heading = `Anonymous ${action} ${host}`;
                    await page.locator('textarea').fill('# ' + heading);
                    assert.equal(await page.locator('input[name=password]').inputValue(), '');
                    const saved = page.waitForResponse(response => response.request().method() === 'POST');
                    await page.getByRole('button', { name: 'Save page' }).click();
                    const response = await saved;
                    const headers = await response.request().allHeaders();
                    assert.equal(headers.origin, origin);
                    assert.equal(response.status(), 303, `${host}${pagePath} save was rejected`);
                    await page.waitForURL(origin + pagePath);
                    assert.equal(await page.locator('main h1').textContent(), heading);
                    console.log(`PASS: ${host}${pagePath} ${action} without a password`);
                }
            }
        }
    } finally {
        if (browser) await browser.close();
        if (server.exitCode === null && !launchError) {
            const stopped = once(server, 'exit');
            server.kill('SIGTERM');
            await stopped;
        }
        await rm(directory, { recursive: true, force: true });
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
