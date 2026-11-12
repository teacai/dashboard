const { randomUUID } = require('node:crypto');
const { test, expect } = require('@playwright/test');

const viewports = [
    { width: 320, height: 568 },
    { width: 390, height: 844 },
    { width: 480, height: 320 },
    { width: 481, height: 320 },
    { width: 568, height: 240 },
    { width: 768, height: 1024 },
    { width: 1280, height: 720 },
    { width: 320, height: 240 }
];
const longDraft = Array.from({ length: 400 }, (_, i) => `Line ${i + 1}: ${'markdown '.repeat(30)}`).join('\n');

async function expectWithinViewport(locator, viewport) {
    const box = await locator.boundingBox();
    expect(box).not.toBeNull();
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.width).toBeGreaterThan(0);
    expect(box.height).toBeGreaterThan(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
}

async function expectEditorFits(page, viewport) {
    for (const selector of ['.navbar', '.brand', 'input[name=password]', 'button[type=submit]', 'textarea']) {
        await expectWithinViewport(page.locator(selector), viewport);
    }
    const sizes = await page.evaluate(() => ({
        width: document.documentElement.scrollWidth,
        height: document.documentElement.scrollHeight,
        bodyWidth: document.body.scrollWidth,
        bodyHeight: document.body.scrollHeight
    }));
    expect(sizes.width).toBeLessThanOrEqual(viewport.width);
    expect(sizes.height).toBeLessThanOrEqual(viewport.height);
    expect(sizes.bodyWidth).toBeLessThanOrEqual(viewport.width);
    expect(sizes.bodyHeight).toBeLessThanOrEqual(viewport.height);
}

test('only the textarea scrolls and editor controls stay in view when resized', async ({ page }) => {
    await page.goto(`/e2e/${randomUUID()}/editor`);
    // WIKI_NAME is configurable; even an unbroken name must leave room for the controls.
    await page.locator('.brand').evaluate(element => { element.textContent = 'LongWikiName'.repeat(40); });
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    const password = page.locator('input[name=password]');
    await password.fill('password'.repeat(100));

    for (const viewport of viewports) {
        await test.step(`${viewport.width}x${viewport.height}`, async () => {
            await page.setViewportSize(viewport);
            await editor.fill('');
            const emptyBox = await editor.boundingBox();
            await editor.fill(longDraft + '\n' + 'x'.repeat(4000));
            expect(await editor.boundingBox()).toEqual(emptyBox);
            await expectEditorFits(page, viewport);
            expect(await editor.evaluate(element => getComputedStyle(element).resize)).toBe('none');
            expect(await editor.evaluate(element => element.scrollHeight)).toBeGreaterThan(emptyBox.height);

            await editor.evaluate(element => { element.scrollTop = 0; });
            await editor.press('PageDown');
            await expect.poll(() => editor.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
            await editor.evaluate(element => { element.scrollTop = element.scrollHeight; });
            expect(await editor.evaluate(element =>
                Math.abs(element.scrollHeight - element.clientHeight - element.scrollTop))).toBeLessThanOrEqual(1);
            expect(await page.evaluate(() => window.scrollY)).toBe(0);
            await expectEditorFits(page, viewport);
        });
    }
});

test('an editor error preserves the draft without pushing controls out of view', async ({ page }) => {
    const pagePath = `/e2e/${randomUUID()}`;
    await page.goto(pagePath + '/editor');
    const editor = page.getByRole('textbox', { name: 'Page Markdown', exact: true });
    await editor.fill('Saved version');
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(pagePath + '$'));
    await page.goto(pagePath + '/editor');
    await editor.fill(longDraft);
    // Simulate a stale editor to exercise the actual server-rendered error layout.
    await page.locator('input[name=version]').evaluate(element => { element.value = '-1'; });
    const rejected = page.waitForResponse(response => response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    expect((await rejected).status()).toBe(409);
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(editor).toHaveValue(longDraft);

    for (const viewport of viewports) {
        await page.setViewportSize(viewport);
        await expectEditorFits(page, viewport);
        await expectWithinViewport(page.getByRole('alert'), viewport);
    }
});

test('rendered Markdown contains wide content without horizontal page scrolling', async ({ page }) => {
    await page.route('**/responsive-image.svg', route => route.fulfill({
        contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="3000" height="1000"><rect width="3000" height="1000" fill="gray"/></svg>'
    }));
    const pagePath = `/e2e/${randomUUID()}`;
    const columns = Array.from({ length: 24 }, (_, i) => `Column${i}`);
    const markdown = [
        '# ' + 'LongHeading'.repeat(40),
        '[A long link](https://example.com/' + 'path'.repeat(500) + ')',
        'https://example.com/' + 'unbroken'.repeat(100),
        '`' + 'inlineCode'.repeat(100) + '`',
        '> A blockquote with ' + 'text'.repeat(400),
        '- A list item with ' + 'text'.repeat(400),
        '```\n' + 'wide-code-'.repeat(400) + '\n```',
        '| ' + columns.join(' | ') + ' |\n| ' + columns.map(() => '---').join(' | ') + ' |\n| ' +
            columns.map(() => 'wide-cell-'.repeat(20)).join(' | ') + ' |',
        '<figure><img src="/responsive-image.svg" width="3000" height="1000" alt="Large image"></figure>',
        ...Array.from({ length: 30 }, () => 'A paragraph of content to keep the page vertically scrollable.')
    ].join('\n\n');
    await page.goto(pagePath + '/editor');
    await page.getByRole('textbox', { name: 'Page Markdown', exact: true }).fill(markdown);
    await page.getByRole('button', { name: 'Save page', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(pagePath + '$'));
    await expect(page.getByRole('img', { name: 'Large image' })).toBeVisible();
    await page.locator('.brand').evaluate(element => { element.textContent = 'LongWikiName'.repeat(40); });

    for (const viewport of viewports) {
        await test.step(`${viewport.width}x${viewport.height}`, async () => {
            await page.setViewportSize(viewport);
            expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
            await expectWithinViewport(page.getByRole('link', { name: 'Edit page', exact: true }), viewport);
            for (const selector of ['main', 'pre', 'table', 'img']) {
                const box = await page.locator(selector).boundingBox();
                expect(box.x).toBeGreaterThanOrEqual(0);
                expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
            }
            for (const selector of viewport.width <= 480 ? ['pre', 'table'] : ['pre']) {
                expect(await page.locator(selector).evaluate(element => {
                    element.scrollLeft = element.scrollWidth;
                    return element.scrollLeft;
                })).toBeGreaterThan(0);
            }
            // Unlike the editor, rendered pages still allow normal vertical document scrolling.
            await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
            expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
            await page.evaluate(() => window.scrollTo(0, 0));
        });
    }
});
