import { html, render } from './vendor/lit-html/lit-html.js';
import { repeat } from './vendor/lit-html/directives/repeat.js';

/**
 * Order Sample UI.
 *
 * The REST API is the only contract this file depends on: PageResponse,
 * `sort=field,asc|desc`, ProblemDetail errors (mapped to a toast), the short-lived
 * access token plus refresh cookie, and the CSRF header for unsafe methods.
 * Markup is produced with the vendored lit-html, so no inline script or style is
 * needed (CSP: script-src/style-src 'self').
 */

const TABS = ['orders', 'products', 'invoices', 'batch'];
const TERMINAL = ['COMPLETED', 'FAILED', 'STOPPED', 'ABANDONED'];

const state = {
    customers: [],
    allProducts: [],
    lines: [],
    lineSequence: 0,
    polling: null,
    isAdmin: false,
    orders: { page: 0, size: 10, sort: 'id,desc', status: '', totalPages: 0, totalElements: 0, first: true, last: true },
    products: { page: 0, size: 10, sort: 'code,asc', totalPages: 0, totalElements: 0, first: true, last: true },
    invoices: { page: 0, size: 10, sort: 'id,desc', totalPages: 0, totalElements: 0, first: true, last: true }
};

const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' });
const el = (id) => document.getElementById(id);
const showError = (error) => toast(error.message, true);

// ---------------------------------------------------------------- API plumbing

async function api(path, options = {}, attempt = 0) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const csrf = cookie('XSRF-TOKEN');
        if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    }
    const response = await fetch(path, { ...options, credentials: 'same-origin', headers });
    if (response.status === 401 && attempt === 0 && !path.startsWith('/api/auth/')) {
        // The access token is short-lived and stateless; the refresh cookie renews it.
        if (await refreshSession()) {
            return api(path, options, attempt + 1);
        }
    }
    if (response.status === 401) {
        window.location.replace('/login.html');
        throw new Error('認証が必要です');
    }
    const type = response.headers.get('content-type') || '';
    const body = type.includes('json') ? await response.json() : null;
    if (!response.ok) {
        throw new Error(body?.detail || body?.message || body?.error || `HTTP ${response.status}`);
    }
    return body;
}

function cookie(name) {
    const prefix = name + '=';
    const part = document.cookie.split(';').map((value) => value.trim())
        .find((value) => value.startsWith(prefix));
    return part ? part.substring(prefix.length) : '';
}

let refreshing = null;

/** Exchanges the HttpOnly refresh cookie for a new access token; at most one call in flight. */
function refreshSession() {
    refreshing ??= fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
        .then((response) => response.ok)
        .catch(() => false)
        .finally(() => { refreshing = null; });
    return refreshing;
}

async function loadSession() {
    let status = await api('/api/auth/status');
    if (!status.authenticated && await refreshSession()) {
        status = await api('/api/auth/status');
    }
    if (!status.authenticated) {
        window.location.replace('/login.html');
        throw new Error('認証が必要です');
    }
    const roles = status.roles || [];
    state.isAdmin = roles.includes('ADMIN');
    el('current-user').textContent = status.username;
    el('current-role').textContent = roles.join(' · ');
    el('user-avatar').textContent = (status.username || '?').charAt(0).toUpperCase();

    if (!state.isAdmin) {
        const batchButton = document.querySelector('[data-tab="batch"]');
        batchButton?.remove();
        el('tab-batch').classList.add('hidden');
        el('tab-batch').hidden = true;
        el('tab-batch').setAttribute('aria-hidden', 'true');
    }
    return status;
}

async function logout() {
    try {
        await api('/api/auth/logout', { method: 'POST' });
    } catch (error) {
        // The session may already be gone; the redirect below is still correct.
    }
    window.location.replace('/login.html');
}

function toast(message, isError = false) {
    const box = el('toast');
    box.textContent = message;
    box.className = 'toast show' + (isError ? ' error' : '');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { box.className = 'toast'; }, 3200);
}

// ---------------------------------------------------------------- pagination

function pageQuery(pageState, extra = {}) {
    const params = new URLSearchParams();
    params.set('page', String(pageState.page));
    params.set('size', String(pageState.size));
    if (pageState.sort) params.set('sort', pageState.sort);
    Object.entries(extra).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') params.set(key, value);
    });
    return params.toString();
}

