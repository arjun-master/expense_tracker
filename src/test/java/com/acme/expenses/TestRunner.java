package com.acme.expenses;

import java.util.List;

public final class TestRunner {
    private TestRunner() {
    }

    public static void main(String[] args) throws Exception {
        List<TestCase> tests = List.of(
                new TestCase("service creates and summarizes expenses", ExpenseServiceTest::createsAndSummarizesExpenses),
                new TestCase("service rejects invalid expenses", ExpenseServiceTest::rejectsInvalidExpenses),
                new TestCase("file repository persists reloads and deletes", FileExpenseRepositoryTest::persistsReloadsAndDeletesExpenses),
                new TestCase("http end-to-end API flow", ExpenseHttpE2ETest::exercisesApiFlow)
        );

        int passed = 0;
        for (TestCase test : tests) {
            try {
                test.run();
                passed++;
                System.out.println("PASS " + test.name());
            } catch (Throwable throwable) {
                System.err.println("FAIL " + test.name());
                throwable.printStackTrace(System.err);
                System.err.println();
            }
        }

        if (passed != tests.size()) {
            throw new AssertionError(passed + " of " + tests.size() + " tests passed");
        }
        System.out.println("All " + passed + " tests passed.");
    }

    private record TestCase(String name, Assertions.ThrowingRunnable runnable) {
        void run() throws Exception {
            runnable.run();
        }
    }
}
