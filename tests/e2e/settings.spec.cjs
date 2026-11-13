const { randomUUID } = require('node:crypto');
const { test, expect } = require('@playwright/test');

async function submitPassword(page, status) {
    const response = page.waitForResponse(response => response.request().method() === 'POST'
        && new URLSearchParams(response.request().postData()).get('action') === 'change-password');
    await page.getByRole('button', { name: 'Change password and save draft', exact: true }).click();
    const result = await response;
    expect(result.status()).toBe(status);
    for (const secret of ['initial-secret', 'rotated-secret', 'wrong-secret']) {
        expect(await result.text()).not.toContain(secret);
    }
    await expect(page.getByRole('button', { name: 'Change password and save draft', exact: true })).toBeEnabled();
    await expect(page.getByRole('tab', { name: 'Password', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#settings-files-panel')).not.toBeVisible();
}

test('settings tabs default to Files, support keyboard navigation and fit small viewports', async ({ page }) => {
    await page.goto(`/e2e/settings/${randomUUID()}/tabs/editor`);
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    await editor.fill('An unsaved draft');
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    const settings = page.getByRole('dialog', { name: 'Page settings', exact: true });
    const files = settings.getByRole('tab', { name: 'Files', exact: true });
    const password = settings.getByRole('tab', { name: 'Password', exact: true });
    await expect(files).toHaveAttribute('aria-selected', 'true');
    await expect(files).toHaveAttribute('tabindex', '0');
    await expect(password).toHaveAttribute('tabindex', '-1');
    await expect(settings.locator('#settings-files-panel')).toBeVisible();
    await expect(settings.locator('#settings-password-panel')).not.toBeVisible();
    await files.focus();
    for (const [key, name] of [['ArrowRight', 'Password'], ['ArrowRight', 'Files'], ['ArrowLeft', 'Password'], ['Home', 'Files'], ['End', 'Password']]) {
        await page.keyboard.press(key);
        const selected = settings.getByRole('tab', { name, exact: true });
        await expect(selected).toBeFocused();
        await expect(selected).toHaveAttribute('aria-selected', 'true');
        await expect(selected).toHaveAttribute('tabindex', '0');
        await expect(settings.locator('#' + await selected.getAttribute('aria-controls'))).toBeVisible();
        expect(await settings.locator('[role=tab][aria-selected=true]').count()).toBe(1);
        expect(await settings.locator('[role=tabpanel]:visible').count()).toBe(1);
    }
    await page.keyboard.press('Tab');
    await expect(settings.locator('#settings-password-panel')).toBeFocused();
    await settings.getByLabel('New password', { exact: true }).fill('unsaved-password');
    for (const viewport of [{ width: 320, height: 240 }, { width: 1280, height: 720 }]) {
        await page.setViewportSize(viewport);
        for (const tab of [files, password]) {
            await tab.click();
            for (const control of [settings, settings.getByRole('button', { name: 'Close page settings', exact: true }), files, password]) {
                const box = await control.boundingBox();
                expect(box.x).toBeGreaterThanOrEqual(0);
                expect(box.y).toBeGreaterThanOrEqual(0);
                expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
                expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
            }
        }
    }
    await expect(settings.getByLabel('New password', { exact: true })).toHaveValue('unsaved-password');
    await expect(editor).toHaveValue('An unsaved draft');
    await settings.getByRole('button', { name: 'Close page settings', exact: true }).click();
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    await expect(files).toHaveAttribute('aria-selected', 'true');
    await password.click();
    await expect(settings.getByLabel('New password', { exact: true })).toHaveValue('');
});

test('page settings combine uploads and files with password creation, confirmation, rotation and removal', async ({ page }) => {
    const slug = `/e2e/settings/${randomUUID()}`;
    await page.setViewportSize({ width: 320, height: 568 });
    await page.goto(slug + '.md/editor');
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    const credential = page.locator('input[name=password]');
    const settings = page.getByRole('dialog', { name: 'Page settings', exact: true });
    await editor.fill('# Password creation draft');
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    await expect(settings.getByRole('button', { name: 'Upload files', exact: true })).toBeVisible();
    await expect(settings.getByRole('button', { name: 'Upload folder', exact: true })).toBeVisible();
    await expect(settings.getByRole('tab', { name: 'Files', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(settings.locator('#settings-password-panel')).not.toBeVisible();
    await settings.getByRole('tab', { name: 'Password', exact: true }).click();
    await settings.getByLabel('New password', { exact: true }).fill('initial-secret');
    await settings.getByLabel('Confirm new password', { exact: true }).fill('initial-secret');
    await submitPassword(page, 200);
    await expect(settings.getByRole('status')).toContainText('Your draft has been saved');
    await expect(credential).toHaveValue('initial-secret');
    for (const input of await settings.locator('input[type=password]').all()) await expect(input).toHaveValue('');
    expect(await (await page.request.get(slug + '.md')).text()).toBe('# Password creation draft');

    await settings.getByRole('button', { name: 'Close page settings', exact: true }).click();
    await editor.fill('Keep this draft through errors');
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    await expect(settings.getByRole('tab', { name: 'Files', exact: true })).toHaveAttribute('aria-selected', 'true');
    await settings.getByRole('tab', { name: 'Password', exact: true }).click();
    await settings.getByLabel('Old password', { exact: true }).fill('wrong-secret');
    await settings.getByLabel('New password', { exact: true }).fill('rotated-secret');
    await settings.getByLabel('Confirm new password', { exact: true }).fill('rotated-secret');
    await submitPassword(page, 403);
    await expect(settings.getByRole('alert')).toContainText('Incorrect password');
    await expect(editor).toHaveValue('Keep this draft through errors');
    expect(await (await page.request.get(slug + '.md')).text()).toBe('# Password creation draft');
    await settings.getByLabel('Old password', { exact: true }).fill('initial-secret');
    await settings.getByLabel('New password', { exact: true }).fill('rotated-secret');
    await settings.getByLabel('Confirm new password', { exact: true }).fill('mismatch');
    await submitPassword(page, 400);
    await expect(settings.getByRole('alert')).toContainText('do not match');
    await settings.getByLabel('Old password', { exact: true }).fill('initial-secret');
    await settings.getByLabel('New password', { exact: true }).fill('rotated-secret');
    await settings.getByLabel('Confirm new password', { exact: true }).fill('rotated-secret');
    await submitPassword(page, 200);
    await expect(credential).toHaveValue('rotated-secret');
    expect(await (await page.request.get(slug + '.md')).text()).toBe('Keep this draft through errors');

    // Both upload buttons and the file tools must still work after settings contents are refreshed.
    await settings.getByRole('tab', { name: 'Files', exact: true }).click();
    const uploaded = page.waitForResponse(response => response.url().endsWith('/_files/upload') && response.request().method() === 'POST');
    const chooser = page.waitForEvent('filechooser');
    await settings.getByRole('button', { name: 'Upload files', exact: true }).click();
    await (await chooser).setFiles({ name: 'settings.txt', mimeType: 'text/plain', buffer: Buffer.from('file') });
    expect((await uploaded).status()).toBe(303);
    await expect(editor).toHaveValue(/settings/);
    await expect(settings).not.toBeVisible();
    await expect(credential).toHaveValue('rotated-secret');
    await editor.fill('Public draft saved while removing protection');
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    await expect(settings.getByRole('link', { name: 'settings.txt', exact: true })).toBeVisible();
    await expect(settings.getByRole('tab', { name: 'Files', exact: true })).toHaveAttribute('aria-selected', 'true');
    await settings.getByRole('tab', { name: 'Password', exact: true }).click();
    await settings.getByLabel('Old password', { exact: true }).fill('rotated-secret');
    await submitPassword(page, 200); // Two empty new-password fields remove protection.
    await expect(credential).toHaveValue('');
    await expect(credential).toHaveAttribute('placeholder', 'Set password (optional)');
    await settings.getByRole('button', { name: 'Close page settings', exact: true }).click();
    await editor.fill('Public edit without a password');
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page.getByRole('main')).toHaveText('Public edit without a password');
    await page.getByRole('link', { name: 'Edit page', exact: true }).click();
    await expect(credential).toHaveValue('');
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

test('password requests lock editing and preserve the draft and credentials after a network failure', async ({ page }) => {
    const slug = `/e2e/settings/${randomUUID()}/failure`;
    await page.goto(slug + '/editor');
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    await editor.fill('Keep this unsaved draft');
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    const settings = page.getByRole('dialog');
    await settings.getByRole('tab', { name: 'Password', exact: true }).click();
    await settings.getByLabel('New password', { exact: true }).fill('not-submitted');
    await settings.getByLabel('Confirm new password', { exact: true }).fill('not-submitted');
    let abort;
    const held = new Promise(resolve => { abort = resolve; });
    await page.route('**' + slug, async route => { await held; await route.abort('failed'); });
    await settings.getByRole('button', { name: 'Change password and save draft', exact: true }).click();
    await expect(editor).not.toBeEditable();
    await expect(settings.getByRole('button', { name: 'Close page settings', exact: true })).toBeDisabled();
    await expect(settings.getByRole('tab', { name: 'Files', exact: true })).toBeDisabled();
    await expect(settings.getByRole('tab', { name: 'Password', exact: true })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(settings).toBeVisible();
    const alert = page.waitForEvent('dialog');
    abort();
    const notice = await alert;
    expect(notice.message()).toContain('Unable to confirm the password change');
    await notice.accept();
    await expect(editor).toBeEditable();
    await expect(editor).toHaveValue('Keep this unsaved draft');
    await expect(settings.getByLabel('New password', { exact: true })).toHaveValue('not-submitted');
    await expect(settings.getByRole('button', { name: 'Close page settings', exact: true })).toBeEnabled();
    await settings.getByRole('button', { name: 'Close page settings', exact: true }).click();
    await page.getByRole('button', { name: 'Page settings', exact: true }).click();
    await expect(settings.getByRole('tab', { name: 'Files', exact: true })).toHaveAttribute('aria-selected', 'true');
    await settings.getByRole('tab', { name: 'Password', exact: true }).click();
    await expect(settings.getByLabel('New password', { exact: true })).toHaveValue('');
});