function applyPage(pageState, response) {
    pageState.page = response.page;
    pageState.size = response.size;
    pageState.totalPages = response.totalPages;
    pageState.totalElements = response.totalElements;
    pageState.first = response.first;
    pageState.last = response.last;
    return response.content || [];
}

/** Runs a refresh with a busy hint on the panel it reloads. */
async function withBusy(panelId, load) {
    const panel = el(panelId);
    if (panel) panel.setAttribute('aria-busy', 'true');
    try {
        await load();
    } finally {
        if (panel) panel.removeAttribute('aria-busy');
    }
}

// ---------------------------------------------------------------- views

const statusBadge = (status) => html`<span class="badge ${status}">${status}</span>`;

const emptyBox = (message) => html`<div class="empty">${message}</div>`;

const table = (headers, rows) => html`<table>
    <thead><tr>${headers.map((header) => html`<th scope="col">${header}</th>`)}</tr></thead>
    <tbody>${rows}</tbody>
</table>`;

const pager = (pageState, name) => html`<div class="pager">
    <button class="small secondary" ?disabled=${pageState.first}
            @click=${() => goPage(name, 'prev')}>前へ</button>
    <span class="page-info">${pageState.page + 1} / ${Math.max(pageState.totalPages, 1)} ページ（${pageState.totalElements} 件）</span>
    <button class="small secondary" ?disabled=${pageState.last}
            @click=${() => goPage(name, 'next')}>次へ</button>
</div>`;

// ---------------------------------------------------------------- tabs

function switchTab(name, { updateHash = true } = {}) {
    if (!TABS.includes(name) || (name === 'batch' && !state.isAdmin)) {
        name = 'orders';
    }
    document.querySelectorAll('[data-tab]').forEach((button) => {
        const active = button.dataset.tab === name;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
        button.tabIndex = active ? 0 : -1;
        if (active) {
            button.setAttribute('aria-current', 'page');
        } else {
            button.removeAttribute('aria-current');
        }
    });
    document.querySelectorAll('main > .tab-panel').forEach((panel) => {
        const hidden = panel.id !== `tab-${name}`;
        panel.classList.toggle('hidden', hidden);
        panel.hidden = hidden;
        panel.setAttribute('aria-hidden', String(hidden));
    });
    if (updateHash) {
        history.replaceState(null, '', `#${name}`);
    }
    if (name === 'invoices') loadInvoices().catch(showError);
    if (name === 'batch') loadExecutions().catch(showError);
}

function activeTabFromHash() {
    const name = window.location.hash.replace('#', '');
    return TABS.includes(name) ? name : 'orders';
}

/** Arrow/Home/End navigation for the tablist (ARIA tabs pattern). */
function onTabKeydown(event) {
    const keys = ['ArrowRight', 'ArrowLeft', 'Home', 'End'];
    if (!keys.includes(event.key)) return;
    const buttons = [...document.querySelectorAll('[data-tab]')];
    const index = buttons.indexOf(document.activeElement);
    if (index < 0) return;
    event.preventDefault();
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % buttons.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + buttons.length) % buttons.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = buttons.length - 1;
    buttons[next].focus();
    switchTab(buttons[next].dataset.tab);
}

// ---------------------------------------------------------------- orders

async function loadCustomers() {
    const page = await api('/api/customers?page=0&size=100&sort=code,asc');
    state.customers = page.content || [];
    render(state.customers.map((customer) => html`
        <option value=${customer.code}>${customer.code} — ${customer.name}</option>`), el('customer'));
}

async function loadAllProducts() {
    const page = await api('/api/products?page=0&size=100&sort=code,asc');
    state.allProducts = page.content || [];
    for (const line of state.lines) {
        if (!line.productCode) line.productCode = state.allProducts[0]?.code ?? '';
    }
    renderLines();
}

async function loadOrders() {
    const page = await api(`/api/orders?${pageQuery(state.orders, { status: state.orders.status })}`);
    const orders = applyPage(state.orders, page);
    render(html`
        ${orders.length === 0
            ? emptyBox('該当する注文はありません。')
            : table(['注文番号', '顧客', '注文日', 'ステータス', '金額', '操作'], orders.map(orderRow))}
        ${pager(state.orders, 'orders')}`, el('orders-body'));
}

