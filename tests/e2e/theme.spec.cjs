const { randomUUID } = require('node:crypto');
const { test, expect } = require('@playwright/test');

const palettes = {
    light: {
        bg: 'rgb(255, 255, 255)', fg: 'rgb(34, 34, 34)', line: 'rgb(221, 221, 221)',
        muted: 'rgb(245, 246, 247)', accent: 'rgb(21, 91, 181)', error: 'rgb(255, 242, 237)'
    },
    dark: {
        bg: 'rgb(23, 25, 28)', fg: 'rgb(227, 229, 232)', line: 'rgb(57, 61, 67)',
        muted: 'rgb(36, 39, 44)', accent: 'rgb(139, 188, 255)', error: 'rgb(68, 37, 30)'
    }
};

test.use({
    httpCredentials: { username: 'e2e-admin', password: 'e2e-admin-secret' },
    javaScriptEnabled: false
});

async function expectTheme(page, scheme) {
    const palette = palettes[scheme];
    await expect(page.locator('html')).toHaveCSS('color-scheme', scheme);
    await expect(page.locator('body')).toHaveCSS('background-color', palette.bg);
    await expect(page.locator('body')).toHaveCSS('color', palette.fg);
    await expect(page.locator('.navbar')).toHaveCSS('border-bottom-color', palette.line);
}

for (const scheme of ['light', 'dark']) {
    test(`pages, editor and administration follow ${scheme} without JavaScript`, async ({ page }) => {
        await page.emulateMedia({ colorScheme: scheme });
        const palette = palettes[scheme];
        const pagePath = `/e2e/theme/${randomUUID()}`;

        await page.goto(pagePath + '/editor');
        await expectTheme(page, scheme);
        for (const selector of ['textarea', 'input[name=password]']) {
            await expect(page.locator(selector)).toHaveCSS('background-color', palette.bg);
            await expect(page.locator(selector)).toHaveCSS('color', palette.fg);
        }
        await page.getByRole('button', { name: 'Upload files', exact: true }).click();
        await expect(page.locator('#upload-menu')).toBeVisible();
        await expect(page.locator('#upload-menu')).toHaveCSS('background-color', palette.bg);
        await expect(page.locator('#upload-menu')).toHaveCSS('color', palette.fg);
        await page.keyboard.press('Escape');

        await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill(
            '# Theme test\n\n[A link](https://example.com)\n\n```\ncode\n```');
        await page.getByRole('button', { name: 'Save page', exact: true }).click();
        await expect(page).toHaveURL(new RegExp(pagePath + '$'));
        await expectTheme(page, scheme);
        await expect(page.getByRole('link', { name: 'A link', exact: true })).toHaveCSS('color', palette.accent);
        await expect(page.locator('pre')).toHaveCSS('background-color', palette.muted);

        await page.goto(pagePath + '/editor');
        // A stale version exercises the real server-rendered error palette.
        await page.locator('input[name=version]').evaluate(element => { element.value = '-1'; });
        await page.getByRole('button', { name: 'Save page', exact: true }).click();
        await expect(page.getByRole('alert')).toBeVisible();
        await expect(page.getByRole('alert')).toHaveCSS('background-color', palette.error);
        await expectTheme(page, scheme);

        await page.goto('/_admin');
        await expectTheme(page, scheme);
        await expect(page.locator('.admin-panel').first()).toHaveCSS('border-top-color', palette.line);
        await expect(page.getByRole('button', { name: 'Restore backup', exact: true }))
            .toHaveCSS('background-color', palette.muted);
    });
}

test('theme updates with the browser preference without reloading or losing the draft', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await page.goto(`/e2e/theme/${randomUUID()}/editor`);
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    await editor.fill('An unsaved draft');
    for (const scheme of ['dark', 'light', 'dark']) {
        await page.emulateMedia({ colorScheme: scheme });
        await expectTheme(page, scheme);
        await expect(editor).toHaveCSS('background-color', palettes[scheme].bg);
        await expect(editor).toHaveValue('An unsaved draft');
    }
});
