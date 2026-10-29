import { html, render } from './vendor/lit-html/lit-html.js';
import { repeat } from './vendor/lit-html/directives/repeat.js';

const state = {
    products: [],
    allProducts: [],
    customers: [],
    lines: [],
    polling: null,
    isAdmin: false,
    orders: { page: 0, size: 10, sort: 'id,desc', status: '', totalPages: 0, totalElements: 0, first: true, last: true },
    productsPage: { page: 0, size: 10, sort: 'code,asc', totalPages: 0, totalElements: 0, first: true, last: true },
    invoices: { page: 0, size: 10, sort: 'id,desc', totalPages: 0, totalElements: 0, first: true, last: true }
};

const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' });
const el = (id) => document.getElementById(id);

async function api(path, options = {}, attempt = 0) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const csrf = getCookie('XSRF-TOKEN');
        if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    }
    const response = await fetch(path, { ...options, credentials: 'same-origin', headers });
    if (response.status === 401 && attempt === 0 && !path.startsWith('/api/auth/')) {
        // The access token is short-lived and stateless; the refresh token cookie renews it.
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
        const message = body?.detail || body?.message || body?.error || `HTTP ${response.status}`;
        throw new Error(message);
    }
    return body;
}

function getCookie(name) {
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

async function loadMe() {
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

    const batchNav = document.querySelector('nav button[data-tab="batch"]');
    if (batchNav) batchNav.hidden = !state.isAdmin;
    if (!state.isAdmin) {
        const batchTab = el('tab-batch');
        batchTab.classList.add('hidden');
        batchTab.hidden = true;
        batchTab.setAttribute('aria-hidden', 'true');
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
    toast.timer = setTimeout(() => { box.className = 'toast'; }, 3800);
}

function previousMonth() {
    const now = new Date();
    const date = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

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

const showError = (error) => toast(error.message, true);

// ---- views (lit-html escapes every interpolation, so no manual esc() is needed) ----

const statusBadge = (status) => html`<span class="badge ${status}">${status}</span>`;

const emptyBox = (message) => html`<div class="empty">${message}</div>`;

const table = (headers, rows) => html`<table>
    <thead><tr>${headers.map((header) => html`<th>${header}</th>`)}</tr></thead>
    <tbody>${rows}</tbody>
</table>`;

const pager = (pageState, name) => html`<div class="pager">
    <button class="ghost small" ?disabled=${pageState.first} @click=${() => goPage(name, 'prev')}>前へ</button>
    <span class="page-info">${pageState.page + 1} / ${Math.max(pageState.totalPages, 1)} ページ（${pageState.totalElements} 件）</span>
    <button class="ghost small" ?disabled=${pageState.last} @click=${() => goPage(name, 'next')}>次へ</button>
</div>`;

function switchTab(name) {
    if (name === 'batch' && !state.isAdmin) {
        return;
    }
    document.querySelectorAll('nav button').forEach((button) => {
        const active = button.dataset.tab === name;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
        if (active) {
            button.setAttribute('aria-current', 'page');
        } else {
            button.removeAttribute('aria-current');
        }
    });
    document.querySelectorAll('main > section').forEach((section) => {
        const hidden = section.id !== `tab-${name}`;
        section.classList.toggle('hidden', hidden);
        section.hidden = hidden;
        section.setAttribute('aria-hidden', String(hidden));
    });
    if (name === 'invoices') loadInvoices();
    if (name === 'batch') loadExecutions();
}

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

async function loadProductPage() {
    const page = await api(`/api/products?${pageQuery(state.productsPage)}`);
    state.products = applyPage(state.productsPage, page);
    render(html`
        ${state.products.length === 0
            ? emptyBox('商品はありません。')
            : table(['コード', '商品名', '単価', '在庫'], state.products.map((product) => html`
                <tr>
                    <td><span class="mono">${product.code}</span></td>
                    <td>${product.name}</td>
                    <td><span class="num">${yen.format(product.unitPrice)}</span></td>
                    <td><span class="num">${product.stock}</span></td>
                </tr>`))}
        ${pager(state.productsPage, 'products')}`, el('products-body'));
}

async function loadProducts() {
    await loadAllProducts();
    await loadProductPage();
}

let lineSequence = 0;

function addLineRow() {
    lineSequence += 1;
    state.lines.push({
        id: lineSequence,
        productCode: state.allProducts[0]?.code ?? '',
        quantity: 1
    });
    renderLines();
}

function removeLine(id) {
    if (state.lines.length <= 1) {
        return;
    }
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
            <button type="button" class="danger small" @click=${() => removeLine(line.id)}>削除</button>
        </div>`)}`, el('lines'));
}

async function loadOrders() {
    const page = await api(`/api/orders?${pageQuery(state.orders, { status: state.orders.status })}`);
    const orders = applyPage(state.orders, page);
    render(html`
        ${orders.length === 0
            ? emptyBox('注文はまだありません。')
            : table(['注文番号', '顧客', '注文日', 'ステータス', '金額', '操作'], orders.map(orderRow))}
        ${pager(state.orders, 'orders')}`, el('orders-body'));
}

const orderRow = (order) => html`<tr>
    <td><span class="mono">${order.orderNo}</span></td>
    <td>${order.customerName}</td>
    <td>${order.orderDate}</td>
    <td>${statusBadge(order.status)}</td>
    <td><span class="num">${yen.format(order.totalAmount)}</span></td>
    <td>${orderActions(order)}</td>
</tr>`;

function orderActions(order) {
    const buttons = [];
    if (order.status === 'DRAFT') {
        buttons.push(html`<button class="small" @click=${() => confirmOrder(order.id)}>確定</button>`);
    }
    if (order.status === 'DRAFT' || order.status === 'CONFIRMED') {
        buttons.push(html`<button class="danger small" @click=${() => cancelOrder(order.id)}>取消</button>`);
    }
    return buttons.length === 0 ? html`<span class="empty">—</span>` : buttons;
}

async function confirmOrder(id) {
    try {
        await api(`/api/orders/${id}/confirm`, { method: 'POST' });
        toast('注文を確定しました');
        await Promise.all([loadOrders(), loadProducts()]);
    } catch (error) {
        toast(error.message, true);
    }
}

async function cancelOrder(id) {
    try {
        await api(`/api/orders/${id}/cancel`, { method: 'POST' });
        toast('注文をキャンセルしました');
        await Promise.all([loadOrders(), loadProducts()]);
    } catch (error) {
        toast(error.message, true);
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
        addLineRow();
        await loadOrders();
    } catch (error) {
        toast(error.message, true);
    }
}

async function loadInvoices() {
    const month = el('invoice-month').value;
    const page = await api(`/api/invoices?${pageQuery(state.invoices, { month })}`);
    const invoices = applyPage(state.invoices, page);
    render(html`
        ${invoices.length === 0
            ? emptyBox('請求書はまだありません。バッチタブから生成してください。')
            : table(['請求書番号', '顧客', '注文番号', '対象月', '税抜', '税額'], invoices.map((invoice) => html`
                <tr>
                    <td><span class="mono">${invoice.invoiceNo}</span></td>
                    <td>${invoice.customerName}</td>
                    <td><span class="mono">${invoice.orderNo}</span></td>
                    <td>${invoice.billingMonth}</td>
                    <td><span class="num">${yen.format(invoice.totalAmount)}</span></td>
                    <td><span class="num">${yen.format(invoice.taxAmount)}</span></td>
                </tr>`))}
        ${pager(state.invoices, 'invoices')}`, el('invoices-body'));
}

async function startInvoiceJob() {
    const month = el('batch-month').value;
    try {
        const started = await api(`/api/batch/invoices${month ? `?month=${encodeURIComponent(month)}` : ''}`, { method: 'POST' });
        toast(`請求書バッチを開始しました (execution ${started.executionId})`);
        pollExecution(started.executionId);
        await loadExecutions();
    } catch (error) {
        toast(error.message, true);
    }
}

async function startImportJob() {
    const inputFile = el('import-file').value.trim();
    try {
        const started = await api(`/api/batch/imports${inputFile ? `?inputFile=${encodeURIComponent(inputFile)}` : ''}`, { method: 'POST' });
        toast(`CSV 取込バッチを開始しました (execution ${started.executionId})`);
        pollExecution(started.executionId);
        await loadExecutions();
    } catch (error) {
        toast(error.message, true);
    }
}

async function pollExecution(executionId) {
    clearInterval(state.polling);
    el('exec-box').hidden = false;
    let attempts = 0;
    const tick = async () => {
        attempts++;
        try {
            const execution = await api(`/api/batch/executions/${executionId}`);
            renderExecution(execution);
            const done = ['COMPLETED', 'FAILED', 'STOPPED', 'ABANDONED'].includes(execution.status);
            if (done || attempts > 60) {
                clearInterval(state.polling);
                if (done) {
                    toast(`ジョブ ${execution.status}: execution ${execution.executionId}`);
                    await Promise.all([loadExecutions(), loadOrders(), loadProducts(), loadInvoices()]);
                }
            }
        } catch (error) {
            clearInterval(state.polling);
            toast(error.message, true);
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
        ? emptyBox('ジョブ実行履歴はありません。')
        : table(['Execution', 'ジョブ', 'ステータス', '開始', '終了'], executions.map((execution) => html`
            <tr>
                <td><span class="mono">${execution.executionId}</span></td>
                <td>${execution.jobName}</td>
                <td>${statusBadge(execution.status)}</td>
                <td>${execution.startTime || '—'}</td>
                <td>${execution.endTime || '—'}</td>
            </tr>`)), el('executions-body'));
}

const pagers = {
    orders: { get state() { return state.orders; }, load: loadOrders },
    products: { get state() { return state.productsPage; }, load: loadProductPage },
    invoices: { get state() { return state.invoices; }, load: loadInvoices }
};

function goPage(name, direction) {
    const pagerState = pagers[name];
    if (!pagerState) {
        return;
    }
    const pageState = pagerState.state;
    if (direction === 'prev' && !pageState.first) {
        pageState.page--;
    } else if (direction === 'next' && !pageState.last) {
        pageState.page++;
    } else {
        return;
    }
    pagerState.load().catch(showError);
}

document.querySelectorAll('nav button').forEach((button) => {
    button.addEventListener('click', () => switchTab(button.dataset.tab));
});
el('add-line').addEventListener('click', addLineRow);
el('order-form').addEventListener('submit', createOrder);

// The static buttons in index.html keep data-action; rows and the pager bind their
// handlers in the template, so no inline handler is ever parsed by the browser.
const clickActions = {
    'logout': () => logout(),
    'reload-orders': () => loadOrders().catch(showError),
    'reload-products': () => loadProducts().catch(showError),
    'reload-invoices': () => loadInvoices().catch(showError),
    'reload-executions': () => loadExecutions().catch(showError),
    'start-invoice-job': () => startInvoiceJob(),
    'start-import-job': () => startImportJob()
};

document.addEventListener('click', (event) => {
    if (!(event.target instanceof Element)) return;
    const button = event.target.closest('[data-action]');
    if (!button) return;
    const handler = clickActions[button.dataset.action];
    if (handler) handler(button);
});

el('orders-status').addEventListener('change', (event) => {
    state.orders.status = event.target.value;
    state.orders.page = 0;
    loadOrders().catch(showError);
});
el('orders-sort').addEventListener('change', (event) => {
    state.orders.sort = event.target.value;
    state.orders.page = 0;
    loadOrders().catch(showError);
});
el('orders-size').addEventListener('change', (event) => {
    state.orders.size = Number(event.target.value);
    state.orders.page = 0;
    loadOrders().catch(showError);
});
el('products-sort').addEventListener('change', (event) => {
    state.productsPage.sort = event.target.value;
    state.productsPage.page = 0;
    loadProductPage().catch(showError);
});
el('products-size').addEventListener('change', (event) => {
    state.productsPage.size = Number(event.target.value);
    state.productsPage.page = 0;
    loadProductPage().catch(showError);
});
el('invoices-sort').addEventListener('change', (event) => {
    state.invoices.sort = event.target.value;
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});
el('invoices-size').addEventListener('change', (event) => {
    state.invoices.size = Number(event.target.value);
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});
el('invoice-month').addEventListener('change', () => {
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});

(async function init() {
    const month = previousMonth();
    el('invoice-month').value = month;
    el('batch-month').value = month;
    addLineRow();
    try {
        await loadMe();
        const tasks = [loadCustomers(), loadProducts(), loadOrders(), loadInvoices()];
        if (state.isAdmin) {
            tasks.push(loadExecutions());
        }
        await Promise.all(tasks);
    } catch (error) {
        if (error.message !== '認証が必要です') {
            toast(`初期化に失敗しました: ${error.message}`, true);
        }
    }
})();
