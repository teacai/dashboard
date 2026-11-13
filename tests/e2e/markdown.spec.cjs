const { randomUUID } = require('node:crypto');
const { readFile } = require('node:fs/promises');
const { test, expect } = require('@playwright/test');

test.use({ javaScriptEnabled: false });

test('Markdown URL editor saves the HTML page and its source downloads with the slug filename', async ({ page }) => {
    const slug = `notes-${randomUUID()}`;
    const pagePath = `/e2e/markdown/${slug}`;
    const content = `# Markdown download 日本\n\n**Rendered text**\n\n[Download Markdown](${pagePath}.md)\n`;

    await page.goto(pagePath + '.md/editor');
    await expect(page.locator('form#editor')).toHaveAttribute('action', pagePath);
    await expect(page.locator('input[name=page]')).toHaveValue(pagePath);
    await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill(content);
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(pagePath + '$'));
    await expect(page.getByRole('heading', { name: 'Markdown download 日本', exact: true })).toBeVisible();
    await expect(page.locator('strong')).toHaveText('Rendered text');

    const downloading = page.waitForEvent('download');
    await page.getByRole('link', { name: 'Download Markdown', exact: true }).click();
    const download = await downloading;
    expect(download.suggestedFilename()).toBe(slug + '.md');
    // Native HTML form submission normalizes textarea line endings to CRLF.
    expect(await readFile(await download.path(), 'utf8')).toBe(content.replace(/\n/g, '\r\n'));
    await expect(page).toHaveURL(new RegExp(pagePath + '$'));

    await page.goto(pagePath + '.md/editor');
    await expect(page.getByRole('textbox', { name: 'Page Markdown', exact: true })).toHaveValue(content);
    await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill('# Changed through Markdown editor');
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(pagePath + '$'));
    await expect(page.getByRole('heading', { name: 'Changed through Markdown editor', exact: true })).toBeVisible();
});
