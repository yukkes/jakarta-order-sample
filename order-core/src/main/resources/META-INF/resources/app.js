const state = {
    products: [],
    allProducts: [],
    customers: [],
    polling: null,
    isAdmin: false,
    orders: { page: 0, size: 10, sort: 'id,desc', status: '', totalPages: 0, totalElements: 0, first: true, last: true },
    productsPage: { page: 0, size: 10, sort: 'code,asc', totalPages: 0, totalElements: 0, first: true, last: true },
    invoices: { page: 0, size: 10, sort: 'id,desc', totalPages: 0, totalElements: 0, first: true, last: true }
};

const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' });
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[c]);

async function api(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const csrf = getCookie('XSRF-TOKEN');
        if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    }
    const response = await fetch(path, { ...options, credentials: 'same-origin', headers });
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

async function loadMe() {
    const status = await api('/api/auth/status');
    if (!status.authenticated) {
        window.location.replace('/login.html');
        throw new Error('認証が必要です');
    }
    const roles = status.roles || [];
    state.isAdmin = roles.includes('ADMIN');
    document.getElementById('current-user').textContent = status.username;
    document.getElementById('current-role').textContent = roles.join(' · ');
    document.getElementById('user-avatar').textContent = (status.username || '?').charAt(0).toUpperCase();

    const batchNav = document.querySelector('nav button[data-tab="batch"]');
    if (batchNav) batchNav.hidden = !state.isAdmin;
    if (!state.isAdmin) {
        const batchTab = document.getElementById('tab-batch');
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
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = 'toast show' + (isError ? ' error' : '');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { el.className = 'toast'; }, 3800);
}

