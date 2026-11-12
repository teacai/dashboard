const { defineConfig, devices } = require('@playwright/test');

const port = Number(process.env.WIKI_E2E_PORT || 18089);
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
    throw new Error('WIKI_E2E_PORT must be an integer between 1024 and 65535');
}
const baseURL = `http://127.0.0.1:${port}`;
const gradle = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';

module.exports = defineConfig({
    testDir: './tests/e2e',
    fullyParallel: false,
    workers: 1,
    forbidOnly: Boolean(process.env.CI),
    timeout: 30_000,
    reporter: [['list'], ['html', { open: 'never' }]],
    use: {
        baseURL,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure'
    },
    projects: [{
        name: 'chromium',
        use: {
            ...devices['Desktop Chrome'],
            ...(process.env.WIKI_BROWSER_CHANNEL ? { channel: process.env.WIKI_BROWSER_CHANNEL } : {})
        }
    }],
    webServer: {
        command: `${gradle} installDist --console=plain && node scripts/e2e-server.cjs`,
        url: `${baseURL}/editor`,
        env: { WIKI_PORT: String(port) },
        timeout: 120_000,
        reuseExistingServer: false,
        gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 }
    }
});
