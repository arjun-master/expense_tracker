const appState = {
  expenses: [],
  summary: null,
  loading: true,
  submitting: false,
};

const pendingDeleteIds = new Set();

const palette = [
  '#167d86',
  '#2f8f61',
  '#0f5e7a',
  '#4d8f62',
  '#c34242',
  '#6a8f3d',
  '#2b8aa8',
  '#746f11',
];

const nodes = {
  statsGrid: document.getElementById('statsGrid'),
  categoryChart: document.getElementById('categoryChart'),
  categoryLegend: document.getElementById('categoryLegend'),
  chartTotal: document.getElementById('chartTotal'),
  monthList: document.getElementById('monthList'),
  expenseRows: document.getElementById('expenseRows'),
  expenseForm: document.getElementById('expenseForm'),
  refreshButton: document.getElementById('refreshButton'),
  featureStack: document.getElementById('featureStack'),
  submitButton: document.getElementById('submitButton'),
  loadState: document.getElementById('loadState'),
  updateState: document.getElementById('updateState'),
  tableMeta: document.getElementById('tableMeta'),
  emptyState: document.getElementById('emptyState'),
  toast: document.getElementById('toast'),
  formHint: document.getElementById('formHint'),
  date: document.getElementById('date'),
  category: document.getElementById('category'),
  merchant: document.getElementById('merchant'),
  amount: document.getElementById('amount'),
  notes: document.getElementById('notes'),
};

function formatMoney(value) {
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  }).format(Number(value || 0));
}

function formatDate(value) {
  if (!value) return '—';
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value));
  const date = dateOnly
    ? new Date(Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3])))
    : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: dateOnly ? 'UTC' : undefined,
  }).format(date);
}

function escapeText(value) {
  return String(value ?? '').replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  })[character]);
}

