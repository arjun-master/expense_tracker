(function () {
  const STORAGE_KEY = 'expense-ledger.category-budgets.v1';
  const NEAR_THRESHOLD = 0.8;
  const LOAD_RETRY_MS = 25;

  const state = {
    initialized: false,
    panel: null,
    body: null,
    form: null,
    categoryInput: null,
    amountInput: null,
    saveButton: null,
    metaRow: null,
    overviewRow: null,
    alerts: null,
    list: null,
    emptyState: null,
    currentMonthLabel: '',
    currentMonthKey: '',
    currentMonthBudgets: {},
    expenses: [],
    summary: null,
  };

  let pendingData = null;
  let storageFallback = {};

  function localMonthKey(date = new Date()) {
    const offset = date.getTimezoneOffset() * 60000;
    return new Date(date.getTime() - offset).toISOString().slice(0, 7);
  }

  function currentMonthLabel(monthKey) {
    return new Intl.DateTimeFormat(undefined, {
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${monthKey}-01T00:00:00Z`));
  }

  function normalizeCategory(value) {
    return String(value ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
  }

  function formatPercent(value) {
    return `${Math.round(value)}%`;
  }

  function ensureStylesheet() {
    if (document.querySelector('link[data-expense-ledger-budgets="true"]')) {
      return;
    }

    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'budgets.css';
    link.dataset.expenseLedgerBudgets = 'true';
    document.head.append(link);
  }

  function storageAvailable() {
    try {
      const probe = '__expense_ledger_probe__';
      window.localStorage.setItem(probe, '1');
      window.localStorage.removeItem(probe);
      return true;
    } catch {
      return false;
    }
  }

  function readStore() {
    if (!storageAvailable()) {
      return storageFallback;
    }

    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      if (!raw) return {};
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  }

  function writeStore(data) {
    if (!storageAvailable()) {
      storageFallback = data;
      return;
    }

    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  }

  function loadMonthBudgets(monthKey) {
    const store = readStore();
    const monthBudgets = store[monthKey];
    if (!monthBudgets || typeof monthBudgets !== 'object') {
      return {};
    }
    return monthBudgets;
  }

  function saveMonthBudgets(monthKey, monthBudgets) {
    const store = readStore();
    if (Object.keys(monthBudgets).length === 0) {
      delete store[monthKey];
    } else {
      store[monthKey] = monthBudgets;
    }
    writeStore(store);
  }

  function currentMonthSpendMap(expenses, monthKey) {
    const map = new Map();
    for (const expense of expenses) {
      if (!expense || typeof expense.date !== 'string' || expense.date.slice(0, 7) !== monthKey) {
        continue;
      }
      const key = normalizeCategory(expense.category || 'Uncategorized');
      const amount = Number(expense.amount || 0);
      map.set(key, (map.get(key) || 0) + amount);
    }
    return map;
  }

  function formatMoneyCompact(value) {
    return window.expenseLedger.formatMoney(Number(value || 0));
  }

  function createChip(text, className = 'budget-chip') {
    const chip = document.createElement('span');
    chip.className = className;
    chip.textContent = text;
    return chip;
  }

  function setText(node, value) {
    node.textContent = value;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function createOverviewItem(label, value, note) {
    const item = document.createElement('article');
    item.className = 'budget-overview-item';

    const labelNode = document.createElement('div');
    labelNode.className = 'budget-overview-label';
    labelNode.textContent = label;

    const valueNode = document.createElement('div');
    valueNode.className = 'budget-overview-value';
    valueNode.textContent = value;

    const noteNode = document.createElement('div');
    noteNode.className = 'budget-overview-note';
    noteNode.textContent = note;

    item.append(labelNode, valueNode, noteNode);
    return item;
  }

  function statusFor(spent, budget) {
    if (budget <= 0) {
      return { label: 'No budget', className: 'is-muted', kind: 'muted' };
    }
    const ratio = spent / budget;
    if (ratio > 1) {
      return { label: 'Over budget', className: 'is-over', kind: 'over' };
    }
    if (ratio >= NEAR_THRESHOLD) {
      return { label: 'Near budget', className: 'is-near', kind: 'near' };
    }
    if (spent <= 0) {
      return { label: 'No spend yet', className: 'is-muted', kind: 'muted' };
    }
    return { label: 'On track', className: 'is-good', kind: 'good' };
  }

  function render() {
    if (!state.initialized) {
      return;
    }

    const spendMap = currentMonthSpendMap(state.expenses, state.currentMonthKey);
    const budgets = Object.entries(state.currentMonthBudgets)
      .map(([key, entry]) => {
        const spent = Number(spendMap.get(key) || 0);
        const budget = Number(entry.amount || 0);
        const ratio = budget > 0 ? spent / budget : 0;
        const status = statusFor(spent, budget);
        return {
          key,
          label: entry.label || key,
          spent,
          budget,
          ratio,
          status,
        };
      })
      .sort((a, b) => {
        const severity = { over: 0, near: 1, good: 2, muted: 3 };
        const left = severity[a.status.kind] ?? 4;
        const right = severity[b.status.kind] ?? 4;
        if (left !== right) return left - right;
        return b.ratio - a.ratio || a.label.localeCompare(b.label);
      });

    const totalBudget = budgets.reduce((sum, item) => sum + item.budget, 0);
    const totalSpent = budgets.reduce((sum, item) => sum + item.spent, 0);
    const overCount = budgets.filter((item) => item.status.kind === 'over').length;
    const nearCount = budgets.filter((item) => item.status.kind === 'near').length;
    const budgetedCount = budgets.length;

    state.overviewRow.replaceChildren(
      createOverviewItem('Budgets', String(budgetedCount), `${state.currentMonthLabel} plan`),
      createOverviewItem('Spent', formatMoneyCompact(totalSpent), 'Across budgeted categories'),
      createOverviewItem('Budget', formatMoneyCompact(totalBudget), 'Monthly cap'),
      createOverviewItem('Alerts', `${overCount} over · ${nearCount} near`, 'Monitor these first'),
    );

    clear(state.alerts);
    if (!budgets.length) {
      const alert = createChip('No category budgets set for this month.', 'budget-alert budget-alert-muted');
      state.alerts.append(alert);
    } else {
      const alertItems = budgets.filter((item) => item.status.kind === 'over' || item.status.kind === 'near');
      if (!alertItems.length) {
        state.alerts.append(createChip('All tracked budgets are on pace.', 'budget-alert budget-alert-good'));
      } else {
        for (const item of alertItems.slice(0, 4)) {
          const label = `${item.label}: ${item.status.label}`;
          state.alerts.append(createChip(label, `budget-alert budget-alert-${item.status.kind}`));
        }
      }
    }

    clear(state.list);
    state.emptyState.hidden = budgets.length !== 0;

    for (const item of budgets) {
      const row = document.createElement('article');
      row.className = `budget-row ${item.status.className}`;

      const heading = document.createElement('div');
      heading.className = 'budget-row-heading';

      const titleWrap = document.createElement('div');
      titleWrap.className = 'budget-row-titlewrap';

      const title = document.createElement('div');
      title.className = 'budget-row-title';
      title.textContent = item.label;

      const subtitle = document.createElement('div');
      subtitle.className = 'budget-row-subtitle';
      subtitle.textContent = `${formatMoneyCompact(item.spent)} spent this month`;

      titleWrap.append(title, subtitle);

      const controls = document.createElement('div');
      controls.className = 'budget-row-controls';

      const statusChip = createChip(item.status.label, `budget-status-chip ${item.status.className}`);
      controls.append(statusChip);

      const removeButton = document.createElement('button');
      removeButton.type = 'button';
      removeButton.className = 'budget-remove-btn';
      removeButton.textContent = 'Remove';
      removeButton.dataset.categoryKey = item.key;
      controls.append(removeButton);

      heading.append(titleWrap, controls);

      const meter = document.createElement('div');
      meter.className = 'budget-meter';
      meter.setAttribute('aria-hidden', 'true');

      const fill = document.createElement('div');
      fill.className = `budget-meter-fill ${item.status.className}`;
      fill.style.width = `${Math.min(item.ratio * 100, 100)}%`;
      meter.append(fill);

      const meta = document.createElement('div');
      meta.className = 'budget-row-meta';

      const spendText = document.createElement('span');
      spendText.textContent = `Spend ${formatMoneyCompact(item.spent)}`;

      const budgetText = document.createElement('span');
      budgetText.textContent = `Budget ${formatMoneyCompact(item.budget)}`;

      const remainingText = document.createElement('span');
      if (item.budget > 0) {
        const remaining = item.budget - item.spent;
        remainingText.textContent = item.spent > item.budget
          ? `Over by ${formatMoneyCompact(Math.abs(remaining))}`
          : `Remaining ${formatMoneyCompact(remaining)}`;
      } else {
        remainingText.textContent = 'Set a budget to start tracking';
      }

      meta.append(spendText, budgetText, remainingText);

      row.append(heading, meter, meta);
      state.list.append(row);
    }
  }

  function readForm() {
    return {
      category: state.categoryInput.value.trim(),
      amount: Number(state.amountInput.value),
    };
  }

  function validateForm(payload) {
    if (!payload.category) return 'Category is required';
    if (!Number.isFinite(payload.amount) || payload.amount <= 0) return 'Budget amount must be greater than zero';
    return '';
  }

  function persistBudget(payload) {
    const monthBudgets = { ...state.currentMonthBudgets };
    const key = normalizeCategory(payload.category);
    monthBudgets[key] = {
      label: payload.category,
      amount: payload.amount,
      updatedAt: new Date().toISOString(),
    };
    saveMonthBudgets(state.currentMonthKey, monthBudgets);
    state.currentMonthBudgets = monthBudgets;
  }

  function handleSubmit(event) {
    event.preventDefault();

    const payload = readForm();
    const validationMessage = validateForm(payload);
    if (validationMessage) {
      window.expenseLedger.setToast(validationMessage, 'error');
      return;
    }

    persistBudget(payload);
    state.categoryInput.value = '';
    state.amountInput.value = '';
    state.categoryInput.focus();
    window.expenseLedger.setToast(`Budget saved for ${payload.category}`);
    render();
  }

  function handleClick(event) {
    const button = event.target.closest('[data-category-key]');
    if (!button) return;

    const key = button.dataset.categoryKey;
    if (!key) return;

    const monthBudgets = { ...state.currentMonthBudgets };
    if (!monthBudgets[key]) return;

    delete monthBudgets[key];
    saveMonthBudgets(state.currentMonthKey, monthBudgets);
    state.currentMonthBudgets = monthBudgets;
    window.expenseLedger.setToast('Budget removed');
    render();
  }

  function handleData(event) {
    pendingData = event.detail || null;
    if (!state.initialized) {
      return;
    }

    state.expenses = Array.isArray(pendingData?.expenses) ? pendingData.expenses : [];
    state.summary = pendingData?.summary || null;
    render();
  }

  function buildPanel() {
    const panel = window.expenseLedger.registerFeaturePanel(
      'categoryBudgetsPanel',
      'Category budgets',
      'Planning',
    );
    panel.classList.add('budget-panel');

    const body = panel.querySelector('.feature-panel-body');
    body.classList.add('budget-panel-body');

    const monthMeta = document.createElement('div');
    monthMeta.className = 'budget-month-meta';

    const monthLabel = document.createElement('p');
    monthLabel.className = 'budget-month-label';
    monthLabel.textContent = `Current month: ${state.currentMonthLabel}`;

	    const monthHint = document.createElement('p');
	    monthHint.className = 'budget-month-hint';
	    monthHint.textContent = 'Set category targets for the current month.';

    monthMeta.append(monthLabel, monthHint);

    const overviewRow = document.createElement('div');
    overviewRow.className = 'budget-overview';

    const alerts = document.createElement('div');
    alerts.className = 'budget-alert-row';

    const form = document.createElement('form');
    form.className = 'budget-form';
    form.noValidate = true;

    const categoryLabel = document.createElement('label');
    categoryLabel.className = 'budget-field';

    const categorySpan = document.createElement('span');
    categorySpan.textContent = 'Category';

    const categoryInput = document.createElement('input');
    categoryInput.type = 'text';
    categoryInput.maxLength = 40;
    categoryInput.placeholder = 'Groceries';
    categoryInput.required = true;

    categoryLabel.append(categorySpan, categoryInput);

    const amountLabel = document.createElement('label');
    amountLabel.className = 'budget-field';

    const amountSpan = document.createElement('span');
    amountSpan.textContent = 'Monthly budget';

    const amountInput = document.createElement('input');
    amountInput.type = 'number';
    amountInput.min = '0.01';
    amountInput.step = '0.01';
    amountInput.inputMode = 'decimal';
    amountInput.placeholder = '0.00';
    amountInput.required = true;

    amountLabel.append(amountSpan, amountInput);

    const saveButton = document.createElement('button');
    saveButton.type = 'submit';
    saveButton.className = 'btn btn-primary budget-save-btn';
    saveButton.textContent = 'Save budget';

	    const hint = document.createElement('p');
	    hint.className = 'budget-form-hint';
	    hint.textContent = 'Save the same category again to update its target.';

    form.append(categoryLabel, amountLabel, saveButton, hint);

    const list = document.createElement('div');
    list.className = 'budget-list';

    const emptyState = document.createElement('div');
    emptyState.className = 'budget-empty';
    emptyState.textContent = 'No budgets have been set for this month.';

    body.append(monthMeta, overviewRow, alerts, form, list, emptyState);

    state.panel = panel;
    state.body = body;
    state.form = form;
    state.categoryInput = categoryInput;
    state.amountInput = amountInput;
    state.saveButton = saveButton;
    state.metaRow = monthMeta;
    state.overviewRow = overviewRow;
    state.alerts = alerts;
    state.list = list;
    state.emptyState = emptyState;
  }

  function init() {
    if (state.initialized) {
      return;
    }

    if (!window.expenseLedger || typeof window.expenseLedger.registerFeaturePanel !== 'function') {
      window.setTimeout(init, LOAD_RETRY_MS);
      return;
    }

    ensureStylesheet();
    state.currentMonthKey = localMonthKey();
    state.currentMonthLabel = currentMonthLabel(state.currentMonthKey);
    state.currentMonthBudgets = loadMonthBudgets(state.currentMonthKey);

    buildPanel();

    state.form.addEventListener('submit', handleSubmit);
    state.list.addEventListener('click', handleClick);
    document.addEventListener('expense-ledger:data', handleData);

    state.initialized = true;

    if (pendingData) {
      state.expenses = Array.isArray(pendingData.expenses) ? pendingData.expenses : [];
      state.summary = pendingData.summary || null;
    } else if (window.expenseLedger.state) {
      state.expenses = Array.isArray(window.expenseLedger.state.expenses) ? window.expenseLedger.state.expenses : [];
      state.summary = window.expenseLedger.state.summary || null;
    }

    render();
  }

  init();
})();
