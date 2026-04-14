package com.acme.expenses.store;

import com.acme.expenses.model.Expense;

import java.util.List;

public interface ExpenseRepository {
    List<Expense> findAll();

    void save(Expense expense);

    boolean deleteById(String id);
}