function localDateValue(date = new Date()) {
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

function setToast(message, type = '') {
  nodes.toast.textContent = message;
  nodes.toast.className = `toast ${type}`.trim();
  nodes.toast.classList.remove('hidden');
  window.clearTimeout(setToast.timer);
  setToast.timer = window.setTimeout(() => nodes.toast.classList.add('hidden'), 3200);
}

function publishDataState() {
  document.dispatchEvent(new CustomEvent('expense-ledger:data', {
    detail: {
      expenses: appState.expenses,
      summary: appState.summary || summaryFromExpenses(appState.expenses),
    },
  }));
}

function registerFeaturePanel(id, title, eyebrow = 'Feature') {
  let panel = document.getElementById(id);
  if (panel) {
    return panel;
  }
  panel = document.createElement('section');
  panel.className = 'panel feature-panel';
  panel.id = id;
  panel.innerHTML = `
    <div class="panel-header">
      <div>
        <p class="eyebrow"></p>
        <h2></h2>
      </div>
    </div>
    <div class="feature-panel-body"></div>
  `;
  panel.querySelector('.eyebrow').textContent = eyebrow;
  panel.querySelector('h2').textContent = title;
  nodes.featureStack.append(panel);
  return panel;
}

function setLoading(isLoading) {
  appState.loading = isLoading;
  document.body.classList.toggle('is-loading', isLoading);
  nodes.loadState.textContent = isLoading ? 'Loading data' : 'Data ready';
  nodes.updateState.textContent = isLoading ? 'Refreshing dashboard' : `Updated ${new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date())}`;
  nodes.refreshButton.disabled = isLoading;
}

function summaryFromExpenses(expenses) {
  const total = expenses.reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const count = expenses.length;
  const average = count ? total / count : 0;
  const byCategoryMap = new Map();
  const byMonthMap = new Map();

  for (const item of expenses) {
    const category = item.category || 'Uncategorized';
    const month = item.date ? String(item.date).slice(0, 7) : 'Unknown';
    const amount = Number(item.amount || 0);

    const categoryEntry = byCategoryMap.get(category) || { category, total: 0, count: 0 };
    categoryEntry.total += amount;
    categoryEntry.count += 1;
    byCategoryMap.set(category, categoryEntry);

    const monthEntry = byMonthMap.get(month) || { month, total: 0, count: 0 };
    monthEntry.total += amount;
    monthEntry.count += 1;
    byMonthMap.set(month, monthEntry);
  }

  const byCategory = Array.from(byCategoryMap.values())
    .sort((a, b) => b.total - a.total)
    .map((item) => ({
      ...item,
      percentage: total ? (item.total / total) * 100 : 0,
    }));

  const byMonth = Array.from(byMonthMap.values())
    .sort((a, b) => a.month.localeCompare(b.month));

  return {
    total,
    count,
    average,
    byCategory,
    byMonth,
    recent: [...expenses].sort((a, b) => new Date(b.createdAt || b.date || 0) - new Date(a.createdAt || a.date || 0)).slice(0, 5),
  };
}

function renderStats(summary) {
  const cards = [
    { label: 'Total spend', value: formatMoney(summary.total), note: `${summary.count} entries` },
    { label: 'Average expense', value: formatMoney(summary.average), note: 'Across all records' },
    { label: 'Expense count', value: String(summary.count), note: 'Tracked transactions' },
    { label: 'Top category', value: summary.byCategory[0]?.category || 'None', note: summary.byCategory[0] ? formatMoney(summary.byCategory[0].total) : 'No activity yet' },
  ];

  nodes.statsGrid.innerHTML = cards.map((card) => `
    <article class="stat">
      <div class="stat-label">${escapeText(card.label)}</div>
      <div class="stat-value">${escapeText(card.value)}</div>
      <div class="stat-note">${escapeText(card.note)}</div>
    </article>
  `).join('');
}

function polarToCartesian(cx, cy, r, angle) {
  const radians = (angle - 90) * Math.PI / 180;
  return {
    x: cx + (r * Math.cos(radians)),
    y: cy + (r * Math.sin(radians)),
  };
}

function describeArc(cx, cy, r, startAngle, endAngle) {
  const start = polarToCartesian(cx, cy, r, endAngle);
  const end = polarToCartesian(cx, cy, r, startAngle);
  const largeArcFlag = endAngle - startAngle <= 180 ? '0' : '1';
  return [
    'M', start.x, start.y,
    'A', r, r, 0, largeArcFlag, 0, end.x, end.y,
  ].join(' ');
}

function renderChart(summary) {
  const items = summary.byCategory;
  const total = summary.total || 0;
  nodes.chartTotal.textContent = formatMoney(total);

  if (!items.length || !total) {
    nodes.categoryChart.innerHTML = `
      <circle cx="120" cy="120" r="84" fill="none" stroke="rgba(87,112,122,0.18)" stroke-width="28"></circle>
      <text x="120" y="124" text-anchor="middle" fill="#58707a" font-size="14">No category data</text>
    `;
    nodes.categoryLegend.innerHTML = '';
    return;
  }

  if (items.length === 1) {
    nodes.categoryChart.innerHTML = `
      <circle cx="120" cy="120" r="84" fill="none" stroke="${palette[0]}" stroke-width="28"></circle>
    `;
    nodes.categoryLegend.innerHTML = `
      <span class="legend-item">
        <i class="legend-swatch" style="background:${palette[0]}"></i>
        ${escapeText(items[0].category)} ${formatMoney(items[0].total)}
      </span>
    `;
    return;
  }

  let start = 0;
  const arcs = items.map((item, index) => {
    const fraction = item.total / total;
    const angle = fraction * 360;
    const end = start + angle;
    const path = describeArc(120, 120, 84, start, end);
    const color = palette[index % palette.length];
    start = end;
    return `<path d="${path}" fill="none" stroke="${color}" stroke-width="28" stroke-linecap="butt"></path>`;
  }).join('');

  nodes.categoryChart.innerHTML = `
    <circle cx="120" cy="120" r="70" fill="none" stroke="rgba(87,112,122,0.12)" stroke-width="1"></circle>
    ${arcs}
  `;

  nodes.categoryLegend.innerHTML = items.map((item, index) => `
    <span class="legend-item">
      <i class="legend-swatch" style="background:${palette[index % palette.length]}"></i>
      ${escapeText(item.category)} ${formatMoney(item.total)}
    </span>
  `).join('');
}

function renderMonths(summary) {
  const months = summary.byMonth;
  if (!months.length) {
    nodes.monthList.innerHTML = '<div class="empty-state">No monthly activity yet.</div>';
    return;
  }

  const maxTotal = Math.max(...months.map((item) => item.total), 1);
  nodes.monthList.innerHTML = months.slice(-6).reverse().map((item) => {
    const width = Math.max((item.total / maxTotal) * 100, 6);
    const label = item.month === 'Unknown'
      ? 'Unknown'
      : new Intl.DateTimeFormat(undefined, { month: 'short', year: 'numeric', timeZone: 'UTC' })
        .format(new Date(`${item.month}-01T00:00:00Z`));

    return `
      <article class="month-item">
        <div class="month-label">${escapeText(label)}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${width}%"></div></div>
        <div class="month-meta">${formatMoney(item.total)} · ${item.count}</div>
      </article>
    `;
  }).join('');
}

function renderExpenses(expenses) {
  nodes.tableMeta.textContent = `${expenses.length} item${expenses.length === 1 ? '' : 's'}`;
  nodes.emptyState.classList.toggle('hidden', expenses.length !== 0);

  if (!expenses.length) {
    nodes.expenseRows.innerHTML = '';
    return;
  }

  nodes.expenseRows.innerHTML = expenses.map((item) => `
    <tr>
      <td>${escapeText(formatDate(item.date))}</td>
      <td><span class="pill">${escapeText(item.category || 'Uncategorized')}</span></td>
      <td>${escapeText(item.merchant || '—')}</td>
      <td class="money">${escapeText(formatMoney(item.amount))}</td>
      <td class="notes">${escapeText(item.notes || '—')}</td>
      <td>
        <button class="action-btn" data-delete-id="${escapeText(item.id)}" type="button">Delete</button>
      </td>
    </tr>
  `).join('');
}

function syncSummary() {
  const summary = appState.summary || summaryFromExpenses(appState.expenses);
  renderStats(summary);
  renderChart(summary);
  renderMonths(summary);
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
    ...options,
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    const message = (data && typeof data === 'object' && (data.error || data.message)) || (typeof data === 'string' && data) || `Request failed (${response.status})`;
    throw new Error(message);
  }

  return data;
}

