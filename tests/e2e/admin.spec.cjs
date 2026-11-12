const { randomUUID } = require('node:crypto');
const { readFile } = require('node:fs/promises');
const { test, expect } = require('@playwright/test');

test.use({
    httpCredentials: { username: 'e2e-admin', password: 'e2e-admin-secret' },
    javaScriptEnabled: false
});

test('administrator can download and restore a backup without JavaScript', async ({ page, baseURL }) => {
    const pagePath = `/e2e/admin/${randomUUID()}`;

    async function save(content) {
        await page.goto(pagePath + '/editor');
        await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill(content);
        const saved = page.waitForResponse(response => response.url() === baseURL + pagePath
            && response.request().method() === 'POST');
        await page.getByRole('button', { name: 'Save page', exact: true }).click();
        expect((await saved).status()).toBe(303);
        await expect(page).toHaveURL(baseURL + pagePath);
    }

    await save('# Original backup content');
    const response = await page.goto('/_admin');
    expect(response.status()).toBe(200);
    await expect(page.getByRole('heading', { level: 1, name: 'Backup and restore' })).toBeVisible();

    const downloading = page.waitForEvent('download');
    await page.getByRole('link', { name: 'Download backup', exact: true }).click();
    const download = await downloading;
    expect(download.suggestedFilename()).toMatch(/^backup_\d{4}_\d{2}_\d{2}\.zip$/);
    const archive = await readFile(await download.path());
    expect(archive.subarray(0, 2).toString()).toBe('PK');

    await save('# Changed after backup');
    await expect(page.getByRole('heading', { level: 1, name: 'Changed after backup', exact: true })).toBeVisible();
    await page.goto('/_admin');
    await page.getByLabel('Backup ZIP archive', { exact: true }).setInputFiles({
        name: download.suggestedFilename(), mimeType: 'application/zip', buffer: archive
    });
    await page.getByRole('checkbox').check();
    const restoring = page.waitForResponse(response => response.url() === baseURL + '/_admin/restore'
        && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Restore backup', exact: true }).click();
    expect((await restoring).status()).toBe(303);
    await expect(page).toHaveURL(baseURL + '/_admin?restored=true');
    await expect(page.getByRole('status')).toContainText('Backup restored successfully');
    await page.reload();
    await expect(page.getByRole('status')).toContainText('Backup restored successfully');

    await page.goto(pagePath);
    await expect(page.getByRole('heading', { level: 1, name: 'Original backup content', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Changed after backup', exact: true })).toHaveCount(0);
});
