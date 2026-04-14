(function () {
  const FEATURE_ID = 'advancedFiltersPanel';
  const STYLE_ID = 'expense-ledger-filters-style';

  const state = {
    expenses: [],
    filters: {
      query: '',
      category: '',
      from: '',
      to: '',
      min: '',
      max: '',
      sort: 'newest',
    },
    panel: null,
    controls: null,
    resultCount: null,
    resultTotal: null,
  };

  function injectStylesheet() {
    if (document.getElementById(STYLE_ID)) return;
    const link = document.createElement('link');
    link.id = STYLE_ID;
    link.rel = 'stylesheet';
    link.href = 'filters.css';
    document.head.appendChild(link);
  }

  function normalizeText(value) {
    return String(value ?? '').trim().toLowerCase();
  }

  function parseAmount(value) {
    if (value === '' || value == null) return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function getExpenseDateValue(expense) {
    const value = expense?.date ? String(expense.date) : '';
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (match) {
      return Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    }
    const timestamp = Date.parse(value);
    return Number.isFinite(timestamp) ? timestamp : Number.NEGATIVE_INFINITY;
  }

  function getSearchBlob(expense) {
    return normalizeText([
      expense?.category,
      expense?.merchant,
      expense?.notes,
    ].filter(Boolean).join(' '));
  }

  function getCategory(expense) {
    return String(expense?.category || 'Uncategorized');
  }

  function getAmount(expense) {
    const amount = Number(expense?.amount);
    return Number.isFinite(amount) ? amount : 0;
  }

  function getMerchant(expense) {
    return String(expense?.merchant || '');
  }

  function buildCategoryOptions(expenses) {
    const categories = Array.from(new Set(expenses.map(getCategory))).sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' }),
    );

    return [{ value: '', label: 'All categories' }, ...categories.map((category) => ({
      value: category,
      label: category,
    }))];
  }

  function createLabel(text, fieldId) {
    const label = document.createElement('label');
    label.className = 'expense-filter-field';
    if (fieldId) label.htmlFor = fieldId;

    const span = document.createElement('span');
    span.className = 'expense-filter-label';
    span.textContent = text;
    label.appendChild(span);
    return label;
  }

  function createInput(type, id, placeholder) {
    const input = document.createElement('input');
    input.type = type;
    input.id = id;
    input.className = 'expense-filter-control';
    if (placeholder) input.placeholder = placeholder;
    return input;
  }

  function createSelect(id) {
    const select = document.createElement('select');
    select.id = id;
    select.className = 'expense-filter-control';
    return select;
  }

  function syncCategoryOptions(expenses) {
    if (!state.controls?.category) return;

    const currentValue = state.controls.category.value;
    const options = buildCategoryOptions(expenses);

    while (state.controls.category.firstChild) {
      state.controls.category.removeChild(state.controls.category.firstChild);
    }

    for (const option of options) {
      const el = document.createElement('option');
      el.value = option.value;
      el.textContent = option.label;
      state.controls.category.appendChild(el);
    }

    if (options.some((option) => option.value === currentValue)) {
      state.controls.category.value = currentValue;
    } else {
      state.controls.category.value = '';
      state.filters.category = '';
    }
  }

  function readFiltersFromUI() {
    if (!state.controls) return;

    state.filters.query = normalizeText(state.controls.query.value);
    state.filters.category = state.controls.category.value;
    state.filters.from = state.controls.from.value;
    state.filters.to = state.controls.to.value;
    state.filters.min = state.controls.min.value;
    state.filters.max = state.controls.max.value;
    state.filters.sort = state.controls.sort.value;
  }

  function applySorting(items) {
    const sortMode = state.filters.sort;
    const sorted = [...items];

    sorted.sort((left, right) => {
      switch (sortMode) {
        case 'oldest':
          return getExpenseDateValue(left) - getExpenseDateValue(right) || getAmount(left) - getAmount(right);
        case 'amount-high':
          return getAmount(right) - getAmount(left) || getExpenseDateValue(right) - getExpenseDateValue(left);
        case 'amount-low':
          return getAmount(left) - getAmount(right) || getExpenseDateValue(left) - getExpenseDateValue(right);
        case 'merchant':
          return getMerchant(left).localeCompare(getMerchant(right), undefined, { sensitivity: 'base' })
            || getExpenseDateValue(right) - getExpenseDateValue(left);
        case 'newest':
        default:
          return getExpenseDateValue(right) - getExpenseDateValue(left) || getAmount(right) - getAmount(left);
      }
    });

    return sorted;
  }

  function filterExpenses() {
    const query = normalizeText(state.filters.query);
    const category = state.filters.category;
    const from = state.filters.from ? Date.parse(`${state.filters.from}T00:00:00Z`) : null;
    const to = state.filters.to ? Date.parse(`${state.filters.to}T23:59:59.999Z`) : null;
    const min = parseAmount(state.filters.min);
    const max = parseAmount(state.filters.max);

    const effectiveMin = min != null && max != null && min > max ? max : min;
    const effectiveMax = min != null && max != null && min > max ? min : max;
    const effectiveFrom = from != null && to != null && from > to ? to : from;
    const effectiveTo = from != null && to != null && from > to ? from : to;

    const filtered = state.expenses.filter((expense) => {
      if (query && !getSearchBlob(expense).includes(query)) {
        return false;
      }

      if (category && getCategory(expense) !== category) {
        return false;
      }

      const dateValue = getExpenseDateValue(expense);
      if (effectiveFrom != null && dateValue < effectiveFrom) {
        return false;
      }
      if (effectiveTo != null && dateValue > effectiveTo) {
        return false;
      }

      const amount = getAmount(expense);
      if (effectiveMin != null && amount < effectiveMin) {
        return false;
      }
      if (effectiveMax != null && amount > effectiveMax) {
        return false;
      }

      return true;
    });

    return applySorting(filtered);
  }

  function updateResultSummary(filtered) {
    if (state.resultCount) {
      state.resultCount.textContent = `${filtered.length} result${filtered.length === 1 ? '' : 's'}`;
    }

    if (state.resultTotal && window.expenseLedger?.formatMoney) {
      const total = filtered.reduce((sum, expense) => sum + getAmount(expense), 0);
      state.resultTotal.textContent = `${window.expenseLedger.formatMoney(total)} filtered total`;
    }
  }

  function renderFilteredExpenses() {
    if (!window.expenseLedger) return;

    readFiltersFromUI();
    const filtered = filterExpenses();
    window.expenseLedger.renderExpenses(filtered);
    updateResultSummary(filtered);
  }

  function resetFilters() {
    if (!state.controls) return;

    state.filters = {
      query: '',
      category: '',
      from: '',
      to: '',
      min: '',
      max: '',
      sort: 'newest',
    };

    state.controls.query.value = '';
    state.controls.category.value = '';
    state.controls.from.value = '';
    state.controls.to.value = '';
    state.controls.min.value = '';
    state.controls.max.value = '';
    state.controls.sort.value = 'newest';

    renderFilteredExpenses();
    window.expenseLedger?.setToast?.('Filters reset');
  }

  function wireControl(control, handler) {
    control.addEventListener('input', handler);
    control.addEventListener('change', handler);
  }

  function buildPanelBody(panel) {
    const body = panel.querySelector('.feature-panel-body');
    if (!body) return;

    body.className = 'feature-panel-body expense-filters';

	    const description = document.createElement('p');
	    description.className = 'expense-filters-copy';
	    description.textContent = 'Find the transactions that matter now.';
	    body.appendChild(description);

    const form = document.createElement('form');
    form.className = 'expense-filters-form';
    form.noValidate = true;

    const controls = {
      query: createInput('search', 'expenseFilterQuery', 'Search merchant, category, or notes'),
      category: createSelect('expenseFilterCategory'),
      from: createInput('date', 'expenseFilterFrom'),
      to: createInput('date', 'expenseFilterTo'),
      min: createInput('number', 'expenseFilterMin', '0.00'),
      max: createInput('number', 'expenseFilterMax', '0.00'),
      sort: createSelect('expenseFilterSort'),
    };

    controls.min.step = '0.01';
    controls.min.inputMode = 'decimal';
    controls.max.step = '0.01';
    controls.max.inputMode = 'decimal';

    const categoryField = createLabel('Category', controls.category.id);
    categoryField.appendChild(controls.category);

    const queryField = createLabel('Search', controls.query.id);
    queryField.appendChild(controls.query);

    const dateFromField = createLabel('From', controls.from.id);
    dateFromField.appendChild(controls.from);

    const dateToField = createLabel('To', controls.to.id);
    dateToField.appendChild(controls.to);

    const minField = createLabel('Min amount', controls.min.id);
    minField.appendChild(controls.min);

    const maxField = createLabel('Max amount', controls.max.id);
    maxField.appendChild(controls.max);

    const sortField = createLabel('Sort by', controls.sort.id);
    sortField.appendChild(controls.sort);

    const optionData = [
      ['newest', 'Newest'],
      ['oldest', 'Oldest'],
      ['amount-high', 'Amount: high to low'],
      ['amount-low', 'Amount: low to high'],
      ['merchant', 'Merchant A to Z'],
    ];

    for (const [value, label] of optionData) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = label;
      controls.sort.appendChild(option);
    }

    const actions = document.createElement('div');
    actions.className = 'expense-filters-actions';

    const resetButton = document.createElement('button');
    resetButton.type = 'button';
    resetButton.className = 'btn btn-secondary expense-filters-reset';
    resetButton.textContent = 'Reset filters';
    resetButton.addEventListener('click', resetFilters);

    const resultBlock = document.createElement('div');
    resultBlock.className = 'expense-filters-results';

    const resultCount = document.createElement('p');
    resultCount.className = 'expense-filters-count';
    resultCount.textContent = '0 results';

    const resultTotal = document.createElement('p');
    resultTotal.className = 'expense-filters-total';
    resultTotal.textContent = '$0.00 filtered total';

    resultBlock.appendChild(resultCount);
    resultBlock.appendChild(resultTotal);

    actions.appendChild(resetButton);
    actions.appendChild(resultBlock);

    const fields = document.createElement('div');
    fields.className = 'expense-filters-grid';
    fields.appendChild(queryField);
    fields.appendChild(categoryField);
    fields.appendChild(dateFromField);
    fields.appendChild(dateToField);
    fields.appendChild(minField);
    fields.appendChild(maxField);
    fields.appendChild(sortField);

    form.appendChild(fields);
    form.appendChild(actions);
    body.appendChild(form);

    state.controls = controls;
    state.resultCount = resultCount;
    state.resultTotal = resultTotal;

    const onAnyChange = () => {
      renderFilteredExpenses();
    };

    for (const control of Object.values(controls)) {
      wireControl(control, onAnyChange);
    }

    syncCategoryOptions(state.expenses);
    renderFilteredExpenses();
  }

  function syncFromData(expenses) {
    state.expenses = Array.isArray(expenses) ? expenses.slice() : [];
    syncCategoryOptions(state.expenses);
    renderFilteredExpenses();
  }

  function initFeature() {
    if (!window.expenseLedger?.registerFeaturePanel || !window.expenseLedger?.renderExpenses) {
      return false;
    }

    injectStylesheet();
    state.panel = window.expenseLedger.registerFeaturePanel(FEATURE_ID, 'Advanced filtering and sorting', 'Analysis');
    buildPanelBody(state.panel);
    syncFromData(window.expenseLedger.state?.expenses || []);

    document.addEventListener('expense-ledger:data', (event) => {
      const detailExpenses = event?.detail?.expenses;
      const currentExpenses = Array.isArray(detailExpenses)
        ? detailExpenses
        : window.expenseLedger?.state?.expenses || [];
      syncFromData(currentExpenses);
    });

    window.expenseLedgerFilters = {
      apply: renderFilteredExpenses,
      reset: resetFilters,
    };

    return true;
  }

  function boot() {
    if (initFeature()) return;
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
      setTimeout(boot, 25);
      return;
    }
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  }

  boot();
}());
