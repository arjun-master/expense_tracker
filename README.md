# Expense Ledger

A self-contained Java expense tracker with file-backed storage, reporting APIs, CSV export, and a responsive browser UI.

## Features

- Add, delete, categorize, and export expenses
- Dashboard totals, category chart, and monthly trend report
- Advanced filtering by text, category, date range, amount range, and sort order
- Current-month category budgets with local alerts
- Spending insights for largest expense, month-over-month movement, top merchants/categories, cadence, and recurring merchant patterns

## Run

```bash
./scripts/run.sh 8080
```

Open `http://127.0.0.1:8080/`.

Data is stored in `data/expenses.csv` by default. Override it with:

```bash
java -Dapp.dataFile=/path/to/expenses.csv -cp build/classes com.acme.expenses.ExpenseTrackerApplication 8080
```

## Test

```bash
./scripts/test.sh
```

The test script compiles with `javac` and runs unit tests plus an HTTP end-to-end flow without external dependencies.

## API

- `GET /health`
- `GET /api/expenses`
- `POST /api/expenses`
- `DELETE /api/expenses/{id}`
- `GET /api/reports/summary`
- `GET /api/export.csv`
