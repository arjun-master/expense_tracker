(function () {
  'use strict';

  var STYLE_ID = 'spending-insights-styles';
  var PANEL_ID = 'spending-insights-panel';
  var BOOT_RETRY_MS = 50;
  var BOOT_RETRY_LIMIT = 200;
  var dayMs = 24 * 60 * 60 * 1000;
  var bootRetries = 0;
  var initialized = false;
  var nodes = {};

  function ensureStyles() {
    if (document.getElementById(STYLE_ID)) return;
    var link = document.createElement('link');
    link.id = STYLE_ID;
    link.rel = 'stylesheet';
    link.href = 'insights.css';
    document.head.appendChild(link);
  }

  function parseDateOnly(value) {
    if (!value) return null;
    var text = String(value);
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    var date = match
      ? new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
      : new Date(text);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function monthKeyFromDate(date) {
    return String(date.getUTCFullYear()) + '-' + String(date.getUTCMonth() + 1).padStart(2, '0');
  }

  function localMonthKey(date) {
    return String(date.getFullYear()) + '-' + String(date.getMonth() + 1).padStart(2, '0');
  }

  function shiftMonthKey(monthKey, offset) {
    var parts = monthKey.split('-');
    var date = new Date(Date.UTC(Number(parts[0]), Number(parts[1]) - 1 + offset, 1));
    return monthKeyFromDate(date);
  }

  function formatMonthKey(monthKey) {
    var parts = monthKey.split('-');
    var date = new Date(Date.UTC(Number(parts[0]), Number(parts[1]) - 1, 1));
    return new Intl.DateTimeFormat(undefined, {
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(date);
  }

  function formatPercent(value) {
    return new Intl.NumberFormat(undefined, {
      maximumFractionDigits: 1,
      minimumFractionDigits: 0,
    }).format(value);
  }

  function formatNumber(value) {
    return new Intl.NumberFormat(undefined, {
      maximumFractionDigits: 1,
      minimumFractionDigits: 0,
    }).format(value);
  }

  function normalizeMerchant(value) {
    return String(value || 'Unknown')
      .trim()
      .toLowerCase()
      .replace(/\s+/g, ' ');
  }

  function getLabel(value, fallback) {
    var text = String(value || '').trim();
    return text || fallback;
  }

  function createEl(tag, className, text) {
    var el = document.createElement(tag);
    if (className) el.className = className;
    if (typeof text === 'string') el.textContent = text;
    return el;
  }

  function createMetricCard(label, value, detail, tone) {
    var card = createEl('article', 'insight-card');
    if (tone) card.dataset.tone = tone;
    card.appendChild(createEl('div', 'insight-label', label));
    card.appendChild(createEl('div', 'insight-value', value));
    card.appendChild(createEl('div', 'insight-detail', detail));
    return card;
  }

  function createMiniStat(label, value) {
    var item = createEl('div', 'mini-stat');
    item.appendChild(createEl('span', 'mini-stat-label', label));
    item.appendChild(createEl('strong', 'mini-stat-value', value));
    return item;
  }

  function average(values) {
    if (!values.length) return 0;
    var total = values.reduce(function (sum, value) { return sum + value; }, 0);
    return total / values.length;
  }

  function buildInsights(expenses) {
    var items = Array.isArray(expenses) ? expenses.slice() : [];
    var dated = [];

    for (var i = 0; i < items.length; i += 1) {
      var expense = items[i] || {};
      var date = parseDateOnly(expense.date || expense.createdAt);
      if (!date) continue;
      dated.push({
        raw: expense,
        date: date,
        amount: Number(expense.amount || 0),
        category: getLabel(expense.category, 'Uncategorized'),
        merchant: getLabel(expense.merchant, 'Unknown'),
      });
    }

    dated.sort(function (a, b) { return a.date - b.date; });

    var currentDate = new Date();
    var currentMonthKey = localMonthKey(currentDate);
    var previousMonthKey = shiftMonthKey(currentMonthKey, -1);
    var monthTotals = new Map();
    var categoryTotals = new Map();
    var merchantTotals = new Map();
    var weekdayCounts = new Array(7).fill(0);
    var recurringMap = new Map();
    var total = 0;

    for (var j = 0; j < dated.length; j += 1) {
      var entry = dated[j];
      var amount = Number(entry.amount || 0);
      total += amount;

      var monthKey = monthKeyFromDate(entry.date);
      monthTotals.set(monthKey, (monthTotals.get(monthKey) || 0) + amount);
      categoryTotals.set(entry.category, (categoryTotals.get(entry.category) || 0) + amount);

      var merchantKey = normalizeMerchant(entry.merchant);
      var merchantGroup = merchantTotals.get(merchantKey);
      if (!merchantGroup) {
        merchantGroup = {
          label: entry.merchant,
          total: 0,
          count: 0,
        };
        merchantTotals.set(merchantKey, merchantGroup);
      }
      merchantGroup.total += amount;
      merchantGroup.count += 1;

      weekdayCounts[entry.date.getUTCDay()] += 1;

      var recurringGroup = recurringMap.get(merchantKey);
      if (!recurringGroup) {
        recurringGroup = {
          label: entry.merchant,
          total: 0,
          count: 0,
          months: new Set(),
          dates: [],
        };
        recurringMap.set(merchantKey, recurringGroup);
      }
      recurringGroup.label = recurringGroup.label || entry.merchant;
      recurringGroup.total += amount;
      recurringGroup.count += 1;
      recurringGroup.months.add(monthKey);
      recurringGroup.dates.push(entry.date);
    }

    var currentMonthTotal = monthTotals.get(currentMonthKey) || 0;
    var previousMonthTotal = monthTotals.get(previousMonthKey) || 0;
    var monthChange = previousMonthTotal > 0 ? currentMonthTotal - previousMonthTotal : null;
    var monthChangePercent = previousMonthTotal > 0 ? (monthChange / previousMonthTotal) * 100 : null;

    var largestExpense = dated.reduce(function (best, entry) {
      if (!best || entry.amount > best.amount) return entry;
      return best;
    }, null);

    var topCategory = null;
    categoryTotals.forEach(function (amount, category) {
      if (!topCategory || amount > topCategory.total) {
        topCategory = { label: category, total: amount };
      }
    });

    var topMerchant = null;
    merchantTotals.forEach(function (group) {
      if (!topMerchant || group.total > topMerchant.total) {
        topMerchant = group;
      }
    });

    var gaps = [];
    for (var k = 1; k < dated.length; k += 1) {
      gaps.push((dated[k].date - dated[k - 1].date) / dayMs);
    }

    var activeDays = new Set();
    for (var m = 0; m < dated.length; m += 1) {
      activeDays.add(dated[m].date.toISOString().slice(0, 10));
    }

    var busiestWeekdayIndex = 0;
    for (var w = 1; w < weekdayCounts.length; w += 1) {
      if (weekdayCounts[w] > weekdayCounts[busiestWeekdayIndex]) {
        busiestWeekdayIndex = w;
      }
    }
    var weekdayNames = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

    var recurringMerchants = [];
    recurringMap.forEach(function (group) {
      if (group.count < 2 || group.months.size < 2) return;

      var sortedDates = group.dates.slice().sort(function (a, b) { return a - b; });
      var merchantGaps = [];
      for (var n = 1; n < sortedDates.length; n += 1) {
        merchantGaps.push((sortedDates[n] - sortedDates[n - 1]) / dayMs);
      }

      var avgGap = merchantGaps.length ? average(merchantGaps) : null;
      var looksRecurring = group.count >= 3 || (avgGap !== null && avgGap <= 45);
      if (!looksRecurring) return;

      recurringMerchants.push({
        label: group.label,
        total: group.total,
        count: group.count,
        months: group.months.size,
        avgGap: avgGap,
        lastDate: sortedDates[sortedDates.length - 1],
      });
    });

    recurringMerchants.sort(function (a, b) {
      if (b.count !== a.count) return b.count - a.count;
      if (b.months !== a.months) return b.months - a.months;
      return b.total - a.total;
    });

    return {
      total: total,
      count: dated.length,
      currentMonthKey: currentMonthKey,
      previousMonthKey: previousMonthKey,
      currentMonthTotal: currentMonthTotal,
      previousMonthTotal: previousMonthTotal,
      monthChange: monthChange,
      monthChangePercent: monthChangePercent,
      largestExpense: largestExpense,
      topCategory: topCategory,
      topMerchant: topMerchant,
      avgGapDays: gaps.length ? average(gaps) : null,
      busiestWeekdayLabel: weekdayCounts.some(function (count) { return count > 0; })
        ? weekdayNames[busiestWeekdayIndex]
        : null,
      weekdayCounts: weekdayCounts,
      activeDays: activeDays.size,
      recurringMerchants: recurringMerchants,
    };
  }

  function renderInsights(expenses, summary) {
    if (!nodes.panel) return;

    var metrics = buildInsights(expenses);
    var body = nodes.body;

    var currentMonthLabel = formatMonthKey(metrics.currentMonthKey);
    var previousMonthLabel = formatMonthKey(metrics.previousMonthKey);
    var monthDeltaLabel = metrics.monthChange === null
      ? 'Not enough prior-month history'
      : (metrics.monthChange >= 0 ? '+' : '−') + window.expenseLedger.formatMoney(Math.abs(metrics.monthChange)) +
        ' vs ' + previousMonthLabel +
        (metrics.monthChangePercent !== null ? ' (' + (metrics.monthChange >= 0 ? '+' : '−') + formatPercent(Math.abs(metrics.monthChangePercent)) + '%)' : '');

    nodes.meta.textContent = metrics.count
      ? metrics.count + ' transaction' + (metrics.count === 1 ? '' : 's') + ' analyzed'
      : 'No expenses analyzed yet';

    nodes.grid.replaceChildren(
      createMetricCard(
        'Largest expense',
        metrics.largestExpense ? window.expenseLedger.formatMoney(metrics.largestExpense.amount) : '—',
        metrics.largestExpense
          ? metrics.largestExpense.merchant + ' · ' + window.expenseLedger.formatDate(metrics.largestExpense.date.toISOString().slice(0, 10)) + ' · ' + metrics.largestExpense.category
          : 'No expense records yet',
        'accent'
      ),
      createMetricCard(
        'Current month total',
        window.expenseLedger.formatMoney(metrics.currentMonthTotal),
        currentMonthLabel + ' activity only',
        'success'
      ),
      createMetricCard(
        'Month-over-month',
        metrics.monthChange === null
          ? '—'
          : (metrics.monthChange >= 0 ? '+' : '−') + window.expenseLedger.formatMoney(Math.abs(metrics.monthChange)),
        monthDeltaLabel,
        metrics.monthChange === null ? 'muted' : (metrics.monthChange >= 0 ? 'success' : 'warning')
      ),
      createMetricCard(
        'Top merchant',
        metrics.topMerchant ? metrics.topMerchant.label : '—',
        metrics.topMerchant
          ? window.expenseLedger.formatMoney(metrics.topMerchant.total) + ' across ' + metrics.topMerchant.count + ' transaction' + (metrics.topMerchant.count === 1 ? '' : 's')
          : 'No merchant data yet',
        'accent'
      ),
      createMetricCard(
        'Top category',
        metrics.topCategory ? metrics.topCategory.label : '—',
        metrics.topCategory
          ? window.expenseLedger.formatMoney(metrics.topCategory.total) + ' total spend'
          : 'No category data yet',
        'success'
      ),
      createMetricCard(
        'Transaction cadence',
        metrics.avgGapDays === null
          ? 'Insufficient data'
          : '1 every ' + formatNumber(metrics.avgGapDays) + ' days',
        metrics.avgGapDays === null
          ? 'Need at least two dated transactions'
          : 'Most active weekday: ' + (metrics.busiestWeekdayLabel || '—') +
            ' · Active days: ' + metrics.activeDays,
        'neutral'
      )
    );

    nodes.cadence.replaceChildren(
      createMiniStat('Current month', window.expenseLedger.formatMoney(metrics.currentMonthTotal)),
      createMiniStat('Previous month', window.expenseLedger.formatMoney(metrics.previousMonthTotal)),
      createMiniStat('Transactions', String(metrics.count))
    );

    if (!metrics.recurringMerchants.length) {
      nodes.recurring.replaceChildren(createEl('div', 'insight-empty', 'No repeat merchant pattern has crossed the threshold yet.'));
      return;
    }

    var list = document.createElement('div');
    list.className = 'recurring-list';

    for (var i = 0; i < metrics.recurringMerchants.length; i += 1) {
      var merchant = metrics.recurringMerchants[i];
      var row = createEl('article', 'recurring-item');
      var header = createEl('div', 'recurring-head');
      header.appendChild(createEl('div', 'recurring-title', merchant.label));
      header.appendChild(createEl('div', 'recurring-total', window.expenseLedger.formatMoney(merchant.total)));

      var meta = createEl('div', 'recurring-meta');
      meta.appendChild(createEl('span', 'pill pill-muted', merchant.count + ' transactions'));
      meta.appendChild(createEl('span', 'pill pill-muted', merchant.months + ' months'));
      if (merchant.avgGap !== null) {
        meta.appendChild(createEl('span', 'pill pill-muted', 'avg gap ' + formatNumber(merchant.avgGap) + ' days'));
      }
      meta.appendChild(createEl('span', 'recurring-last', 'Last seen ' + window.expenseLedger.formatDate(merchant.lastDate.toISOString().slice(0, 10))));

      row.appendChild(header);
      row.appendChild(meta);
      list.appendChild(row);
    }

    nodes.recurring.replaceChildren(list);
  }

  function ensurePanel() {
    var ledger = window.expenseLedger;
    if (!ledger || typeof ledger.registerFeaturePanel !== 'function') return false;
    if (initialized) return true;

    ensureStyles();

    var panel = ledger.registerFeaturePanel(PANEL_ID, 'Spending insights', 'Signals');
    panel.classList.add('insights-panel');

    if (panel.dataset.insightsReady === 'true') {
      initialized = true;
      return true;
    }

    var header = panel.querySelector('.panel-header');
    var body = panel.querySelector('.feature-panel-body');
    if (!body) {
      body = createEl('div', 'feature-panel-body');
      panel.appendChild(body);
    }

    nodes.panel = panel;
    nodes.body = body;
    nodes.meta = createEl('div', 'insights-meta', '');
    nodes.grid = createEl('div', 'insights-grid');
    nodes.columns = createEl('div', 'insights-columns');

    var cadenceCard = createEl('section', 'insights-section');
    cadenceCard.appendChild(createEl('h3', 'insights-section-title', 'Transaction rhythm'));
    nodes.cadence = createEl('div', 'mini-stat-grid');
    cadenceCard.appendChild(nodes.cadence);

    var recurringCard = createEl('section', 'insights-section');
    recurringCard.appendChild(createEl('h3', 'insights-section-title', 'Possible recurring merchants'));
    nodes.recurring = createEl('div', 'insight-empty');
    recurringCard.appendChild(nodes.recurring);

    nodes.columns.appendChild(cadenceCard);
    nodes.columns.appendChild(recurringCard);

    body.replaceChildren(nodes.grid, nodes.columns);
    if (header) header.appendChild(nodes.meta);
    panel.dataset.insightsReady = 'true';

    document.addEventListener('expense-ledger:data', function (event) {
      var detail = event && event.detail ? event.detail : {};
      renderInsights(detail.expenses || [], detail.summary || null);
    });

    if (ledger.state) {
      renderInsights(ledger.state.expenses || [], ledger.state.summary || null);
    }

    initialized = true;
    return true;
  }

  function boot() {
    if (ensurePanel()) return;
    bootRetries += 1;
    if (bootRetries > BOOT_RETRY_LIMIT) return;
    window.setTimeout(boot, BOOT_RETRY_MS);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
}());