const orderRow = (order) => html`<tr>
    <td><span class="mono">${order.orderNo}</span></td>
    <td>${order.customerName}</td>
    <td>${order.orderDate}</td>
    <td>${statusBadge(order.status)}</td>
    <td class="num">${yen.format(order.totalAmount)}</td>
    <td>${orderActions(order)}</td>
</tr>`;

function orderActions(order) {
    const buttons = [];
    if (order.status === 'DRAFT') {
        buttons.push(html`<button class="small secondary" @click=${() => confirmOrder(order.id)}>確定</button>`);
    }
    if (order.status === 'DRAFT' || order.status === 'CONFIRMED') {
        buttons.push(html`<button class="ghost small danger" @click=${() => cancelOrder(order.id)}>取消</button>`);
    }
    return buttons.length === 0 ? html`<span class="empty">—</span>` : buttons;
}

async function confirmOrder(id) {
    try {
        await api(`/api/orders/${id}/confirm`, { method: 'POST' });
        toast('注文を確定しました');
        await Promise.all([loadOrders(), loadProductPage()]);
    } catch (error) {
        showError(error);
    }
}

async function cancelOrder(id) {
    try {
        await api(`/api/orders/${id}/cancel`, { method: 'POST' });
        toast('注文をキャンセルしました');
        await Promise.all([loadOrders(), loadProductPage()]);
    } catch (error) {
        showError(error);
    }
}

async function createOrder(event) {
    event.preventDefault();
    const body = {
        customerCode: el('customer').value,
        orderDate: el('order-date').value || null,
        lines: state.lines.map((line) => ({ productCode: line.productCode, quantity: line.quantity }))
    };
    try {
        const created = await api('/api/orders', { method: 'POST', body: JSON.stringify(body) });
        toast(`注文を作成しました: ${created.orderNo}`);
        state.lines = [];
        addLine();
        await Promise.all([loadOrders(), loadProductPage()]);
    } catch (error) {
        showError(error);
    }
}

function addLine() {
    state.lineSequence += 1;
    state.lines.push({
        id: state.lineSequence,
        productCode: state.allProducts[0]?.code ?? '',
        quantity: 1
    });
    renderLines();
}

function removeLine(id) {
    if (state.lines.length <= 1) return;
    state.lines = state.lines.filter((line) => line.id !== id);
    renderLines();
}

function renderLines() {
    render(html`${repeat(state.lines, (line) => line.id, (line) => html`
        <div class="line-row">
            <div class="field">
                <label for="line-product-${line.id}">商品</label>
                <select id="line-product-${line.id}"
                        @change=${(event) => { line.productCode = event.target.value; }}>
                    ${state.allProducts.map((product) => html`
                        <option value=${product.code} ?selected=${product.code === line.productCode}>
                            ${product.code} — ${product.name} (${yen.format(product.unitPrice)})
                        </option>`)}
                </select>
            </div>
            <div class="field field-quantity">
                <label for="line-quantity-${line.id}">数量</label>
                <input id="line-quantity-${line.id}" type="number" class="quantity" min="1" step="1"
                       .value=${String(line.quantity)}
                       @input=${(event) => { line.quantity = Number(event.target.value); }}>
            </div>
            <button type="button" class="ghost small danger" aria-label="明細を削除"
                    @click=${() => removeLine(line.id)}>削除</button>
        </div>`)}`, el('lines'));
}

// ---------------------------------------------------------------- products

async function loadProductPage() {
    const page = await api(`/api/products?${pageQuery(state.products)}`);
    const products = applyPage(state.products, page);
    render(html`
        ${products.length === 0
            ? emptyBox('商品がありません。')
            : table(['コード', '商品名', '単価', '在庫'], products.map((product) => html`
                <tr>
                    <td><span class="mono">${product.code}</span></td>
                    <td>${product.name}</td>
                    <td class="num">${yen.format(product.unitPrice)}</td>
                    <td class="num">${product.stock}</td>
                </tr>`))}
        ${pager(state.products, 'products')}`, el('products-body'));
}

