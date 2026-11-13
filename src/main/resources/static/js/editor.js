'use strict';

let editor = document.getElementById('editor');
const dialog = document.getElementById('settings-dialog');
const password = document.querySelector('input[name=password]');
const limit = 2 * 1024 * 1024 * 1024;
let busy = false;

function setBusy(value) {
    busy = value;
    document.querySelectorAll('.editor-actions button, #settings-dialog button, #settings-dialog input')
        .forEach(control => { control.disabled = value; });
    password.readOnly = value;
    editor.querySelector('textarea').readOnly = value;
    for (const region of [editor, dialog]) {
        if (value) region.setAttribute('aria-busy', 'true');
        else region.removeAttribute('aria-busy');
    }
}

function selectSettingsTab(id, focus = false) {
    for (const tab of dialog.querySelectorAll('[role=tab]')) {
        const selected = tab.id === id;
        tab.setAttribute('aria-selected', String(selected));
        tab.tabIndex = selected ? 0 : -1;
        document.getElementById(tab.getAttribute('aria-controls')).hidden = !selected;
        if (selected && focus) tab.focus();
    }
    dialog.querySelector('.settings-body').scrollTop = 0;
}

async function updateEditor(response) {
    const nextDocument = new DOMParser().parseFromString(await response.text(), 'text/html');
    const nextEditor = nextDocument.getElementById('editor');
    const nextDialog = nextDocument.getElementById('settings-dialog');
    const nextPassword = nextDocument.querySelector('input[name=password]');
    if (!nextEditor || !nextDialog || !nextPassword) throw new Error('Request did not return an editor');
    // Refresh the draft, version, CSRF token, errors and settings, never echoing passwords from HTML.
    editor.replaceWith(nextEditor);
    editor = nextEditor;
    const activeTab = dialog.querySelector('[role=tab][aria-selected=true]').id;
    dialog.replaceChildren(...nextDialog.childNodes);
    selectSettingsTab(activeTab);
    for (const attribute of ['placeholder', 'aria-label']) {
        password.setAttribute(attribute, nextPassword.getAttribute(attribute));
    }
    password.closest('label').querySelector('span').textContent = nextPassword.getAttribute('aria-label');
    if (response.ok && response.redirected) history.replaceState(null, '', response.url);
}

for (const picker of ['file-picker', 'folder-picker']) {
    document.getElementById(picker).addEventListener('change', async event => {
        const files = Array.from(event.target.files);
        if (files.length === 0 || busy) return;
        if (files.length > 1000 || files.some(file => file.size > limit) || files.reduce((sum, file) => sum + file.size, 0) > limit) {
            window.alert('Select up to 1000 files, with at most 2 GiB per file and 2 GiB total.');
            event.target.value = '';
            return;
        }
        // Indexed parts and a manifest preserve paths independently of multipart completion order.
        const fields = new FormData(editor);
        fields.set('manifest', JSON.stringify(files.map(file => file.webkitRelativePath || file.name)));
        files.forEach((file, index) => fields.append(`file${index}`, file, file.name));
        // Return to the draft while uploading. Cancelling the picker leaves settings open.
        dialog.close();
        setBusy(true);
        try {
            await updateEditor(await fetch('/_files/upload', { method: 'POST', body: fields }));
        } catch {
            window.alert('Unable to complete the upload. Your draft and password are still in the editor. Check the page files before retrying.');
        } finally {
            setBusy(false);
            event.target.value = '';
        }
    });
}

async function changePassword(form) {
    const fields = new FormData(editor);
    fields.delete('password');
    for (const [name, value] of new FormData(form)) fields.set(name, value);
    const newPassword = form.elements.newPassword.value;
    setBusy(true);
    try {
        const response = await fetch(editor.action, { method: 'POST', body: new URLSearchParams(fields) });
        await updateEditor(response);
        // Keep the new edit credential only in this document, never in storage or server-rendered HTML.
        if (response.ok) password.value = newPassword;
    } catch {
        window.alert('Unable to confirm the password change. Your draft is still in the editor. Check the page before retrying.');
    } finally {
        setBusy(false);
        dialog.querySelector('[role=alert], [role=status]')?.focus();
    }
}

document.getElementById('page-settings').addEventListener('click', () => {
    selectSettingsTab('settings-files-tab');
    dialog.showModal();
});
if (dialog.dataset.open === 'true') {
    selectSettingsTab('settings-password-tab');
    dialog.showModal();
}
dialog.addEventListener('keydown', event => {
    const tab = event.target.closest('[role=tab]');
    if (busy || !tab) return;
    const tabs = Array.from(dialog.querySelectorAll('[role=tab]'));
    let index = tabs.indexOf(tab);
    if (event.key === 'ArrowRight') index = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') index = (index + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') index = 0;
    else if (event.key === 'End') index = tabs.length - 1;
    else return;
    event.preventDefault();
    selectSettingsTab(tabs[index].id, true);
});
dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
dialog.addEventListener('close', () => dialog.querySelector('#password-settings')?.reset());
dialog.addEventListener('click', event => {
    if (busy) return;
    const tab = event.target.closest('[role=tab]');
    if (tab) selectSettingsTab(tab.id);
    if (event.target.closest('#close-settings')) dialog.close();
    if (event.target.closest('#choose-files')) document.getElementById('file-picker').click();
    if (event.target.closest('#choose-folder')) document.getElementById('folder-picker').click();
    const insert = event.target.closest('.insert-file');
    if (!insert) return;
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
    if (busy && (event.target.id === 'editor' || event.target.id === 'password-settings')) {
        event.preventDefault();
    } else if (event.target.id === 'password-settings') {
        event.preventDefault();
        void changePassword(event.target);
    } else if (event.target.id === 'editor' && event.submitter?.classList.contains('delete-file') && !window.confirm('Delete this file? Existing Markdown links will stop working.')) {
        event.preventDefault();
    }
});
