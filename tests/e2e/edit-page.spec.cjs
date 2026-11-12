const { randomUUID } = require('node:crypto');
const { test, expect } = require('@playwright/test');

test('edit a random page and display saved Markdown as HTML at the same path', async ({ page, baseURL }) => {
    const pagePath = `/e2e/${randomUUID()}/notes`;
    const heading = 'My end-to-end page';
    const markdown = [
        `# ${heading}`,
        '',
        'This page has **bold text**, *emphasis*, and `inline code`.',
        '',
        '- First item',
        '- Second item'
    ].join('\n');

    await test.step('Navigate to a new random path and click the pencil', async () => {
        const response = await page.goto(pagePath);
        expect(response.status()).toBe(200);
        await expect(page).toHaveURL(baseURL + pagePath);
        await expect(page.getByRole('main')).toBeEmpty();

        await page.getByRole('link', { name: 'Edit page', exact: true }).click();
        await expect(page).toHaveURL(baseURL + pagePath + '/editor');
    });

    await test.step('Enter Markdown and save without a password', async () => {
        const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
        await expect(editor).toHaveValue('');
        await editor.fill(markdown);
        await expect(page.locator('input[name=password]')).toHaveValue('');

        // Assert the real form POST succeeds, rather than just observing a URL change to an error page.
        const saved = page.waitForResponse(response =>
            response.url() === baseURL + pagePath && response.request().method() === 'POST');
        await page.getByRole('button', { name: 'Save page', exact: true }).click();
        expect((await saved).status()).toBe(303);
        await expect(page).toHaveURL(baseURL + pagePath);
    });

    async function expectRenderedPage() {
        const content = page.getByRole('main');
        await expect(content.getByRole('heading', { level: 1, name: heading, exact: true })).toBeVisible();
        await expect(content.locator('strong')).toHaveText('bold text');
        await expect(content.locator('em')).toHaveText('emphasis');
        await expect(content.locator('code')).toHaveText('inline code');
        await expect(content.getByRole('listitem')).toHaveText(['First item', 'Second item']);
        await expect(page.getByRole('textbox', { name: 'Page Markdown', exact: true })).toHaveCount(0);
        await expect(page.getByRole('link', { name: 'Edit page', exact: true })).toBeVisible();
    }

    await test.step('Show rendered HTML, not the editor or raw Markdown', expectRenderedPage);

    await test.step('Reload the page and verify the saved content persists', async () => {
        const response = await page.reload();
        expect(response.status()).toBe(200);
        await expect(page).toHaveURL(baseURL + pagePath);
        await expectRenderedPage();
    });
});