async function loadProducts() {
    await loadAllProducts();
    await loadProductPage();
}

// ---------------------------------------------------------------- invoices

async function loadInvoices() {
    const month = el('invoice-month').value;
    const page = await api(`/api/invoices?${pageQuery(state.invoices, { month })}`);
    const invoices = applyPage(state.invoices, page);
    render(html`
        ${invoices.length === 0
            ? emptyBox('この月の請求書はありません。')
            : table(['請求書番号', '顧客', '注文番号', '対象月', '税抜', '税額'], invoices.map((invoice) => html`
                <tr>
                    <td><span class="mono">${invoice.invoiceNo}</span></td>
                    <td>${invoice.customerName}</td>
                    <td><span class="mono">${invoice.orderNo}</span></td>
                    <td>${invoice.billingMonth}</td>
                    <td class="num">${yen.format(invoice.totalAmount)}</td>
                    <td class="num">${yen.format(invoice.taxAmount)}</td>
                </tr>`))}
        ${pager(state.invoices, 'invoices')}`, el('invoices-body'));
}

// ---------------------------------------------------------------- batch

async function startInvoiceJob() {
    const month = el('batch-month').value;
    try {
        const query = month ? `?month=${encodeURIComponent(month)}` : '';
        const started = await api(`/api/batch/invoices${query}`, { method: 'POST' });
        toast(`請求書生成を開始しました（execution ${started.executionId}）`);
        pollExecution(started.executionId);
        await loadExecutions();
    } catch (error) {
        showError(error);
    }
}

async function startImportJob() {
    const inputFile = el('import-file').value.trim();
    try {
        const query = inputFile ? `?inputFile=${encodeURIComponent(inputFile)}` : '';
        const started = await api(`/api/batch/imports${query}`, { method: 'POST' });
        toast(`CSV 取込を開始しました（execution ${started.executionId}）`);
        pollExecution(started.executionId);
        await loadExecutions();
    } catch (error) {
        showError(error);
    }
}

async function pollExecution(executionId) {
    clearInterval(state.polling);
    el('execution-panel').hidden = false;
    let attempts = 0;
    const tick = async () => {
        attempts += 1;
        try {
            const execution = await api(`/api/batch/executions/${executionId}`);
            renderExecution(execution);
            const done = TERMINAL.includes(execution.status);
            if (done || attempts > 60) {
                clearInterval(state.polling);
                if (done) {
                    toast(`ジョブ ${execution.status}（execution ${execution.executionId}）`);
                    await Promise.all([loadExecutions(), loadOrders(), loadProductPage(), loadInvoices()]);
                }
            }
        } catch (error) {
            clearInterval(state.polling);
            showError(error);
        }
    };
    tick();
    state.polling = setInterval(tick, 1000);
}

function renderExecution(execution) {
    render(html`
        <div><strong>execution ${execution.executionId}</strong> — ${execution.jobName} ${statusBadge(execution.status)}</div>
        <div class="hint">exit=${execution.exitStatus || '—'} / start=${execution.startTime || '—'} / end=${execution.endTime || '—'}</div>
        ${(execution.steps || []).map((step) => html`
            <div class="hint exec-step">
                step <span class="mono">${step.stepName}</span>:
                read=${step.readCount}, write=${step.writeCount}, commit=${step.commitCount},
                rollback=${step.rollbackCount}, skip=${step.skipCount}
            </div>`)}`, el('exec-box'));
}

async function loadExecutions() {
    const executions = await api('/api/batch/executions?count=10');
    render(executions.length === 0
        ? emptyBox('実行履歴はありません。')
        : table(['Execution', 'ジョブ', 'ステータス', '開始', '終了'], executions.map((execution) => html`
            <tr>
                <td><span class="mono">${execution.executionId}</span></td>
                <td>${execution.jobName}</td>
                <td>${statusBadge(execution.status)}</td>
                <td>${execution.startTime || '—'}</td>
                <td>${execution.endTime || '—'}</td>
            </tr>`)), el('executions-body'));
}

// ---------------------------------------------------------------- paging & filters