function previousMonth() {
    const now = new Date();
    const date = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function statusBadge(status) {
    return `<span class="badge ${esc(status)}">${esc(status)}</span>`;
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

function pager(pageState, name) {
    return `<div class="pager">
        <button class="ghost small" data-action="page" data-pager="${name}" data-direction="prev" ${pageState.first ? 'disabled' : ''}>前へ</button>
        <span class="page-info">${pageState.page + 1} / ${Math.max(pageState.totalPages, 1)} ページ（${pageState.totalElements} 件）</span>
        <button class="ghost small" data-action="page" data-pager="${name}" data-direction="next" ${pageState.last ? 'disabled' : ''}>次へ</button>
    </div>`;
}

const showError = (error) => toast(error.message, true);

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
    const select = document.getElementById('customer');
    select.innerHTML = state.customers
        .map((customer) => `<option value="${esc(customer.code)}">${esc(customer.code)} — ${esc(customer.name)}</option>`)
        .join('');
}

async function loadAllProducts() {
    const page = await api('/api/products?page=0&size=100&sort=code,asc');
    state.allProducts = page.content || [];
    document.querySelectorAll('.product-select').forEach(renderProductOptions);
}

async function loadProductPage() {
    const page = await api(`/api/products?${pageQuery(state.productsPage)}`);
    state.products = applyPage(state.productsPage, page);
    const body = document.getElementById('products-body');
    body.innerHTML = (state.products.length === 0
        ? '<div class="empty">商品はありません。</div>'
        : table(
            ['コード', '商品名', '単価', '在庫'],
            state.products.map((product) => [
                `<span class="mono">${esc(product.code)}</span>`,
                esc(product.name),
                `<span class="num">${yen.format(product.unitPrice)}</span>`,
                `<span class="num">${product.stock}</span>`
            ])
        )) + pager(state.productsPage, 'products');
}

async function loadProducts() {
    await loadAllProducts();
    await loadProductPage();
}

function renderProductOptions(select) {
    const current = select.value;
    select.innerHTML = state.allProducts
        .map((product) => `<option value="${esc(product.code)}">${esc(product.code)} — ${esc(product.name)} (${yen.format(product.unitPrice)})</option>`)
        .join('');
    if (current) select.value = current;
}

let lineSequence = 0;

function addLineRow() {
    lineSequence += 1;
    const productId = `line-product-${lineSequence}`;
    const quantityId = `line-quantity-${lineSequence}`;
    const container = document.getElementById('lines');
    const row = document.createElement('div');
    row.className = 'line-row';
    row.innerHTML = `
        <div class="field">
            <label for="${productId}">商品</label>
            <select id="${productId}" class="product-select"></select>
        </div>
        <div class="field field-quantity">
            <label for="${quantityId}">数量</label>
            <input id="${quantityId}" type="number" class="quantity" value="1" min="1" step="1">
        </div>
        <button type="button" class="danger small">削除</button>`;
    row.querySelector('button').addEventListener('click', () => {
        if (container.children.length > 1) row.remove();
    });
    container.appendChild(row);
    renderProductOptions(row.querySelector('.product-select'));
}

async function loadOrders() {
    const page = await api(`/api/orders?${pageQuery(state.orders, { status: state.orders.status })}`);
    const orders = applyPage(state.orders, page);
    document.getElementById('orders-body').innerHTML = (orders.length === 0
        ? '<div class="empty">注文はまだありません。</div>'
        : table(
            ['注文番号', '顧客', '注文日', 'ステータス', '金額', '操作'],
            orders.map((order) => [
                `<span class="mono">${esc(order.orderNo)}</span>`,
                esc(order.customerName),
                esc(order.orderDate),
                statusBadge(order.status),
                `<span class="num">${yen.format(order.totalAmount)}</span>`,
                actions(order)
            ])
        )) + pager(state.orders, 'orders');
}

function actions(order) {
    const buttons = [];
    if (order.status === 'DRAFT') {
        buttons.push(`<button class="small" data-action="confirm-order" data-id="${order.id}">確定</button>`);
    }
    if (order.status === 'DRAFT' || order.status === 'CONFIRMED') {
        buttons.push(`<button class="danger small" data-action="cancel-order" data-id="${order.id}">取消</button>`);
    }
    return buttons.join(' ') || '<span class="empty">—</span>';
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
    const lines = [...document.querySelectorAll('#lines .line-row')].map((row) => ({
        productCode: row.querySelector('.product-select').value,
        quantity: Number(row.querySelector('.quantity').value)
    }));
    const body = {
        customerCode: document.getElementById('customer').value,
        orderDate: document.getElementById('order-date').value || null,
        lines
    };
    try {
        const created = await api('/api/orders', { method: 'POST', body: JSON.stringify(body) });
        toast(`注文を作成しました: ${created.orderNo}`);
        document.getElementById('lines').innerHTML = '';
        addLineRow();
        await loadOrders();
    } catch (error) {
        toast(error.message, true);
    }
}

async function loadInvoices() {
    const month = document.getElementById('invoice-month').value;
    const page = await api(`/api/invoices?${pageQuery(state.invoices, { month })}`);
    const invoices = applyPage(state.invoices, page);
    document.getElementById('invoices-body').innerHTML = (invoices.length === 0
        ? '<div class="empty">請求書はまだありません。バッチタブから生成してください。</div>'
        : table(
            ['請求書番号', '顧客', '注文番号', '対象月', '税抜', '税額'],
            invoices.map((invoice) => [
                `<span class="mono">${esc(invoice.invoiceNo)}</span>`,
                esc(invoice.customerName),
                `<span class="mono">${esc(invoice.orderNo)}</span>`,
                esc(invoice.billingMonth),
                `<span class="num">${yen.format(invoice.totalAmount)}</span>`,
                `<span class="num">${yen.format(invoice.taxAmount)}</span>`
            ])
        )) + pager(state.invoices, 'invoices');
}

async function startInvoiceJob() {
    const month = document.getElementById('batch-month').value;
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
    const inputFile = document.getElementById('import-file').value.trim();
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
    const box = document.getElementById('exec-box');
    box.hidden = false;
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
    const steps = (execution.steps || []).map((step) => `
        <div class="hint exec-step">
            step <span class="mono">${esc(step.stepName)}</span>:
            read=${step.readCount}, write=${step.writeCount}, commit=${step.commitCount},
            rollback=${step.rollbackCount}, skip=${step.skipCount}
        </div>`).join('');
    document.getElementById('exec-box').innerHTML = `
        <div><strong>execution ${execution.executionId}</strong> — ${esc(execution.jobName)} ${statusBadge(execution.status)}</div>
        <div class="hint">exit=${esc(execution.exitStatus || '—')} / start=${esc(execution.startTime || '—')} / end=${esc(execution.endTime || '—')}</div>
        ${steps}`;
}

async function loadExecutions() {
    const executions = await api('/api/batch/executions?count=10');
    document.getElementById('executions-body').innerHTML = executions.length === 0
        ? '<div class="empty">ジョブ実行履歴はありません。</div>'
        : table(
            ['Execution', 'ジョブ', 'ステータス', '開始', '終了'],
            executions.map((execution) => [
                `<span class="mono">${execution.executionId}</span>`,
                esc(execution.jobName),
                statusBadge(execution.status),
                esc(execution.startTime || '—'),
                esc(execution.endTime || '—')
            ])
        );
}

function table(headers, rows) {
    return `<table><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join('')}</tr></thead>
        <tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
}

const pagers = {
    orders: { get state() { return state.orders; }, load: loadOrders },
    products: { get state() { return state.productsPage; }, load: loadProductPage },
    invoices: { get state() { return state.invoices; }, load: loadInvoices }
};

function goPage(name, direction) {
    const pagerState = pagers[name];
    if (!pagerState) return;
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
document.getElementById('add-line').addEventListener('click', addLineRow);
document.getElementById('order-form').addEventListener('submit', createOrder);

// Single delegated click handler: the markup carries data-action instead of inline
// onclick attributes, so the page works under a CSP without 'unsafe-inline'.
const clickActions = {
    'logout': () => logout(),
    'reload-orders': () => loadOrders().catch(showError),
    'reload-products': () => loadProducts().catch(showError),
    'reload-invoices': () => loadInvoices().catch(showError),
    'reload-executions': () => loadExecutions().catch(showError),
    'start-invoice-job': () => startInvoiceJob(),
    'start-import-job': () => startImportJob(),
    'confirm-order': (button) => confirmOrder(Number(button.dataset.id)),
    'cancel-order': (button) => cancelOrder(Number(button.dataset.id)),
    'page': (button) => goPage(button.dataset.pager, button.dataset.direction)
};

document.addEventListener('click', (event) => {
    if (!(event.target instanceof Element)) return;
    const button = event.target.closest('[data-action]');
    if (!button) return;
    const handler = clickActions[button.dataset.action];
    if (handler) handler(button);
});

document.getElementById('orders-status').addEventListener('change', (event) => {
    state.orders.status = event.target.value;
    state.orders.page = 0;
    loadOrders().catch(showError);
});
document.getElementById('orders-sort').addEventListener('change', (event) => {
    state.orders.sort = event.target.value;
    state.orders.page = 0;
    loadOrders().catch(showError);
});
document.getElementById('orders-size').addEventListener('change', (event) => {
    state.orders.size = Number(event.target.value);
    state.orders.page = 0;
    loadOrders().catch(showError);
});
document.getElementById('products-sort').addEventListener('change', (event) => {
    state.productsPage.sort = event.target.value;
    state.productsPage.page = 0;
    loadProductPage().catch(showError);
});
document.getElementById('products-size').addEventListener('change', (event) => {
    state.productsPage.size = Number(event.target.value);
    state.productsPage.page = 0;
    loadProductPage().catch(showError);
});
document.getElementById('invoices-sort').addEventListener('change', (event) => {
    state.invoices.sort = event.target.value;
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});
document.getElementById('invoices-size').addEventListener('change', (event) => {
    state.invoices.size = Number(event.target.value);
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});
document.getElementById('invoice-month').addEventListener('change', () => {
    state.invoices.page = 0;
    loadInvoices().catch(showError);
});

(async function init() {
    const month = previousMonth();
    document.getElementById('invoice-month').value = month;
    document.getElementById('batch-month').value = month;
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
