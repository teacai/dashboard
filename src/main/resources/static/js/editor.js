'use strict';

let editor = document.getElementById('editor');
const menu = document.getElementById('upload-menu');
const dialog = document.getElementById('files-dialog');
const limit = 2 * 1024 * 1024 * 1024;
let uploading = false;

for (const [button, picker] of [['choose-files', 'file-picker'], ['choose-folder', 'folder-picker']]) {
    document.getElementById(button).addEventListener('click', () => {
        menu.hidePopover();
        document.getElementById(picker).click();
    });
    document.getElementById(picker).addEventListener('change', async event => {
        const files = Array.from(event.target.files);
        if (files.length === 0 || uploading) return;
        if (files.length > 1000 || files.some(file => file.size > limit) || files.reduce((sum, file) => sum + file.size, 0) > limit) {
            window.alert('Select up to 1000 files, with at most 2 GiB per file and 2 GiB total.');
            event.target.value = '';
            return;
        }
        // Keep the password field in this document, never in storage or server-rendered HTML.
        // Indexed parts and a manifest preserve paths independently of multipart completion order.
        const fields = new FormData(editor);
        fields.set('manifest', JSON.stringify(files.map(file => file.webkitRelativePath || file.name)));
        files.forEach((file, index) => fields.append(`file${index}`, file, file.name));
        const controls = document.querySelectorAll('.editor-actions button');
        const password = document.querySelector('input[name=password]');
        const textarea = editor.querySelector('textarea');
        uploading = true;
        controls.forEach(control => { control.disabled = true; });
        password.readOnly = true;
        textarea.readOnly = true;
        editor.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch('/_files/upload', { method: 'POST', body: fields });
            const nextDocument = new DOMParser().parseFromString(await response.text(), 'text/html');
            const nextEditor = nextDocument.getElementById('editor');
            const nextDialog = nextDocument.getElementById('files-dialog');
            if (!nextEditor || !nextDialog) throw new Error('Upload did not return an editor');
            // Update the saved draft, version, CSRF token, errors and file list, but not the password.
            editor.replaceWith(nextEditor);
            editor = nextEditor;
            dialog.replaceChildren(...nextDialog.childNodes);
            if (response.ok && response.redirected) history.replaceState(null, '', response.url);
        } catch {
            window.alert('Unable to complete the upload. Your draft and password are still in the editor. Check the page files before retrying.');
        } finally {
            uploading = false;
            controls.forEach(control => { control.disabled = false; });
            password.readOnly = false;
            textarea.readOnly = false;
            editor.removeAttribute('aria-busy');
            event.target.value = '';
        }
    });
}

document.getElementById('manage-files').addEventListener('click', () => dialog.showModal());
dialog.addEventListener('click', event => {
    if (event.target.closest('#close-files')) dialog.close();
    const insert = event.target.closest('.insert-file');
    if (!insert || uploading) return;
    const file = insert.closest('li').querySelector('a');
    // Escape ASCII punctuation, as in the links appended by the upload service.
    const label = file.textContent.replace(/[\x21-\x2f\x3a-\x40\x5b-\x60\x7b-\x7e]/g, '\\$&');
    const link = `[${label}](${file.getAttribute('href')})`;
    const textarea = editor.querySelector('textarea');
    try {
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end > textarea.value.length) {
            throw new Error('Cursor position unavailable');
        }
        textarea.setRangeText(link, start, end, 'end');
    } catch {
        textarea.value += link;
    }
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    dialog.close();
    textarea.focus();
});
document.addEventListener('submit', event => {
    if (event.target.id === 'editor' && event.submitter?.classList.contains('delete-file') && !window.confirm('Delete this file? Existing Markdown links will stop working.')) {
        event.preventDefault();
    }
});