const pagers = {
    orders: { get state() { return state.orders; }, load: loadOrders },
    products: { get state() { return state.products; }, load: loadProductPage },
    invoices: { get state() { return state.invoices; }, load: loadInvoices }
};

const pagerPanels = { orders: 'orders-body', products: 'products-body', invoices: 'invoices-body' };

function goPage(name, direction) {
    const pagerState = pagers[name];
    if (!pagerState) return;
    const pageState = pagerState.state;
    if (direction === 'prev' && !pageState.first) {
        pageState.page -= 1;
    } else if (direction === 'next' && !pageState.last) {
        pageState.page += 1;
    } else {
        return;
    }
    withBusy(pagerPanels[name], pagerState.load).catch(showError);
}

/** `data-filter="group.field"` in the markup, one handler for every select. */
const filters = {
    'orders.status': (value) => { state.orders.status = value; state.orders.page = 0; return loadOrders(); },
    'orders.sort': (value) => { state.orders.sort = value; state.orders.page = 0; return loadOrders(); },
    'orders.size': (value) => { state.orders.size = Number(value); state.orders.page = 0; return loadOrders(); },
    'products.sort': (value) => { state.products.sort = value; state.products.page = 0; return loadProductPage(); },
    'products.size': (value) => { state.products.size = Number(value); state.products.page = 0; return loadProductPage(); },
    'invoices.sort': (value) => { state.invoices.sort = value; state.invoices.page = 0; return loadInvoices(); },
    'invoices.size': (value) => { state.invoices.size = Number(value); state.invoices.page = 0; return loadInvoices(); }
};

const filterPanels = {
    'orders.': 'orders-body',
    'products.': 'products-body',
    'invoices.': 'invoices-body'
};

function applyFilter(name, value) {
    const apply = filters[name];
    if (!apply) return;
    const prefix = Object.keys(filterPanels).find((key) => name.startsWith(key));
    withBusy(filterPanels[prefix], () => apply(value)).catch(showError);
}

// ---------------------------------------------------------------- wiring

const clickActions = {
    'logout': () => logout(),
    'reload-orders': () => withBusy('orders-body', loadOrders).catch(showError),
    'reload-products': () => withBusy('products-body', loadProducts).catch(showError),
    'reload-invoices': () => withBusy('invoices-body', loadInvoices).catch(showError),
    'reload-executions': () => withBusy('executions-body', loadExecutions).catch(showError),
    'start-invoice-job': () => startInvoiceJob(),
    'start-import-job': () => startImportJob()
};

// Static buttons keep data-action; rows and the pager bind handlers in their lit
// template, so the browser never parses an inline handler (CSP-safe).
document.addEventListener('click', (event) => {
    if (!(event.target instanceof Element)) return;
    const trigger = event.target.closest('[data-action]');
    if (trigger) {
        clickActions[trigger.dataset.action]?.();
        return;
    }
    const tab = event.target.closest('[data-tab]');
    if (tab) switchTab(tab.dataset.tab);
});

document.addEventListener('change', (event) => {
    if (!(event.target instanceof Element)) return;
    const field = event.target.closest('[data-filter]');
    if (field) applyFilter(field.dataset.filter, field.value);
});

document.querySelector('.tabs').addEventListener('keydown', onTabKeydown);
el('add-line').addEventListener('click', addLine);
el('order-form').addEventListener('submit', createOrder);
el('invoice-month').addEventListener('change', () => {
    state.invoices.page = 0;
    withBusy('invoices-body', loadInvoices).catch(showError);
});
window.addEventListener('hashchange', () => switchTab(activeTabFromHash(), { updateHash: false }));

function previousMonth() {
    const now = new Date();
    const date = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

(async function init() {
    const month = previousMonth();
    el('invoice-month').value = month;
    el('batch-month').value = month;
    addLine();
    try {
        await loadSession();
        switchTab(activeTabFromHash(), { updateHash: false });
        const tasks = [loadCustomers(), loadProducts(), loadOrders(), loadInvoices()];
        if (state.isAdmin) tasks.push(loadExecutions());
        await Promise.all(tasks);
    } catch (error) {
        if (error.message !== '認証が必要です') {
            toast(`初期化に失敗しました: ${error.message}`, true);
        }
    }
})();