async function loadData(options = {}) {
  const { silent = false } = options;
  setLoading(true);
  nodes.updateState.textContent = 'Refreshing dashboard';
  let failed = false;
  try {
    const [expensesResponse, summaryResponse] = await Promise.all([
      fetchJson('/api/expenses'),
      fetchJson('/api/reports/summary'),
    ]);

    appState.expenses = Array.isArray(expensesResponse?.expenses) ? expensesResponse.expenses : [];
    appState.summary = summaryResponse || summaryFromExpenses(appState.expenses);
    syncSummary();
    if (!(window.expenseLedgerFilters && typeof window.expenseLedgerFilters.apply === 'function')) {
      renderExpenses(appState.expenses);
    }
    publishDataState();
    if (!silent) {
      setToast('Dashboard refreshed');
    }
  } catch (error) {
    failed = true;
    appState.summary = summaryFromExpenses(appState.expenses);
    syncSummary();
    if (!(window.expenseLedgerFilters && typeof window.expenseLedgerFilters.apply === 'function')) {
      renderExpenses(appState.expenses);
    }
    publishDataState();
    if (!silent) {
      setToast(error.message || 'Unable to load data', 'error');
    }
  } finally {
    setLoading(false);
    if (failed) {
      nodes.loadState.textContent = 'Load failed';
      nodes.updateState.textContent = 'Check the API connection';
    }
  }
}

function readForm() {
  return {
    date: nodes.date.value,
    category: nodes.category.value.trim(),
    merchant: nodes.merchant.value.trim(),
    amount: Number(nodes.amount.value),
    notes: nodes.notes.value.trim(),
  };
}

function validateForm(payload) {
  if (!payload.date) return 'Date is required';
  if (!payload.category) return 'Category is required';
  if (!payload.merchant) return 'Merchant is required';
  if (!Number.isFinite(payload.amount) || payload.amount <= 0) return 'Amount must be greater than zero';
  return '';
}

async function submitExpense(event) {
  event.preventDefault();
  if (appState.submitting) return;

  const payload = readForm();
  const validationMessage = validateForm(payload);
  if (validationMessage) {
    setToast(validationMessage, 'error');
    return;
  }

  appState.submitting = true;
  nodes.submitButton.disabled = true;
  nodes.submitButton.textContent = 'Saving';

  try {
    const created = await fetchJson('/api/expenses', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    if (created && created.id) {
      appState.expenses = [created, ...appState.expenses];
    }

    nodes.expenseForm.reset();
    nodes.date.value = localDateValue();
    setToast('Expense saved');
    await loadData({ silent: true });
  } catch (error) {
    setToast(error.message || 'Unable to save expense', 'error');
  } finally {
    appState.submitting = false;
    nodes.submitButton.disabled = false;
    nodes.submitButton.textContent = 'Save expense';
  }
}

async function deleteExpense(id) {
  if (pendingDeleteIds.has(id)) {
    return;
  }

  pendingDeleteIds.add(id);
  try {
    nodes.updateState.textContent = 'Deleting expense';
    await fetchJson(`/api/expenses/${encodeURIComponent(id)}`, { method: 'DELETE' });
    setToast('Expense deleted');
    await loadData({ silent: true });
  } catch (error) {
    setToast(error.message || 'Unable to delete expense', 'error');
  } finally {
    pendingDeleteIds.delete(id);
  }
}

function handleTableClick(event) {
  const button = event.target.closest('[data-delete-id]');
  if (!button) return;
  const { deleteId } = button.dataset;
  if (!deleteId || pendingDeleteIds.has(deleteId)) return;

  const row = button.closest('tr');
  const previousDisabled = button.disabled;
  const previousLabel = button.textContent;

  if (row) {
    row.classList.add('skeleton');
    row.style.opacity = '0.7';
  }

  button.disabled = true;
  button.textContent = 'Deleting';

  deleteExpense(deleteId).finally(() => {
    button.disabled = previousDisabled;
    button.textContent = previousLabel;
    if (row) {
      row.classList.remove('skeleton');
      row.style.opacity = '';
    }
  });
}

function initDefaults() {
  nodes.date.value = localDateValue();
}

nodes.expenseForm.addEventListener('submit', submitExpense);
nodes.refreshButton.addEventListener('click', loadData);
nodes.expenseRows.addEventListener('click', handleTableClick);

window.expenseLedger = {
  state: appState,
  nodes,
  fetchJson,
  formatMoney,
  formatDate,
  escapeText,
  loadData,
  publishDataState,
  registerFeaturePanel,
  renderExpenses,
  setToast,
  summaryFromExpenses,
};

initDefaults();
loadData({ silent: true });
