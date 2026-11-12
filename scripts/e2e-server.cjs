// Playwright owns this process and shuts it down after the suite. Never uses ./data.
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { mkdtemp, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
let directory;
let server;
let stopping;

function stop(exitCode) {
    if (stopping) return stopping;
    stopping = (async () => {
        if (server?.pid && server.exitCode === null && server.signalCode === null) {
            const exited = once(server, 'exit');
            server.kill('SIGTERM');
            await exited;
        }
        if (directory) await rm(directory, { recursive: true, force: true });
        process.exitCode = exitCode;
    })();
    return stopping;
}

process.once('SIGTERM', () => { void stop(0); });
process.once('SIGINT', () => { void stop(0); });

async function main() {
    directory = await mkdtemp(path.join(tmpdir(), 'wiki-e2e-'));
    const executable = process.platform === 'win32' ? 'wiki.bat' : 'wiki';
    server = spawn(path.join(root, 'build/install/wiki/bin', executable), [], {
        cwd: root,
        shell: process.platform === 'win32',
        env: {
            ...process.env,
            WIKI_NAME: 'Wiki E2E',
            WIKI_DB_URL: `jdbc:h2:file:${directory}/wiki`,
            WIKI_DB_PASSWORD: '',
            WIKI_DATA_DIRECTORY: directory,
            WIKI_ADMIN_USERNAME: 'e2e-admin',
            WIKI_ADMIN_PASSWORD: 'e2e-admin-secret',
            WIKI_FILES_DIRECTORY: path.join(directory, 'files'),
            WIKI_MASTER_PASSWORD: '',
            WIKI_SECURE_COOKIES: 'false',
            JAVA_OPTS: '-Xms32m -Xmx256m'
        },
        stdio: ['ignore', 'inherit', 'inherit']
    });
    server.once('error', error => {
        console.error(error);
        void stop(1);
    });
    server.once('exit', (code, signal) => {
        if (!stopping) {
            console.error(`E2E application exited unexpectedly (${signal || code})`);
            void stop(code || 1);
        }
    });
}

main().catch(async error => {
    console.error(error);
    await stop(1);
});
