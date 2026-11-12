const { randomUUID } = require('node:crypto');
const { mkdtemp, mkdir, writeFile, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { test, expect } = require('@playwright/test');

async function pickFiles(page, files, folder = false) {
    await page.getByRole('button', { name: 'Upload files', exact: true }).click();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: folder ? 'Upload folder' : 'Upload files', exact: true })
        .filter({ visible: true }).last().click();
    const uploaded = page.waitForResponse(response => response.url().endsWith('/_files/upload') && response.request().method() === 'POST');
    await (await chooser).setFiles(files);
    return uploaded;
}

test('upload files saves the draft, replaces original names, downloads and deletes while preserving edits', async ({ page, baseURL }) => {
    const slug = `/e2e/${randomUUID()}/attachments`;
    await page.setViewportSize({ width: 320, height: 568 });
    await page.goto(slug + '/editor');
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    await editor.fill('# Draft before upload\n\nUnsaved text');
    await page.locator('input[name=password]').fill('file-secret');
    const response = await pickFiles(page, [
        { name: 'notes [one].txt', mimeType: 'text/plain', buffer: Buffer.from('first upload') },
        { name: '日本 %22 "quoted".txt', mimeType: 'text/plain', buffer: Buffer.from('Unicode filename') }
    ]);
    expect(response.status()).toBe(303);
    await expect(page).toHaveURL(baseURL + slug + '/editor');
    await expect(editor).toHaveValue(/# Draft before upload\n\nUnsaved text\n\n- \[notes/);
    await expect(page.locator('input[name=password]')).toHaveValue('file-secret');
    await page.reload();
    await expect(page.locator('input[name=password]')).toHaveValue('');
    await expect(editor).toHaveValue(/日本/);
    await page.getByRole('button', { name: 'Manage files', exact: true }).click();
    const manager = page.getByRole('dialog');
    await expect(manager.locator('.file-list li')).toHaveCount(2);
    await expect(manager.getByRole('link', { name: '日本 %22 "quoted".txt', exact: true })).toBeVisible();
    for (const viewport of [{ width: 320, height: 240 }, { width: 481, height: 320 }, { width: 1280, height: 720 }]) {
        await page.setViewportSize(viewport);
        for (const locator of [manager, manager.getByRole('button', { name: 'Close files', exact: true })]) {
            const box = await locator.boundingBox();
            expect(box.x).toBeGreaterThanOrEqual(0);
            expect(box.y).toBeGreaterThanOrEqual(0);
            expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
            expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
        }
    }
    await page.setViewportSize({ width: 320, height: 568 });
    const originalUrl = await manager.getByRole('link', { name: 'notes [one].txt', exact: true }).getAttribute('href');
    const downloaded = page.waitForEvent('download');
    await manager.getByRole('link', { name: 'notes [one].txt', exact: true }).click();
    expect((await downloaded).suggestedFilename()).toBe('notes [one].txt');
    await page.getByRole('button', { name: 'Close files', exact: true }).click();

    await editor.fill('Edited draft for replacement');
    await page.locator('input[name=password]').fill('file-secret');
    expect((await pickFiles(page, [{ name: 'notes [one].txt', mimeType: 'text/plain', buffer: Buffer.from('replacement') }])).status()).toBe(303);
    await expect(page).toHaveURL(baseURL + slug + '/editor');
    expect(await (await page.request.get(originalUrl)).text()).toBe('replacement');
    await expect(editor).toHaveValue(/Edited draft for replacement\n\n- /);

    await editor.fill('Unsaved draft during deletion');
    await page.locator('input[name=password]').fill('file-secret');
    await page.getByRole('button', { name: 'Manage files', exact: true }).click();
    page.once('dialog', dialog => dialog.accept());
    await manager.getByRole('button', { name: 'Delete notes [one].txt', exact: true }).click();
    await expect(editor).toHaveValue('Unsaved draft during deletion');
    expect((await page.request.get(originalUrl)).status()).toBe(404);
    await page.getByRole('button', { name: 'Manage files', exact: true }).click();
    await expect(page.getByRole('dialog').locator('.file-list li')).toHaveCount(1);
    await page.getByRole('button', { name: 'Close files', exact: true }).click();
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible(); // Password was intentionally not echoed after deletion.
    await expect(editor).toHaveValue('Unsaved draft during deletion');
});

test('directory picker mirrors selected folder structure and leaves the user editing', async ({ page, baseURL }) => {
    const directory = await mkdtemp(path.join(tmpdir(), 'wiki-folder-upload-'));
    const folder = path.join(directory, 'selected-folder');
    try {
        await mkdir(path.join(folder, 'nested', 'deeper'), { recursive: true });
        await writeFile(path.join(folder, 'root.txt'), 'root content');
        await writeFile(path.join(folder, 'nested', 'deeper', 'leaf.txt'), 'nested content');
        const slug = `/e2e/${randomUUID()}/directory`;
        await page.goto(slug + '/editor');
        const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
        await editor.fill('Directory upload draft');
        expect((await pickFiles(page, folder, true)).status()).toBe(303);
        await expect(page).toHaveURL(baseURL + slug + '/editor');
        await expect.poll(async () => (await editor.inputValue()).replaceAll('\\', ''))
            .toContain('selected-folder/nested/deeper/leaf.txt');
        await page.getByRole('button', { name: 'Manage files', exact: true }).click();
        const manager = page.getByRole('dialog');
        await expect(manager.locator('.file-list li')).toHaveCount(2);
        await expect(manager.getByRole('link', { name: 'selected-folder/root.txt', exact: true })).toBeVisible();
        await expect(manager.getByRole('link', { name: 'selected-folder/nested/deeper/leaf.txt', exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Close files', exact: true }).click();
        await editor.fill((await editor.inputValue()) + '\nContinuing to edit');
        await page.getByRole('button', { name: 'Save page', exact: true }).click();
        await expect(page).toHaveURL(baseURL + slug);
        await expect(page.getByRole('main').getByRole('link')).toHaveCount(2);
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});

test('a failed upload keeps the original draft and password and unlocks editing', async ({ page }) => {
    await page.goto(`/e2e/${randomUUID()}/network-failure/editor`);
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    const password = page.locator('input[name=password]');
    await editor.fill('Keep this unsaved draft');
    await password.fill('keep-this-password');
    await page.route('**/_files/upload', route => route.abort('failed'));
    const alert = page.waitForEvent('dialog');
    await page.locator('#file-picker').setInputFiles({ name: 'file.txt', mimeType: 'text/plain', buffer: Buffer.from('content') });
    const notice = await alert;
    expect(notice.message()).toContain('Your draft and password are still in the editor');
    await notice.accept();
    await expect(page.getByRole('button', { name: 'Save page', exact: true })).toBeEnabled();
    await expect(editor).toHaveValue('Keep this unsaved draft');
    await expect(password).toHaveValue('keep-this-password');
    await expect(editor).toBeEditable();
    await expect(password).toBeEditable();
});

test('uploads retain the password for another upload and save without persisting it', async ({ page, baseURL }) => {
    const slug = `/e2e/${randomUUID()}/retained-password`;
    const password = 'upload-<" & secret';
    await page.goto(slug + '/editor');
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    const passwordField = page.locator('input[name=password]');
    await editor.fill('Draft before uploads');
    await passwordField.fill(password);

    for (const name of ['first.txt', 'second.txt']) {
        expect((await pickFiles(page, [{ name, mimeType: 'text/plain', buffer: Buffer.from(name) }])).status()).toBe(303);
        await expect(editor).toHaveValue(new RegExp(name.split('.')[0]));
        await expect(passwordField).toHaveValue(password);
        await expect(page).toHaveURL(baseURL + slug + '/editor');
        await expect(page.getByRole('button', { name: 'Save page', exact: true })).toBeEnabled();
    }
    await expect(editor).toHaveValue(/first[\s\S]*second/);
    await editor.fill((await editor.inputValue()) + '\nContinued editing');
    const saved = page.waitForResponse(response => response.request().method() === 'POST' && response.url() === baseURL + slug);
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    expect((await saved).status()).toBe(303);
    await expect(page).toHaveURL(baseURL + slug);
    await expect(page.getByRole('main')).toContainText('Continued editing');
    await page.getByRole('link', { name: 'Edit page', exact: true }).click();
    await expect(passwordField).toHaveValue('');
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

test('incorrect upload password keeps the draft and does not create a file', async ({ page }) => {
    const slug = `/e2e/${randomUUID()}/protected-files`;
    await page.goto(slug + '/editor');
    await page.locator('input[name=password]').fill('correct');
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await page.getByRole('link', { name: 'Edit page', exact: true }).click();
    await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill('Keep this draft');
    await page.locator('input[name=password]').fill('wrong');
    expect((await pickFiles(page, [{ name: 'rejected.txt', mimeType: 'text/plain', buffer: Buffer.from('rejected') }])).status()).toBe(403);
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Page Markdown', exact: true })).toHaveValue('Keep this draft');
    await expect(page.locator('input[name=password]')).toHaveValue('wrong');
    await expect(page.getByRole('button', { name: 'Save page', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Manage files', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText('No files uploaded');
});
